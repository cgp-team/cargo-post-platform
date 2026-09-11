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
import cn.iocoder.yudao.module.transport.util.GeoDistanceUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
    /** 真实道路几何（高德 Web key 直连，带缓存）：线路道路轨迹的首选来源 */
    @Resource private cn.iocoder.yudao.module.transport.service.geo.RoadPolylineService roadPolylineService;
    /** 附近公交数据源分层：现实公交（高德，可缺省）+ 项目自建线路；各自标注来源，不互相伪装 */
    @Resource private List<TransitProvider> transitProviders;

    /** radius 默认值：5000 米 */
    private static final double DEFAULT_RADIUS_M = 5000;
    /** radius 合理上限：50 公里（防异常超大范围） */
    private static final double MAX_RADIUS_M = 50_000;
    /** 路线缓存 TTL：60 秒（车辆 GPS 每秒变化，不能每次 15s 刷新都打高德） */
    private static final long ETA_CACHE_TTL_MS = 60_000;

    /** 路线缓存：key = rounded(车辆坐标,4位):下一站id → 共享相同路线的 ETA（节流高德） */
    private final Map<String, RouteEta> etaCache = new ConcurrentHashMap<>();

    /** 线路真实道路 polyline 缓存：key=routeId → 整条线路的道路点序列，TTL 5 分钟（路网几何稳定，无需频繁重算） */
    private final Map<Long, RoutePolylineCache> routePolylineCache = new ConcurrentHashMap<>();

    /** 线路道路轨迹缓存条目 */
    private record RoutePolylineCache(List<AppBusLineRespVO.RoadPoint> polyline, long expireAt) {
    }

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
                // 有班次（线路已知）或有坐标的真实上报车辆都进列表：
                // 不能因为 REAL 车辆缺 shiftCode 就把它过滤掉（否则司机端刚上报也看不到车）
                .filter(v -> v.getShiftCode() != null || v.getLongitude() != null)
                .map(v -> {
                    AppBusRespVO vo = new AppBusRespVO();
                    vo.setBusId(v.getVehicleId());
                    vo.setPlateNo(v.getPlateNo());
                    vo.setShiftCode(v.getShiftCode());
                    vo.setRouteName(v.getRouteName());
                    MonitoringMapDataRespVO.Route route = v.getRouteName() == null ? null
                            : routeByName.get(v.getRouteName());
                    if (route != null && route.getPoints() != null && !route.getPoints().isEmpty()) {
                        vo.setStartStation(route.getPoints().get(0).getStationName());
                        vo.setEndStation(route.getPoints().get(route.getPoints().size() - 1).getStationName());
                    }
                    vo.setStatus(v.getStatus());
                    vo.setNextStation(v.getNextStationName());
                    vo.setCurrentStation(v.getCurrentStationName());
                    vo.setLongitude(v.getLongitude());
                    vo.setLatitude(v.getLatitude());
                    vo.setProgress(v.getProgress());
                    vo.setSpeedKmh(v.getSpeedKmh());
                    // 数据来源（REAL / SIMULATED）：小程序据此标注「模拟演示」，不拿模拟位置冒充真实上报
                    vo.setDataSource(v.getDataSource());
                    // ETA = 剩余进度占比 × 班次计划时长（向下取整至少 1 分钟）
                    int duration = v.getShiftCode() == null ? 60
                            : durationByShiftCode.getOrDefault(v.getShiftCode(), 60);
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
            // 真实道路 polyline 改为「按需查询」：见 getLinePolyline(routeId)。
            // 这里不再逐条线路打高德（真实线网几十条 × 20~40 站会让小程序超时）。
            return vo;
        }).toList();
    }

    @Override
    public List<AppBusLineRespVO.RoadPoint> getLinePolyline(Long routeId) {
        if (routeId == null) {
            return null;
        }
        MonitoringMapDataRespVO mapData = monitoringService.getMapData();
        if (mapData.getRoutes() == null) {
            return null;
        }
        for (MonitoringMapDataRespVO.Route route : mapData.getRoutes()) {
            if (!routeId.equals(route.getId()) || route.getPoints() == null) {
                continue;
            }
            List<AppBusLineRespVO.Point> points = route.getPoints().stream().map(p -> {
                AppBusLineRespVO.Point point = new AppBusLineRespVO.Point();
                point.setStationName(p.getStationName());
                point.setLongitude(p.getLongitude());
                point.setLatitude(p.getLatitude());
                return point;
            }).toList();
            return fetchRoutePolyline(route.getId(), points);
        }
        return null;
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

        // 项目线路数据（一次加载：站点聚合 / 车辆线路关联复用）
        MonitoringMapDataRespVO mapData = monitoringService.getMapData();
        Map<String, MonitoringMapDataRespVO.Route> routeByName = mapData.getRoutes() == null ? Map.of()
                : mapData.getRoutes().stream().collect(Collectors.toMap(
                        MonitoringMapDataRespVO.Route::getRouteName, Function.identity(), (a, b) -> a));
        Map<Long, List<String>> stationLines = stationLines(mapData);
        List<StationDO> stations = stationMapper.selectList();
        Map<String, StationDO> stationByName = stations.stream()
                .filter(s -> s.getStationName() != null)
                .collect(Collectors.toMap(StationDO::getStationName, Function.identity(), (a, b) -> a));

        // 附近站点分层：A 现实公交站点（REAL_TRANSIT，需高德 key）+ B 项目自建站点（PROJECT_TRANSIT）
        // 关键：不能再把 transport_station 当成"现实世界公交库"；现实层没数据也不能直接判定"附近没有公交"
        List<AppBusNearbyRespVO.NearbyStation> nearbyStations = new ArrayList<>();
        int realStationCount = 0;
        boolean realTransitAvailable = false;
        String transitProvider = "NONE";
        if (located) {
            for (TransitProvider provider : transitProviders) {
                if (!provider.available()) {
                    continue;
                }
                List<TransitProvider.TransitStation> found =
                        provider.searchNearbyStations(latitude, longitude, radM);
                // 注意：用 dataSource()（REAL_TRANSIT/PROJECT_TRANSIT）判断分层，name() 是展示名（AMAP/PROJECT）
                if (TransitProvider.REAL_TRANSIT.equals(provider.dataSource())) {
                    realTransitAvailable = true;
                    transitProvider = provider.name();
                }
                for (TransitProvider.TransitStation station : found) {
                    Double distKm = station.distanceKm() != null ? station.distanceKm()
                            : round2(GeoDistanceUtil.haversineKm(longitude, latitude,
                                    station.longitude(), station.latitude()));
                    AppBusNearbyRespVO.NearbyStation ns = new AppBusNearbyRespVO.NearbyStation();
                    ns.setName(station.name());
                    ns.setLongitude(station.longitude());
                    ns.setLatitude(station.latitude());
                    ns.setDistanceKm(distKm);
                    ns.setDataSource(station.dataSource());
                    ns.setLines(station.lines());
                    if (TransitProvider.REAL_TRANSIT.equals(station.dataSource())) {
                        realStationCount++;
                    } else {
                        StationDO matched = stationByName.get(station.name());
                        if (matched != null) {
                            ns.setId(matched.getId());
                        }
                    }
                    nearbyStations.add(ns);
                }
            }
        } else if (district != null && !district.isBlank()) {
            // 无精确坐标：district 区域 fallback（项目自建站点，名称/地址模糊匹配）
            for (StationDO station : stations) {
                if (station.getLongitude() == null || station.getLatitude() == null) {
                    continue;
                }
                boolean hit = (station.getStationName() != null && station.getStationName().contains(district))
                        || (station.getAddress() != null && station.getAddress().contains(district));
                if (!hit) {
                    continue;
                }
                AppBusNearbyRespVO.NearbyStation ns = new AppBusNearbyRespVO.NearbyStation();
                ns.setId(station.getId());
                ns.setName(station.getStationName());
                ns.setLongitude(station.getLongitude().doubleValue());
                ns.setLatitude(station.getLatitude().doubleValue());
                ns.setDataSource(TransitProvider.PROJECT_TRANSIT);
                ns.setLines(stationLines.getOrDefault(station.getId(), List.of()));
                nearbyStations.add(ns);
            }
        }
        // 分层合并后统一去重：同名同坐标（5 位小数）合并为一条，线路取并集（现实层优先保留来源标识）
        nearbyStations = dedupeNearbyStations(nearbyStations);
        nearbyStations.sort(Comparator.comparing(AppBusNearbyRespVO.NearbyStation::getDistanceKm,
                Comparator.nullsLast(Comparator.naturalOrder())));
        // 兜底：精确坐标下若项目线路层未产出任何站点（未装配 TransitProvider / 半径内无站点），
        // 退回按站点表 Haversine 直查，保证"项目自建线路正常展示"不因装配问题退化
        if (located && nearbyStations.isEmpty()) {
            for (StationDO station : stations) {
                if (station.getLongitude() == null || station.getLatitude() == null) {
                    continue;
                }
                double d = GeoDistanceUtil.haversineKm(longitude, latitude,
                        station.getLongitude().doubleValue(), station.getLatitude().doubleValue());
                if (d > radKm) {
                    continue;
                }
                AppBusNearbyRespVO.NearbyStation ns = new AppBusNearbyRespVO.NearbyStation();
                ns.setId(station.getId());
                ns.setName(station.getStationName());
                ns.setLongitude(station.getLongitude().doubleValue());
                ns.setLatitude(station.getLatitude().doubleValue());
                ns.setDistanceKm(round2(d));
                ns.setDataSource(TransitProvider.PROJECT_TRANSIT);
                ns.setLines(stationLines.getOrDefault(station.getId(), List.of()));
                nearbyStations.add(ns);
            }
            nearbyStations = dedupeNearbyStations(nearbyStations);
            nearbyStations.sort(Comparator.comparing(AppBusNearbyRespVO.NearbyStation::getDistanceKm,
                    Comparator.nullsLast(Comparator.naturalOrder())));
            resp.setNearbyStations(nearbyStations);
            resp.setNearestStation(nearbyStations.isEmpty() ? null : nearbyStations.get(0));
            resp.setProjectStationCount(nearbyStations.size());
        }
        int projectStationCount = (int) nearbyStations.stream()
                .filter(s -> TransitProvider.PROJECT_TRANSIT.equals(s.getDataSource())).count();
        resp.setNearbyStations(nearbyStations);
        resp.setNearestStation(nearbyStations.isEmpty() ? null : nearbyStations.get(0));
        resp.setRealStationCount(realStationCount);
        resp.setProjectStationCount(projectStationCount);
        resp.setRealTransitAvailable(realTransitAvailable);
        resp.setTransitProvider(transitProvider);

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
            bus.setCurrentStation(v.getCurrentStationName());
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
        List<AppBusNearbyRespVO.NearbyLine> lines = new ArrayList<>(buildLines(mapData, nearbyRouteNames));
        if (located) {
            // 现实线路层（高德等）：项目线路已由 buildLines 给出（含起终点），这里只补现实来源
            for (TransitProvider provider : transitProviders) {
                if (!provider.available() || TransitProvider.PROJECT_TRANSIT.equals(provider.dataSource())) {
                    continue;
                }
                for (TransitProvider.TransitLine line : provider.searchNearbyLines(latitude, longitude, radM)) {
                    AppBusNearbyRespVO.NearbyLine item = new AppBusNearbyRespVO.NearbyLine();
                    item.setRouteName(line.routeName());
                    item.setStartStation(line.startStation());
                    item.setEndStation(line.endStation());
                    item.setDataSource(line.dataSource());
                    lines.add(item);
                }
            }
        }
        // 按「线路名 + 来源」去重，并给出条数（首页文案"附近有 N 条公交线路"）
        Map<String, AppBusNearbyRespVO.NearbyLine> deduped = new LinkedHashMap<>();
        for (AppBusNearbyRespVO.NearbyLine line : lines) {
            if (line.getRouteName() == null || line.getRouteName().isBlank()) {
                continue;
            }
            deduped.putIfAbsent(line.getRouteName() + "|" + line.getDataSource(), line);
        }
        resp.setLines(new ArrayList<>(deduped.values()));
        resp.setLineCount(deduped.size());
        resp.setDataSource(buses.isEmpty() ? AppBusNearbyRespVO.SOURCE_NONE
                : (hasReal && hasSimulated ? "MIXED" : (hasReal ? AppBusNearbyRespVO.SOURCE_REAL
                        : AppBusNearbyRespVO.SOURCE_SIMULATED)));
        return resp;
    }

    /** 站点 → 途经线路名（项目自建线路；基于已加载线路经停点，无额外查库） */
    /**
     * 附近站点去重（同名 + 5 位小数坐标）：距离取更近、线路取并集、现实层来源优先、名称取更简洁的。
     * 现实层（高德）同一站点常有多条 POI 记录，项目层也可能与之一一重叠，不去重会出现重复卡片。
     */
    private List<AppBusNearbyRespVO.NearbyStation> dedupeNearbyStations(List<AppBusNearbyRespVO.NearbyStation> stations) {
        Map<String, AppBusNearbyRespVO.NearbyStation> map = new LinkedHashMap<>();
        for (AppBusNearbyRespVO.NearbyStation station : stations) {
            if (station == null || station.getLatitude() == null || station.getLongitude() == null) {
                continue;
            }
            String key = TransitProvider.normalizeStationName(station.getName()) + "|"
                    + String.format("%.5f", station.getLatitude()) + "|" + String.format("%.5f", station.getLongitude());
            AppBusNearbyRespVO.NearbyStation exist = map.get(key);
            if (exist == null) {
                map.put(key, station);
                continue;
            }
            if (exist.getDistanceKm() == null
                    || (station.getDistanceKm() != null && station.getDistanceKm() < exist.getDistanceKm())) {
                exist.setDistanceKm(station.getDistanceKm());
            }
            Set<String> lines = new java.util.LinkedHashSet<>(exist.getLines() == null ? List.of() : exist.getLines());
            if (station.getLines() != null) {
                lines.addAll(station.getLines());
            }
            exist.setLines(new ArrayList<>(lines));
            if (TransitProvider.REAL_TRANSIT.equals(station.getDataSource())) {
                exist.setDataSource(TransitProvider.REAL_TRANSIT);
            }
            if (exist.getName() != null && station.getName() != null
                    && station.getName().length() < exist.getName().length()) {
                exist.setName(station.getName());
            }
        }
        return new ArrayList<>(map.values());
    }

    private Map<Long, List<String>> stationLines(MonitoringMapDataRespVO mapData) {
        if (mapData.getRoutes() == null) {
            return Map.of();
        }
        Map<Long, List<String>> result = new HashMap<>();
        for (MonitoringMapDataRespVO.Route route : mapData.getRoutes()) {
            if (route.getPoints() == null || route.getRouteName() == null) {
                continue;
            }
            for (MonitoringMapDataRespVO.Point point : route.getPoints()) {
                if (point.getStationId() == null) {
                    continue;
                }
                result.computeIfAbsent(point.getStationId(), k -> new ArrayList<>());
                if (!result.get(point.getStationId()).contains(route.getRouteName())) {
                    result.get(point.getStationId()).add(route.getRouteName());
                }
            }
        }
        return result;
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
                    line.setDataSource(TransitProvider.PROJECT_TRANSIT);
                    return line;
                }).toList();
    }

    /** 真实位置新鲜度阈值（分钟）：超过视为 STALE（司机中断上报但未超过监控窗口） */
    private static final long REAL_FRESH_MINUTES = 5;

    /** 位置新鲜度：REAL 且 lastLocationTime<5min→REAL_FRESH；REAL 且 ≥5min→REAL_STALE；SIMULATED→SIMULATED；无坐标→NO_LOCATION */
    private String locationSource(MonitoringVehicleRespVO v) {
        if (v.getLongitude() == null || v.getLatitude() == null) {
            return "NO_LOCATION";
        }
        if (AppBusNearbyRespVO.SOURCE_REAL.equals(v.getDataSource())) {
            if (v.getLastLocationTime() != null
                    && v.getLastLocationTime().isBefore(LocalDateTime.now().minusMinutes(REAL_FRESH_MINUTES))) {
                return "REAL_STALE";
            }
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

    /**
     * 按站点序列逐段取真实道路 polyline，拼接为整条线路轨迹。
     * 缓存 5 分钟（路网几何稳定）；某段失败/不可用时该段回退为直线（起终点两点），不伪装真实道路。
     * 返回 null 表示整条线路无法绘制（少于 2 个有效坐标点）。
     */
    private List<AppBusLineRespVO.RoadPoint> fetchRoutePolyline(Long routeId, List<AppBusLineRespVO.Point> points) {
        if (routeId == null || points == null || points.size() < 2) {
            return null;
        }
        RoutePolylineCache cached = routePolylineCache.get(routeId);
        if (cached != null && cached.expireAt() > System.currentTimeMillis()) {
            return cached.polyline();
        }
        List<AppBusLineRespVO.RoadPoint> fullPolyline = new ArrayList<>();
        // 0) 整条线路一次（或多个途经点分组）取真实道路：几十个站逐段请求会拖到几秒~几十秒，
        //    小程序 10s 超时就报 request:fail timeout；分组串联通常 1~3 次请求即可拿全。
        List<double[]> stops = new ArrayList<>();
        for (AppBusLineRespVO.Point point : points) {
            if (point.getLongitude() != null && point.getLatitude() != null) {
                stops.add(new double[]{point.getLongitude(), point.getLatitude()});
            }
        }
        List<double[]> through = roadPolylineService == null ? null : roadPolylineService.routeThrough(stops);
        if (through != null && through.size() >= 2) {
            List<AppBusLineRespVO.RoadPoint> direct = new ArrayList<>();
            for (double[] p : through) {
                direct.add(toRoadPoint(p[0], p[1]));
            }
            direct = dedupePolyline(direct);
            routePolylineCache.put(routeId, new RoutePolylineCache(direct,
                    System.currentTimeMillis() + 300_000));
            return direct;
        }
        for (int i = 0; i < points.size() - 1; i++) {
            AppBusLineRespVO.Point from = points.get(i);
            AppBusLineRespVO.Point to = points.get(i + 1);
            if (from.getLongitude() == null || from.getLatitude() == null
                    || to.getLongitude() == null || to.getLatitude() == null) {
                continue;
            }
            // 1) 后端直连高德驾车路网（带 10 分钟缓存）：比算法服务更稳，避免"线路轨迹变直线"
            List<double[]> road = roadPolylineService == null ? null : roadPolylineService.route(
                    from.getLongitude(), from.getLatitude(), to.getLongitude(), to.getLatitude());
            if (road != null && road.size() >= 2) {
                for (double[] p : road) {
                    fullPolyline.add(toRoadPoint(p[0], p[1]));
                }
                continue;
            }
            // 2) 高德不可用：退回算法服务 /route
            try {
                AlgorithmRouteRespDTO route = algorithmClient.route(AlgorithmRouteReqDTO.builder()
                        .origin(AlgorithmRouteReqDTO.RoutePoint.builder()
                                .latitude(from.getLatitude()).longitude(from.getLongitude()).build())
                        .destination(AlgorithmRouteReqDTO.RoutePoint.builder()
                                .latitude(to.getLatitude()).longitude(to.getLongitude()).build())
                        .build());
                if (route != null && Boolean.TRUE.equals(route.getAvailable())
                        && route.getPolyline() != null && !route.getPolyline().isEmpty()) {
                    for (AlgorithmRouteRespDTO.PolylinePoint p : route.getPolyline()) {
                        fullPolyline.add(toRoadPoint(p.getLongitude(), p.getLatitude()));
                    }
                } else {
                    addFallbackPoint(fullPolyline, from);
                    addFallbackPoint(fullPolyline, to);
                }
            } catch (Exception e) {
                log.debug("[bus-lines] 获取线路{}分段{}->{}道路polyline失败：{}",
                        routeId, from.getStationName(), to.getStationName(), e.getMessage());
                addFallbackPoint(fullPolyline, from);
                addFallbackPoint(fullPolyline, to);
            }
        }
        fullPolyline = dedupePolyline(fullPolyline);
        if (fullPolyline.isEmpty()) {
            return null;
        }
        routePolylineCache.put(routeId, new RoutePolylineCache(fullPolyline, System.currentTimeMillis() + 300_000));
        return fullPolyline;
    }

    private static AppBusLineRespVO.RoadPoint toRoadPoint(Double longitude, Double latitude) {
        AppBusLineRespVO.RoadPoint rp = new AppBusLineRespVO.RoadPoint();
        rp.setLongitude(longitude);
        rp.setLatitude(latitude);
        return rp;
    }

    private static void addFallbackPoint(List<AppBusLineRespVO.RoadPoint> list, AppBusLineRespVO.Point point) {
        if (point.getLongitude() == null || point.getLatitude() == null) {
            return;
        }
        list.add(toRoadPoint(point.getLongitude(), point.getLatitude()));
    }

    /** 去掉相邻重复点（分段拼接处会出现重复的起终点） */
    private static List<AppBusLineRespVO.RoadPoint> dedupePolyline(List<AppBusLineRespVO.RoadPoint> polyline) {
        if (polyline == null || polyline.size() <= 1) {
            return polyline == null ? new ArrayList<>() : polyline;
        }
        List<AppBusLineRespVO.RoadPoint> result = new ArrayList<>();
        result.add(polyline.get(0));
        for (int i = 1; i < polyline.size(); i++) {
            AppBusLineRespVO.RoadPoint prev = result.get(result.size() - 1);
            AppBusLineRespVO.RoadPoint curr = polyline.get(i);
            if (!java.util.Objects.equals(prev.getLongitude(), curr.getLongitude())
                    || !java.util.Objects.equals(prev.getLatitude(), curr.getLatitude())) {
                result.add(curr);
            }
        }
        return result;
    }

}
