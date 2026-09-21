package cn.iocoder.yudao.module.transport.service.monitoring;

import cn.iocoder.yudao.module.transport.controller.admin.monitoring.vo.MonitoringPlanRespVO;
import cn.iocoder.yudao.module.transport.controller.admin.monitoring.vo.MonitoringTrackRespVO;
import cn.iocoder.yudao.module.transport.controller.admin.monitoring.vo.MonitoringVehicleRespVO;
import cn.iocoder.yudao.module.transport.dal.dataobject.route.RouteDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.route.RouteStationDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.shift.ShiftDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.station.StationDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.vehicle.VehicleDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.vehicle.VehicleLocationTrackDO;
import cn.iocoder.yudao.module.transport.dal.mysql.dispatch.DispatchPlanItemMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.driver.DriverMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.driver.DriverVehicleMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.route.RouteMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.route.RouteStationMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.shift.ShiftExecutionMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.shift.ShiftMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.station.StationMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.vehicle.VehicleLocationMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.vehicle.VehicleLocationTrackMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.vehicle.VehicleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 车辆历史轨迹查询（轨迹回放）纯 Mockito 单测：
 * 时间范围 [当日 00:00, 次日 00:00)、升序点映射、车牌填充与空结果语义。
 */
@ExtendWith(MockitoExtension.class)
class MonitoringServiceImplTest {

    private static final Long VEHICLE_ID = 10L;
    private static final LocalDate DATE = LocalDate.of(2026, 8, 19);

    @Mock private VehicleMapper vehicleMapper;
    @Mock private VehicleLocationTrackMapper vehicleLocationTrackMapper;
    @Mock private VehicleLocationMapper vehicleLocationMapper;
    @Mock private DispatchPlanItemMapper dispatchPlanItemMapper;
    @Mock private DriverMapper driverMapper;
    @Mock private DriverVehicleMapper driverVehicleMapper;
    @Mock private ShiftMapper shiftMapper;
    @Mock private ShiftExecutionMapper shiftExecutionMapper;
    @Mock private RouteMapper routeMapper;
    @Mock private RouteStationMapper routeStationMapper;
    @Mock private StationMapper stationMapper;
    @Mock private VehicleLocationProvider locationProvider;

    private MonitoringServiceImpl monitoringService;

    @BeforeEach
    void setUp() {
        monitoringService = new MonitoringServiceImpl();
        ReflectionTestUtils.setField(monitoringService, "vehicleMapper", vehicleMapper);
        ReflectionTestUtils.setField(monitoringService, "vehicleLocationTrackMapper", vehicleLocationTrackMapper);
        ReflectionTestUtils.setField(monitoringService, "vehicleLocationMapper", vehicleLocationMapper);
        ReflectionTestUtils.setField(monitoringService, "dispatchPlanItemMapper", dispatchPlanItemMapper);
        ReflectionTestUtils.setField(monitoringService, "driverMapper", driverMapper);
        ReflectionTestUtils.setField(monitoringService, "driverVehicleMapper", driverVehicleMapper);
        ReflectionTestUtils.setField(monitoringService, "shiftMapper", shiftMapper);
        ReflectionTestUtils.setField(monitoringService, "shiftExecutionMapper", shiftExecutionMapper);
        ReflectionTestUtils.setField(monitoringService, "routeMapper", routeMapper);
        ReflectionTestUtils.setField(monitoringService, "routeStationMapper", routeStationMapper);
        ReflectionTestUtils.setField(monitoringService, "stationMapper", stationMapper);
        ReflectionTestUtils.setField(monitoringService, "locationProvider", locationProvider);
    }

    @Test
    void getVehicleTrack_withTracks() {
        when(vehicleMapper.selectById(VEHICLE_ID))
                .thenReturn(VehicleDO.builder().id(VEHICLE_ID).plateNo("川A12345").build());
        LocalDateTime t1 = DATE.atTime(8, 0, 0);
        LocalDateTime t2 = DATE.atTime(8, 0, 10);
        when(vehicleLocationTrackMapper.selectByVehicleIdAndTimeRange(
                eq(VEHICLE_ID), eq(DATE.atStartOfDay()), eq(DATE.plusDays(1).atStartOfDay()), anyInt()))
                .thenReturn(List.of(
                        track(t1, 103.01, 30.01, 40.5, 1L),
                        track(t2, 103.02, 30.02, 42.0, 1L)));

        MonitoringTrackRespVO vo = monitoringService.getVehicleTrack(VEHICLE_ID, DATE);

        assertEquals(VEHICLE_ID, vo.getVehicleId());
        assertEquals("川A12345", vo.getPlateNo());
        assertEquals(2, vo.getPoints().size());
        MonitoringTrackRespVO.TrackPoint p1 = vo.getPoints().get(0);
        assertEquals(103.01, p1.getLongitude());
        assertEquals(30.01, p1.getLatitude());
        assertEquals(40.5, p1.getSpeedKmh());
        assertEquals(t1, p1.getReportTime());
        assertEquals(1L, p1.getShiftId());
        assertEquals(t2, vo.getPoints().get(1).getReportTime());
    }

