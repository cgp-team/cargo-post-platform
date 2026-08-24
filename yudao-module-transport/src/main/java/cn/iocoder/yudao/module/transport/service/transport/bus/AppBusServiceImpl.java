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
import cn.iocoder.yudao.module.transport.util.GeoDistanceUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 小程序实时公交 Service 实现。
 * 车辆位置直接复用 {@link MonitoringService#getRealtimeVehicles()}（司机上报 5 分钟优先，否则时刻插值），
 * 线路起终点取自 {@link MonitoringService#getMapData()} 的线路站点序列，ETA 由进度与班次计划时长估算。
 */
@Service
@Validated
@Slf4j
public class AppBusServiceImpl implements AppBusService {

    @Resource private MonitoringService monitoringService;
    @Resource private ShiftMapper shiftMapper;
    @Resource private StationMapper stationMapper;
    @Resource private AlgorithmClient algorithmClient;

    /** radius 默认值：5000 米 */
    private static final double DEFAULT_RADIUS_M = 5000;
    /** radius 合理上限：50 公里（防异常超大范围） */
    private static final double MAX_RADIUS_M = 50_000;
    /** 路线缓存 TTL：60 秒（车辆 GPS 每秒变化，不能每次 15s 刷新都打高德） */
    private static final long ETA_CACHE_TTL_MS = 60_000;

    /** 路线缓存：key = rounded(车辆坐标,4位):下一站id → 共享相同路线的 ETA（节流高德） */
    private final Map<String, RouteEta> etaCache = new ConcurrentHashMap<>();

    /** 路线缓存条目 */
    private record RouteEta(Double distanceKm, Integer etaMinutes, String provider, long expireAt) {
    }

    @Override
    public List<AppBusRespVO> getRealtimeBuses() {
        return getRealtimeBuses(monitoringService.getMapData());
    }

    /** 实时公交列表（地图数据由调用方加载，供 getLines 复用避免重复加载） */
    private List<AppBusRespVO> getRealtimeBuses(MonitoringMapDataRespVO mapData) {
        List<MonitoringVehicleRespVO> vehicles = monitoringService.getRealtimeVehicles();
        // 线路名称 → 线路（取起点/终点站名）
        Map<String, MonitoringMapDataRespVO.Route> routeByName = mapData.getRoutes() == null ? Map.of()
                : mapData.getRoutes().stream().collect(Collectors.toMap(
                        MonitoringMapDataRespVO.Route::getRouteName, Function.identity(), (a, b) -> a));
        // 班次编码 → 计划时长（ETA 估算）
        Map<String, Integer> durationByShiftCode = shiftMapper.selectList().stream()
                .collect(Collectors.toMap(ShiftDO::getShiftCode,
                        s -> s.getPlannedDurationMinutes() != null ? s.getPlannedDurationMinutes() : 60,
                        (a, b) -> a));
        return vehicles.stream()
                // 只有分配到班次的车辆才进入公交列表（在途行驶中 / 空闲停靠起点）
                .filter(v -> v.getShiftCode() != null)
                .map(v -> {
                    AppBusRespVO vo = new AppBusRespVO();
                    vo.setBusId(v.getVehicleId());
                    vo.setPlateNo(v.getPlateNo());
                    vo.setShiftCode(v.getShiftCode());
                    vo.setRouteName(v.getRouteName());
                    MonitoringMapDataRespVO.Route route = routeByName.get(v.getRouteName());
                    if (route != null && route.getPoints() != null && !route.getPoints().isEmpty()) {
                        vo.setStartStation(route.getPoints().get(0).getStationName());
                        vo.setEndStation(route.getPoints().get(route.getPoints().size() - 1).getStationName());
                    }
                    vo.setStatus(v.getStatus());
                    vo.setNextStation(v.getNextStationName());
                    vo.setLongitude(v.getLongitude());
                    vo.setLatitude(v.getLatitude());
                    vo.setProgress(v.getProgress());
                    vo.setSpeedKmh(v.getSpeedKmh());
                    // ETA = 剩余进度占比 × 班次计划时长（向下取整至少 1 分钟）
                    int duration = durationByShiftCode.getOrDefault(v.getShiftCode(), 60);
                    int progress = v.getProgress() != null ? v.getProgress() : 0;
                    vo.setEtaMinutes(Math.max(1, Math.round((100 - progress) / 100.0f * duration)));
                    return vo;
                }).toList();
    }

    @Override
    public List<AppBusLineRespVO> getLines() {
        MonitoringMapDataRespVO mapData = monitoringService.getMapData();
        List<AppBusRespVO> buses = getRealtimeBuses(mapData); // 复用同一份地图数据，不重复加载
        // 线路名称 → 在线车辆（无线路的车辆不计入）
        Map<String, List<AppBusRespVO>> busesByRoute = buses.stream()
                .filter(b -> b.getRouteName() != null)
                .collect(Collectors.groupingBy(AppBusRespVO::getRouteName));
        if (mapData.getRoutes() == null) {
            return List.of();
        }
        return mapData.getRoutes().stream().map(route -> {
            AppBusLineRespVO vo = new AppBusLineRespVO();
            vo.setRouteId(route.getId());
            vo.setRouteCode(route.getRouteCode());
            vo.setRouteName(route.getRouteName());
            vo.setDistanceKm(route.getDistanceKm());
            List<MonitoringMapDataRespVO.Point> points = route.getPoints() == null ? List.of() : route.getPoints();
            vo.setPoints(points.stream().map(p -> {
                AppBusLineRespVO.Point point = new AppBusLineRespVO.Point();
                point.setSequenceNo(p.getSequenceNo());
                point.setStationId(p.getStationId());
                point.setStationName(p.getStationName());
                point.setLongitude(p.getLongitude());
                point.setLatitude(p.getLatitude());
                point.setPlannedMinutes(p.getPlannedMinutes());
                return point;
            }).toList());
            if (!points.isEmpty()) {
                vo.setStartStation(points.get(0).getStationName());
                vo.setEndStation(points.get(points.size() - 1).getStationName());
            }
            vo.setBuses(busesByRoute.getOrDefault(route.getRouteName(), List.of()));
            return vo;
        }).toList();
    }

    @Override
    public AppBusNearbyRespVO getNearbyBuses(Double latitude, Double longitude, Double radius, String district) {
        AppBusNearbyRespVO resp = new AppBusNearbyRespVO();
        boolean located = latitude != null && longitude != null
                && latitude >= -90 && latitude <= 90 && longitude >= -180 && longitude <= 180;
        resp.setLocated(located);
        resp.setLocationLevel(located ? "PRECISE" : (district != null && !district.isBlank() ? "DISTRICT" : "UNKNOWN"));
        double radM = radius != null && radius > 0 ? Math.min(radius, MAX_RADIUS_M) : DEFAULT_RADIUS_M;
        double radKm = radM / 1000.0;

        // 附近站点：有精确坐标按 Haversine 过滤；无坐标按区域名（站点名称/地址）模糊匹配
        List<AppBusNearbyRespVO.NearbyStation> nearbyStations = new ArrayList<>();
        List<StationDO> stations = stationMapper.selectList();
        Map<String, StationDO> stationByName = stations.stream()
                .filter(s -> s.getStationName() != null)
                .collect(Collectors.toMap(StationDO::getStationName, Function.identity(), (a, b) -> a));
        for (StationDO station : stations) {
            if (station.getLongitude() == null || station.getLatitude() == null) {
                continue;
            }
            Double distKm = null;
            if (located) {
                double d = GeoDistanceUtil.haversineKm(longitude, latitude,
                        station.getLongitude().doubleValue(), station.getLatitude().doubleValue());
                if (d > radKm) {
                    continue;
                }
                distKm = round2(d);
            } else if (district != null && !district.isBlank()) {
                boolean hit = (station.getStationName() != null && station.getStationName().contains(district))
                        || (station.getAddress() != null && station.getAddress().contains(district));
                if (!hit) {
                    continue;
                }
            } else {
                continue;
            }
            AppBusNearbyRespVO.NearbyStation ns = new AppBusNearbyRespVO.NearbyStation();
            ns.setId(station.getId());
            ns.setName(station.getStationName());
            ns.setLongitude(station.getLongitude().doubleValue());
            ns.setLatitude(station.getLatitude().doubleValue());
            ns.setDistanceKm(distKm);
            nearbyStations.add(ns);
        }
        nearbyStations.sort(Comparator.comparing(AppBusNearbyRespVO.NearbyStation::getDistanceKm,
                Comparator.nullsLast(Comparator.naturalOrder())));
        resp.setNearbyStations(nearbyStations);
        resp.setNearestStation(nearbyStations.isEmpty() ? null : nearbyStations.get(0));

        // 附近车辆：复用监控车辆（真实上报 5min 优先 / 模拟插值），Haversine 过滤 + 距离排序
        MonitoringMapDataRespVO mapData = monitoringService.getMapData();
        Map<String, MonitoringMapDataRespVO.Route> routeByName = mapData.getRoutes() == null ? Map.of()
                : mapData.getRoutes().stream().collect(Collectors.toMap(
                        MonitoringMapDataRespVO.Route::getRouteName, Function.identity(), (a, b) -> a));
        // 区域 fallback 下：只返回"途经附近站点"线路上的车辆
        Set<Long> nearbyStationIds = nearbyStations.stream()
                .map(AppBusNearbyRespVO.NearbyStation::getId).collect(Collectors.toSet());
        Set<String> nearbyRouteNames = mapData.getRoutes() == null ? Set.of()
                : mapData.getRoutes().stream()
                        .filter(r -> r.getPoints() != null && r.getPoints().stream()
                                .anyMatch(p -> nearbyStationIds.contains(p.getStationId())))
                        .map(MonitoringMapDataRespVO.Route::getRouteName).collect(Collectors.toSet());

        List<AppBusNearbyRespVO.NearbyBus> buses = new ArrayList<>();
        boolean hasReal = false;
        boolean hasSimulated = false;
        for (MonitoringVehicleRespVO v : monitoringService.getRealtimeVehicles()) {
            if (v.getStatus() != null && v.getStatus() == 2) {
                continue; // 停用车辆
            }
            Double vlon = v.getLongitude();
            Double vlat = v.getLatitude();
            if (vlon == null || vlat == null) {
                continue; // 无位置（NO_LOCATION）不进附近列表
            }
            if (!located) {
                // 区域 fallback：仅返回附近站点所在线路的车辆（无精确坐标无法算距离）
                if (!nearbyRouteNames.contains(v.getRouteName())) {
                    continue;
                }
            } else {
                double d = GeoDistanceUtil.haversineKm(longitude, latitude, vlon, vlat);
                if (d > radKm) {
                    continue;
                }
            }
            AppBusNearbyRespVO.NearbyBus bus = new AppBusNearbyRespVO.NearbyBus();
            bus.setBusId(v.getVehicleId());
            bus.setPlateNo(v.getPlateNo());
            bus.setShiftCode(v.getShiftCode());
            bus.setRouteName(v.getRouteName());
            MonitoringMapDataRespVO.Route route = routeByName.get(v.getRouteName());
            if (route != null && route.getPoints() != null && !route.getPoints().isEmpty()) {
                bus.setStartStation(route.getPoints().get(0).getStationName());
                bus.setEndStation(route.getPoints().get(route.getPoints().size() - 1).getStationName());
            }
            bus.setLongitude(vlon);
            bus.setLatitude(vlat);
            bus.setDataSource(v.getDataSource() == null ? AppBusNearbyRespVO.SOURCE_SIMULATED : v.getDataSource());
            bus.setNextStation(v.getNextStationName());
            bus.setStatus(mapStatus(v));
            bus.setDistanceKm(located ? round2(GeoDistanceUtil.haversineKm(longitude, latitude, vlon, vlat)) : null);
            bus.setLocationSource(locationSource(v));
            bus.setLastLocationTime(v.getLastLocationTime());
            bus.setUpdatedAt(LocalDateTime.now());
            // 车辆位置 → 下一站 → 高德真实道路距离/ETA（短 TTL 缓存节流；无下一站/无位置不调高德）
            this.fillEta(bus, v, stationByName);
            if (AppBusNearbyRespVO.SOURCE_REAL.equals(bus.getDataSource())) {
                hasReal = true;
            } else {
                hasSimulated = true;
            }
            buses.add(bus);
        }
        buses.sort(Comparator.comparing(AppBusNearbyRespVO.NearbyBus::getDistanceKm,
                Comparator.nullsLast(Comparator.naturalOrder())));
        resp.setBuses(buses);
        // 附近站点关联线路：无运营车辆也返回（前端展示"该区域有哪些线路 / 当前不在运营"）
        resp.setLines(buildLines(mapData, nearbyRouteNames));
        resp.setDataSource(buses.isEmpty() ? AppBusNearbyRespVO.SOURCE_NONE
                : (hasReal && hasSimulated ? "MIXED" : (hasReal ? AppBusNearbyRespVO.SOURCE_REAL
                        : AppBusNearbyRespVO.SOURCE_SIMULATED)));
        return resp;
    }

    /** 附近站点关联线路（按线路名去重，取起点/终点站名） */
    private List<AppBusNearbyRespVO.NearbyLine> buildLines(MonitoringMapDataRespVO mapData, Set<String> nearbyRouteNames) {
        if (mapData.getRoutes() == null || nearbyRouteNames.isEmpty()) {
            return List.of();
        }
        return mapData.getRoutes().stream()
                .filter(r -> nearbyRouteNames.contains(r.getRouteName()))
                .map(r -> {
                    AppBusNearbyRespVO.NearbyLine line = new AppBusNearbyRespVO.NearbyLine();
                    line.setRouteName(r.getRouteName());
                    List<MonitoringMapDataRespVO.Point> pts = r.getPoints() == null ? List.of() : r.getPoints();
                    line.setStartStation(pts.isEmpty() ? null : pts.get(0).getStationName());
                    line.setEndStation(pts.isEmpty() ? null : pts.get(pts.size() - 1).getStationName());
                    return line;
                }).toList();
    }

    /** 位置新鲜度：REAL(5min 内真实上报)→REAL_FRESH；SIMULATED→SIMULATED；无坐标→NO_LOCATION */
    private String locationSource(MonitoringVehicleRespVO v) {
        if (v.getLongitude() == null || v.getLatitude() == null) {
            return "NO_LOCATION";
        }
        if (AppBusNearbyRespVO.SOURCE_REAL.equals(v.getDataSource())) {
            return "REAL_FRESH";
        }
        return "SIMULATED";
    }

    /**
     * 车辆位置 → 下一站 → 高德真实道路距离/ETA。
     * 无下一站/无车辆位置/下一站无坐标：不调高德（bus 保持 null）。
     * 缓存：key = rounded(车辆坐标,4位):下一站id，TTL 60s——微小 GPS 位移共享同路线，节流高德调用。
     * 失败/不可用：本次不返回 ETA，不疯狂重试（下次刷新再试）。
     */
    private void fillEta(AppBusNearbyRespVO.NearbyBus bus, MonitoringVehicleRespVO v,
                         Map<String, StationDO> stationByName) {
        String nextName = v.getNextStationName();
        if (nextName == null || v.getLongitude() == null || v.getLatitude() == null) {
            return;
        }
        StationDO next = stationByName.get(nextName);
        if (next == null || next.getLongitude() == null || next.getLatitude() == null) {
            return;
        }
        String key = String.format(Locale.ROOT, "%.4f,%.4f:%d", v.getLatitude(), v.getLongitude(), next.getId());
        RouteEta cached = etaCache.get(key);
        if (cached != null && cached.expireAt() > System.currentTimeMillis()) {
            bus.setDistanceToNextStationKm(cached.distanceKm());
            bus.setEtaMinutes(cached.etaMinutes());
            bus.setRouteProvider(cached.provider());
            return;
        }
        try {
            AlgorithmRouteRespDTO route = algorithmClient.route(AlgorithmRouteReqDTO.builder()
                    .origin(AlgorithmRouteReqDTO.RoutePoint.builder()
                            .latitude(v.getLatitude()).longitude(v.getLongitude()).build())
                    .destination(AlgorithmRouteReqDTO.RoutePoint.builder()
                            .latitude(next.getLatitude().doubleValue())
                            .longitude(next.getLongitude().doubleValue()).build())
                    .build());
            if (route != null && Boolean.TRUE.equals(route.getAvailable()) && route.getDistanceKm() != null) {
                Integer minutes = route.getDurationSeconds() != null
                        ? (int) Math.ceil(route.getDurationSeconds() / 60.0) : null;
                RouteEta fresh = new RouteEta(round2(route.getDistanceKm()), minutes,
                        "amap".equals(route.getProvider()) ? "AMAP" : "EUCLIDEAN",
                        System.currentTimeMillis() + ETA_CACHE_TTL_MS);
                etaCache.put(key, fresh);
                bus.setDistanceToNextStationKm(fresh.distanceKm());
                bus.setEtaMinutes(fresh.etaMinutes());
                bus.setRouteProvider(fresh.provider());
            }
            // route 不可用/失败：本次不返回 ETA（bus 保持 null），下次刷新再试
        } catch (Exception ex) {
            log.warn("[nearby][车辆 {} 计算下一站 ETA 失败：{}]", v.getVehicleId(), ex.getMessage());
        }
    }

    /** 监控状态 → AppBus 稳定状态：2 停用→NO_LOCATION；1 在途→RUNNING（到终点无下一站→ARRIVED）；0 空闲→IDLE */
    private String mapStatus(MonitoringVehicleRespVO v) {
        Integer status = v.getStatus();
        if (status == null || status == 0) {
            return AppBusNearbyRespVO.STATUS_IDLE;
        }
        if (status == 2) {
            return AppBusNearbyRespVO.STATUS_NO_LOCATION;
        }
        // 在途
        return v.getNextStationName() == null ? AppBusNearbyRespVO.STATUS_ARRIVED
                : AppBusNearbyRespVO.STATUS_RUNNING;
    }

    private static Double round2(double value) {
        return Math.round(value * 100) / 100.0;
    }

}
