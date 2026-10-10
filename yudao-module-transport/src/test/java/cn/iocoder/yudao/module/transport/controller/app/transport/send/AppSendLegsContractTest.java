package cn.iocoder.yudao.module.transport.controller.app.transport.send;

import cn.iocoder.yudao.module.transport.controller.app.transport.send.vo.AppSendLegRespVO;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.TransportLegDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.TransportOrderDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.station.StationDO;
import cn.iocoder.yudao.module.transport.dal.mysql.driver.DriverMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.vehicle.VehicleMapper;
import cn.iocoder.yudao.module.transport.service.dispatch.MultiLegService;
import cn.iocoder.yudao.module.transport.service.transport.order.TransportOrderService;
import cn.iocoder.yudao.module.transport.service.transport.station.StationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 用户端多段进度 {@code /transport/send/legs} 的契约单测。
 *
 * <p>补的这一段（起点/终点坐标、本段里程与计划耗时、导航来源、真实道路轨迹、是否换乘交接）
 * 生产者是 {@code transport_leg} 与 {@code transport_station} 的既有列，与司机端
 * {@code AppDriverLegRespVO}、后台拓扑 {@code OrderTopologyRespVO.Leg} 同源；
 * 这里钉住的是"没给就是 null，不许塌成 0 或空列表"——前端据此区分"后端未下发"与"真的是 0"。
 */
@ExtendWith(MockitoExtension.class)
class AppSendLegsContractTest {

    @Mock private TransportOrderService transportOrderService;
    @Mock private MultiLegService multiLegService;
    @Mock private StationService stationService;
    @Mock private DriverMapper driverMapper;
    @Mock private VehicleMapper vehicleMapper;

    private AppSendController controller;

    @BeforeEach
    void setUp() {
        controller = new AppSendController();
        ReflectionTestUtils.setField(controller, "transportOrderService", transportOrderService);
        ReflectionTestUtils.setField(controller, "multiLegService", multiLegService);
        ReflectionTestUtils.setField(controller, "stationService", stationService);
        ReflectionTestUtils.setField(controller, "driverMapper", driverMapper);
        ReflectionTestUtils.setField(controller, "vehicleMapper", vehicleMapper);
    }

    private TransportLegDO leg(Long fromStationId, Long toStationId) {
        return TransportLegDO.builder()
                .id(501L).orderId(1L).legSequence(1)
                .fromStationId(fromStationId).toStationId(toStationId)
                .status(7)
                .estimatedDeparture(LocalDateTime.of(2026, 10, 6, 8, 30))
                .estimatedArrival(LocalDateTime.of(2026, 10, 6, 9, 10))
                .distanceKm(new BigDecimal("12.40")).durationMinutes(45)
                .navigationSource("AMAP")
                .navigationPolyline("104.0100,30.0000;104.0200,30.0100")
                .handoverRequired(true)
                .build();
    }

    /** 上车站有坐标、下车站档案里坐标为空 */
    private List<StationDO> stations() {
        return List.of(
                StationDO.builder().id(11L).stationName("起始站")
                        .longitude(new BigDecimal("104.0100")).latitude(new BigDecimal("30.0000")).build(),
                StationDO.builder().id(12L).stationName("终点站").build());
    }

    private void stubVisible(List<TransportLegDO> legs) {
        TransportOrderDO order = TransportOrderDO.builder().id(1L).orderNo("NO1").build();
        when(transportOrderService.getByOrderNo("NO1")).thenReturn(order);
        when(transportOrderService.canViewOrderDetail(eq(order), any())).thenReturn(true);
        when(multiLegService.getLegsByOrderId(1L)).thenReturn(legs);
        when(stationService.getSimpleList()).thenReturn(stations());
    }

    @Test
    void legs_stationCoordinates_onlySentWhenRecordHasThem() {
        stubVisible(List.of(leg(11L, 12L)));

        AppSendLegRespVO vo = controller.legs("NO1").getData().get(0);

        assertEquals("起始站", vo.getFromStationName());
        assertEquals(Double.valueOf(104.0100), vo.getFromLongitude());
        assertEquals(Double.valueOf(30.0000), vo.getFromLatitude());
        assertEquals("终点站", vo.getToStationName());
        // 站点档案没录坐标 ⇒ null，不是 0.0（0.0 会被画到几内亚湾）
        assertNull(vo.getToLongitude());
        assertNull(vo.getToLatitude());
    }

    @Test
    void legs_planMetricsPassedThroughVerbatim() {
        stubVisible(List.of(leg(11L, 12L)));

        AppSendLegRespVO vo = controller.legs("NO1").getData().get(0);

        assertEquals(new BigDecimal("12.40"), vo.getDistanceKm());
        assertEquals(Integer.valueOf(45), vo.getDurationMinutes());
        assertEquals("AMAP", vo.getNavigationSource());
        assertEquals(LocalDateTime.of(2026, 10, 6, 8, 30), vo.getEstimatedDeparture());
        assertEquals(Boolean.TRUE, vo.getHandoverRequired());
        assertEquals("运输中", vo.getStatusName());
    }

    @Test
    void legs_missingMetricsStayNull() {
        TransportLegDO bare = TransportLegDO.builder().id(502L).orderId(1L).legSequence(1)
                .fromStationId(11L).toStationId(12L).status(0).build();
        stubVisible(List.of(bare));

        AppSendLegRespVO vo = controller.legs("NO1").getData().get(0);

        assertNull(vo.getDistanceKm());
        assertNull(vo.getDurationMinutes());
        assertNull(vo.getNavigationSource());
        assertNull(vo.getEstimatedDeparture());
        assertNull(vo.getHandoverRequired());
        // 紧凑串为空 ⇒ null（不是空列表），前端据此区分"没测算"与"测算过但没点"
        assertNull(vo.getNavigationPolyline());
    }

    @Test
    void legs_polylineDecodedAsLongitudeThenLatitude() {
        stubVisible(List.of(leg(11L, 12L)));

        List<AppSendLegRespVO.RoadPoint> points = controller.legs("NO1").getData().get(0).getNavigationPolyline();

        assertNotNull(points);
        assertEquals(2, points.size());
        assertEquals(Double.valueOf(104.0100), points.get(0).getLongitude());
        assertEquals(Double.valueOf(30.0000), points.get(0).getLatitude());
        assertEquals(Double.valueOf(104.0200), points.get(1).getLongitude());
    }

    @Test
    void legs_illegalPolylineYieldsNullNotPartialInvention() {
        // 单点非法跳过；全非法 ⇒ null（绝不返回"看起来有轨迹"的空集合或补出来的点）
        assertNull(AppSendController.parseLegPolyline(null));
        assertNull(AppSendController.parseLegPolyline("   "));
        assertNull(AppSendController.parseLegPolyline("abc;104.01"));
        List<AppSendLegRespVO.RoadPoint> mixed = AppSendController.parseLegPolyline("bad;104.01,30.00");
        assertEquals(1, mixed.size());
        assertEquals(Double.valueOf(30.0000), mixed.get(0).getLatitude());
    }

    @Test
    void legs_notOwner_getsEmptyList() {
        TransportOrderDO order = TransportOrderDO.builder().id(1L).orderNo("NO1").build();
        when(transportOrderService.getByOrderNo("NO1")).thenReturn(order);
        when(transportOrderService.canViewOrderDetail(eq(order), any())).thenReturn(false);

        List<AppSendLegRespVO> data = controller.legs("NO1").getData();

        assertTrue(data.isEmpty());
    }
}
