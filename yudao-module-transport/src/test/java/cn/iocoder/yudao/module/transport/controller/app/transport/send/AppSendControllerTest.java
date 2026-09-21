package cn.iocoder.yudao.module.transport.controller.app.transport.send;

import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.transport.controller.app.transport.send.vo.AppSendOrderRespVO;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.DispatchPlanItemDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.TransportOrderDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.station.StationDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.vehicle.VehicleDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.vehicle.VehicleLocationDO;
import cn.iocoder.yudao.module.transport.dal.mysql.dispatch.DispatchPlanItemMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.vehicle.VehicleLocationMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.vehicle.VehicleMapper;
import cn.iocoder.yudao.module.transport.enums.dispatch.TransportOrderStatusEnum;
import cn.iocoder.yudao.module.transport.service.transport.station.StationService;
import cn.iocoder.yudao.module.transport.service.monitoring.VehicleLocationSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * "车来取货/送货"提醒纯 Mockito 单测。
 * 覆盖修复：①订单状态收口（仅在途订单提醒，完成/取消不提醒）；②车辆位置新鲜度（过期上报不提醒）。
 */
@ExtendWith(MockitoExtension.class)
class AppSendControllerTest {

    @Mock private DispatchPlanItemMapper dispatchPlanItemMapper;
    @Mock private VehicleLocationMapper vehicleLocationMapper;
    @Mock private VehicleMapper vehicleMapper;
    @Mock private StationService stationService;
    @Mock private cn.iocoder.yudao.module.transport.service.monitoring.VehicleLocationProvider vehicleLocationProvider;
    @Mock private cn.iocoder.yudao.module.transport.dal.mysql.station.StationMapper stationMapper;
    @Mock private cn.iocoder.yudao.module.transport.service.dispatch.MultiLegService multiLegService;

    private AppSendController controller;

    @BeforeEach
    void setUp() {
        controller = new AppSendController();
        ReflectionTestUtils.setField(controller, "dispatchPlanItemMapper", dispatchPlanItemMapper);
        ReflectionTestUtils.setField(controller, "vehicleLocationMapper", vehicleLocationMapper);
        ReflectionTestUtils.setField(controller, "vehicleMapper", vehicleMapper);
        ReflectionTestUtils.setField(controller, "stationService", stationService);
        ReflectionTestUtils.setField(controller, "vehicleLocationProvider", vehicleLocationProvider);
    }

    // ==================== 车来取货/送货提醒 ====================

    @Test
    void carrierApproaching_withinThreshold_only() {
        // 距目标站点 <= 10 分钟 → 前端高亮"车快到了"；0/负数（已到站/异常）与超阈值都不提醒
        assertTrue(AppSendController.carrierApproaching(1));
        assertTrue(AppSendController.carrierApproaching(10));
        assertFalse(AppSendController.carrierApproaching(11));
        assertFalse(AppSendController.carrierApproaching(0));
        assertFalse(AppSendController.carrierApproaching(null));
    }

    @Test
    void isRealLocationSource_onlyReal() {
        // 仅真实上报来源参与"过期不提醒"判定
        assertTrue(AppSendController.isRealLocationSource("REAL"));
        assertTrue(AppSendController.isRealLocationSource("REAL_STALE"));
        assertFalse(AppSendController.isRealLocationSource("OFFLINE"));
        assertFalse(AppSendController.isRealLocationSource(null));
    }

    @Test
    void carrierReminderEligible_only_in_transit_statuses() {
        assertTrue(AppSendController.carrierReminderEligible(TransportOrderStatusEnum.ASSIGNED.getStatus()));
        assertTrue(AppSendController.carrierReminderEligible(TransportOrderStatusEnum.DEPARTED.getStatus()));
        assertFalse(AppSendController.carrierReminderEligible(TransportOrderStatusEnum.CREATED.getStatus()));
        assertFalse(AppSendController.carrierReminderEligible(TransportOrderStatusEnum.POOLED.getStatus()));
        assertFalse(AppSendController.carrierReminderEligible(TransportOrderStatusEnum.COMPLETED.getStatus()));
        assertFalse(AppSendController.carrierReminderEligible(TransportOrderStatusEnum.CANCELLED.getStatus()));
        assertFalse(AppSendController.carrierReminderEligible(null));
    }

    @Test
    void isCarrierLocationFresh_boundary() {
        LocalDateTime now = LocalDateTime.now();
        assertTrue(AppSendController.isCarrierLocationFresh(now.minusMinutes(10), now));
        assertTrue(AppSendController.isCarrierLocationFresh(now.minusMinutes(29), now));
        // 恰好 30 分钟整不算新鲜（isAfter 严格大于）
        assertFalse(AppSendController.isCarrierLocationFresh(now.minusMinutes(30), now));
        assertFalse(AppSendController.isCarrierLocationFresh(now.minusHours(20), now));
        assertFalse(AppSendController.isCarrierLocationFresh(null, now));
    }

