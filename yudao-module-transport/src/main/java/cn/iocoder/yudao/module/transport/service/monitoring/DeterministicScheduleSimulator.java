package cn.iocoder.yudao.module.transport.service.monitoring;

import cn.iocoder.yudao.module.transport.dal.dataobject.route.RouteDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.route.RouteStationDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.shift.ShiftDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.station.StationDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.vehicle.VehicleDO;
import cn.iocoder.yudao.module.transport.dal.mysql.route.RouteMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.route.RouteStationMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.shift.ShiftMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.station.StationMapper;
import cn.iocoder.yudao.module.transport.service.geo.RoadPolylineService;
import cn.iocoder.yudao.module.transport.util.GeoDistanceUtil;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 确定性班次模拟器（DeterministicScheduleSimulator）。
 *
 * 目标：**不依赖任何人工操作**（无需启动 SimulationEngine、无需司机开 GPS），
 * 仅凭「数据库车辆 + 启用班次 + 线路 + 线路站点 + 当前时间」即可得到稳定的 SIMULATED 车辆位置，
 * 供附近公交 / 实时公交 / 监控中心 / 小程序首页展示与演示。
 *
 * 确定性规则（可单测，纯函数）：
 * 1. 可用车辆（status=0）按 ID 升序、启用班次（status=0）按 plannedDepartureTime 升序，轮转分配（一车多班）；
 * 2. 当前时间早于计划发车 → 待发（IDLE，停起点站）；落在 [发车, 发车+计划时长] → 在途（RUNNING）；
 *    晚于计划结束 → 收车（IDLE，停终点站）。**窗口外不继续伪造行驶**；
 * 3. 按 route_station.planned_minutes 累计分钟定位当前区间，区间内线性插值经纬度，
 *    同时给出当前站 / 下一站 / 进度 / 速度 / 到下一站分钟（ETA）；
 * 4. source 固定 {@code SIMULATED}，绝不冒充 REAL（前端据此标注"模拟演示"）。
 */
@Service
public class DeterministicScheduleSimulator {

    /** 数据来源标识：确定性班次模拟 */
    public static final String SOURCE_SIMULATED = "SIMULATED";
    /** 监控状态：空闲（待发 / 收车） */
    private static final int STATUS_IDLE = 0;
    /** 监控状态：在途 */
    private static final int STATUS_IN_TRANSIT = 1;
    /** 班次/车辆状态：启用/可用 */
    private static final int STATUS_ENABLED = 0;
    /** 计划时长缺省值（分钟） */
    private static final int DEFAULT_DURATION_MINUTES = 60;

    @Resource private ShiftMapper shiftMapper;
    @Resource private RouteMapper routeMapper;
    @Resource private RouteStationMapper routeStationMapper;
    @Resource private StationMapper stationMapper;
    /** 真实道路几何（用于让模拟车辆沿道路而非站点连线行驶）；未配置高德 key 时返回 null，自动退化为直线 */
    @Resource private RoadPolylineService roadPolylineService;

    /**
     * 路段真实道路几何提供者：给定起终点坐标返回道路点序列（{@code [lng,lat]}），
     * 不可用返回 null（调用方退化为两点直线，不伪装真实道路）。
     */
    @FunctionalInterface
    public interface RoadGeometryProvider {
        List<double[]> road(double fromLongitude, double fromLatitude, double toLongitude, double toLatitude);
    }

