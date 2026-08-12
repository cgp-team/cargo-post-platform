package cn.iocoder.yudao.module.transport.service.transport.driver;

import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.transport.controller.app.transport.driver.vo.*;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.DispatchPlanDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.DispatchPlanItemDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.driver.DriverDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.driver.DriverVehicleDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.CargoOrderDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.TransportOrderDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.route.RouteDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.route.RouteStationDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.shift.ShiftDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.shift.ShiftExecutionDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.station.StationDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.vehicle.VehicleDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.vehicle.VehicleLocationDO;
import cn.iocoder.yudao.module.transport.dal.mysql.dispatch.DispatchPlanItemMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.dispatch.DispatchPlanMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.driver.DriverMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.driver.DriverVehicleMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.order.CargoOrderMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.order.TransportOrderMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.route.RouteMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.route.RouteStationMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.shift.ShiftExecutionMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.shift.ShiftMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.station.StationMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.vehicle.VehicleLocationMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.vehicle.VehicleMapper;
import cn.iocoder.yudao.module.transport.enums.dispatch.TransportOrderStatusEnum;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.*;

/**
 * 司机端 App 聚合查询 Service 实现。
 * 班次三态与经停站点序列逻辑参照 {@link cn.iocoder.yudao.module.transport.service.monitoring.MonitoringServiceImpl}。
 */
@Service
@Validated
public class DriverAppServiceImpl implements DriverAppService {

    /** 班次状态：启用 */
    private static final int SHIFT_STATUS_ENABLED = 0;
    /** 订单类型：货运/生鲜 */
    private static final int ORDER_TYPE_CARGO = 2;
    /** 订单已取消 */
    private static final int ORDER_STATUS_CANCELLED = 5;
    /** 执行状态：在途 */
    private static final int EXEC_STATUS_IN_TRANSIT = 0;
    /** 执行状态：已完成 */
    private static final int EXEC_STATUS_COMPLETED = 1;
    /** 待处理状态集合（待调度/已入池/已分配） */
    private static final List<Integer> PENDING_STATUSES = List.of(0, 1, 2);

    private static final Map<Integer, String> SHIFT_STATUS_NAMES = Map.of(0, "未发车", 1, "在途", 2, "已完成");

    /** 调度明细动作类型中文名（0出发 1接客 2送客 3派送 4揽收 5返回） */
    private static final Map<Integer, String> ACTION_NAMES = Map.of(
            0, "出发", 1, "接客", 2, "送客", 3, "派送", 4, "揽收", 5, "返回");

    @Resource private DriverMapper driverMapper;
    @Resource private DriverVehicleMapper driverVehicleMapper;
    @Resource private VehicleMapper vehicleMapper;
    @Resource private ShiftMapper shiftMapper;
    @Resource private RouteMapper routeMapper;
    @Resource private RouteStationMapper routeStationMapper;
    @Resource private StationMapper stationMapper;
    @Resource private TransportOrderMapper transportOrderMapper;
    @Resource private CargoOrderMapper cargoOrderMapper;
    @Resource private DispatchPlanItemMapper dispatchPlanItemMapper;
    @Resource private DispatchPlanMapper dispatchPlanMapper;
    @Resource private ShiftExecutionMapper shiftExecutionMapper;
    @Resource private VehicleLocationMapper vehicleLocationMapper;

    @Override
    public AppDriverProfileRespVO profile(String mobile) {
        if (mobile == null || mobile.isBlank()) {
            return null;
        }
        DriverDO driver = driverMapper.selectByMobile(mobile.trim());
        if (driver == null) {
            return null;
        }
        AppDriverProfileRespVO vo = new AppDriverProfileRespVO();
        vo.setDriverId(driver.getId());
        vo.setName(driver.getName());
        vo.setMobile(driver.getMobile());
        driverVehicleMapper.selectActiveBindings().stream()
                .filter(bind -> Objects.equals(bind.getDriverId(), driver.getId()))
                .findFirst()
                .ifPresent(bind -> fillVehicle(vo, bind.getVehicleId()));
        return vo;
    }

    private void fillVehicle(AppDriverProfileRespVO vo, Long vehicleId) {
        VehicleDO vehicle = vehicleMapper.selectById(vehicleId);
        if (vehicle == null) {
            return;
        }
        vo.setVehicleId(vehicle.getId());
        vo.setPlateNo(vehicle.getPlateNo());
        vo.setCargoCapacity(vehicle.getCargoCapacity());
        vo.setCargoCapacityKg(vehicle.getCargoCapacityKg() != null ? vehicle.getCargoCapacityKg().intValue() : null);
    }

