package cn.iocoder.yudao.module.transport.service.monitoring;

import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.transport.controller.admin.monitoring.vo.MonitoringMapDataRespVO;
import cn.iocoder.yudao.module.transport.controller.admin.monitoring.vo.MonitoringShiftRespVO;
import cn.iocoder.yudao.module.transport.controller.admin.monitoring.vo.MonitoringVehicleRespVO;
import cn.iocoder.yudao.module.transport.dal.dataobject.driver.DriverDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.driver.DriverVehicleDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.route.RouteDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.route.RouteStationDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.shift.ShiftDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.shift.ShiftExecutionDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.station.StationDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.vehicle.VehicleDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.vehicle.VehicleLocationDO;
import cn.iocoder.yudao.module.transport.dal.mysql.driver.DriverMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.driver.DriverVehicleMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.route.RouteMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.route.RouteStationMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.shift.ShiftExecutionMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.shift.ShiftMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.station.StationMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.vehicle.VehicleLocationMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.vehicle.VehicleMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 车辆监控 Service 实现
 *
 * 位置优先取司机端上报的真实位置（transport_vehicle_location，report_time 5 分钟内有效，状态置在途）；
 * 无有效上报时回退以下确定性模拟规则（便于演示与测试）：
 * 1. 启用班次（status=0）按发车时间升序，轮转分配给可用车辆（status=0，一车多班，模拟排班）；
 * 2. 当前时间落在 [计划发车时间, 发车时间+计划时长] 内 → 在途，
 *    按已行驶分钟数在线路站点的累计 planned_minutes 上线性插值经纬度；
 * 3. 窗口外 → 空闲，停靠在其当前/下一班次线路的起点站；无对应班次 → 无坐标，不上图；
 * 4. 停用车辆（status=1）→ 状态停用，不上图。
 *
 * 调度闭环（transport_dispatch_plan_item）落地后，步骤 1 的映射应切换为真实派单结果。
 */
@Service
@Validated
public class MonitoringServiceImpl implements MonitoringService {

    /** 监控状态：空闲 */
    public static final int STATUS_IDLE = 0;
    /** 监控状态：在途 */
    public static final int STATUS_IN_TRANSIT = 1;
    /** 监控状态：停用 */
    public static final int STATUS_DISABLED = 2;

    /** 班次状态：启用 */
    private static final int SHIFT_STATUS_ENABLED = 0;
    /** 车辆状态：可用 */
    private static final int VEHICLE_STATUS_AVAILABLE = 0;
    /** 车辆状态：停用维修（transport_vehicle.status） */
    private static final int VEHICLE_STATUS_DISABLED = 1;

    @Resource private VehicleMapper vehicleMapper;
    @Resource private DriverMapper driverMapper;
    @Resource private DriverVehicleMapper driverVehicleMapper;
    @Resource private ShiftMapper shiftMapper;
    @Resource private RouteMapper routeMapper;
    @Resource private RouteStationMapper routeStationMapper;
    @Resource private StationMapper stationMapper;
    @Resource private VehicleLocationMapper vehicleLocationMapper;
    @Resource private ShiftExecutionMapper shiftExecutionMapper;

    @Override
    public MonitoringMapDataRespVO getMapData() {
        List<StationDO> stations = stationMapper.selectList();
        Map<Long, StationDO> stationMap = stations.stream()
                .collect(Collectors.toMap(StationDO::getId, Function.identity()));
        List<RouteDO> routes = routeMapper.selectList();
        Map<Long, List<RouteStationDO>> routeStations = loadRouteStationMap(
                routes.stream().map(RouteDO::getId).toList());

        MonitoringMapDataRespVO respVO = new MonitoringMapDataRespVO();
        respVO.setStations(stations.stream().map(station -> {
            MonitoringMapDataRespVO.Station item = new MonitoringMapDataRespVO.Station();
            item.setId(station.getId());
            item.setStationCode(station.getStationCode());
            item.setStationName(station.getStationName());
            item.setStationLevel(station.getStationLevel());
            item.setLongitude(toDouble(station.getLongitude()));
            item.setLatitude(toDouble(station.getLatitude()));
            item.setAddress(station.getAddress());
            return item;
        }).toList());
        respVO.setRoutes(routes.stream().map(route -> {
            MonitoringMapDataRespVO.Route item = new MonitoringMapDataRespVO.Route();
            item.setId(route.getId());
            item.setRouteCode(route.getRouteCode());
            item.setRouteName(route.getRouteName());
            item.setDistanceKm(toDouble(route.getDistanceKm()));
            item.setPoints(buildPoints(routeStations.getOrDefault(route.getId(), List.of()), stationMap));
            return item;
        }).toList());
        return respVO;
    }

