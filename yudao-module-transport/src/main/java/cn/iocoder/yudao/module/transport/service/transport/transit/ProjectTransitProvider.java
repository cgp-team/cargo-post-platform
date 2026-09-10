package cn.iocoder.yudao.module.transport.service.transport.transit;

import cn.iocoder.yudao.module.transport.dal.dataobject.route.RouteDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.route.RouteStationDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.station.StationDO;
import cn.iocoder.yudao.module.transport.dal.mysql.route.RouteMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.route.RouteStationMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.station.StationMapper;
import cn.iocoder.yudao.module.transport.util.GeoDistanceUtil;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 项目自建客货邮线路数据源（PROJECT_TRANSIT）：transport_station / transport_route / transport_route_station。
 *
 * 只代表"项目自己的线路"（村—镇—县），不代表现实城市公交；返回结果一律标注 PROJECT_TRANSIT，
 * 小程序对现实公交与项目线路分层展示，不混为一谈。
 */
@Service
public class ProjectTransitProvider implements TransitProvider {

    @Resource private StationMapper stationMapper;
    @Resource private RouteMapper routeMapper;
    @Resource private RouteStationMapper routeStationMapper;

    @Override
    public String name() {
        return "PROJECT";
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public List<TransitStation> searchNearbyStations(double latitude, double longitude, double radiusMeters) {
        double radiusKm = radiusMeters / 1000.0;
        Map<Long, List<String>> linesByStation = linesByStation();
        List<TransitStation> result = new ArrayList<>();
        for (StationDO station : stationMapper.selectList()) {
            if (station.getLongitude() == null || station.getLatitude() == null) {
                continue;
            }
            double km = GeoDistanceUtil.haversineKm(longitude, latitude,
                    station.getLongitude().doubleValue(), station.getLatitude().doubleValue());
            if (km > radiusKm) {
                continue;
            }
            result.add(new TransitStation(station.getStationName(),
                    station.getLongitude().doubleValue(), station.getLatitude().doubleValue(),
                    Math.round(km * 100) / 100.0,
                    linesByStation.getOrDefault(station.getId(), List.of()),
                    station.getAddress(), PROJECT_TRANSIT));
        }
        result.sort((a, b) -> Double.compare(a.distanceKm() == null ? Double.MAX_VALUE : a.distanceKm(),
                b.distanceKm() == null ? Double.MAX_VALUE : b.distanceKm()));
        return result;
    }

    @Override
    public List<TransitLine> searchNearbyLines(double latitude, double longitude, double radiusMeters) {
        List<TransitStation> stations = searchNearbyStations(latitude, longitude, radiusMeters);
        if (stations.isEmpty()) {
            return List.of();
        }
        List<String> nearbyRouteNames = stations.stream().flatMap(s -> s.lines().stream()).distinct().toList();
        if (nearbyRouteNames.isEmpty()) {
            return List.of();
        }
        return routeMapper.selectList().stream()
                .filter(r -> nearbyRouteNames.contains(r.getRouteName()))
                .map(r -> new TransitLine(r.getRouteName(), null, null, PROJECT_TRANSIT))
                .toList();
    }

    /** 站点 → 途经线路名（站—线 反向索引，一次加载避免 N+1） */
    private Map<Long, List<String>> linesByStation() {
        List<RouteDO> routes = routeMapper.selectList();
        if (routes.isEmpty()) {
            return Map.of();
        }
        Map<Long, RouteDO> routeMap = routes.stream()
                .collect(Collectors.toMap(RouteDO::getId, Function.identity(), (a, b) -> a));
        Map<Long, List<String>> result = new HashMap<>();
        for (RouteStationDO rs : routeStationMapper.selectListByRouteIds(routeMap.keySet().stream().toList())) {
            RouteDO route = routeMap.get(rs.getRouteId());
            if (route == null || route.getRouteName() == null) {
                continue;
            }
            result.computeIfAbsent(rs.getStationId(), k -> new ArrayList<>());
            if (!result.get(rs.getStationId()).contains(route.getRouteName())) {
                result.get(rs.getStationId()).add(route.getRouteName());
            }
        }
        return result;
    }
}
