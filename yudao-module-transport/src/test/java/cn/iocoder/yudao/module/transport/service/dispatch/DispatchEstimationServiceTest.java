package cn.iocoder.yudao.module.transport.service.dispatch;

import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.DispatchPlanDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.DispatchPlanItemDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.CargoOrderDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.PassengerOrderDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.TransportOrderDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.station.StationDO;
import cn.iocoder.yudao.module.transport.dal.mysql.dispatch.DispatchPlanItemMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.dispatch.DispatchPlanMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.order.CargoOrderMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.order.PassengerOrderMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.order.PostalOrderMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.order.TransportOrderMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.station.StationMapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

/**
 * DispatchEstimationService 纯 Mockito 单测：ETA 逐站累计（行驶分钟 + 停站作业分钟）、
 * 坐标缺失不中断、多车独立、空输入不处理、方案摘要（耗时/收入/成本）。
 *
 * 坐标基准（与 GeoDistanceUtilTest 同口径）：0.01° 经度 @ 纬度 30° ≈ 0.963km，
 * 25 km/h 下 0.963km → 2 分钟，0.02° ≈ 1.926km → 5 分钟。
 */
@ExtendWith(MockitoExtension.class)
class DispatchEstimationServiceTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 8, 23, 8, 0);

    @Mock private DispatchPlanItemMapper dispatchPlanItemMapper;
    @Mock private DispatchPlanMapper dispatchPlanMapper;
    @Mock private StationMapper stationMapper;
    @Mock private TransportOrderMapper orderMapper;
    @Mock private PassengerOrderMapper passengerOrderMapper;
    @Mock private CargoOrderMapper cargoOrderMapper;
    @Mock private PostalOrderMapper postalOrderMapper;
    @Mock private PricingRuleService pricingRuleService;

    private DispatchEstimationService estimationService;

    @BeforeEach
    void setUp() {
        estimationService = new DispatchEstimationService();
        ReflectionTestUtils.setField(estimationService, "dispatchPlanItemMapper", dispatchPlanItemMapper);
        ReflectionTestUtils.setField(estimationService, "dispatchPlanMapper", dispatchPlanMapper);
        ReflectionTestUtils.setField(estimationService, "stationMapper", stationMapper);
        ReflectionTestUtils.setField(estimationService, "orderMapper", orderMapper);
        ReflectionTestUtils.setField(estimationService, "passengerOrderMapper", passengerOrderMapper);
        ReflectionTestUtils.setField(estimationService, "cargoOrderMapper", cargoOrderMapper);
        ReflectionTestUtils.setField(estimationService, "postalOrderMapper", postalOrderMapper);
        ReflectionTestUtils.setField(estimationService, "pricingRuleService", pricingRuleService);
    }

    @Test
    void estimatePlan_single_vehicle_chain() {
        // 单车闭环：DEPART(S0) → BOARD(S1) → ALIGHT(S2) → RETURN(S0)；故意乱序给出，验证按 visitSequence 排序
        mockRule();
        mockStations(station(10L, "104.0000"), station(11L, "104.0100"), station(12L, "104.0200"));
        mockItems(
                item(4L, 7L, 10L, 4, 5, null),      // RETURN
                item(2L, 7L, 11L, 2, 1, 1001L),    // BOARD
                item(1L, 7L, 10L, 1, 0, null),     // DEPART
                item(3L, 7L, 12L, 3, 2, 1001L)     // ALIGHT
        );

        estimationService.estimatePlan(100L, T0);

        Map<Long, LocalDateTime> etaById = captureItemEtas(4);
        assertEquals(T0, etaById.get(1L));                          // DEPART：出发时刻
        assertEquals(T0.plusMinutes(2), etaById.get(2L));           // BOARD：S0→S1 行驶 2 分钟
        assertEquals(T0.plusMinutes(2 + 3 + 2), etaById.get(3L));   // ALIGHT：BOARD 作业 3 + S1→S2 行驶 2
        assertEquals(T0.plusMinutes(2 + 3 + 2 + 3 + 5), etaById.get(4L)); // RETURN：ALIGHT 作业 3 + S2→S0 行驶 5
    }

    @Test
    void estimatePlan_missing_coords_continues_chain() {
        // S1 无坐标：相关站段按 0 里程，估算链不中断
        mockRule();
        mockStations(station(10L, "104.0000"), StationDO.builder().id(11L).stationName("无名站").build(),
                station(12L, "104.0200"));
        mockItems(item(1L, 7L, 10L, 1, 0, null), item(2L, 7L, 11L, 2, 1, null),
                item(3L, 7L, 12L, 3, 2, null));

        estimationService.estimatePlan(100L, T0);

        Map<Long, LocalDateTime> etaById = captureItemEtas(3);
        assertEquals(T0, etaById.get(1L));                // DEPART
        assertEquals(T0, etaById.get(2L));                // BOARD：S1 无坐标，站段 0 里程
        assertEquals(T0.plusMinutes(3), etaById.get(3L)); // ALIGHT：BOARD 作业 3 分钟，站段仍 0 里程
    }

    @Test
    void estimatePlan_multi_vehicle_independent() {
        // 两车各自从出发时刻独立累计，互不影响
        mockRule();
        mockStations(station(10L, "104.0000"), station(11L, "104.0100"));
        mockItems(
                item(1L, 7L, 10L, 1, 0, null), item(2L, 7L, 11L, 2, 4, null),   // 车 7：DEPART→PICKUP
                item(3L, 8L, 10L, 1, 0, null), item(4L, 8L, 11L, 2, 3, null));  // 车 8：DEPART→DELIVER

        estimationService.estimatePlan(100L, T0);

        Map<Long, LocalDateTime> etaById = captureItemEtas(4);
        assertEquals(T0, etaById.get(1L));
        assertEquals(T0.plusMinutes(2), etaById.get(2L)); // 车 7 第二站
        assertEquals(T0, etaById.get(3L));
        assertEquals(T0.plusMinutes(2), etaById.get(4L)); // 车 8 第二站（不受车 7 影响）
    }

    @Test
    void estimatePlan_empty_or_null_skips() {
        estimationService.estimatePlan(null, T0);
        estimationService.estimatePlan(100L, null);
        verifyNoInteractions(dispatchPlanItemMapper);

        mockItems();
        estimationService.estimatePlan(100L, T0);
        verify(dispatchPlanItemMapper, never()).updateById(any(DispatchPlanItemDO.class));
        verifyNoInteractions(dispatchPlanMapper, pricingRuleService, orderMapper);
    }

    @Test
    void estimatePlan_summary_duration_revenue_cost() {
        // 车 7：DEPART(S0)→BOARD(S1)→ALIGHT(S2)→RETURN(S0)，载客运单 1001（2 人，S1→S2）
        // 车 8：DEPART(S0)→DELIVER(S2)→RETURN(S0)，载货运单 1002（2 件）
        // 默认计价：人公里 1.00、货运件 5.00、公里成本 2.50、时速 25、停站 3 分钟
        mockRule();
        mockStations(station(10L, "104.0000"), station(11L, "104.0100"), station(12L, "104.0200"));
        mockItems(
                item(1L, 7L, 10L, 1, 0, null), item(2L, 7L, 11L, 2, 1, 1001L),
                item(3L, 7L, 12L, 3, 2, 1001L), item(4L, 7L, 10L, 4, 5, null),
                item(5L, 8L, 10L, 1, 0, null), item(6L, 8L, 12L, 2, 3, 1002L),
                item(7L, 8L, 10L, 3, 5, null));
        when(orderMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                TransportOrderDO.builder().id(1001L).orderType(1)
                        .pickupStationId(11L).deliveryStationId(12L).build(),
                TransportOrderDO.builder().id(1002L).orderType(2)
                        .pickupStationId(12L).deliveryStationId(10L).build()));
        when(passengerOrderMapper.selectOne(any(SFunction.class), any()))
                .thenReturn(PassengerOrderDO.builder().passengerCount(2).build());
        when(cargoOrderMapper.selectOne(any(SFunction.class), any()))
                .thenReturn(CargoOrderDO.builder().itemCount(2).build());

        estimationService.estimatePlan(100L, T0);

        ArgumentCaptor<DispatchPlanDO> captor = ArgumentCaptor.forClass(DispatchPlanDO.class);
        verify(dispatchPlanMapper).updateById(captor.capture());
        DispatchPlanDO plan = captor.getValue();
        // 耗时：车 7 为 2+3+2+3+5=15 分钟，车 8 为 5+3+5=13 分钟，取最大 15
        assertEquals(15, plan.getEstDurationMinutes());
        // 收入：客运 2 人 × 0.963km × 1.00 = 1.93；货运 2 件 × 5.00 = 10.00；合计 11.93
        assertEquals(0, plan.getEstRevenue().compareTo(new BigDecimal("11.93")));
        // 成本：两车总里程 (3.852 + 3.852)km × 2.50 = 19.26
        assertEquals(0, plan.getEstCost().compareTo(new BigDecimal("19.26")));
    }

    // ==================== 测试夹具 ====================

    private DispatchPlanItemDO item(Long id, Long vehicleId, Long stationId, int seq, int actionType, Long orderId) {
        return DispatchPlanItemDO.builder().id(id).planId(100L).vehicleId(vehicleId)
                .stationId(stationId).visitSequence(seq).actionType(actionType).orderId(orderId).build();
    }

    private StationDO station(Long id, String longitude) {
        return StationDO.builder().id(id).stationName("站" + id)
                .longitude(new BigDecimal(longitude)).latitude(new BigDecimal("30.0000")).build();
    }

    private void mockRule() {
        when(pricingRuleService.getRule()).thenReturn(PricingRuleService.defaultRule());
    }

    private void mockStations(StationDO... stations) {
        when(stationMapper.selectBatchIds(anyCollection())).thenReturn(List.of(stations));
    }

    private void mockItems(DispatchPlanItemDO... items) {
        // BaseMapperX.selectList(SFunction, value) 的默认方法体会转调 selectList(Wrapper)，需 stub Wrapper 重载
        when(dispatchPlanItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(items));
    }

    private Map<Long, LocalDateTime> captureItemEtas(int expectedUpdates) {
        ArgumentCaptor<DispatchPlanItemDO> captor = ArgumentCaptor.forClass(DispatchPlanItemDO.class);
        verify(dispatchPlanItemMapper, times(expectedUpdates)).updateById(captor.capture());
        return captor.getAllValues().stream().collect(Collectors.toMap(
                DispatchPlanItemDO::getId, DispatchPlanItemDO::getEstimatedArrivalTime));
    }
}