    @Override
    public List<MonitoringVehicleRespVO> getRealtimeVehicles() {
        LocalTime now = LocalTime.now();
        // 基础数据
        List<VehicleDO> vehicles = vehicleMapper.selectList();
        Map<Long, DriverDO> driverMap = driverMapper.selectList().stream()
                .collect(Collectors.toMap(DriverDO::getId, Function.identity()));
        Map<Long, Long> vehicleDriverMap = driverVehicleMapper.selectActiveBindings().stream()
                .collect(Collectors.toMap(DriverVehicleDO::getVehicleId, DriverVehicleDO::getDriverId, (a, b) -> a));
        List<ShiftDO> enabledShifts = listEnabledShifts();
        Map<Long, RouteDO> routeMap = routeMapper.selectList().stream()
                .collect(Collectors.toMap(RouteDO::getId, Function.identity()));
        Map<Long, StationDO> stationMap = stationMapper.selectList().stream()
                .collect(Collectors.toMap(StationDO::getId, Function.identity()));
        Map<Long, List<RouteStationDO>> routeStationMap = loadRouteStationMap(
                routeMap.values().stream().map(RouteDO::getId).toList());
        // 司机上报的实时位置（5 分钟内有效）：存在时优先于插值模拟
        Map<Long, VehicleLocationDO> realLocationMap = vehicleLocationMapper
                .selectRecent(LocalDateTime.now().minusMinutes(5)).stream()
                .collect(Collectors.toMap(VehicleLocationDO::getVehicleId, Function.identity(), (a, b) -> a));

        // 模拟排班：启用班次按发车时间升序，轮转分配给可用车辆（一车多班）。
        // 每辆车对应若干互不重叠的班次窗口，任意时段都可能有车在途，演示更真实。
        List<VehicleDO> availableVehicles = vehicles.stream()
                .filter(v -> Objects.equals(v.getStatus(), VEHICLE_STATUS_AVAILABLE))
                .sorted(Comparator.comparing(VehicleDO::getId))
                .toList();
        List<ShiftDO> sortedShifts = enabledShifts.stream()
                .filter(s -> s.getPlannedDepartureTime() != null)
                .sorted(Comparator.comparing(ShiftDO::getPlannedDepartureTime))
                .toList();
        Map<Long, List<ShiftDO>> vehicleShiftsMap = new HashMap<>();
        if (!availableVehicles.isEmpty()) {
            for (int j = 0; j < sortedShifts.size(); j++) {
                VehicleDO vehicle = availableVehicles.get(j % availableVehicles.size());
                vehicleShiftsMap.computeIfAbsent(vehicle.getId(), k -> new ArrayList<>())
                        .add(sortedShifts.get(j));
            }
        }

        return vehicles.stream().map(vehicle -> {
            MonitoringVehicleRespVO vo = new MonitoringVehicleRespVO();
            vo.setVehicleId(vehicle.getId());
            vo.setPlateNo(vehicle.getPlateNo());
            Long driverId = vehicleDriverMap.get(vehicle.getId());
            vo.setDriverName(driverId != null && driverMap.containsKey(driverId)
                    ? driverMap.get(driverId).getName() : null);
            if (Objects.equals(vehicle.getStatus(), VEHICLE_STATUS_DISABLED)) {
                vo.setStatus(STATUS_DISABLED);
                return vo;
            }
            // 真实位置优先：5 分钟内有司机上报位置时直接采用（状态置在途）
            VehicleLocationDO realLocation = realLocationMap.get(vehicle.getId());
            if (realLocation != null) {
                vo.setStatus(STATUS_IN_TRANSIT);
                vo.setLongitude(toDouble(realLocation.getLongitude()));
                vo.setLatitude(toDouble(realLocation.getLatitude()));
                vo.setSpeedKmh(toDouble(realLocation.getSpeedKmh()));
                return vo;
            }
            // 选取当前班次：优先窗口内（在途），其次下一班待发，否则当天最后一班
            ShiftDO shift = selectCurrentShift(vehicleShiftsMap.get(vehicle.getId()), now);
            if (shift == null) {
                vo.setStatus(STATUS_IDLE);
                return vo;
            }
            vo.setShiftCode(shift.getShiftCode());
            RouteDO route = routeMap.get(shift.getRouteId());
            vo.setRouteName(route != null ? route.getRouteName() : null);
            List<MonitoringMapDataRespVO.Point> points = buildPoints(
                    routeStationMap.getOrDefault(shift.getRouteId(), List.of()), stationMap);
            if (points.isEmpty()) {
                vo.setStatus(STATUS_IDLE);
                return vo;
            }
            fillPosition(vo, shift, route, points, now);
            return vo;
        }).toList();
    }

