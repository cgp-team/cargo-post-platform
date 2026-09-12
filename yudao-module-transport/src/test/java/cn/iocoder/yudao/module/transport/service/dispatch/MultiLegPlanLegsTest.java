package cn.iocoder.yudao.module.transport.service.dispatch;

import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.TransportLegDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.TransportOrderDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.station.StationDO;
import cn.iocoder.yudao.module.transport.dal.mysql.dispatch.TransportHandoverMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.dispatch.TransportLegMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.order.TransportOrderMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.route.RouteStationMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.station.StationMapper;
import cn.iocoder.yudao.module.transport.enums.dispatch.DispatchPlanningModeEnum;
import cn.iocoder.yudao.module.transport.enums.dispatch.TransportLegStatusEnum;
import cn.iocoder.yudao.module.transport.service.notification.UserNotificationService;
import cn.iocoder.yudao.module.transport.service.order.OrderEventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * P0-A 回归：运输段（transport_leg）的幂等维度必须是「方案」，不能跨方案复用旧段。
 *
 * 历史缺陷：planLegs 只按 order_id 做幂等，只要该订单历史上拆过段就永远返回旧方案的段 ——
 * 于是新方案在 transport_leg 里 0 行，而 total_leg_count/transfer_count/plan_reason 全部
 * 取自旧段（实测方案 31/32/33），司机端 current-leg 也指向旧方案的段，与当前方案错位。
 */
@ExtendWith(MockitoExtension.class)
class MultiLegPlanLegsTest {

    private static final Long ORDER_ID = 1L;
    private static final Long PLAN_ID = 100L;
    private static final Long OLD_PLAN_ID = 29L;

    @Mock private TransportLegMapper legMapper;
    @Mock private TransportOrderMapper orderMapper;
    @Mock private StationMapper stationMapper;
    @Mock private RouteStationMapper routeStationMapper;
    @Mock private TransportHandoverMapper handoverMapper;
    @Mock private MultiLegPlanner multiLegPlanner;
    @Mock private OrderEventService orderEventService;
    @Mock private UserNotificationService userNotificationService;

