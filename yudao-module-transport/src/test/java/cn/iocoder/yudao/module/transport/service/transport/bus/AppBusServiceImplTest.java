package cn.iocoder.yudao.module.transport.service.transport.bus;

import cn.iocoder.yudao.module.transport.controller.admin.monitoring.vo.MonitoringMapDataRespVO;
import cn.iocoder.yudao.module.transport.controller.admin.monitoring.vo.MonitoringVehicleRespVO;
import cn.iocoder.yudao.module.transport.controller.app.transport.bus.vo.AppBusRespVO;
import cn.iocoder.yudao.module.transport.dal.dataobject.shift.ShiftDO;
import cn.iocoder.yudao.module.transport.dal.mysql.shift.ShiftMapper;
import cn.iocoder.yudao.module.transport.service.monitoring.MonitoringService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * 小程序实时公交纯 Mockito 单测：复用监控车辆位置 + 线路/班次聚合映射。
 */
@ExtendWith(MockitoExtension.class)
class AppBusServiceImplTest {

    @Mock private MonitoringService monitoringService;
    @Mock private ShiftMapper shiftMapper;

    private AppBusServiceImpl appBusService;

    @BeforeEach
    void setUp() {
        appBusService = new AppBusServiceImpl();
        ReflectionTestUtils.setField(appBusService, "monitoringService", monitoringService);
        ReflectionTestUtils.setField(appBusService, "shiftMapper", shiftMapper);
    }

    @Test
    void realtimeBuses_maps_route_start_end_and_eta() {
        // 监控：一辆在途车（班次 SH001 / 线路 县城—青山镇线 / 进度 40）
        MonitoringVehicleRespVO vehicle = new MonitoringVehicleRespVO();
        vehicle.setVehicleId(7L);
        vehicle.setPlateNo("川A·5201");
        vehicle.setShiftCode("SH001");
        vehicle.setRouteName("县城—青山镇线");
        vehicle.setStatus(1);
        vehicle.setNextStationName("红花村站");
        vehicle.setProgress(40);
        vehicle.setSpeedKmh(42.0);
        when(monitoringService.getRealtimeVehicles()).thenReturn(List.of(vehicle));

        // 地图数据：线路起点/终点
        MonitoringMapDataRespVO mapData = new MonitoringMapDataRespVO();
        MonitoringMapDataRespVO.Route route = new MonitoringMapDataRespVO.Route();
        route.setRouteName("县城—青山镇线");
        MonitoringMapDataRespVO.Point start = new MonitoringMapDataRespVO.Point();
        start.setStationName("县城客运中心");
        MonitoringMapDataRespVO.Point end = new MonitoringMapDataRespVO.Point();
        end.setStationName("青山镇站");
        route.setPoints(List.of(start, end));
        mapData.setRoutes(List.of(route));
        when(monitoringService.getMapData()).thenReturn(mapData);

        // 班次计划时长 50 分钟 → ETA = (100-40)/100*50 = 30
        when(shiftMapper.selectList()).thenReturn(List.of(
                ShiftDO.builder().shiftCode("SH001").plannedDurationMinutes(50).build()));

        List<AppBusRespVO> buses = appBusService.getRealtimeBuses();

        assertEquals(1, buses.size());
        AppBusRespVO vo = buses.get(0);
        assertEquals(7L, vo.getBusId());
        assertEquals("川A·5201", vo.getPlateNo());
        assertEquals("SH001", vo.getShiftCode());
        assertEquals("县城客运中心", vo.getStartStation());
        assertEquals("青山镇站", vo.getEndStation());
        assertEquals(1, vo.getStatus());
        assertEquals("红花村站", vo.getNextStation());
        assertEquals(30, vo.getEtaMinutes());
        assertEquals(40, vo.getProgress());
    }

    @Test
    void realtimeBuses_filters_out_shiftless_vehicles() {
        MonitoringVehicleRespVO idle = new MonitoringVehicleRespVO();
        idle.setVehicleId(8L);
        idle.setShiftCode(null); // 无班次 → 不进公交列表
        when(monitoringService.getRealtimeVehicles()).thenReturn(List.of(idle));
        when(monitoringService.getMapData()).thenReturn(new MonitoringMapDataRespVO());
        when(shiftMapper.selectList()).thenReturn(List.of());

        assertTrue(appBusService.getRealtimeBuses().isEmpty());
    }

}