    @Override
    public List<MonitoringShiftRespVO> getShiftExecution() {
        LocalTime now = LocalTime.now();
        Map<Long, RouteDO> routeMap = routeMapper.selectList().stream()
                .collect(Collectors.toMap(RouteDO::getId, Function.identity()));
        Map<Long, DriverDO> driverMap = driverMapper.selectList().stream()
                .collect(Collectors.toMap(DriverDO::getId, Function.identity()));
        Map<Long, VehicleDO> vehicleMap = vehicleMapper.selectList().stream()
                .collect(Collectors.toMap(VehicleDO::getId, Function.identity()));
        Map<Long, StationDO> stationMap = stationMapper.selectList().stream()
                .collect(Collectors.toMap(StationDO::getId, Function.identity()));
        List<ShiftDO> shifts = listEnabledShifts();
        // 当天真实执行记录：有记录时以司机端落库状态为准（与司机端三态一致），否则时钟推导兜底
        Map<Long, ShiftExecutionDO> executionMap = shifts.isEmpty() ? Map.of()
                : shiftExecutionMapper.selectListByShiftIdsAndExecDate(
                        shifts.stream().map(ShiftDO::getId).toList(), LocalDate.now()).stream()
                        .collect(Collectors.toMap(ShiftExecutionDO::getShiftId, Function.identity(), (a, b) -> a));
        return shifts.stream().map(shift -> {
            MonitoringShiftRespVO vo = new MonitoringShiftRespVO();
            vo.setShiftId(shift.getId());
            vo.setShiftCode(shift.getShiftCode());
            RouteDO route = routeMap.get(shift.getRouteId());
            vo.setRouteName(route != null ? route.getRouteName() : null);
            vo.setPlannedDepartureTime(shift.getPlannedDepartureTime());
            vo.setPlannedDurationMinutes(shift.getPlannedDurationMinutes());
            ShiftExecutionDO execution = executionMap.get(shift.getId());
            if (execution != null) {
                // 执行状态：0 在途 → 1，1 已完成 → 2
                vo.setStatus(Objects.equals(execution.getStatus(), 1) ? 2 : 1);
                vo.setDriverId(execution.getDriverId());
                vo.setDriverName(nameOf(driverMap, execution.getDriverId(), DriverDO::getName));
                vo.setVehicleId(execution.getVehicleId());
                vo.setPlateNo(nameOf(vehicleMap, execution.getVehicleId(), VehicleDO::getPlateNo));
                vo.setCurrentStationId(execution.getCurrentStationId());
                vo.setCurrentStationName(nameOf(stationMap, execution.getCurrentStationId(), StationDO::getStationName));
                vo.setLoadedCount(execution.getLoadedCount() != null ? execution.getLoadedCount() : 0);
                vo.setDepartTime(execution.getDepartTime());
                vo.setArriveTime(execution.getArriveTime());
            } else {
                long elapsed = elapsedMinutes(shift, now);
                vo.setStatus(elapsed < 0 ? 0 : (elapsed <= durationMinutes(shift) ? 1 : 2));
            }
            return vo;
        }).toList();
    }

    /** 从 map 按 id 取对象的指定字段（对象缺失返回 null） */
    private static <T> String nameOf(Map<Long, T> map, Long id, Function<T, String> getter) {
        T value = map.get(id);
        return value != null ? getter.apply(value) : null;
    }

    // ==================== 私有方法 ====================

    private List<ShiftDO> listEnabledShifts() {
        return shiftMapper.selectList(new LambdaQueryWrapperX<ShiftDO>()
                .eq(ShiftDO::getStatus, SHIFT_STATUS_ENABLED)
                .orderByAsc(ShiftDO::getShiftCode));
    }

    private Map<Long, List<RouteStationDO>> loadRouteStationMap(List<Long> routeIds) {
        if (routeIds.isEmpty()) {
            return Map.of();
        }
        return routeStationMapper.selectListByRouteIds(routeIds).stream()
                .collect(Collectors.groupingBy(RouteStationDO::getRouteId));
    }

