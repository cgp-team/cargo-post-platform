package cn.iocoder.yudao.module.transport.service.transport.bus;

import cn.iocoder.yudao.module.transport.controller.admin.monitoring.vo.MonitoringMapDataRespVO;
import cn.iocoder.yudao.module.transport.controller.admin.monitoring.vo.MonitoringVehicleRespVO;
import cn.iocoder.yudao.module.transport.controller.app.transport.bus.vo.AppBusLineRespVO;
import cn.iocoder.yudao.module.transport.controller.app.transport.bus.vo.AppBusNearbyRespVO;
import cn.iocoder.yudao.module.transport.controller.app.transport.bus.vo.AppBusRespVO;
import cn.iocoder.yudao.module.transport.dal.dataobject.shift.ShiftDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.station.StationDO;
import cn.iocoder.yudao.module.transport.dal.mysql.shift.ShiftMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.station.StationMapper;
import cn.iocoder.yudao.module.transport.integration.algorithm.AlgorithmClient;
import cn.iocoder.yudao.module.transport.integration.algorithm.dto.AlgorithmRouteRespDTO;
import cn.iocoder.yudao.module.transport.service.monitoring.MonitoringService;
import cn.iocoder.yudao.module.transport.service.transport.transit.TransitProvider;
import cn.iocoder.yudao.module.transport.service.transport.transit.TransitProvider.TransitStation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static cn.iocoder.yudao.module.transport.service.transport.transit.TransitProvider.REAL_TRANSIT;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import static org.mockito.Mockito.when;

/**
 * 小程序实时公交纯 Mockito 单测：复用监控车辆位置 + 线路/班次聚合映射。
 */
@ExtendWith(MockitoExtension.class)
class AppBusServiceImplTest {

    @Mock private MonitoringService monitoringService;
    @Mock private ShiftMapper shiftMapper;
    @Mock private StationMapper stationMapper;
    @Mock private AlgorithmClient algorithmClient;

    private AppBusServiceImpl appBusService;