    @Test
    void getVehicleTrack_empty() {
        // 车辆已删除时车牌为 null；无轨迹时返回空列表而非报错
        when(vehicleMapper.selectById(VEHICLE_ID)).thenReturn(null);
        when(vehicleLocationTrackMapper.selectByVehicleIdAndTimeRange(
                eq(VEHICLE_ID), eq(DATE.atStartOfDay()), eq(DATE.plusDays(1).atStartOfDay()), anyInt()))
                .thenReturn(List.of());

        MonitoringTrackRespVO vo = monitoringService.getVehicleTrack(VEHICLE_ID, DATE);

        assertEquals(VEHICLE_ID, vo.getVehicleId());
        assertNull(vo.getPlateNo());
        assertNotNull(vo.getPoints());
        assertTrue(vo.getPoints().isEmpty());
    }

    @Test
    void getVehiclePlan_no_plan_returns_vehicle_info() {
        // Phase 11：车辆无调度方案时返回车牌与位置信息，不报错
        when(vehicleMapper.selectById(VEHICLE_ID))
                .thenReturn(VehicleDO.builder().id(VEHICLE_ID).plateNo("川A12345").build());
        when(dispatchPlanItemMapper.selectList(any())).thenReturn(List.of());

        MonitoringPlanRespVO vo = monitoringService.getVehiclePlan(VEHICLE_ID);

        assertEquals(VEHICLE_ID, vo.getVehicleId());
        assertEquals("川A12345", vo.getPlateNo());
        assertNull(vo.getPlanId()); // 无方案
    }

    @Test
    void getVehicleTrack_timeRangeAndLimit() {
        when(vehicleMapper.selectById(VEHICLE_ID)).thenReturn(null);
        when(vehicleLocationTrackMapper.selectByVehicleIdAndTimeRange(
                eq(VEHICLE_ID), eq(DATE.atStartOfDay()), eq(DATE.plusDays(1).atStartOfDay()), eq(2000)))
                .thenReturn(List.of());

        monitoringService.getVehicleTrack(VEHICLE_ID, DATE);

        // 查询范围必须是 [当日 00:00, 次日 00:00)，上限 2000（与 app 溯源一致）
        verify(vehicleLocationTrackMapper).selectByVehicleIdAndTimeRange(
                VEHICLE_ID, DATE.atStartOfDay(), DATE.plusDays(1).atStartOfDay(), 2000);
    }

    private VehicleLocationTrackDO track(LocalDateTime reportTime, double lng, double lat, double speed, Long shiftId) {
        return VehicleLocationTrackDO.builder()
                .vehicleId(VEHICLE_ID)
                .shiftId(shiftId)
                .longitude(BigDecimal.valueOf(lng))
                .latitude(BigDecimal.valueOf(lat))
                .speedKmh(BigDecimal.valueOf(speed))
                .reportTime(reportTime)
                .build();
    }

    // ==================== 统一位置模型：快照上下文透传到监控 VO ====================

    @Test
    void getRealtimeVehicles_realSnapshot_keepsShiftRouteContext() {
        stubEmptyFleetLookups();
        when(locationProvider.getLocations(any())).thenReturn(Map.of(VEHICLE_ID,
                VehicleLocationSnapshot.builder().vehicleId(VEHICLE_ID).source("REAL").status(1)
                        .longitude(104.1).latitude(30.6)
                        .shiftId(1L).shiftCode("SH001").routeId(1L).routeName("县城—青山镇线")
                        .progress(40).currentStationName("红花村站").nextStationName("青山镇站")
                        .updatedAt(LocalDateTime.now()).build()));

        List<MonitoringVehicleRespVO> vehicles = monitoringService.getRealtimeVehicles();

        assertEquals(1, vehicles.size());
        MonitoringVehicleRespVO vo = vehicles.get(0);
        // 真实位置快照必须带班次/线路上下文（实时公交按线路聚合车辆依赖）
        assertEquals("SH001", vo.getShiftCode());
        assertEquals("县城—青山镇线", vo.getRouteName());
        assertEquals("REAL", vo.getDataSource());
        assertEquals(MonitoringServiceImpl.STATUS_IN_TRANSIT, vo.getStatus().intValue());
        assertEquals(40, vo.getProgress().intValue());
        assertEquals("青山镇站", vo.getNextStationName());
        assertEquals("红花村站", vo.getCurrentStationName());
        assertEquals(104.1, vo.getLongitude());
    }

    @Test
    void getRealtimeVehicles_offlineSnapshot_isIdleWithoutPosition() {
        stubEmptyFleetLookups();
        when(locationProvider.getLocations(any())).thenReturn(Map.of(VEHICLE_ID,
                VehicleLocationSnapshot.builder().vehicleId(VEHICLE_ID).source("OFFLINE").status(0).build()));

        List<MonitoringVehicleRespVO> vehicles = monitoringService.getRealtimeVehicles();

        MonitoringVehicleRespVO vo = vehicles.get(0);
        assertEquals(MonitoringServiceImpl.STATUS_IDLE, vo.getStatus().intValue());
        assertNull(vo.getLongitude()); // OFFLINE 只表示真正没有位置，不编造坐标
        assertNull(vo.getShiftCode());
    }

    private void stubEmptyFleetLookups() {
        when(vehicleMapper.selectList()).thenReturn(List.of(
                VehicleDO.builder().id(VEHICLE_ID).plateNo("川A12345").status(0).build()));
        when(driverMapper.selectList()).thenReturn(List.of());
        when(driverVehicleMapper.selectActiveBindings()).thenReturn(List.of());
        when(shiftMapper.selectList()).thenReturn(List.of());
        when(shiftMapper.selectList(any())).thenReturn(List.of());
        when(routeMapper.selectList()).thenReturn(List.of());
        when(stationMapper.selectList()).thenReturn(List.of());
    }
}