    /** 将线路站点 DO 转为带坐标的途经点；planned_minutes 缺失时沿用上一站（首站为 0） */
    private List<MonitoringMapDataRespVO.Point> buildPoints(List<RouteStationDO> routeStations,
                                                            Map<Long, StationDO> stationMap) {
        List<MonitoringMapDataRespVO.Point> points = new ArrayList<>(routeStations.size());
        int lastMinutes = 0;
        for (RouteStationDO rs : routeStations) {
            StationDO station = stationMap.get(rs.getStationId());
            if (station == null || station.getLongitude() == null || station.getLatitude() == null) {
                continue;
            }
            MonitoringMapDataRespVO.Point point = new MonitoringMapDataRespVO.Point();
            point.setSequenceNo(rs.getSequenceNo());
            point.setStationId(rs.getStationId());
            point.setStationName(station.getStationName());
            point.setLongitude(toDouble(station.getLongitude()));
            point.setLatitude(toDouble(station.getLatitude()));
            if (rs.getPlannedMinutes() != null) {
                lastMinutes = rs.getPlannedMinutes();
            }
            point.setPlannedMinutes(lastMinutes);
            points.add(point);
        }
        return points;
    }

    /** 计算车辆当前位置与状态（在途插值 / 空闲停靠起点站） */
    private void fillPosition(MonitoringVehicleRespVO vo, ShiftDO shift, RouteDO route,
                              List<MonitoringMapDataRespVO.Point> points, LocalTime now) {
        int duration = durationMinutes(shift);
        long elapsed = elapsedMinutes(shift, now);
        MonitoringMapDataRespVO.Point first = points.get(0);
        if (elapsed < 0 || elapsed > duration) {
            // 空闲：停靠起点站
            vo.setStatus(STATUS_IDLE);
            vo.setLongitude(first.getLongitude());
            vo.setLatitude(first.getLatitude());
            return;
        }
        vo.setStatus(STATUS_IN_TRANSIT);
        vo.setProgress((int) Math.min(100, elapsed * 100 / duration));
        if (route != null && route.getDistanceKm() != null && duration > 0) {
            vo.setSpeedKmh(BigDecimal.valueOf(route.getDistanceKm().doubleValue() * 60 / duration)
                    .setScale(1, RoundingMode.HALF_UP).doubleValue());
        }
        // 定位当前所处站点区间并线性插值
        MonitoringMapDataRespVO.Point prev = first;
        for (int i = 1; i < points.size(); i++) {
            MonitoringMapDataRespVO.Point next = points.get(i);
            if (elapsed <= next.getPlannedMinutes()) {
                int span = next.getPlannedMinutes() - prev.getPlannedMinutes();
                double ratio = span > 0 ? (double) (elapsed - prev.getPlannedMinutes()) / span : 0;
                vo.setLongitude(round7(prev.getLongitude() + (next.getLongitude() - prev.getLongitude()) * ratio));
                vo.setLatitude(round7(prev.getLatitude() + (next.getLatitude() - prev.getLatitude()) * ratio));
                vo.setNextStationName(next.getStationName());
                return;
            }
            prev = next;
        }
        // 已到终点站
        MonitoringMapDataRespVO.Point last = points.get(points.size() - 1);
        vo.setLongitude(last.getLongitude());
        vo.setLatitude(last.getLatitude());
    }

    /** 选取车辆当前班次：优先在途窗口，其次下一班待发，否则当天最后一班 */
    private ShiftDO selectCurrentShift(List<ShiftDO> shifts, LocalTime now) {
        if (shifts == null || shifts.isEmpty()) {
            return null;
        }
        ShiftDO next = null;
        for (ShiftDO shift : shifts) { // shifts 已按发车时间升序
            long elapsed = elapsedMinutes(shift, now);
            if (elapsed >= 0 && elapsed <= durationMinutes(shift)) {
                return shift;
            }
            if (elapsed < 0 && next == null) {
                next = shift;
            }
        }
        return next != null ? next : shifts.get(shifts.size() - 1);
    }

    /** 已行驶分钟数；未发车为负数 */
    private long elapsedMinutes(ShiftDO shift, LocalTime now) {
        if (shift.getPlannedDepartureTime() == null) {
            return -1;
        }
        return Duration.between(shift.getPlannedDepartureTime(), now).toMinutes();
    }

    private int durationMinutes(ShiftDO shift) {
        return shift.getPlannedDurationMinutes() != null ? shift.getPlannedDurationMinutes() : 60;
    }

    private static Double toDouble(BigDecimal value) {
        return value != null ? value.doubleValue() : null;
    }

    private static double round7(double value) {
        return BigDecimal.valueOf(value).setScale(7, RoundingMode.HALF_UP).doubleValue();
    }
}