    @BeforeEach
    void setUp() {
        appBusService = new AppBusServiceImpl();
        ReflectionTestUtils.setField(appBusService, "monitoringService", monitoringService);
        ReflectionTestUtils.setField(appBusService, "shiftMapper", shiftMapper);
        ReflectionTestUtils.setField(appBusService, "stationMapper", stationMapper);
        ReflectionTestUtils.setField(appBusService, "algorithmClient", algorithmClient);
        // 分层数据源：默认无现实公交数据源（未配置高德 key），项目线路层由本测试的站点/线路桩数据提供
        ReflectionTestUtils.setField(appBusService, "transitProviders", List.of());
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

    @Test
    void realtimeBuses_carries_position_for_map() {
        MonitoringVehicleRespVO vehicle = new MonitoringVehicleRespVO();
        vehicle.setVehicleId(7L);
        vehicle.setPlateNo("川A·5201");
        vehicle.setShiftCode("SH001");
        vehicle.setRouteName("县城—青山镇线");
        vehicle.setStatus(1);
        vehicle.setProgress(40);
        vehicle.setLongitude(103.1234567);
        vehicle.setLatitude(30.7654321);
        when(monitoringService.getRealtimeVehicles()).thenReturn(List.of(vehicle));
        when(monitoringService.getMapData()).thenReturn(new MonitoringMapDataRespVO());
        when(shiftMapper.selectList()).thenReturn(List.of(
                ShiftDO.builder().shiftCode("SH001").plannedDurationMinutes(50).build()));

        AppBusRespVO vo = appBusService.getRealtimeBuses().get(0);

        assertEquals(103.1234567, vo.getLongitude());
        assertEquals(30.7654321, vo.getLatitude());
    }

    @Test
    void lines_groups_points_and_buses_by_route() {
        MonitoringVehicleRespVO vehicle = new MonitoringVehicleRespVO();
        vehicle.setVehicleId(7L);
        vehicle.setPlateNo("川A·5201");
        vehicle.setShiftCode("SH001");
        vehicle.setRouteName("县城—青山镇线");
        vehicle.setStatus(1);
        vehicle.setProgress(50);
        when(monitoringService.getRealtimeVehicles()).thenReturn(List.of(vehicle));
        when(shiftMapper.selectList()).thenReturn(List.of(
                ShiftDO.builder().shiftCode("SH001").plannedDurationMinutes(50).build()));

        MonitoringMapDataRespVO mapData = new MonitoringMapDataRespVO();
        MonitoringMapDataRespVO.Route route = new MonitoringMapDataRespVO.Route();
        route.setId(3L);
        route.setRouteCode("C302");
        route.setRouteName("县城—青山镇线");
        route.setDistanceKm(25.5);
        MonitoringMapDataRespVO.Point start = new MonitoringMapDataRespVO.Point();
        start.setSequenceNo(1);
        start.setStationId(1L);
        start.setStationName("县城客运中心");
        start.setLongitude(103.0);
        start.setLatitude(30.0);
        MonitoringMapDataRespVO.Point end = new MonitoringMapDataRespVO.Point();
        end.setSequenceNo(2);
        end.setStationId(2L);
        end.setStationName("青山镇站");
        end.setLongitude(103.5);
        end.setLatitude(30.5);
        route.setPoints(List.of(start, end));
        mapData.setRoutes(List.of(route));
        when(monitoringService.getMapData()).thenReturn(mapData);

        List<AppBusLineRespVO> lines = appBusService.getLines();

        assertEquals(1, lines.size());
        AppBusLineRespVO line = lines.get(0);
        assertEquals(3L, line.getRouteId());
        assertEquals("C302", line.getRouteCode());
        assertEquals("县城客运中心", line.getStartStation());
        assertEquals("青山镇站", line.getEndStation());
        assertEquals(25.5, line.getDistanceKm());
        assertEquals(2, line.getPoints().size());
        assertEquals(1L, line.getPoints().get(0).getStationId());
        assertEquals("县城客运中心", line.getPoints().get(0).getStationName());
        // 该线车辆聚合
        assertEquals(1, line.getBuses().size());
        assertEquals("川A·5201", line.getBuses().get(0).getPlateNo());
    }

    // ==================== 附近实时公交（getNearbyBuses） ====================

    /** 假实现：现实公交层（AMAP）。用于验证分层标识 realTransitAvailable/transitProvider 回传 */
    private static TransitProvider fakeRealProvider() {
        return new TransitProvider() {
            @Override
            public String name() {
                return "AMAP";
            }

            @Override
            public String dataSource() {
                return REAL_TRANSIT;
            }

            @Override
            public boolean available() {
                return true;
            }

            @Override
            public List<TransitStation> searchNearbyStations(double latitude, double longitude, double radiusMeters) {
                return List.of(new TransitStation("人民公园站", 104.002, 30.001, 0.22,
                        List.of("125路"), "人民路1号", REAL_TRANSIT));
            }
        };
    }

    /**
     * 分层标识回归：现实层（AMAP）产出站点时，必须回传 realTransitAvailable=true 与 transitProvider=AMAP。
     * 历史 bug：用 provider.name()（"AMAP"）去比 REAL_TRANSIT 常量，条件永不成立 → 前端会误判"未配置现实公交"。
     */
    @Test
    void nearby_realTransitProvider_marksAvailableAndProviderName() {
        when(stationMapper.selectList()).thenReturn(List.of());
        when(monitoringService.getMapData()).thenReturn(mapData());
        when(monitoringService.getRealtimeVehicles()).thenReturn(List.of());
        ReflectionTestUtils.setField(appBusService, "transitProviders", List.of(fakeRealProvider()));

        AppBusNearbyRespVO resp = appBusService.getNearbyBuses(USER_LAT, USER_LON, 5000.0, null);

        assertTrue(resp.getRealTransitAvailable());
        assertEquals("AMAP", resp.getTransitProvider());
        assertEquals(1, resp.getRealStationCount());
        assertEquals("人民公园站", resp.getNearestStation().getName());
        assertEquals("REAL_TRANSIT", resp.getNearestStation().getDataSource());
        assertEquals(List.of("125路"), resp.getNearestStation().getLines());
    }

    private static final double USER_LAT = 30.0;
    private static final double USER_LON = 104.0;

    private StationDO station(long id, String name, double lon, double lat) {
        return StationDO.builder().id(id).stationName(name)
                .longitude(new BigDecimal(String.valueOf(lon))).latitude(new BigDecimal(String.valueOf(lat))).build();
    }

    private MonitoringVehicleRespVO vehicle(long id, Integer status, Double lon, Double lat,
                                            String shiftCode, String routeName, String nextStation, String dataSource) {
        MonitoringVehicleRespVO v = new MonitoringVehicleRespVO();
        v.setVehicleId(id);
        v.setStatus(status);
        v.setLongitude(lon);
        v.setLatitude(lat);
        v.setShiftCode(shiftCode);
        v.setRouteName(routeName);
        v.setNextStationName(nextStation);
        v.setDataSource(dataSource);
        return v;
    }

    private MonitoringMapDataRespVO.Route route(String name, List<MonitoringMapDataRespVO.Point> points) {
        MonitoringMapDataRespVO.Route r = new MonitoringMapDataRespVO.Route();
        r.setRouteName(name);
        r.setPoints(points);
        return r;
    }

    private MonitoringMapDataRespVO.Point point(Long stationId, String name, double lon, double lat) {
        MonitoringMapDataRespVO.Point p = new MonitoringMapDataRespVO.Point();
        p.setStationId(stationId);
        p.setStationName(name);
        p.setLongitude(lon);
        p.setLatitude(lat);
        return p;
    }

    private MonitoringMapDataRespVO mapData(MonitoringMapDataRespVO.Route... routes) {
        MonitoringMapDataRespVO m = new MonitoringMapDataRespVO();
        m.setRoutes(List.of(routes));
        return m;
    }

    @Test
    void nearby_filters_by_radius_and_prefers_real() {
        when(stationMapper.selectList()).thenReturn(List.of(
                station(1L, "红花村站", 104.005, 30.0),   // 近站 ~0.48km
                station(2L, "远山站", 104.5, 30.4)));       // 远站 > 5km
        when(monitoringService.getMapData()).thenReturn(mapData(
                route("R001", List.of(point(1L, "红花村站", 104.005, 30.0), point(2L, "远山站", 104.5, 30.4)))));
        when(monitoringService.getRealtimeVehicles()).thenReturn(List.of(
                vehicle(10L, 1, 104.006, 30.0, "SH001", "R001", "远山站", "REAL"),    // 近车，真实
                vehicle(20L, 1, 104.5, 30.4, "SH002", "R001", null, "SIMULATED")));   // 远车，模拟

        AppBusNearbyRespVO resp = appBusService.getNearbyBuses(USER_LAT, USER_LON, 5000.0, null);

        assertTrue(resp.getLocated());
        assertEquals(1, resp.getBuses().size());
        assertEquals(10L, resp.getBuses().get(0).getBusId());
        assertEquals(AppBusNearbyRespVO.SOURCE_REAL, resp.getBuses().get(0).getDataSource());
        assertEquals(AppBusNearbyRespVO.STATUS_RUNNING, resp.getBuses().get(0).getStatus());
        assertEquals(1, resp.getNearbyStations().size());
        assertNotNull(resp.getNearestStation());
        assertEquals("红花村站", resp.getNearestStation().getName());
        assertEquals(AppBusNearbyRespVO.SOURCE_REAL, resp.getDataSource());
    }

    @Test
    void nearby_no_vehicle_in_radius_returns_empty() {
        when(stationMapper.selectList()).thenReturn(List.of(station(1L, "红花村站", 104.005, 30.0)));
        when(monitoringService.getMapData()).thenReturn(mapData(route("R001",
                List.of(point(1L, "红花村站", 104.005, 30.0)))));
        when(monitoringService.getRealtimeVehicles()).thenReturn(List.of(
                vehicle(20L, 1, 104.5, 30.4, "SH002", "R001", null, "SIMULATED"))); // 远车被过滤

        AppBusNearbyRespVO resp = appBusService.getNearbyBuses(USER_LAT, USER_LON, 5000.0, null);

        assertTrue(resp.getBuses().isEmpty());
        assertEquals(AppBusNearbyRespVO.SOURCE_NONE, resp.getDataSource());
        assertEquals(1, resp.getNearbyStations().size());
    }

    @Test
    void nearby_no_stations_returns_empty_stations() {
        when(stationMapper.selectList()).thenReturn(List.of());
        when(monitoringService.getMapData()).thenReturn(mapData(route("R001",
                List.of(point(1L, "红花村站", 104.005, 30.0)))));
        when(monitoringService.getRealtimeVehicles()).thenReturn(List.of(
                vehicle(10L, 1, 104.006, 30.0, "SH001", "R001", "远山站", "REAL")));

        AppBusNearbyRespVO resp = appBusService.getNearbyBuses(USER_LAT, USER_LON, 5000.0, null);

        assertTrue(resp.getNearbyStations().isEmpty());
        assertNull(resp.getNearestStation());
        assertEquals(1, resp.getBuses().size());
    }

    @Test
    void nearby_disabled_vehicle_excluded() {
        when(stationMapper.selectList()).thenReturn(List.of(station(1L, "红花村站", 104.005, 30.0)));
        when(monitoringService.getMapData()).thenReturn(mapData(route("R001",
                List.of(point(1L, "红花村站", 104.005, 30.0)))));
        when(monitoringService.getRealtimeVehicles()).thenReturn(List.of(
                vehicle(10L, 2, 104.006, 30.0, "SH001", "R001", null, "REAL"))); // status=2 停用

        AppBusNearbyRespVO resp = appBusService.getNearbyBuses(USER_LAT, USER_LON, 5000.0, null);

        assertTrue(resp.getBuses().isEmpty());
    }

    @Test
    void nearby_status_mapping() {
        when(stationMapper.selectList()).thenReturn(List.of(station(1L, "红花村站", 104.005, 30.0)));
        when(monitoringService.getMapData()).thenReturn(mapData(route("R001",
                List.of(point(1L, "红花村站", 104.005, 30.0)))));
        when(monitoringService.getRealtimeVehicles()).thenReturn(List.of(
                vehicle(1L, 1, 104.006, 30.0, "SH1", "R001", "下一站", "REAL"),
                vehicle(2L, 1, 104.006, 30.0, "SH2", "R001", null, "REAL"),
                vehicle(3L, 0, 104.006, 30.0, "SH3", "R001", null, "SIMULATED")));

        AppBusNearbyRespVO resp = appBusService.getNearbyBuses(USER_LAT, USER_LON, 5000.0, null);

        assertEquals(AppBusNearbyRespVO.STATUS_RUNNING, resp.getBuses().get(0).getStatus());
        assertEquals(AppBusNearbyRespVO.STATUS_ARRIVED, resp.getBuses().get(1).getStatus());
        assertEquals(AppBusNearbyRespVO.STATUS_IDLE, resp.getBuses().get(2).getStatus());
    }

    @Test
    void nearby_invalid_coordinates_uses_district_fallback() {
        when(stationMapper.selectList()).thenReturn(List.of(
                station(1L, "红花村站", 104.005, 30.0),
                station(3L, "青山镇站", 104.3, 30.3)));
        when(monitoringService.getMapData()).thenReturn(mapData(
                route("R001", List.of(point(1L, "红花村站", 104.005, 30.0))),
                route("R002", List.of(point(3L, "青山镇站", 104.3, 30.3)))));
        when(monitoringService.getRealtimeVehicles()).thenReturn(List.of(
                vehicle(10L, 1, 104.006, 30.0, "SH001", "R001", "远山站", "REAL"),
                vehicle(20L, 1, 104.3, 30.3, "SH002", "R002", null, "SIMULATED")));

        AppBusNearbyRespVO resp = appBusService.getNearbyBuses(null, null, null, "红花");

        assertEquals(Boolean.FALSE, resp.getLocated());
        assertEquals("DISTRICT", resp.getLocationLevel());
        assertEquals(1, resp.getNearbyStations().size());
        assertEquals("红花村站", resp.getNearbyStations().get(0).getName());
        assertEquals(1, resp.getBuses().size());
        assertEquals(10L, resp.getBuses().get(0).getBusId());
    }

    @Test
    void nearby_simulated_only_data_source() {
        when(stationMapper.selectList()).thenReturn(List.of(station(1L, "红花村站", 104.005, 30.0)));
        when(monitoringService.getMapData()).thenReturn(mapData(route("R001",
                List.of(point(1L, "红花村站", 104.005, 30.0)))));
        when(monitoringService.getRealtimeVehicles()).thenReturn(List.of(
                vehicle(10L, 1, 104.006, 30.0, "SH001", "R001", "远山站", "SIMULATED")));

        AppBusNearbyRespVO resp = appBusService.getNearbyBuses(USER_LAT, USER_LON, 5000.0, null);

        assertEquals(1, resp.getBuses().size());
        assertEquals(AppBusNearbyRespVO.SOURCE_SIMULATED, resp.getBuses().get(0).getDataSource());
        assertEquals(AppBusNearbyRespVO.SOURCE_SIMULATED, resp.getDataSource());
    }

    // ==================== 车辆→下一站 ETA（高德路网） ====================

    private void stubNearbyWithNextStation(String nextStation, String dataSource) {
        when(stationMapper.selectList()).thenReturn(List.of(
                station(1L, "红花村站", 104.005, 30.0),
                station(2L, "青山镇站", 104.1234, 30.6012)));
        when(monitoringService.getMapData()).thenReturn(mapData(route("R001",
                List.of(point(1L, "红花村站", 104.005, 30.0), point(2L, "青山镇站", 104.1234, 30.6012)))));
        MonitoringVehicleRespVO v = vehicle(10L, 1, 104.005, 30.01, "SH001", "R001", nextStation, dataSource);
        v.setLastLocationTime(java.time.LocalDateTime.now()); // 新鲜：<5min → REAL_FRESH
        when(monitoringService.getRealtimeVehicles()).thenReturn(List.of(v));
    }

    @Test
    void nearby_eta_from_route_success() {
        stubNearbyWithNextStation("青山镇站", "REAL");
        when(algorithmClient.route(any())).thenReturn(AlgorithmRouteRespDTO.builder()
                .available(true).distanceKm(2.8).durationSeconds(360.0).provider("amap").build());

        AppBusNearbyRespVO resp = appBusService.getNearbyBuses(USER_LAT, USER_LON, 5000.0, null);

        AppBusNearbyRespVO.NearbyBus bus = resp.getBuses().get(0);
        assertEquals("REAL_FRESH", bus.getLocationSource());
        assertEquals(2.8, bus.getDistanceToNextStationKm());
        assertEquals(6, bus.getEtaMinutes()); // ceil(360/60)=6
        assertEquals("AMAP", bus.getRouteProvider());
        assertNotNull(bus.getLastLocationTime());
        assertNotNull(bus.getUpdatedAt());
    }

    @Test
    void nearby_eta_route_unavailable_keeps_null() {
        stubNearbyWithNextStation("青山镇站", "REAL");
        when(algorithmClient.route(any())).thenReturn(AlgorithmRouteRespDTO.builder()
                .available(false).reasonCode("ROUTE_UNAVAILABLE").build());

        AppBusNearbyRespVO resp = appBusService.getNearbyBuses(USER_LAT, USER_LON, 5000.0, null);

        assertNull(resp.getBuses().get(0).getEtaMinutes());
        assertNull(resp.getBuses().get(0).getDistanceToNextStationKm());
    }

    @Test
    void nearby_eta_simulated_location_source() {
        stubNearbyWithNextStation("青山镇站", "SIMULATED");
        when(algorithmClient.route(any())).thenReturn(AlgorithmRouteRespDTO.builder()
                .available(true).distanceKm(2.8).durationSeconds(360.0).provider("amap").build());

        AppBusNearbyRespVO resp = appBusService.getNearbyBuses(USER_LAT, USER_LON, 5000.0, null);

        assertEquals("SIMULATED", resp.getBuses().get(0).getLocationSource());
        // 模拟位置也给出路网 ETA（dataSource 仍标注 SIMULATED，前端不伪装成真实）
        assertEquals(6, resp.getBuses().get(0).getEtaMinutes());
    }

    @Test
    void nearby_eta_cache_reused_across_calls() {
        stubNearbyWithNextStation("青山镇站", "REAL");
        when(algorithmClient.route(any())).thenReturn(AlgorithmRouteRespDTO.builder()
                .available(true).distanceKm(2.8).durationSeconds(360.0).provider("amap").build());

        appBusService.getNearbyBuses(USER_LAT, USER_LON, 5000.0, null);
        appBusService.getNearbyBuses(USER_LAT, USER_LON, 5000.0, null); // 同坐标同站 → 缓存命中

        verify(algorithmClient, times(1)).route(any());
    }

    @Test
    void nearby_eta_missing_next_station_skips_route() {
        stubNearbyWithNextStation(null, "REAL");

        appBusService.getNearbyBuses(USER_LAT, USER_LON, 5000.0, null);

        verify(algorithmClient, never()).route(any());
    }

}