    /**
     * 批量模拟：返回「有当前班次的车辆 → 位置快照」。无班次/无线路/无坐标的车辆不产出（下游按 OFFLINE 处理）。
     */
    public Map<Long, VehicleLocationSnapshot> simulateAll(List<VehicleDO> vehicles, LocalTime now) {
        if (vehicles == null || vehicles.isEmpty()) {
            return Map.of();
        }
        List<ShiftDO> shifts = shiftMapper.selectList().stream()
                .filter(s -> STATUS_ENABLED == (s.getStatus() == null ? STATUS_ENABLED : s.getStatus()))
                .toList();
        if (shifts.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<ShiftDO>> assignment = assignShifts(vehicles, shifts);
        Map<Long, RouteDO> routeMap = routeMapper.selectList().stream()
                .collect(Collectors.toMap(RouteDO::getId, Function.identity(), (a, b) -> a));
        Map<Long, StationDO> stationMap = stationMapper.selectList().stream()
                .collect(Collectors.toMap(StationDO::getId, Function.identity(), (a, b) -> a));
        List<Long> routeIds = routeMap.keySet().stream().toList();
        Map<Long, List<RouteStationDO>> routeStationMap = routeIds.isEmpty() ? Map.of()
                : routeStationMapper.selectListByRouteIds(routeIds).stream()
                .collect(Collectors.groupingBy(RouteStationDO::getRouteId));

        Map<Long, VehicleLocationSnapshot> result = new HashMap<>();
        for (VehicleDO vehicle : vehicles) {
            ShiftDO shift = selectCurrentShift(assignment.getOrDefault(vehicle.getId(), List.of()), now);
            if (shift == null) {
                continue;
            }
            VehicleLocationSnapshot snapshot = compute(vehicle, shift, routeMap.get(shift.getRouteId()),
                    routeStationMap.getOrDefault(shift.getRouteId(), List.of()), stationMap, now,
                    roadPolylineService == null ? null : roadPolylineService::route);
            if (snapshot != null) {
                result.put(vehicle.getId(), snapshot);
            }
        }
        return result;
    }

    /** 单车模拟（司机端 / 单车查询用）：等价于 simulateAll 取单条 */
    public VehicleLocationSnapshot simulate(VehicleDO vehicle, LocalTime now) {
        if (vehicle == null) {
            return null;
        }
        return simulateAll(List.of(vehicle), now).get(vehicle.getId());
    }

    // ==================== 纯函数（单测覆盖） ====================

    /**
     * 模拟排班：可用车辆按 ID 升序、班次按发车时间升序，轮转分配（一车可多班）。
     * 与历史 MonitoringServiceImpl 的分配口径一致，保证演示数据稳定。
     */
    public static Map<Long, List<ShiftDO>> assignShifts(List<VehicleDO> vehicles, List<ShiftDO> shifts) {
        List<VehicleDO> available = vehicles.stream()
                .filter(v -> STATUS_ENABLED == (v.getStatus() == null ? STATUS_ENABLED : v.getStatus()))
                .sorted(Comparator.comparing(VehicleDO::getId))
                .toList();
        List<ShiftDO> sorted = shifts.stream()
                .filter(s -> s.getPlannedDepartureTime() != null)
                .sorted(Comparator.comparing(ShiftDO::getPlannedDepartureTime)
                        .thenComparing(s -> s.getId() == null ? 0L : s.getId()))
                .toList();
        Map<Long, List<ShiftDO>> map = new HashMap<>();
        if (available.isEmpty()) {
            return map;
        }
        for (int i = 0; i < sorted.size(); i++) {
            Long vehicleId = available.get(i % available.size()).getId();
            map.computeIfAbsent(vehicleId, k -> new ArrayList<>()).add(sorted.get(i));
        }
        return map;
    }

    /** 选取当前班次：优先在途窗口，其次下一班待发，否则当天最后一班（已收车） */
    public static ShiftDO selectCurrentShift(List<ShiftDO> shifts, LocalTime now) {
        if (shifts == null || shifts.isEmpty()) {
            return null;
        }
        List<ShiftDO> sorted = shifts.stream()
                .sorted(Comparator.comparing(ShiftDO::getPlannedDepartureTime))
                .toList();
        ShiftDO next = null;
        for (ShiftDO shift : sorted) {
            long elapsed = elapsedMinutes(shift, now);
            if (elapsed >= 0 && elapsed <= durationMinutes(shift)) {
                return shift;
            }
            if (elapsed < 0 && next == null) {
                next = shift;
            }
        }
        return next != null ? next : sorted.get(sorted.size() - 1);
    }

    /**
     * 计算单车位置快照：待发→起点站(IDLE)；在途→区间线性插值(RUNNING)；收车→终点站(IDLE)。
     * 返回 null 表示无法模拟（无线路 / 线路无可定位站点）。
     */
    public static VehicleLocationSnapshot compute(VehicleDO vehicle, ShiftDO shift, RouteDO route,
                                                  List<RouteStationDO> routeStations,
                                                  Map<Long, StationDO> stationMap, LocalTime now) {
        return compute(vehicle, shift, route, buildPoints(routeStations, stationMap), now, null);
    }

    /**
     * 计算单车位置快照（带真实道路几何）：在途时优先沿「上一站→下一站」的真实道路轨迹按里程比例插值，
     * 道路几何不可用时退化回两点直线插值。
     */
    public static VehicleLocationSnapshot compute(VehicleDO vehicle, ShiftDO shift, RouteDO route,
                                                  List<RouteStationDO> routeStations,
                                                  Map<Long, StationDO> stationMap, LocalTime now,
                                                  RoadGeometryProvider geometryProvider) {
        return compute(vehicle, shift, route, buildPoints(routeStations, stationMap), now, geometryProvider);
    }

    /** 计算单车位置快照（点位已构建；包内/单测可见） */
    static VehicleLocationSnapshot compute(VehicleDO vehicle, ShiftDO shift, RouteDO route,
                                           List<Point> points, LocalTime now,
                                           RoadGeometryProvider geometryProvider) {
        if (vehicle == null || shift == null || route == null) {
            return null;
        }
        if (points == null || points.isEmpty()) {
            return null;
        }
        int duration = durationMinutes(shift);
        long elapsed = elapsedMinutes(shift, now);
        Point first = points.get(0);
        Point last = points.get(points.size() - 1);
        VehicleLocationSnapshot.VehicleLocationSnapshotBuilder builder = base(vehicle, shift, route);

        // 1) 尚未发车：待发，停起点站
        if (elapsed < 0) {
            return builder.status(STATUS_IDLE).progress(0).speedKmh(0.0)
                    .longitude(first.lon()).latitude(first.lat())
                    .currentStationId(first.stationId()).currentStationName(first.name())
                    .nextStationId(first.stationId()).nextStationName(first.name())
                    .etaToNextStationMinutes((double) Math.max(0, -elapsed)).build();
        }
        // 2) 已过计划结束：收车，停终点站（不继续伪造行驶）
        if (elapsed > duration) {
            return builder.status(STATUS_IDLE).progress(100).speedKmh(0.0)
                    .longitude(last.lon()).latitude(last.lat())
                    .currentStationId(last.stationId()).currentStationName(last.name())
                    .build();
        }
        // 3) 在途：定位当前区间并线性插值
        int progress = (int) Math.min(100, elapsed * 100 / Math.max(1, duration));
        double speedKmh = route.getDistanceKm() != null && duration > 0
                ? Math.round(route.getDistanceKm().doubleValue() * 60.0 / duration * 10) / 10.0 : 0.0;
        Point prev = first;
        for (int i = 1; i < points.size(); i++) {
            Point next = points.get(i);
            if (elapsed <= next.minutes()) {
                int span = next.minutes() - prev.minutes();
                double ratio = span > 0 ? (double) (elapsed - prev.minutes()) / span : 0;
                // 真实道路优先：沿「上一站→下一站」的道路轨迹按里程比例取点；无道路几何则两点直线
                double[] onRoad = pointOnRoad(geometryProvider, prev, next, ratio);
                double lon = onRoad != null ? onRoad[0] : prev.lon() + (next.lon() - prev.lon()) * ratio;
                double lat = onRoad != null ? onRoad[1] : prev.lat() + (next.lat() - prev.lat()) * ratio;
                if (elapsed == next.minutes()) { // 恰好到站：当前站即该站，下一站取其后一站
                    Point after = i + 1 < points.size() ? points.get(i + 1) : null;
                    return builder.status(STATUS_IN_TRANSIT).progress(progress).speedKmh(0.0)
                            .longitude(next.lon()).latitude(next.lat())
                            .currentStationId(next.stationId()).currentStationName(next.name())
                            .nextStationId(after == null ? null : after.stationId())
                            .nextStationName(after == null ? null : after.name())
                            .etaToNextStationMinutes(after == null ? null : (double) (after.minutes() - elapsed))
                            .build();
                }
                return builder.status(STATUS_IN_TRANSIT).progress(progress).speedKmh(speedKmh)
                        .longitude(round7(lon)).latitude(round7(lat))
                        .currentStationId(prev.stationId()).currentStationName(prev.name())
                        .nextStationId(next.stationId()).nextStationName(next.name())
                        .etaToNextStationMinutes((double) (next.minutes() - elapsed))
                        .build();
            }
            prev = next;
        }
        // 4) 已到终点（区间循环未命中）：停终点站
        return builder.status(STATUS_IN_TRANSIT).progress(100).speedKmh(0.0)
                .longitude(last.lon()).latitude(last.lat())
                .currentStationId(last.stationId()).currentStationName(last.name())
                .build();
    }

    private static VehicleLocationSnapshot.VehicleLocationSnapshotBuilder base(VehicleDO vehicle, ShiftDO shift, RouteDO route) {
        return VehicleLocationSnapshot.builder()
                .vehicleId(vehicle.getId())
                .source(SOURCE_SIMULATED)
                .updatedAt(LocalDateTime.now())
                .shiftId(shift.getId())
                .shiftCode(shift.getShiftCode())
                .routeId(route.getId())
                .routeCode(route.getRouteCode())
                .routeName(route.getRouteName());
    }

    /**
     * 在「上一站→下一站」的真实道路上按里程比例取点（0=起点，1=终点）。
     * 道路几何缺失/异常时返回 {@code null}，由调用方退化为两点直线。
     */
    static double[] pointOnRoad(RoadGeometryProvider provider, Point from, Point to, double ratio) {
        if (provider == null) {
            return null;
        }
        List<double[]> road;
        try {
            road = provider.road(from.lon(), from.lat(), to.lon(), to.lat());
        } catch (Exception e) {
            return null;
        }
        if (road == null || road.size() < 2) {
            return null;
        }
        double target = Math.max(0, Math.min(1, ratio));
        double total = 0;
        double[] cumulative = new double[road.size()];
        for (int i = 1; i < road.size(); i++) {
            total += GeoDistanceUtil.haversineKm(road.get(i - 1)[0], road.get(i - 1)[1],
                    road.get(i)[0], road.get(i)[1]);
            cumulative[i] = total;
        }
        if (total <= 0) {
            return new double[]{road.get(0)[0], road.get(0)[1]};
        }
        double wanted = total * target;
        for (int i = 1; i < road.size(); i++) {
            if (wanted <= cumulative[i] || i == road.size() - 1) {
                double segLen = cumulative[i] - cumulative[i - 1];
                double t = segLen <= 0 ? 0 : (wanted - cumulative[i - 1]) / segLen;
                double[] a = road.get(i - 1);
                double[] b = road.get(i);
                return new double[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t};
            }
        }
        return null;
    }

    /** 线路站点 → 带坐标与累计分钟的点位（无坐标站点跳过；plannedMinutes 缺失沿用上一站） */
    static List<Point> buildPoints(List<RouteStationDO> routeStations, Map<Long, StationDO> stationMap) {
        if (routeStations == null || routeStations.isEmpty()) {
            return List.of();
        }
        List<RouteStationDO> sorted = routeStations.stream()
                .sorted(Comparator.comparing(RouteStationDO::getSequenceNo,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        List<Point> points = new ArrayList<>();
        int minutes = 0;
        for (RouteStationDO rs : sorted) {
            StationDO station = stationMap.get(rs.getStationId());
            if (station == null || station.getLongitude() == null || station.getLatitude() == null) {
                continue;
            }
            if (rs.getPlannedMinutes() != null) {
                minutes = rs.getPlannedMinutes();
            }
            points.add(new Point(station.getId(), station.getStationName(),
                    station.getLongitude().doubleValue(), station.getLatitude().doubleValue(), minutes));
        }
        return points;
    }

    /** 已行驶分钟（未发车为负） */
    static long elapsedMinutes(ShiftDO shift, LocalTime now) {
        if (shift.getPlannedDepartureTime() == null) {
            return -1;
        }
        return Duration.between(shift.getPlannedDepartureTime(), now).toMinutes();
    }

    static int durationMinutes(ShiftDO shift) {
        return shift.getPlannedDurationMinutes() != null ? shift.getPlannedDurationMinutes() : DEFAULT_DURATION_MINUTES;
    }

    private static double round7(double value) {
        return Math.round(value * 1e7) / 1e7;
    }

    /** 线路点位（站点 + 累计计划分钟） */
    record Point(Long stationId, String name, double lon, double lat, int minutes) {
    }
}