    private MultiLegServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new MultiLegServiceImpl();
        ReflectionTestUtils.setField(service, "legMapper", legMapper);
        ReflectionTestUtils.setField(service, "orderMapper", orderMapper);
        ReflectionTestUtils.setField(service, "stationMapper", stationMapper);
        ReflectionTestUtils.setField(service, "routeStationMapper", routeStationMapper);
        ReflectionTestUtils.setField(service, "handoverMapper", handoverMapper);
        ReflectionTestUtils.setField(service, "multiLegPlanner", multiLegPlanner);
        ReflectionTestUtils.setField(service, "orderEventService", orderEventService);
        ReflectionTestUtils.setField(service, "userNotificationService", userNotificationService);
        // 注：driverVehicleMapper / algorithmClient / dispatchPlanItemMapper 故意不注入，
        // 对应能力（按片区改派、真实道路、算法明细回填）在单测中短路，不影响本用例断言。
    }

    @Test
    void planLegs_same_plan_is_idempotent_and_does_not_touch_other_plans() {
        TransportLegDO own = leg(11L, PLAN_ID, 1, TransportLegStatusEnum.PLANNED);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order());
        when(legMapper.selectListByPlanIdAndOrderId(PLAN_ID, ORDER_ID)).thenReturn(List.of(own));

        List<TransportLegDO> result = service.planLegs(ORDER_ID, PLAN_ID, null, null);

        assertEquals(List.of(own), result);
        // 同一方案重复调度：不新增段、不查其它方案的段、不作废任何东西
        verify(legMapper, never()).insert(any(TransportLegDO.class));
        verify(legMapper, never()).selectListByOrderId(anyLong());
        verify(legMapper, never()).deleteById(anyLong());
    }

    @Test
    void planLegs_new_plan_voids_unstarted_legs_of_old_plan_and_creates_own_legs() {
        // 新方案（100）还没有该订单的段；旧方案（29）遗留一段未执行（已规划）+ 一段已完成
        when(legMapper.selectListByPlanIdAndOrderId(PLAN_ID, ORDER_ID)).thenReturn(List.of());
        when(legMapper.selectListByOrderId(ORDER_ID)).thenReturn(List.of(
                leg(77L, OLD_PLAN_ID, 1, TransportLegStatusEnum.PLANNED),
                leg(78L, 28L, 1, TransportLegStatusEnum.COMPLETED)));
        mockDirectPlanContext();

        List<TransportLegDO> legs = service.planLegs(ORDER_ID, PLAN_ID, null, null);

        // 新段归属本方案（不再挂到旧方案上）
        assertEquals(1, legs.size());
        assertEquals(PLAN_ID, legs.get(0).getPlanId());
        ArgumentCaptor<TransportLegDO> legCaptor = ArgumentCaptor.forClass(TransportLegDO.class);
        verify(legMapper).insert(legCaptor.capture());
        assertEquals(PLAN_ID, legCaptor.getValue().getPlanId());
        // 旧方案未执行的段连同其交接记录一并作废；已完成的段是历史事实，保留
        verify(handoverMapper).delete(any());
        verify(legMapper).deleteById(77L);
        verify(legMapper, never()).deleteById(78L);
    }

    @Test
    void planLegs_skips_order_already_in_transit_under_another_plan() {
        // 货物已在路上（运输中）：绝不为新方案重复拆段，也绝不把别人的段挂到新方案上
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order());
        when(legMapper.selectListByPlanIdAndOrderId(PLAN_ID, ORDER_ID)).thenReturn(List.of());
        when(legMapper.selectListByOrderId(ORDER_ID)).thenReturn(List.of(
                leg(79L, OLD_PLAN_ID, 1, TransportLegStatusEnum.IN_TRANSIT)));

        List<TransportLegDO> legs = service.planLegs(ORDER_ID, PLAN_ID, null, null);

        assertTrue(legs.isEmpty());
        verify(legMapper, never()).insert(any(TransportLegDO.class));
        verify(legMapper, never()).deleteById(anyLong());
    }

    /** 直达场景规划上下文：取货站 11 → 送达站 12，一段直达 */
    private void mockDirectPlanContext() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(TransportOrderDO.builder()
                .id(ORDER_ID).orderType(2).pickupStationId(11L).deliveryStationId(12L).build());
        StationDO pickup = StationDO.builder().id(11L).longitude(new BigDecimal("106.5"))
                .latitude(new BigDecimal("29.5")).build();
        StationDO delivery = StationDO.builder().id(12L).longitude(new BigDecimal("106.6"))
                .latitude(new BigDecimal("29.6")).build();
        when(stationMapper.selectById(11L)).thenReturn(pickup);
        when(stationMapper.selectById(12L)).thenReturn(delivery);
        when(stationMapper.selectList()).thenReturn(List.of(pickup, delivery));
        when(multiLegPlanner.plan(any(), eq(pickup), eq(delivery), any(), any()))
                .thenReturn(new MultiLegPlanner.PlanResult(DispatchPlanningModeEnum.DIRECT.getMode(),
                        1, 0, 12.0, 30, 1.0,
                        List.of(new MultiLegPlanner.LegDraft(1, 11L, 12L, 12.0, 30, false)),
                        List.of(), null, "取货站与送达站间有直达线路"));
    }

    private static TransportLegDO leg(Long id, Long planId, int sequence, TransportLegStatusEnum status) {
        return TransportLegDO.builder().id(id).orderId(ORDER_ID).planId(planId)
                .legSequence(sequence).status(status.getStatus()).build();
    }

    private static TransportOrderDO order() {
        return TransportOrderDO.builder().id(ORDER_ID).orderType(2)
                .pickupStationId(11L).deliveryStationId(12L).build();
    }
}
