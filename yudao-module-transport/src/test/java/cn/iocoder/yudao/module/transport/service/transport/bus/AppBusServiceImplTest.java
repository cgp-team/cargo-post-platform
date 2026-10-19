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
import cn.iocoder.yudao.module.transport.integration.algorithm.dto.AlgorithmRouteReqDTO;
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
        route.setId(41L);
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
        // 线路编号：车辆只带 routeName，编号是后端按名字回查监控地图数据得到的（#110）。
        // 前端拿它跳线路详情 —— 以前只能自己按名字回查，名字一错就点不进地图。
        assertEquals(41L, vo.getRouteId());
        assertEquals("县城客运中心", vo.getStartStation());
        assertEquals("青山镇站", vo.getEndStation());
        assertEquals(1, vo.getStatus());
        assertEquals("红花村站", vo.getNextStation());
        assertEquals(30, vo.getEtaMinutes());
        assertEquals(40, vo.getProgress());
    }

    /**
     * #110：线路名回查不上监控地图数据时，<b>编号与起终点一起不给</b>。
     *
     * 钉的是"同生同灭"这一条，不是"给不给得出编号"：join 上了三样一起给、join 不上三样一起 null。
     * 半截形状（有起终点却没有编号）会长出一张看着完整、点了没反应的卡片 ——
     * 那正是这条链路以前在真机上的表现（审计 §69）。
     */
    @Test
    void realtimeBuses_routeIdAndStations_areGivenTogetherOrNotAtAll() {
        MonitoringVehicleRespVO vehicle = new MonitoringVehicleRespVO();
        vehicle.setVehicleId(8L);
        vehicle.setShiftCode("SH009");
        vehicle.setRouteName("查无此线");     // 监控地图数据里没有这条线
        vehicle.setStatus(1);
        when(monitoringService.getRealtimeVehicles()).thenReturn(List.of(vehicle));

        MonitoringMapDataRespVO.Route other = new MonitoringMapDataRespVO.Route();
        other.setId(99L);
        other.setRouteName("县城—青山镇线");
        MonitoringMapDataRespVO.Point start = new MonitoringMapDataRespVO.Point();
        start.setStationName("县城客运中心");
        other.setPoints(List.of(start));
        MonitoringMapDataRespVO mapData = new MonitoringMapDataRespVO();
        mapData.setRoutes(List.of(other));
        when(monitoringService.getMapData()).thenReturn(mapData);
        when(shiftMapper.selectList()).thenReturn(List.of());

        List<AppBusRespVO> buses = appBusService.getRealtimeBuses();

        assertEquals(1, buses.size());
        AppBusRespVO vo = buses.get(0);
        assertNull(vo.getRouteId());
        assertNull(vo.getStartStation());
        assertNull(vo.getEndStation());
        // 名字仍然要给：前端拿它显示卡片标题，只是"看这条线"那一跳不能给
        assertEquals("查无此线", vo.getRouteName());
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
                vehicle(20L, 1, 104.5, 30.4, "SH002", "R001", null, "REAL_STALE")));  // 远车，过期上报

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

    /**
     * #110：{@code /bus/nearby} 的车辆卡片与项目自建线路都要带 {@code routeId}，
     * 且两处的编号必须<b>是同一个值</b>（同一个 join 的产物，不是两次各自查名）。
     *
     * 反面对照：现实公交（高德那一路）今天给不出编号 —— 那条支路留 null，
     * 由前端据此不给可点态，不许拿线路名编一个假编号顶上。
     */
    @Test
    void nearby_vehicleAndProjectLine_carryTheSameRouteId() {
        when(stationMapper.selectList()).thenReturn(List.of(
                station(1L, "红花村站", 104.005, 30.0)));
        MonitoringMapDataRespVO.Route route = route("R001",
                List.of(point(1L, "红花村站", 104.005, 30.0), point(2L, "远山站", 104.5, 30.4)));
        route.setId(77L);
        when(monitoringService.getMapData()).thenReturn(mapData(route));
        when(monitoringService.getRealtimeVehicles()).thenReturn(List.of(
                vehicle(10L, 1, 104.006, 30.0, "SH001", "R001", "远山站", "REAL")));

        AppBusNearbyRespVO resp = appBusService.getNearbyBuses(USER_LAT, USER_LON, 5000.0, null);

        assertEquals(1, resp.getBuses().size());
        assertEquals(77L, resp.getBuses().get(0).getRouteId());
        assertFalse(resp.getLines().isEmpty(), "这一屏没有线路可给，下面那条断言就是空的");
        assertEquals(77L, resp.getLines().get(0).getRouteId());
    }

    @Test
    void nearby_no_vehicle_in_radius_returns_empty() {
        when(stationMapper.selectList()).thenReturn(List.of(station(1L, "红花村站", 104.005, 30.0)));
        when(monitoringService.getMapData()).thenReturn(mapData(route("R001",
                List.of(point(1L, "红花村站", 104.005, 30.0)))));
        when(monitoringService.getRealtimeVehicles()).thenReturn(List.of(
                vehicle(20L, 1, 104.5, 30.4, "SH002", "R001", null, "REAL_STALE"))); // 远车被过滤

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
                vehicle(3L, 0, 104.006, 30.0, "SH3", "R001", null, "REAL_STALE")));

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
                vehicle(20L, 1, 104.3, 30.3, "SH002", "R002", null, "REAL_STALE")));

        AppBusNearbyRespVO resp = appBusService.getNearbyBuses(null, null, null, "红花");

        assertEquals(Boolean.FALSE, resp.getLocated());
        assertEquals("DISTRICT", resp.getLocationLevel());
        assertEquals(1, resp.getNearbyStations().size());
        assertEquals("红花村站", resp.getNearbyStations().get(0).getName());
        assertEquals(1, resp.getBuses().size());
        assertEquals(10L, resp.getBuses().get(0).getBusId());
    }

    @Test
    void nearby_stale_only_data_source() {
        when(stationMapper.selectList()).thenReturn(List.of(station(1L, "红花村站", 104.005, 30.0)));
        when(monitoringService.getMapData()).thenReturn(mapData(route("R001",
                List.of(point(1L, "红花村站", 104.005, 30.0)))));
        when(monitoringService.getRealtimeVehicles()).thenReturn(List.of(
                vehicle(10L, 1, 104.006, 30.0, "SH001", "R001", "远山站", "REAL_STALE")));

        AppBusNearbyRespVO resp = appBusService.getNearbyBuses(USER_LAT, USER_LON, 5000.0, null);

        assertEquals(1, resp.getBuses().size());
        assertEquals("REAL_STALE", resp.getBuses().get(0).getDataSource());
        assertEquals(AppBusNearbyRespVO.SOURCE_REAL, resp.getDataSource());
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
    void nearby_eta_stale_location_source() {
        stubNearbyWithNextStation("青山镇站", "REAL");
        // 上报时间超过 5 分钟 → REAL_STALE（司机中断上报但未超过监控窗口）
        MonitoringVehicleRespVO stale = vehicle(10L, 1, 104.005, 30.01, "SH001", "R001", "青山镇站", "REAL");
        stale.setLastLocationTime(java.time.LocalDateTime.now().minusMinutes(10));
        when(monitoringService.getRealtimeVehicles()).thenReturn(List.of(stale));
        when(algorithmClient.route(any())).thenReturn(AlgorithmRouteRespDTO.builder()
                .available(true).distanceKm(2.8).durationSeconds(360.0).provider("amap").build());

        AppBusNearbyRespVO resp = appBusService.getNearbyBuses(USER_LAT, USER_LON, 5000.0, null);

        assertEquals("REAL_STALE", resp.getBuses().get(0).getLocationSource());
        // 过期真实位置仍给出路网 ETA
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

    // ==================== 线路真实道路轨迹（getLinePolyline） ====================
    // 硬规则：线路轨迹的每个点都必须来自真实路网。某段取不到道路几何就在那里截断，
    // 绝不把相邻两站直连补进"道路轨迹"（历史 bug：算法不可用时补两点直线，前端当成真实线路画）。

    private MonitoringMapDataRespVO mapDataWithRouteId(long routeId, MonitoringMapDataRespVO.Point... points) {
        MonitoringMapDataRespVO.Route route = new MonitoringMapDataRespVO.Route();
        route.setId(routeId);
        route.setRouteName("R" + routeId);
        route.setPoints(List.of(points));
        MonitoringMapDataRespVO data = new MonitoringMapDataRespVO();
        data.setRoutes(List.of(route));
        return data;
    }

    private AlgorithmRouteRespDTO.PolylinePoint road(double lon, double lat) {
        return AlgorithmRouteRespDTO.PolylinePoint.builder().longitude(lon).latitude(lat).build();
    }

    /** 没有任何真实道路来源：返回 null（前端显示"暂无真实路线"），而不是把站点连线交出去 */
    @Test
    void linePolyline_withoutRoadSource_returnsNullNotStationLine() {
        when(monitoringService.getMapData()).thenReturn(mapDataWithRouteId(7L,
                point(1L, "A站", 104.000, 30.000), point(2L, "B站", 104.100, 30.100)));
        when(algorithmClient.route(any())).thenReturn(AlgorithmRouteRespDTO.builder()
                .available(false).reasonCode("REAL_ROAD_UNAVAILABLE").build());

        assertNull(appBusService.getLinePolyline(7L));
    }

    /** 断线即截断：B→C 没有道路几何就停在这里，C→D 连请求都不发，C/D 站坐标一个不许出现在轨迹里 */
    @Test
    void linePolyline_truncatesAtFirstSegmentWithoutRoad() {
        when(monitoringService.getMapData()).thenReturn(mapDataWithRouteId(8L,
                point(1L, "A站", 104.000, 30.000), point(2L, "B站", 104.100, 30.100),
                point(3L, "C站", 104.200, 30.200), point(4L, "D站", 104.300, 30.300)));
        when(algorithmClient.route(any())).thenAnswer(invocation -> {
            AlgorithmRouteReqDTO req = invocation.getArgument(0);
            boolean firstSegment = Double.compare(req.getDestination().getLatitude(), 30.100) == 0;
            return firstSegment
                    ? AlgorithmRouteRespDTO.builder().available(true).provider("amap")
                            .polyline(List.of(road(104.000, 30.000), road(104.050, 30.060), road(104.100, 30.100)))
                            .build()
                    : AlgorithmRouteRespDTO.builder().available(false).reasonCode("REAL_ROAD_UNAVAILABLE").build();
        });

        List<AppBusLineRespVO.RoadPoint> polyline = appBusService.getLinePolyline(8L);

        assertNotNull(polyline);
        assertEquals(3, polyline.size());
        assertEquals(104.050, polyline.get(1).getLongitude(), 1e-9);
        assertTrue(polyline.stream().noneMatch(p -> p.getLongitude() >= 104.200));
        verify(algorithmClient, times(2)).route(any());
    }

    /** 来源白名单：GraphHopper(OSM) 与高德一样是真实路网，必须被接受，不再因为只有"provider != amap"被丢掉 */
    @Test
    void linePolyline_acceptsGraphhopperOsmSource() {
        when(monitoringService.getMapData()).thenReturn(mapDataWithRouteId(9L,
                point(1L, "A站", 104.000, 30.000), point(2L, "B站", 104.100, 30.100)));
        when(algorithmClient.route(any())).thenReturn(AlgorithmRouteRespDTO.builder()
                .available(true).provider("graphhopper")
                .polyline(List.of(road(104.000, 30.000), road(104.080, 30.090), road(104.100, 30.100))).build());

        List<AppBusLineRespVO.RoadPoint> polyline = appBusService.getLinePolyline(9L);

        assertEquals(3, polyline.size());
    }

    /** 白名单外的来源（估算/直线）即使带着几何也不可信：按"没有路线"处理 */
    @Test
    void linePolyline_rejectsNonRoadProviderLabel() {
        when(monitoringService.getMapData()).thenReturn(mapDataWithRouteId(10L,
                point(1L, "A站", 104.000, 30.000), point(2L, "B站", 104.100, 30.100)));
        when(algorithmClient.route(any())).thenReturn(AlgorithmRouteRespDTO.builder()
                .available(true).provider("euclidean")
                .polyline(List.of(road(104.000, 30.000), road(104.100, 30.100))).build());

        assertNull(appBusService.getLinePolyline(10L));
    }

}