    @Override
    public List<AppDriverShiftRespVO> shifts() {
        LocalTime now = LocalTime.now();
        List<ShiftDO> shifts = listEnabledShifts();
        if (shifts.isEmpty()) {
            return List.of();
        }
        Map<Long, RouteDO> routeMap = routeMapper.selectList().stream()
                .collect(Collectors.toMap(RouteDO::getId, Function.identity()));
        Map<Long, StationDO> stationMap = stationMapper.selectList().stream()
                .collect(Collectors.toMap(StationDO::getId, Function.identity()));
        Map<Long, List<RouteStationDO>> routeStationMap = loadRouteStationMap(
                routeMap.values().stream().map(RouteDO::getId).toList());
        // 当天班次执行记录（司机维度不限）：有记录时班次状态以执行记录为准，否则保留时钟推导
        Map<Long, ShiftExecutionDO> executionMap = loadTodayExecutionMap(
                shifts.stream().map(ShiftDO::getId).toList());
        return shifts.stream().map(shift -> {
            AppDriverShiftRespVO vo = new AppDriverShiftRespVO();
            vo.setShiftId(shift.getId());
            vo.setShiftCode(shift.getShiftCode());
            vo.setRouteId(shift.getRouteId());
            RouteDO route = routeMap.get(shift.getRouteId());
            vo.setRouteName(route != null ? route.getRouteName() : null);
            vo.setPlannedDepartureTime(shift.getPlannedDepartureTime());
            vo.setPlannedDurationMinutes(shift.getPlannedDurationMinutes());
            ShiftExecutionDO execution = executionMap.get(shift.getId());
            int status = execution != null ? toShiftStatus(execution) : calcShiftStatus(shift, now);
            vo.setStatus(status);
            vo.setStatusName(SHIFT_STATUS_NAMES.getOrDefault(status, ""));
            vo.setStops(buildStops(routeStationMap.getOrDefault(shift.getRouteId(), List.of()), stationMap));
            return vo;
        }).toList();
    }

    @Override
    public List<AppDriverPickupRespVO> pickups() {
        List<TransportOrderDO> orders = transportOrderMapper.selectList(new LambdaQueryWrapperX<TransportOrderDO>()
                .eq(TransportOrderDO::getOrderType, ORDER_TYPE_CARGO)
                .in(TransportOrderDO::getStatus, PENDING_STATUSES)
                .orderByDesc(TransportOrderDO::getId));
        return orders.stream().map(order -> {
            AppDriverPickupRespVO vo = new AppDriverPickupRespVO();
            vo.setOrderId(order.getId());
            vo.setOrderNo(order.getOrderNo());
            CargoOrderDO cargo = cargoOrderMapper.selectOne(CargoOrderDO::getOrderId, order.getId());
            if (cargo != null) {
                vo.setGoodsName(cargo.getGoodsName());
                vo.setWeightKg(cargo.getWeightKg());
                vo.setReceiverName(cargo.getReceiverName());
                vo.setReceiverMobile(cargo.getReceiverMobile());
                vo.setReceiverAddress(cargo.getReceiverAddress());
            }
            return vo;
        }).toList();
    }

    @Override
    public AppDriverEarningsRespVO earnings() {
        AppDriverEarningsRespVO vo = new AppDriverEarningsRespVO();
        // 班次统计
        List<ShiftDO> shifts = listEnabledShifts();
        vo.setShiftCount((long) shifts.size());
        // 货运订单统计（排除已取消）
        List<TransportOrderDO> cargoOrders = transportOrderMapper.selectList(new LambdaQueryWrapperX<TransportOrderDO>()
                .eq(TransportOrderDO::getOrderType, ORDER_TYPE_CARGO)
                .ne(TransportOrderDO::getStatus, ORDER_STATUS_CANCELLED)
                .orderByDesc(TransportOrderDO::getId));
        vo.setTotalOrders((long) cargoOrders.size());
        vo.setTotalAmount(sumAmount(cargoOrders));
        LocalDateTime dayStart = LocalDate.now().atStartOfDay();
        List<TransportOrderDO> todayOrders = cargoOrders.stream()
                .filter(o -> o.getCreateTime() != null && o.getCreateTime().isAfter(dayStart))
                .toList();
        vo.setTodayOrders((long) todayOrders.size());
        vo.setTodayAmount(sumAmount(todayOrders));
        long pending = cargoOrders.stream()
                .filter(o -> PENDING_STATUSES.contains(o.getStatus()))
                .count();
        vo.setPendingCount(pending);
        // 最近明细（最多 10 条）
        List<AppDriverEarningsRecordRespVO> records = cargoOrders.stream().limit(10).map(this::toRecord).toList();
        vo.setRecords(records);
        return vo;
    }

