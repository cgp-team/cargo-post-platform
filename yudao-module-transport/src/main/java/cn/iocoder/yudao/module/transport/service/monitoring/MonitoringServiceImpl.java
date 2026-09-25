package cn.iocoder.yudao.module.transport.service.monitoring;

import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import lombok.extern.slf4j.Slf4j;
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
import cn.iocoder.yudao.module.transport.util.GeoDistanceUtil;
import jakarta.annotation.Resource;
import org.springframework.cache.annotation.Cacheable;
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
 * 位置仅取司机端上报的真实位置（transport_vehicle_location，report_time 15 分钟内有效，状态置在途）；
 * 无有效上报 → 空闲，不上图，不伪造位置；停用车辆（status=1）→ 状态停用，不上图。
 */
@Service
@Validated
@Slf4j
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
    @Resource @Lazy private VehicleLocationProvider locationProvider;
    @Resource private DispatchPlanMapper dispatchPlanMapper;
    @Resource private DispatchPlanItemMapper dispatchPlanItemMapper;
    @Resource private TransportOrderMapper transportOrderMapper;
    @Resource private AlgorithmClient algorithmClient;
    @Resource private cn.iocoder.yudao.module.transport.service.geo.RouteCorridorService routeCorridorService;

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
            // 线路真实道路折线：库里预热过就用真实几何（监控地图不再用"站点直连"冒充路线）；
            // 没预热过的线路在前端画成虚线示意（明确不是真实轨迹）。
            if (route.getNavigationPolyline() != null
                    && "AMAP".equalsIgnoreCase(route.getNavigationSource())) {
                List<double[]> corridor = cn.iocoder.yudao.module.transport.service.geo.RouteCorridorService
                        .parse(route.getNavigationPolyline());
                if (corridor.size() >= 2) {
                    item.setRoadProvider("AMAP");
                    item.setRoadPoints(corridor.stream().map(p -> {
                        MonitoringMapDataRespVO.RoadPoint rp = new MonitoringMapDataRespVO.RoadPoint();
                        rp.setLongitude(p[0]);
                        rp.setLatitude(p[1]);
                        return rp;
                    }).toList());
                }
            }
            return item;
        }).toList());
        return respVO;
    }

    @Override
    @Cacheable(cacheNames = "transport:monitoring:vehicles#12s", key = "'all'")
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

        // 统一位置模型：通过 VehicleLocationProvider 获取所有车辆位置（真实上报，无真实位置则 OFFLINE）
        Set<Long> vehicleIds = vehicles.stream().map(VehicleDO::getId).collect(Collectors.toSet());
        Map<Long, VehicleLocationSnapshot> locationSnapshots = locationProvider.getLocations(vehicleIds);

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
                // 待发/收车（IDLE）与在途状态由快照给出；真实上报固定为在途
                vo.setStatus(snapshot.getStatus() != null && snapshot.getStatus() == STATUS_IDLE
                        ? STATUS_IDLE : STATUS_IN_TRANSIT);
                vo.setLongitude(snapshot.getLongitude());
                vo.setLatitude(snapshot.getLatitude());
                vo.setSpeedKmh(snapshot.getSpeedKmh());
                vo.setDataSource(snapshot.getSource());
                vo.setNextStationName(snapshot.getNextStationName());
                vo.setCurrentStationName(snapshot.getCurrentStationName());
                // 到下一站的剩余距离/分钟：由统一快照给出，前端"预计到达下一站"用它
                vo.setDistanceToNextStationKm(snapshot.getDistanceToNextStation());
                vo.setEtaToNextStationMinutes(snapshot.getEtaToNextStationMinutes());
                // 班次/线路/进度：由统一快照直接给出（REAL 上报缺班次时下面再按派单/执行回填）
                vo.setShiftId(snapshot.getShiftId());
                vo.setShiftCode(snapshot.getShiftCode());
                vo.setRouteId(snapshot.getRouteId());
                vo.setRouteName(snapshot.getRouteName());
                if (snapshot.getProgress() != null) {
                    vo.setProgress(snapshot.getProgress());
                }
                if (snapshot.getUpdatedAt() != null) {
                    vo.setLastLocationTime(snapshot.getUpdatedAt());
                }
                // 真实上报车辆：按派单明细 / 当天班次执行回填班次与线路（取不到保持空，不猜线路）
                if ("REAL".equals(snapshot.getSource()) || "REAL_STALE".equals(snapshot.getSource())) {
                    fillRealVehicleShift(vo, vehicle.getId(), driverId, shiftMap, routeMap, enabledShifts);
                }
                return vo;
            }

            // 真正 OFFLINE（无真实上报）：不上图，不伪造位置
            vo.setStatus(STATUS_IDLE);
            return vo;
        }).toList();
    }

    /**
     * 真实上报车辆补充班次/线路：优先取算法派单明细，其次取当天司机端已发车的班次执行记录。
     * 都取不到时保持空（不进实时公交列表），不猜测线路，避免把车辆挂到错误线路。
     */
    private void fillRealVehicleShift(MonitoringVehicleRespVO vo, Long vehicleId, Long driverId,
                                      Map<Long, ShiftDO> shiftMap, Map<Long, RouteDO> routeMap,
                                      List<ShiftDO> enabledShifts) {
        Long shiftId = dispatchPlanItemMapper.selectListByVehicleId(vehicleId).stream()
                .filter(item -> item.getShiftId() != null)
                .max(Comparator.comparing(DispatchPlanItemDO::getId))
                .map(DispatchPlanItemDO::getShiftId).orElse(null);
        if (shiftId == null && driverId != null && !enabledShifts.isEmpty()) {
            shiftId = shiftExecutionMapper.selectListByShiftIdsAndExecDate(
                            enabledShifts.stream().map(ShiftDO::getId).toList(), LocalDate.now()).stream()
                    .filter(execution -> Objects.equals(execution.getDriverId(), driverId))
                    .max(Comparator.comparing(ShiftExecutionDO::getId))
                    .map(ShiftExecutionDO::getShiftId).orElse(null);
        }
        applyShiftRoute(vo, shiftId, shiftMap, routeMap);
    }

    /** 写班次编码 + 线路名（班次或线路缺失时保持原值） */
    private void applyShiftRoute(MonitoringVehicleRespVO vo, Long shiftId,
                                 Map<Long, ShiftDO> shiftMap, Map<Long, RouteDO> routeMap) {
        ShiftDO shift = shiftId != null ? shiftMap.get(shiftId) : null;
        if (shift == null) {
            return;
        }
        vo.setShiftCode(shift.getShiftCode());
        RouteDO route = shift.getRouteId() != null ? routeMap.get(shift.getRouteId()) : null;
        if (route != null) {
            vo.setRouteName(route.getRouteName());
        }
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
        // 最新位置（仅真实上报）
        VehicleLocationDO loc = vehicleLocationMapper.selectByVehicleId(vehicleId);
        if (loc != null && loc.getLongitude() != null) {
            vo.setLongitude(toDouble(loc.getLongitude()));
            vo.setLatitude(toDouble(loc.getLatitude()));
            vo.setDataSource("REAL");
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
                List<double[]> seg = fetchPlanPolyline(vehicleId, prev.getStationId(), item.getStationId(), from, station);
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
        // BE-28：routeProvider 反映真实来源——有折线才是 amap，否则如实标注 none（之前恒为 "amap" 误导排查）
        vo.setRouteProvider(fullPolyline.isEmpty() ? "none" : "amap");
        return vo;
    }

    /**
     * 坐标对 → 真实道路 polyline。
     *
     * <p>优先级：车辆运营线路走廊 → 算法 /route（只认 provider=amap）→ 取不到返回 {@code null}
     * （调用方断开折线，绝不回退"两点直线"：演示里那种直线既不好看也不可靠）。</p>
     */
    private List<double[]> fetchPlanPolyline(Long vehicleId, Long fromStationId, Long toStationId,
                                             StationDO from, StationDO to) {
        if (from == null || to == null || from.getLongitude() == null || from.getLatitude() == null
                || to.getLongitude() == null || to.getLatitude() == null) {
            return null;
        }
        if (routeCorridorService != null) {
            List<double[]> corridor = routeCorridorService.resolveForLeg(vehicleId, fromStationId, toStationId);
            if (corridor != null && corridor.size() >= 2) {
                return corridor;
            }
        }
        try {
            AlgorithmRouteRespDTO route = algorithmClient.route(AlgorithmRouteReqDTO.builder()
                    .origin(AlgorithmRouteReqDTO.RoutePoint.builder()
                            .longitude(from.getLongitude().doubleValue()).latitude(from.getLatitude().doubleValue()).build())
                    .destination(AlgorithmRouteReqDTO.RoutePoint.builder()
                            .longitude(to.getLongitude().doubleValue()).latitude(to.getLatitude().doubleValue()).build())
                    .build());
            if (route != null && Boolean.TRUE.equals(route.getAvailable()) && route.getPolyline() != null
                    && route.getPolyline().size() >= 2 && "amap".equalsIgnoreCase(route.getProvider())) {
                return route.getPolyline().stream()
                        .map(p -> new double[]{p.getLongitude(), p.getLatitude()})
                        .collect(Collectors.toList());
            }
        } catch (Exception ex) {
            // BE-28：降级必须留痕，"静默 ignored" 会让折线消失却查不到原因
            log.warn("[fetchPlanPolyline] 算法 /route 失败降级 vehicleId={} {}→{}: {}",
                    vehicleId, fromStationId, toStationId, ex.getMessage());
        }
        return null;
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
