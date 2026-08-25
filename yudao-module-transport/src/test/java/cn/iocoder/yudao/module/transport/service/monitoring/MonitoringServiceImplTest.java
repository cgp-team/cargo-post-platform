package cn.iocoder.yudao.module.transport.service.monitoring;

import cn.iocoder.yudao.module.transport.controller.admin.monitoring.vo.MonitoringPlanRespVO;
import cn.iocoder.yudao.module.transport.controller.admin.monitoring.vo.MonitoringTrackRespVO;
import cn.iocoder.yudao.module.transport.dal.dataobject.vehicle.VehicleDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.vehicle.VehicleLocationTrackDO;
import cn.iocoder.yudao.module.transport.dal.mysql.dispatch.DispatchPlanItemMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.vehicle.VehicleLocationMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.vehicle.VehicleLocationTrackMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.vehicle.VehicleMapper;
import cn.iocoder.yudao.module.transport.service.simulation.SimulationEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

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
    @Mock private SimulationEngine simulationEngine;
    @Mock private DispatchPlanItemMapper dispatchPlanItemMapper;

    private MonitoringServiceImpl monitoringService;

    @BeforeEach
    void setUp() {
        monitoringService = new MonitoringServiceImpl();
        ReflectionTestUtils.setField(monitoringService, "vehicleMapper", vehicleMapper);
        ReflectionTestUtils.setField(monitoringService, "vehicleLocationTrackMapper", vehicleLocationTrackMapper);
        ReflectionTestUtils.setField(monitoringService, "vehicleLocationMapper", vehicleLocationMapper);
        ReflectionTestUtils.setField(monitoringService, "simulationEngine", simulationEngine);
        ReflectionTestUtils.setField(monitoringService, "dispatchPlanItemMapper", dispatchPlanItemMapper);
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
}
