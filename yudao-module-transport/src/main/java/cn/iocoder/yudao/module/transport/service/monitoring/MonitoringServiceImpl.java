package cn.iocoder.yudao.module.transport.service.monitoring;

import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.transport.controller.admin.monitoring.vo.MonitoringMapDataRespVO;
import cn.iocoder.yudao.module.transport.controller.admin.monitoring.vo.MonitoringPlanRespVO;
import cn.iocoder.yudao.module.transport.controller.admin.monitoring.vo.MonitoringShiftRespVO;
import cn.iocoder.yudao.module.transport.controller.admin.monitoring.vo.MonitoringTrackRespVO;
import cn.iocoder.yudao.module.transport.controller.admin.monitoring.vo.MonitoringVehicleRespVO;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.DispatchPlanDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.DispatchPlanItemDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.driver.DriverDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.driver.DriverVehicleDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.TransportOrderDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.route.RouteDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.route.RouteStationDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.shift.ShiftDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.shift.ShiftExecutionDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.station.StationDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.vehicle.VehicleDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.vehicle.VehicleLocationDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.vehicle.VehicleLocationTrackDO;
import cn.iocoder.yudao.module.transport.dal.mysql.dispatch.DispatchPlanItemMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.dispatch.DispatchPlanMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.driver.DriverMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.driver.DriverVehicleMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.order.TransportOrderMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.route.RouteMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.route.RouteStationMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.shift.ShiftExecutionMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.shift.ShiftMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.station.StationMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.vehicle.VehicleLocationMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.vehicle.VehicleLocationTrackMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.vehicle.VehicleMapper;
import cn.iocoder.yudao.module.transport.enums.dispatch.DispatchPlanStatusEnum;
import cn.iocoder.yudao.module.transport.enums.dispatch.TaskItemStatusEnum;
import cn.iocoder.yudao.module.transport.integration.algorithm.AlgorithmClient;
import cn.iocoder.yudao.module.transport.integration.algorithm.dto.AlgorithmRouteReqDTO;
import cn.iocoder.yudao.module.transport.integration.algorithm.dto.AlgorithmRouteRespDTO;
import cn.iocoder.yudao.module.transport.service.simulation.SimulationEngine;
import cn.iocoder.yudao.module.transport.util.GeoDistanceUtil;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Lazy;
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
    @Resource private VehicleLocationTrackMapper vehicleLocationTrackMapper;
    @Resource private ShiftExecutionMapper shiftExecutionMapper;
    @Resource private SimulationEngine simulationEngine;
    @Resource @Lazy private VehicleLocationProvider locationProvider;
    @Resource private DispatchPlanMapper dispatchPlanMapper;
    @Resource private DispatchPlanItemMapper dispatchPlanItemMapper;
    @Resource private TransportOrderMapper transportOrderMapper;
    @Resource private AlgorithmClient algorithmClient;

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
        Map<Long, ShiftDO> shiftMap = shiftMapper.selectList().stream()
                .collect(Collectors.toMap(ShiftDO::getId, Function.identity(), (a, b) -> a));

        // 统一位置模型：通过 VehicleLocationProvider 获取所有车辆位置
        Set<Long> vehicleIds = vehicles.stream().map(VehicleDO::getId).collect(Collectors.toSet());
        Map<Long, VehicleLocationSnapshot> locationSnapshots = locationProvider.getLocations(vehicleIds);

        // 模拟排班：启用班次按发车时间升序，轮转分配给可用车辆（一车多班）
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

            // 统一位置模型：优先使用 VehicleLocationProvider
            VehicleLocationSnapshot snapshot = locationSnapshots.get(vehicle.getId());
            if (snapshot != null && !"OFFLINE".equals(snapshot.getSource())) {
                vo.setStatus(STATUS_IN_TRANSIT);
                vo.setLongitude(snapshot.getLongitude());
                vo.setLatitude(snapshot.getLatitude());
                vo.setSpeedKmh(snapshot.getSpeedKmh());
                vo.setDataSource(snapshot.getSource());
                vo.setNextStationName(snapshot.getNextStationName());
                if (snapshot.getUpdatedAt() != null) {
                    vo.setLastLocationTime(snapshot.getUpdatedAt());
                }
                // 从 snapshot 推导进度
                if (snapshot.getSimulationSeconds() != null && snapshot.getSimulationSeconds() > 0) {
                    SimulationEngine.SimRun run = simulationEngine.getRun(vehicle.getId());
                    if (run != null && run.getTotalSimSeconds() > 0) {
                        vo.setProgress((int) Math.min(100, snapshot.getSimulationSeconds() * 100 / run.getTotalSimSeconds()));
                    }
                }
                // 补充班次/线路信息（REAL 从 shiftMap，SIMULATED 不需要）
                if ("REAL".equals(snapshot.getSource()) || "REAL_STALE".equals(snapshot.getSource())) {
                    // 从真实位置获取班次信息需要额外逻辑，简化处理
                }
                return vo;
            }

            // 离线车辆：无有效位置
            vo.setStatus(STATUS_IDLE);
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

    /** 单次查询返回的轨迹点上限（与 app 溯源一致，防大包） */
    private static final int TRACK_POINT_LIMIT = 2000;

    @Override
    public MonitoringTrackRespVO getVehicleTrack(Long vehicleId, LocalDate date) {
        MonitoringTrackRespVO respVO = new MonitoringTrackRespVO();
        respVO.setVehicleId(vehicleId);
        VehicleDO vehicle = vehicleMapper.selectById(vehicleId);
        respVO.setPlateNo(vehicle != null ? vehicle.getPlateNo() : null);
        // 当日 [00:00, 次日 00:00) 范围，时间升序
        List<VehicleLocationTrackDO> tracks = vehicleLocationTrackMapper.selectByVehicleIdAndTimeRange(
                vehicleId, date.atStartOfDay(), date.plusDays(1).atStartOfDay(), TRACK_POINT_LIMIT);
        respVO.setPoints(tracks.stream().map(t -> {
            MonitoringTrackRespVO.TrackPoint point = new MonitoringTrackRespVO.TrackPoint();
            point.setLongitude(toDouble(t.getLongitude()));
            point.setLatitude(toDouble(t.getLatitude()));
            point.setSpeedKmh(toDouble(t.getSpeedKmh()));
            point.setReportTime(t.getReportTime());
            point.setShiftId(t.getShiftId());
            return point;
        }).toList());
        return respVO;
    }

    /** 经停动作中文名 */
    private static final Map<Integer, String> ACTION_NAMES = Map.of(
            0, "出发", 1, "接客", 2, "送客", 3, "派送", 4, "揽收", 5, "返回", 6, "经停");

    @Override
    public MonitoringPlanRespVO getVehiclePlan(Long vehicleId) {
        VehicleDO vehicle = vehicleMapper.selectById(vehicleId);
        if (vehicle == null) {
            return null;
        }
        MonitoringPlanRespVO vo = new MonitoringPlanRespVO();
        vo.setVehicleId(vehicleId);
        vo.setPlateNo(vehicle.getPlateNo());
        // 最新位置（REAL 优先；模拟引擎运行中取引擎位置）
        VehicleLocationDO loc = vehicleLocationMapper.selectByVehicleId(vehicleId);
        if (loc != null && loc.getLongitude() != null) {
            vo.setLongitude(toDouble(loc.getLongitude()));
            vo.setLatitude(toDouble(loc.getLatitude()));
            vo.setDataSource("REAL");
        } else {
            SimulationEngine.SimTick sim = simulationEngine.tick(vehicleId);
            if (sim != null) {
                vo.setLongitude(sim.getLongitude());
                vo.setLatitude(sim.getLatitude());
                vo.setDataSource("SIMULATED");
            }
        }
        // 该车辆方案（取最新一条已下发/执行中）
        List<DispatchPlanItemDO> items = dispatchPlanItemMapper.selectList(new LambdaQueryWrapperX<DispatchPlanItemDO>()
                .eq(DispatchPlanItemDO::getVehicleId, vehicleId));
        if (items.isEmpty()) {
            return vo;
        }
        Set<Long> planIds = items.stream().map(DispatchPlanItemDO::getPlanId).collect(Collectors.toSet());
        Map<Long, DispatchPlanDO> planMap = planIds.isEmpty() ? Map.of()
                : dispatchPlanMapper.selectList(new LambdaQueryWrapperX<DispatchPlanDO>()
                        .in(DispatchPlanDO::getId, planIds))
                .stream().collect(Collectors.toMap(DispatchPlanDO::getId, Function.identity(), (a, b) -> a));
        DispatchPlanItemDO anchor = items.stream()
                .filter(i -> planMap.containsKey(i.getPlanId()))
                .max(Comparator.comparing(DispatchPlanItemDO::getId)).orElse(null);
        if (anchor == null) {
            return vo;
        }
        DispatchPlanDO plan = planMap.get(anchor.getPlanId());
        vo.setPlanId(plan.getId());
        vo.setPlanStatusName(DispatchPlanStatusEnum.nameOf(plan.getStatus()));
        vo.setTaskWindowStart(plan.getTaskWindowStart());
        vo.setTaskWindowEnd(plan.getTaskWindowEnd());
        // 该方案该车辆经停（运营顺序来自 DispatchPlan）
        items = items.stream()
                .filter(i -> Objects.equals(i.getPlanId(), plan.getId()))
                .sorted(Comparator.comparing(DispatchPlanItemDO::getVisitSequence,
                        Comparator.nullsLast(Integer::compareTo)))
                .toList();
        Map<Long, StationDO> stationMap = stationMapper.selectList().stream()
                .collect(Collectors.toMap(StationDO::getId, Function.identity(), (a, b) -> a));
        Set<Long> orderIds = items.stream().map(DispatchPlanItemDO::getOrderId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> orderNoMap = orderIds.isEmpty() ? Map.of()
                : transportOrderMapper.selectBatchIds(orderIds).stream()
                        .collect(Collectors.toMap(TransportOrderDO::getId, TransportOrderDO::getOrderNo, (a, b) -> a));
        // 真实道路 polyline（RoadSegments，与司机端同一份；来源恒为 amap，逐段 euclidean 兜底不深究）
        List<double[]> fullPolyline = new ArrayList<>();
        List<MonitoringPlanRespVO.Stop> stops = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            DispatchPlanItemDO item = items.get(i);
            StationDO station = item.getStationId() != null ? stationMap.get(item.getStationId()) : null;
            MonitoringPlanRespVO.Stop stop = new MonitoringPlanRespVO.Stop();
            stop.setStationId(item.getStationId());
            stop.setStationName(station != null ? station.getStationName() : "");
            if (station != null) {
                stop.setLongitude(station.getLongitude() != null ? station.getLongitude().doubleValue() : null);
                stop.setLatitude(station.getLatitude() != null ? station.getLatitude().doubleValue() : null);
            }
            stop.setVisitSequence(item.getVisitSequence());
            stop.setActionName(ACTION_NAMES.getOrDefault(item.getActionType(), ""));
            stop.setOrderId(item.getOrderId());
            stop.setOrderNo(item.getOrderId() != null ? orderNoMap.get(item.getOrderId()) : null);
            stop.setQuantity(item.getQuantity());
            stop.setEstimatedArrivalTime(item.getEstimatedArrivalTime());
            stop.setPlannedDepartureTime(item.getPlannedDepartureTime());
            stop.setStatus(item.getStatus());
            stop.setStatusName(TaskItemStatusEnum.nameOf(item.getStatus()));
            stops.add(stop);
            if (i > 0) {
                DispatchPlanItemDO prev = items.get(i - 1);
                StationDO from = prev.getStationId() != null ? stationMap.get(prev.getStationId()) : null;
                List<double[]> seg = fetchPlanPolyline(from, station);
                if (seg != null) {
                    if (fullPolyline.isEmpty()) {
                        fullPolyline.addAll(seg);
                    } else {
                        fullPolyline.addAll(seg.subList(1, seg.size()));
                    }
                }
            }
        }
        vo.setStops(stops);
        vo.setPolyline(fullPolyline.stream().map(p -> new MonitoringPlanRespVO.Point(p[0], p[1])).toList());
        vo.setRouteProvider("amap");
        return vo;
    }

    /** 坐标对 → 真实道路 polyline（算法 /route；不可用回退两点直线，明确 euclidean） */
    private List<double[]> fetchPlanPolyline(StationDO from, StationDO to) {
        if (from == null || to == null || from.getLongitude() == null || from.getLatitude() == null
                || to.getLongitude() == null || to.getLatitude() == null) {
            return null;
        }
        try {
            AlgorithmRouteRespDTO route = algorithmClient.route(AlgorithmRouteReqDTO.builder()
                    .origin(AlgorithmRouteReqDTO.RoutePoint.builder()
                            .longitude(from.getLongitude().doubleValue()).latitude(from.getLatitude().doubleValue()).build())
                    .destination(AlgorithmRouteReqDTO.RoutePoint.builder()
                            .longitude(to.getLongitude().doubleValue()).latitude(to.getLatitude().doubleValue()).build())
                    .build());
            if (route != null && Boolean.TRUE.equals(route.getAvailable()) && route.getPolyline() != null
                    && route.getPolyline().size() >= 2) {
                return route.getPolyline().stream()
                        .map(p -> new double[]{p.getLongitude(), p.getLatitude()})
                        .collect(Collectors.toList());
            }
        } catch (Exception ignored) {
            // 算法不可用：走直线兜底
        }
        return List.of(new double[]{from.getLongitude().doubleValue(), from.getLatitude().doubleValue()},
                new double[]{to.getLongitude().doubleValue(), to.getLatitude().doubleValue()});
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

    /** 车辆位置 → 下一站：按线路经停顺序取"车辆最接近站点的下一站"（前方站）；已到终点无下一站返回 null */
    private String computeNextStation(List<RouteStationDO> routeStations, Map<Long, StationDO> stationMap,
                                      VehicleLocationDO location) {
        if (routeStations == null || routeStations.isEmpty()
                || location.getLongitude() == null || location.getLatitude() == null) {
            return null;
        }
        List<RouteStationDO> sorted = new ArrayList<>(routeStations);
        sorted.sort(Comparator.comparing(RouteStationDO::getSequenceNo));
        double lon = location.getLongitude().doubleValue();
        double lat = location.getLatitude().doubleValue();
        int nearestIdx = 0;
        double nearestDist = Double.MAX_VALUE;
        for (int i = 0; i < sorted.size(); i++) {
            StationDO station = stationMap.get(sorted.get(i).getStationId());
            if (station == null || station.getLongitude() == null || station.getLatitude() == null) {
                continue;
            }
            double dist = GeoDistanceUtil.haversineKm(lon, lat,
                    station.getLongitude().doubleValue(), station.getLatitude().doubleValue());
            if (dist < nearestDist) {
                nearestDist = dist;
                nearestIdx = i;
            }
        }
        int nextIdx = nearestIdx + 1;
        if (nextIdx >= sorted.size()) {
            return null; // 已到/越过终点站，无下一站
        }
        StationDO next = stationMap.get(sorted.get(nextIdx).getStationId());
        return next != null ? next.getStationName() : null;
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