    @Test
    void fillCarrierBatch_completed_order_not_reminded() {
        // 两单：1 号已分配（在途）、2 号已完成；车辆位置新鲜、站点坐标齐全
        TransportOrderDO inTransit = order(1L, TransportOrderStatusEnum.ASSIGNED.getStatus());
        TransportOrderDO completed = order(2L, TransportOrderStatusEnum.COMPLETED.getStatus());
        List<TransportOrderDO> orders = List.of(inTransit, completed);
        List<AppSendOrderRespVO> list = List.of(new AppSendOrderRespVO(), new AppSendOrderRespVO());
        mockCarrierFixture();
        mockFreshLocation();

        invokeFillCarrierBatch(list, orders);

        // 在途单填充提醒（0.01° 经度 ≈ 0.96km @25km/h → 分钟数有值），完成单不填充
        assertNotNull(list.get(0).getCarrierEtaMinutes());
        assertNotNull(list.get(0).getCarrierDistanceKm());
        assertEquals("川A12345", list.get(0).getVehiclePlate());
        assertNull(list.get(1).getCarrierEtaMinutes());
        assertNull(list.get(1).getCarrierLongitude());
    }

    @Test
    void fillCarrierBatch_stale_location_not_reminded() {
        // 在途单，但车辆上报是 60 分钟前的残留位置（班次已结束）
        TransportOrderDO inTransit = order(1L, TransportOrderStatusEnum.ASSIGNED.getStatus());
        List<TransportOrderDO> orders = List.of(inTransit);
        List<AppSendOrderRespVO> list = List.of(new AppSendOrderRespVO());
        mockCarrierFixture();
        // 真实上报是 60 分钟前的残留位置（班次已结束）→ 不提醒
        when(vehicleLocationProvider.getLocations(any()))
                .thenReturn(Map.of(7L, snapshot("REAL", LocalDateTime.now().minusMinutes(60))));

        invokeFillCarrierBatch(list, orders);

        assertNull(list.get(0).getCarrierEtaMinutes());
        assertNull(list.get(0).getCarrierLongitude());
    }

    @Test
    void fillCarrierBatch_fresh_location_reminded() {
        TransportOrderDO departed = order(1L, TransportOrderStatusEnum.DEPARTED.getStatus());
        List<TransportOrderDO> orders = List.of(departed);
        List<AppSendOrderRespVO> list = List.of(new AppSendOrderRespVO());
        mockCarrierFixture();
        mockFreshLocation();

        invokeFillCarrierBatch(list, orders);

        assertNotNull(list.get(0).getCarrierEtaMinutes());
        assertEquals("中心站", list.get(0).getTargetStation());
    }

    // ==================== 测试夹具 ====================

    private TransportOrderDO order(Long id, Integer status) {
        return TransportOrderDO.builder().id(id).status(status).build();
    }

    /** 两单各一条派送经停（动作 3，车 7、站 11）；车辆与站点档案齐全。位置上报由各用例自行 stub */
    private void mockCarrierFixture() {
        List<DispatchPlanItemDO> items = new ArrayList<>();
        items.add(DispatchPlanItemDO.builder().id(101L).orderId(1L).vehicleId(7L)
                .stationId(11L).actionType(3).build());
        items.add(DispatchPlanItemDO.builder().id(102L).orderId(2L).vehicleId(7L)
                .stationId(11L).actionType(3).build());
        when(dispatchPlanItemMapper.selectList(any(LambdaQueryWrapperX.class))).thenReturn(items);
        when(vehicleMapper.selectBatchIds(anyCollection()))
                .thenReturn(List.of(VehicleDO.builder().id(7L).plateNo("川A12345").build()));
        when(stationService.getSimpleList()).thenReturn(List.of(StationDO.builder()
                .id(11L).stationName("中心站")
                .longitude(new BigDecimal("104.0100")).latitude(new BigDecimal("30.0000")).build()));
    }

    /** 车辆最新位置为新鲜的真实上报（2 分钟前，统一位置模型给出的 REAL 快照） */
    private void mockFreshLocation() {
        when(vehicleLocationProvider.getLocations(any()))
                .thenReturn(Map.of(7L, snapshot("REAL", LocalDateTime.now().minusMinutes(2))));
    }

    /** 统一位置模型快照（source: REAL / OFFLINE；坐标为 104.0000,30.0000 → 距中心站约 0.96km） */
    private VehicleLocationSnapshot snapshot(String source, LocalDateTime updatedAt) {
        return VehicleLocationSnapshot.builder().vehicleId(7L).source(source)
                .longitude(104.0000).latitude(30.0000).updatedAt(updatedAt).build();
    }

    @SuppressWarnings("unchecked")
    private void invokeFillCarrierBatch(List<AppSendOrderRespVO> list, List<TransportOrderDO> orders) {
        ReflectionTestUtils.invokeMethod(controller, "fillCarrierBatch", list, orders);
    }
}