    @Override
    public List<AppDriverTaskRespVO> tasks(Long driverId) {
        if (driverId == null) {
            return List.of();
        }
        List<DispatchPlanItemDO> items = dispatchPlanItemMapper.selectListByDriverId(driverId);
        if (items.isEmpty()) {
            return List.of();
        }
        // 仅返回已下发/执行中方案的明细
        Set<Long> planIds = items.stream().map(DispatchPlanItemDO::getPlanId).collect(Collectors.toSet());
        Map<Long, Integer> planStatusMap = dispatchPlanMapper.selectList(new LambdaQueryWrapperX<DispatchPlanDO>()
                        .in(DispatchPlanDO::getId, planIds))
                .stream().collect(Collectors.toMap(DispatchPlanDO::getId, DispatchPlanDO::getStatus));
        Map<Long, String> stationNameMap = stationMapper.selectList().stream()
                .collect(Collectors.toMap(StationDO::getId, StationDO::getStationName));
        Set<Long> orderIdSet = items.stream().map(DispatchPlanItemDO::getOrderId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> orderNoMap = orderIdSet.isEmpty() ? Map.of()
                : transportOrderMapper.selectList(new LambdaQueryWrapperX<TransportOrderDO>()
                        .in(TransportOrderDO::getId, orderIdSet))
                .stream().collect(Collectors.toMap(TransportOrderDO::getId, TransportOrderDO::getOrderNo));
        return items.stream()
                .filter(item -> isIssued(planStatusMap.get(item.getPlanId())))
                .map(item -> {
                    AppDriverTaskRespVO vo = new AppDriverTaskRespVO();
                    vo.setId(item.getId());
                    vo.setStationId(item.getStationId());
                    vo.setStationName(item.getStationId() != null ? stationNameMap.get(item.getStationId()) : null);
                    vo.setActionType(item.getActionType());
                    vo.setActionName(ACTION_NAMES.getOrDefault(item.getActionType(), ""));
                    vo.setOrderId(item.getOrderId());
                    vo.setOrderNo(item.getOrderId() != null ? orderNoMap.get(item.getOrderId()) : null);
                    vo.setEstimatedArrivalTime(item.getEstimatedArrivalTime());
                    return vo;
                }).toList();
    }

    private boolean isIssued(Integer planStatus) {
        return planStatus != null && (planStatus == 1 || planStatus == 2);
    }

    // ==================== 司机端写操作闭环 ====================

    @Override
    @Transactional
    public void depart(AppDriverDepartReqVO reqVO) {
        DriverDO driver = validateDriverExists(reqVO.getDriverId());
        ShiftDO shift = validateShiftExists(reqVO.getShiftId());
        Long vehicleId = resolveVehicleId(driver.getId());
        // 创建/复用当天执行记录并置在途
        LocalDate today = LocalDate.now();
        ShiftExecutionDO execution = shiftExecutionMapper.selectByShiftAndDriverAndDate(
                shift.getId(), driver.getId(), today);
        if (execution == null) {
            shiftExecutionMapper.insert(ShiftExecutionDO.builder()
                    .shiftId(shift.getId())
                    .driverId(driver.getId())
                    .vehicleId(vehicleId)
                    .execDate(today)
                    .departTime(LocalDateTime.now())
                    .status(EXEC_STATUS_IN_TRANSIT)
                    .build());
        } else if (!Objects.equals(execution.getStatus(), EXEC_STATUS_IN_TRANSIT)) {
            execution.setStatus(EXEC_STATUS_IN_TRANSIT);
            execution.setDepartTime(LocalDateTime.now());
            execution.setArriveTime(null);
            shiftExecutionMapper.updateById(execution);
        }
        // 该司机名下已分配的货运订单推进为已发车
        List<Long> orderIds = dispatchPlanItemMapper.selectListByDriverId(driver.getId()).stream()
                .map(DispatchPlanItemDO::getOrderId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (orderIds.isEmpty()) {
            return;
        }
        TransportOrderDO updateObj = new TransportOrderDO();
        updateObj.setStatus(TransportOrderStatusEnum.DEPARTED.getStatus());
        transportOrderMapper.update(updateObj, new LambdaQueryWrapperX<TransportOrderDO>()
                .in(TransportOrderDO::getId, orderIds)
                .eq(TransportOrderDO::getOrderType, ORDER_TYPE_CARGO)
                .eq(TransportOrderDO::getStatus, TransportOrderStatusEnum.ASSIGNED.getStatus()));
    }

    @Override
    @Transactional
    public void arrive(AppDriverArriveReqVO reqVO) {
        DriverDO driver = validateDriverExists(reqVO.getDriverId());
        ShiftDO shift = validateShiftExists(reqVO.getShiftId());
        ShiftExecutionDO execution = shiftExecutionMapper.selectByShiftAndDriverAndDate(
                shift.getId(), driver.getId(), LocalDate.now());
        if (execution == null) {
            throw exception(DRIVER_SHIFT_EXECUTION_NOT_EXISTS);
        }
        execution.setCurrentStationId(reqVO.getStationId());
        // 到达线路终点站（route_station 最大 sequence_no）：执行记录置已完成
        Long terminalStationId = routeStationMapper.selectListByRouteIds(List.of(shift.getRouteId())).stream()
                .max(Comparator.comparing(RouteStationDO::getSequenceNo))
                .map(RouteStationDO::getStationId)
                .orElse(null);
        if (Objects.equals(terminalStationId, reqVO.getStationId())) {
            execution.setArriveTime(LocalDateTime.now());
            execution.setStatus(EXEC_STATUS_COMPLETED);
        }
        shiftExecutionMapper.updateById(execution);
    }

    @Override
    @Transactional
    public void pickupConfirm(AppDriverOrderActionReqVO reqVO) {
        validateDriverExists(reqVO.getDriverId());
        TransportOrderDO order = validateOrderExists(reqVO.getOrderId());
        // 待调度/已入池/已分配 → 已发车
        if (!PENDING_STATUSES.contains(order.getStatus())) {
            throw exception(DRIVER_ORDER_STATUS_ILLEGAL);
        }
        order.setStatus(TransportOrderStatusEnum.DEPARTED.getStatus());
        transportOrderMapper.updateById(order);
    }

    @Override
    @Transactional
    public void deliver(AppDriverOrderActionReqVO reqVO) {
        validateDriverExists(reqVO.getDriverId());
        TransportOrderDO order = validateOrderExists(reqVO.getOrderId());
        // 已发车 → 已完成
        if (!Objects.equals(order.getStatus(), TransportOrderStatusEnum.DEPARTED.getStatus())) {
            throw exception(DRIVER_ORDER_STATUS_ILLEGAL);
        }
        order.setStatus(TransportOrderStatusEnum.COMPLETED.getStatus());
        transportOrderMapper.updateById(order);
    }

    @Override
    @Transactional
    public void reportLocation(AppDriverLocationReqVO reqVO) {
        DriverDO driver = validateDriverExists(reqVO.getDriverId());
        Long vehicleId = resolveVehicleId(driver.getId());
        // 每车一行，按车辆 upsert
        VehicleLocationDO location = vehicleLocationMapper.selectByVehicleId(vehicleId);
        if (location == null) {
            vehicleLocationMapper.insert(VehicleLocationDO.builder()
                    .vehicleId(vehicleId)
                    .shiftId(reqVO.getShiftId())
                    .longitude(reqVO.getLongitude())
                    .latitude(reqVO.getLatitude())
                    .speedKmh(reqVO.getSpeedKmh())
                    .reportTime(LocalDateTime.now())
                    .build());
            return;
        }
        location.setShiftId(reqVO.getShiftId());
        location.setLongitude(reqVO.getLongitude());
        location.setLatitude(reqVO.getLatitude());
        location.setSpeedKmh(reqVO.getSpeedKmh());
        location.setReportTime(LocalDateTime.now());
        vehicleLocationMapper.updateById(location);
    }

    private DriverDO validateDriverExists(Long driverId) {
        DriverDO driver = driverMapper.selectById(driverId);
        if (driver == null) {
            throw exception(DRIVER_NOT_FOUND);
        }
        return driver;
    }

    private ShiftDO validateShiftExists(Long shiftId) {
        ShiftDO shift = shiftMapper.selectById(shiftId);
        if (shift == null) {
            throw exception(SHIFT_NOT_EXISTS);
        }
        return shift;
    }

    private TransportOrderDO validateOrderExists(Long orderId) {
        TransportOrderDO order = transportOrderMapper.selectById(orderId);
        if (order == null) {
            throw exception(ORDER_NOT_EXISTS);
        }
        return order;
    }

    /** 司机当前绑定车辆编号；未绑定抛业务异常 */
    private Long resolveVehicleId(Long driverId) {
        return driverVehicleMapper.selectActiveBindings().stream()
                .filter(bind -> Objects.equals(bind.getDriverId(), driverId))
                .map(DriverVehicleDO::getVehicleId)
                .findFirst()
                .orElseThrow(() -> exception(DRIVER_VEHICLE_NOT_BOUND));
    }

    private AppDriverEarningsRecordRespVO toRecord(TransportOrderDO order) {
        AppDriverEarningsRecordRespVO vo = new AppDriverEarningsRecordRespVO();
        vo.setOrderNo(order.getOrderNo());
        vo.setTotalAmount(order.getTotalAmount());
        vo.setStatus(order.getStatus());
        vo.setStatusName(TransportOrderStatusEnum.nameOf(order.getStatus()));
        vo.setCreateTime(order.getCreateTime());
        CargoOrderDO cargo = cargoOrderMapper.selectOne(CargoOrderDO::getOrderId, order.getId());
        if (cargo != null) {
            vo.setGoodsName(cargo.getGoodsName());
            vo.setWeightKg(cargo.getWeightKg());
        }
        return vo;
    }

    private BigDecimal sumAmount(List<TransportOrderDO> orders) {
        return orders.stream()
                .map(TransportOrderDO::getTotalAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // ==================== 班次/站点聚合（参照 MonitoringServiceImpl） ====================

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

    private List<AppDriverStationRespVO> buildStops(List<RouteStationDO> routeStations, Map<Long, StationDO> stationMap) {
        List<AppDriverStationRespVO> stops = new ArrayList<>(routeStations.size());
        int lastMinutes = 0;
        for (RouteStationDO rs : routeStations) {
            StationDO station = stationMap.get(rs.getStationId());
            if (station == null) {
                continue;
            }
            AppDriverStationRespVO stop = new AppDriverStationRespVO();
            stop.setStationId(rs.getStationId());
            stop.setStationName(station.getStationName());
            stop.setSequenceNo(rs.getSequenceNo());
            if (rs.getPlannedMinutes() != null) {
                lastMinutes = rs.getPlannedMinutes();
            }
            stop.setPlannedMinutes(lastMinutes);
            stop.setLongitude(toDouble(station.getLongitude()));
            stop.setLatitude(toDouble(station.getLatitude()));
            stops.add(stop);
        }
        return stops;
    }

    /** 班次状态：0未发车 1在途 2已完成 */
    private int calcShiftStatus(ShiftDO shift, LocalTime now) {
        long elapsed = elapsedMinutes(shift, now);
        if (elapsed < 0) {
            return 0;
        }
        return elapsed <= durationMinutes(shift) ? 1 : 2;
    }

    /** 当天各班次的执行记录（同班次多条时已完成优先） */
    private Map<Long, ShiftExecutionDO> loadTodayExecutionMap(List<Long> shiftIds) {
        Map<Long, ShiftExecutionDO> map = new HashMap<>();
        for (ShiftExecutionDO execution : shiftExecutionMapper.selectListByShiftIdsAndExecDate(shiftIds, LocalDate.now())) {
            map.merge(execution.getShiftId(), execution,
                    (a, b) -> Objects.equals(b.getStatus(), EXEC_STATUS_COMPLETED) ? b : a);
        }
        return map;
    }

    /** 执行状态映射班次状态：在途→1，已完成→2 */
    private static int toShiftStatus(ShiftExecutionDO execution) {
        return Objects.equals(execution.getStatus(), EXEC_STATUS_COMPLETED) ? 2 : 1;
    }

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
        return value != null ? value.setScale(7, RoundingMode.HALF_UP).doubleValue() : null;
    }
}
