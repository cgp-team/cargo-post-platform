package cn.iocoder.yudao.module.transport.service.transport.driver;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.member.api.user.MemberUserApi;
import cn.iocoder.yudao.module.member.api.user.dto.MemberUserRespDTO;
import cn.iocoder.yudao.module.transport.controller.app.transport.driver.vo.*;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.DispatchPlanDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.DispatchPlanItemDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.DispatchPlanLogDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.TransportHandoverDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.TransportLegDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.driver.DriverDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.driver.DriverVehicleDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.CargoOrderDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.PostalOrderDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.ProductOrderDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.ProductOrderItemDO;
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
import cn.iocoder.yudao.module.transport.dal.mysql.dispatch.DispatchPlanLogMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.dispatch.DispatchPlanMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.dispatch.TransportLegMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.driver.DriverMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.driver.DriverVehicleMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.order.CargoOrderMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.order.PostalOrderMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.order.ProductOrderItemMapper;
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
import cn.iocoder.yudao.module.transport.enums.dispatch.TransportHandoverStatusEnum;
import cn.iocoder.yudao.module.transport.enums.dispatch.TransportLegStatusEnum;
import cn.iocoder.yudao.module.transport.enums.dispatch.TransportOrderEventTypeEnum;
import cn.iocoder.yudao.module.transport.enums.dispatch.TransportOrderStatusEnum;
import cn.iocoder.yudao.module.transport.integration.algorithm.AlgorithmClient;
import cn.iocoder.yudao.module.transport.integration.algorithm.dto.AlgorithmRouteReqDTO;
import cn.iocoder.yudao.module.transport.integration.algorithm.dto.AlgorithmRouteRespDTO;
import cn.iocoder.yudao.module.transport.service.simulation.SimulationEngine;
import cn.iocoder.yudao.module.transport.service.dispatch.HandoverService;
import cn.iocoder.yudao.module.transport.service.dispatch.MultiLegService;
import cn.iocoder.yudao.module.transport.service.notification.UserNotificationService;
import cn.iocoder.yudao.module.transport.service.order.OrderEventService;
import cn.iocoder.yudao.module.transport.service.transport.order.ProductOrderService;
import cn.iocoder.yudao.module.transport.util.GeoDistanceUtil;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.beans.factory.annotation.Value;

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
import static cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants.BAD_REQUEST;
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
    /** 订单类型：邮快件（快递进村取件核销） */
    private static final int ORDER_TYPE_POSTAL = 3;
    /** 商城订单在司机端的展示类型（不属于 transport_order.order_type，仅列表展示 + 分支用） */
    private static final int ORDER_TYPE_PRODUCT = 4;
    /** 订单已取消 */
    private static final int ORDER_STATUS_CANCELLED = 5;
    /** 执行状态：在途 */
    private static final int EXEC_STATUS_IN_TRANSIT = 0;
    /** 执行状态：已完成 */
    private static final int EXEC_STATUS_COMPLETED = 1;
    /** 待处理状态集合（待调度/已入池/已分配） */
    private static final List<Integer> PENDING_STATUSES = List.of(0, 1, 2);
    /** 可确认装车的状态集合（待调度/已入池/已分配/已发车）：
     *  depart 已把司机名下已分配订单推进为已发车(3)，真实流程「先发车→到站扫码装车」必须放行已发车，
     *  否则装车永远失败；deliver 仍要求已发车(3)→已完成(4)。已发车订单装车后保持 3，仅累计 loaded_count。
     *  防重依赖 loaded >= capacity 容量上限兜底（严格防重复装车留待后续）。 */
    private static final List<Integer> PICKUP_CONFIRM_STATUSES = List.of(0, 1, 2, 3);

    private static final Map<Integer, String> SHIFT_STATUS_NAMES = Map.of(0, "未发车", 1, "在途", 2, "已完成");

    /** 调度明细动作类型中文名（0出发 1接客 2送客 3派送 4揽收 5返回 6经停） */
    private static final Map<Integer, String> ACTION_NAMES = Map.of(
            0, "出发", 1, "接客", 2, "送客", 3, "派送", 4, "揽收", 5, "返回", 6, "经停");

    @Resource private DriverMapper driverMapper;
    @Resource private DriverVehicleMapper driverVehicleMapper;
    @Resource private VehicleMapper vehicleMapper;
    @Resource private ShiftMapper shiftMapper;
    @Resource private RouteMapper routeMapper;
    @Resource private RouteStationMapper routeStationMapper;
    @Resource private StationMapper stationMapper;
    @Resource private TransportOrderMapper transportOrderMapper;
    @Resource private CargoOrderMapper cargoOrderMapper;
    @Resource private PostalOrderMapper postalOrderMapper;
    @Resource private ProductOrderItemMapper productOrderItemMapper;
    @Resource private ProductOrderService productOrderService;
    @Resource private DispatchPlanItemMapper dispatchPlanItemMapper;
    @Resource private DispatchPlanMapper dispatchPlanMapper;
    @Resource private DispatchPlanLogMapper dispatchPlanLogMapper;
    @Resource private TransportLegMapper transportLegMapper;
    @Resource private ShiftExecutionMapper shiftExecutionMapper;
    @Resource private VehicleLocationMapper vehicleLocationMapper;
    @Resource private VehicleLocationTrackMapper vehicleLocationTrackMapper;
    @Resource private MemberUserApi memberUserApi;
    @Resource private AlgorithmClient algorithmClient;
    /** 真实道路几何（高德 Web key 直连，带缓存）：司机导航轨迹的首选来源 */
    @Resource private cn.iocoder.yudao.module.transport.service.geo.RoadPolylineService roadPolylineService;
    @Resource private SimulationEngine simulationEngine;
    @Resource private HandoverService handoverService;
    @Resource private MultiLegService multiLegService;
    @Resource private OrderEventService orderEventService;
    @Resource private UserNotificationService userNotificationService;

    /** P1-F/G：车辆接近目标站点触发的距离分级阈值(km)，可用 yudao.transport.approach.* 覆盖 */
    @Value("${yudao.transport.approach.dist-2km:2.0}")
    private double approachDist2Km;
    @Value("${yudao.transport.approach.dist-1km:1.0}")
    private double approachDist1Km;
    @Value("${yudao.transport.approach.dist-arriving:0.5}")
    private double approachDistArriving;

    @Override
    public AppDriverProfileRespVO profile() {
        DriverDO driver = currentDriverOrNull();
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
        List<AppDriverShiftRespVO> all = shifts.stream().map(shift -> {
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
            // 真实运力与当前站点（有执行记录时返回，供前端展示/恢复进度）
            if (execution != null) {
                vo.setLoadedCount(execution.getLoadedCount() != null ? execution.getLoadedCount() : 0);
                vo.setCurrentStationId(execution.getCurrentStationId());
            }
            vo.setStops(buildStops(routeStationMap.getOrDefault(shift.getRouteId(), List.of()), stationMap));
            return vo;
        }).toList();
        // 司机端只展示"与当前时刻相关"的班次：进行中的 + 之后最近 2 班（没有则退回当天最后一班）。
        // 线路网扩充到几十条班次后，把全部班次一次性铺给司机端既没法用、也和司机实际工作无关。
        List<AppDriverShiftRespVO> running = all.stream()
                .filter(s -> Integer.valueOf(1).equals(s.getStatus()))
                .sorted(Comparator.comparing(AppDriverShiftRespVO::getPlannedDepartureTime,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        LocalTime now2 = LocalTime.now();
        List<AppDriverShiftRespVO> upcoming = all.stream()
                .filter(s -> s.getPlannedDepartureTime() != null && s.getPlannedDepartureTime().isAfter(now2))
                .sorted(Comparator.comparing(AppDriverShiftRespVO::getPlannedDepartureTime))
                .limit(2)
                .toList();
        List<AppDriverShiftRespVO> result = new ArrayList<>(running);
        upcoming.forEach(s -> {
            if (result.stream().noneMatch(r -> Objects.equals(r.getShiftId(), s.getShiftId()))) {
                result.add(s);
            }
        });
        if (result.isEmpty()) {
            return all.stream()
                    .filter(s -> s.getPlannedDepartureTime() != null)
                    .max(Comparator.comparing(AppDriverShiftRespVO::getPlannedDepartureTime))
                    .map(List::of).orElse(List.of());
        }
        result.sort(Comparator.comparing(AppDriverShiftRespVO::getPlannedDepartureTime,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return result;
    }

    @Override
    public List<AppDriverPickupRespVO> pickups() {
        DriverDO driver = currentDriverOrNull();
        if (driver == null) {
            return List.of();
        }
        // 商城订单（已发货未妥投、承运车辆=本车）同样进"待装车/待妥投"列表：
        // 村民在小程序商城下单 → 后台发货选本车 → 司机端就能装车、妥投，走同一套司机作业闭环
        List<AppDriverPickupRespVO> productPickups = listProductPickups(driver);
        // 仅返回当前司机已下发/执行中派单明细里可确认装车的货运+邮快件订单（与装车/妥投归属校验一致）；
        // 用 PICKUP_CONFIRM_STATUSES 而非 PENDING_STATUSES：depart 后订单已为已发车(3)，发车后仍须可见待装车任务
        Set<Long> assignedOrderIds = listAssignedOrderIds(driver.getId());
        if (assignedOrderIds.isEmpty()) {
            return productPickups;
        }
        List<TransportOrderDO> orders = transportOrderMapper.selectList(new LambdaQueryWrapperX<TransportOrderDO>()
                .in(TransportOrderDO::getOrderType, ORDER_TYPE_CARGO, ORDER_TYPE_POSTAL)
                .in(TransportOrderDO::getStatus, PICKUP_CONFIRM_STATUSES)
                .in(TransportOrderDO::getId, assignedOrderIds)
                .orderByDesc(TransportOrderDO::getId));
        List<AppDriverPickupRespVO> cargoPickups = orders.stream().map(order -> {
            AppDriverPickupRespVO vo = new AppDriverPickupRespVO();
            vo.setOrderId(order.getId());
            vo.setOrderNo(order.getOrderNo());
            vo.setOrderType(order.getOrderType());
            vo.setBizType(Objects.equals(order.getOrderType(), ORDER_TYPE_POSTAL) ? "POSTAL" : "CARGO");
            if (Objects.equals(order.getOrderType(), ORDER_TYPE_POSTAL)) {
                // 邮快件：取件码/快递单号，收件人取件核销凭证
                PostalOrderDO postal = postalOrderMapper.selectOne(PostalOrderDO::getOrderId, order.getId());
                if (postal != null) {
                    vo.setGoodsName(StrUtil.blankToDefault(postal.getMailNo(), "快递"));
                    vo.setWeightKg(postal.getWeightKg());
                    vo.setReceiverName(postal.getReceiverName());
                    vo.setReceiverMobile(postal.getReceiverMobile());
                    vo.setReceiverAddress(postal.getReceiverAddress());
                    vo.setMailNo(postal.getMailNo());
                    vo.setPickupCode(postal.getPickupCode());
                }
            } else {
                CargoOrderDO cargo = cargoOrderMapper.selectOne(CargoOrderDO::getOrderId, order.getId());
                if (cargo != null) {
                    vo.setGoodsName(cargo.getGoodsName());
                    vo.setWeightKg(cargo.getWeightKg());
                    vo.setReceiverName(cargo.getReceiverName());
                    vo.setReceiverMobile(cargo.getReceiverMobile());
                    vo.setReceiverAddress(cargo.getReceiverAddress());
                }
            }
            return vo;
        }).toList();
        List<AppDriverPickupRespVO> all = new ArrayList<>(cargoPickups);
        all.addAll(productPickups);
        return all;
    }

    /**
     * 本车待执行的商城订单（司机端"待装车/待妥投"）。
     * 未绑定车辆时直接返回空：司机只是没接商城单，不该影响寄货待办列表（不抛"未绑定车辆"）。
     */
    private List<AppDriverPickupRespVO> listProductPickups(DriverDO driver) {
        Long vehicleId = driverVehicleMapper.selectActiveBindings().stream()
                .filter(bind -> Objects.equals(bind.getDriverId(), driver.getId()))
                .map(DriverVehicleDO::getVehicleId)
                .findFirst()
                .orElse(null);
        if (vehicleId == null) {
            return List.of();
        }
        return productOrderService.getDriverDeliveryTasks(vehicleId).stream()
                .map(this::toProductPickup)
                .collect(Collectors.toList());
    }

    /** 商城订单 → 司机端待办卡片（orderType=4 仅展示；动作走 product-load / product-deliver） */
    private AppDriverPickupRespVO toProductPickup(ProductOrderDO order) {
        AppDriverPickupRespVO vo = new AppDriverPickupRespVO();
        vo.setOrderId(order.getId());
        vo.setOrderNo(order.getOrderNo());
        vo.setOrderType(ORDER_TYPE_PRODUCT);
        vo.setBizType("PRODUCT");
        List<ProductOrderItemDO> items = productOrderItemMapper.selectListByOrderId(order.getId());
        if (!items.isEmpty()) {
            ProductOrderItemDO first = items.get(0);
            vo.setGoodsName(items.size() > 1
                    ? first.getProductName() + " 等 " + items.size() + " 种" : first.getProductName());
        }
        vo.setReceiverName(order.getReceiverName());
        vo.setReceiverMobile(order.getReceiverMobile());
        vo.setReceiverAddress(order.getReceiverAddress());
        return vo;
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
        // 归属校验：司机身份从登录态解析，客户端 driverId 仅做一致性校验，防越权查看他人任务
        DriverDO driver = requireCurrentDriver(driverId);
        List<DispatchPlanItemDO> items = dispatchPlanItemMapper.selectListByDriverId(driver.getId());
        if (items.isEmpty()) {
            return List.of();
        }
        // 仅返回已下发/执行中方案的明细；携带任务段窗口（同一 planId 下按 visitSequence 有序即完整任务段）
        Set<Long> planIds = items.stream().map(DispatchPlanItemDO::getPlanId).collect(Collectors.toSet());
        Map<Long, DispatchPlanDO> planMap = planIds.isEmpty() ? Map.of()
                : dispatchPlanMapper.selectList(new LambdaQueryWrapperX<DispatchPlanDO>()
                        .in(DispatchPlanDO::getId, planIds))
                .stream().collect(Collectors.toMap(DispatchPlanDO::getId, Function.identity(), (a, b) -> a));
        Map<Long, String> stationNameMap = stationMapper.selectList().stream()
                .collect(Collectors.toMap(StationDO::getId, StationDO::getStationName));
        Set<Long> orderIdSet = items.stream().map(DispatchPlanItemDO::getOrderId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> orderNoMap = orderIdSet.isEmpty() ? Map.of()
                : transportOrderMapper.selectList(new LambdaQueryWrapperX<TransportOrderDO>()
                        .in(TransportOrderDO::getId, orderIdSet))
                .stream().collect(Collectors.toMap(TransportOrderDO::getId, TransportOrderDO::getOrderNo));
        return items.stream()
                .filter(item -> {
                    DispatchPlanDO plan = planMap.get(item.getPlanId());
                    return plan != null && isIssued(plan.getStatus());
                })
                .map(item -> {
                    AppDriverTaskRespVO vo = new AppDriverTaskRespVO();
                    vo.setPlanId(item.getPlanId());
                    DispatchPlanDO plan = planMap.get(item.getPlanId());
                    if (plan != null) {
                        vo.setTaskWindowStart(plan.getTaskWindowStart());
                        vo.setTaskWindowEnd(plan.getTaskWindowEnd());
                    }
                    vo.setVisitSequence(item.getVisitSequence());
                    vo.setId(item.getId());
                    vo.setStationId(item.getStationId());
                    vo.setStationName(item.getStationId() != null ? stationNameMap.get(item.getStationId()) : null);
                    vo.setActionType(item.getActionType());
                    vo.setActionName(ACTION_NAMES.getOrDefault(item.getActionType(), ""));
                    vo.setOrderId(item.getOrderId());
                    vo.setOrderNo(item.getOrderId() != null ? orderNoMap.get(item.getOrderId()) : null);
                    vo.setEstimatedArrivalTime(item.getEstimatedArrivalTime());
                    vo.setPlannedDepartureTime(item.getPlannedDepartureTime());
                    vo.setServiceDurationSeconds(item.getServiceDurationSeconds());
                    vo.setQuantity(item.getQuantity());
                    vo.setSegmentDurationSeconds(item.getSegmentDurationSeconds());
                    vo.setSegmentDistanceKm(item.getSegmentDistanceKm());
                    vo.setStatus(item.getStatus());
                    vo.setStatusName(TaskItemStatusEnum.nameOf(item.getStatus()));
                    return vo;
                }).toList();
    }

    private boolean isIssued(Integer planStatus) {
        return planStatus != null && (planStatus == 1 || planStatus == 2);
    }

    @Override
    public AppDriverRouteRespVO getRoute(Long driverId) {
        DriverDO driver = requireCurrentDriver(driverId);
        Long vehicleId = resolveVehicleId(driver.getId());
        List<DispatchPlanItemDO> items = dispatchPlanItemMapper.selectList(new LambdaQueryWrapperX<DispatchPlanItemDO>()
                .eq(DispatchPlanItemDO::getDriverId, driver.getId())
                .eq(DispatchPlanItemDO::getVehicleId, vehicleId)
                .orderByAsc(DispatchPlanItemDO::getVisitSequence));
        if (items.isEmpty()) {
            return null;
        }
        Map<Long, Integer> planStatusMap = planStatusMap(items.stream().map(DispatchPlanItemDO::getPlanId)
                .collect(Collectors.toSet()));
        DispatchPlanItemDO anchor = items.stream()
                .filter(i -> isIssued(planStatusMap.get(i.getPlanId())))
                .findFirst().orElse(null);
        if (anchor == null) {
            return null;
        }
        Long planId = anchor.getPlanId();
        // 仅取该方案该车辆的经停
        items = items.stream()
                .filter(i -> Objects.equals(i.getPlanId(), planId))
                .collect(Collectors.toList());
        Map<Long, StationDO> stationMap = stationMapper.selectList().stream()
                .collect(Collectors.toMap(StationDO::getId, Function.identity(), (a, b) -> a));
        AppDriverRouteRespVO vo = new AppDriverRouteRespVO();
        vo.setPlanId(planId);
        vo.setVehicleId(vehicleId);
        List<AppDriverRouteRespVO.Stop> stops = new ArrayList<>();
        List<double[]> fullPolyline = new ArrayList<>();
        String provider = "amap";
        for (int i = 0; i < items.size(); i++) {
            DispatchPlanItemDO item = items.get(i);
            StationDO station = item.getStationId() != null ? stationMap.get(item.getStationId()) : null;
            AppDriverRouteRespVO.Stop stop = new AppDriverRouteRespVO.Stop();
            stop.setStationId(item.getStationId());
            stop.setStationName(station != null ? station.getStationName() : "");
            if (station != null) {
                stop.setLongitude(station.getLongitude() != null ? station.getLongitude().doubleValue() : null);
                stop.setLatitude(station.getLatitude() != null ? station.getLatitude().doubleValue() : null);
            }
            stop.setVisitSequence(item.getVisitSequence());
            stop.setActionName(ACTION_NAMES.getOrDefault(item.getActionType(), ""));
            stop.setStatus(item.getStatus());
            stop.setStatusName(TaskItemStatusEnum.nameOf(item.getStatus()));
            stops.add(stop);
            // 拼接每段真实道路 polyline（首段起点为上一站）
            if (i > 0) {
                DispatchPlanItemDO prev = items.get(i - 1);
                StationDO from = prev.getStationId() != null ? stationMap.get(prev.getStationId()) : null;
                RouteFetch fetched = fetchRoutePolyline(from, station);
                if (fetched != null) {
                    if (fullPolyline.isEmpty()) {
                        fullPolyline.addAll(fetched.points); // 整段从上一站起
                    } else {
                        // 去掉与上段末点重复的起点，再拼接
                        List<double[]> tail = fetched.points.subList(1, fetched.points.size());
                        fullPolyline.addAll(tail);
                    }
                    if ("euclidean".equals(fetched.provider)) {
                        provider = "euclidean";
                    }
                }
            }
        }
        vo.setStops(stops);
        vo.setPolyline(fullPolyline.stream()
                .map(p -> new AppDriverRouteRespVO.Point(p[0], p[1])).toList());
        vo.setRouteProvider(provider);
        // 偏航判定：车辆当前上报位置 → 距规划 polyline 最小距离（>100m 标记 ROUTE_DEVIATED，只报警）
        VehicleLocationDO loc = vehicleLocationMapper.selectByVehicleId(vehicleId);
        if (loc != null && loc.getLongitude() != null && loc.getLatitude() != null && fullPolyline.size() >= 2) {
            double dev = GeoDistanceUtil.deviationMetersFromPolyline(
                    loc.getLongitude().doubleValue(), loc.getLatitude().doubleValue(), fullPolyline);
            vo.setDeviationMeters(Math.round(dev * 10) / 10.0);
            vo.setDeviated(dev > GeoDistanceUtil.DEVIATION_THRESHOLD_METERS);
        }
        return vo;
    }

    /** 单段真实道路 polyline 抓取结果 */
    private record RouteFetch(List<double[]> points, String provider) {
    }

    /** 坐标对 → 真实道路 polyline（算法 /route）；不可用/失败回退两点直线（明确 euclidean） */
    private RouteFetch fetchRoutePolyline(StationDO from, StationDO to) {
        if (from == null || to == null || from.getLongitude() == null || from.getLatitude() == null
                || to.getLongitude() == null || to.getLatitude() == null) {
            return null;
        }
        // 1) 后端直连高德驾车路网（带缓存）：司机导航轨迹不因算法服务不可用而变直线
        List<double[]> direct = roadPolylineService == null ? null : roadPolylineService.route(
                from.getLongitude().doubleValue(), from.getLatitude().doubleValue(),
                to.getLongitude().doubleValue(), to.getLatitude().doubleValue());
        if (direct != null && direct.size() >= 2) {
            return new RouteFetch(direct, "amap");
        }
        // 2) 高德不可用：退回算法服务 /route
        try {
            AlgorithmRouteRespDTO route = algorithmClient.route(AlgorithmRouteReqDTO.builder()
                    .origin(AlgorithmRouteReqDTO.RoutePoint.builder()
                            .longitude(from.getLongitude().doubleValue()).latitude(from.getLatitude().doubleValue()).build())
                    .destination(AlgorithmRouteReqDTO.RoutePoint.builder()
                            .longitude(to.getLongitude().doubleValue()).latitude(to.getLatitude().doubleValue()).build())
                    .build());
            if (route != null && Boolean.TRUE.equals(route.getAvailable()) && route.getPolyline() != null
                    && route.getPolyline().size() >= 2) {
                List<double[]> points = route.getPolyline().stream()
                        .map(p -> new double[]{p.getLongitude(), p.getLatitude()})
                        .collect(Collectors.toList());
                return new RouteFetch(points, route.getProvider());
            }
        } catch (Exception ignored) {
            // 算法不可用：走直线兜底
        }
        return new RouteFetch(List.of(
                new double[]{from.getLongitude().doubleValue(), from.getLatitude().doubleValue()},
                new double[]{to.getLongitude().doubleValue(), to.getLatitude().doubleValue()}), "euclidean");
    }

    // ==================== 司机端写操作闭环 ====================

    @Override
    @Transactional
    public void depart(AppDriverDepartReqVO reqVO) {
        DriverDO driver = requireCurrentDriver(reqVO.getDriverId());
        ShiftDO shift = validateShiftExists(reqVO.getShiftId());
        Long vehicleId = resolveVehicleId(driver.getId());
        // 发车起点 = 线路经停序列首站，作为到站顺序校验的基准（防跳站）
        Long firstStationId = routeStationMapper.selectListByRouteIds(List.of(shift.getRouteId())).stream()
                .min(Comparator.comparing(RouteStationDO::getSequenceNo, Comparator.nullsLast(Integer::compareTo)))
                .map(RouteStationDO::getStationId)
                .orElse(null);
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
                    .currentStationId(firstStationId)
                    .status(EXEC_STATUS_IN_TRANSIT)
                    .build());
        } else if (!Objects.equals(execution.getStatus(), EXEC_STATUS_IN_TRANSIT)) {
            execution.setStatus(EXEC_STATUS_IN_TRANSIT);
            execution.setDepartTime(LocalDateTime.now());
            execution.setArriveTime(null);
            execution.setCurrentStationId(firstStationId);
            shiftExecutionMapper.updateById(execution);
        }
        // 该司机名下已下发/执行中派单的已分配货运订单推进为已发车（与装车/妥投归属校验一致）
        Set<Long> orderIds = listAssignedOrderIds(driver.getId());
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
        DriverDO driver = requireCurrentDriver(reqVO.getDriverId());
        ShiftDO shift = validateShiftExists(reqVO.getShiftId());
        ShiftExecutionDO execution = shiftExecutionMapper.selectByShiftAndDriverAndDate(
                shift.getId(), driver.getId(), LocalDate.now());
        if (execution == null) {
            throw exception(DRIVER_SHIFT_EXECUTION_NOT_EXISTS);
        }
        // 站点归属与顺序校验（双模式）：
        // ① 目标站属于司机当前已下发任务段（plan_item，含绕行村站）→ 按任务段 visit_sequence 校验（放行绕行站）
        // ② 目标站不在任务段 → 按班次线路 sequence_no 逐站校验（向后兼容纯班次模式）
        List<RouteStationDO> routeStations = routeStationMapper.selectListByRouteIds(List.of(shift.getRouteId()));
        RouteStationDO target = routeStations.stream()
                .filter(rs -> Objects.equals(rs.getStationId(), reqVO.getStationId()))
                .findFirst().orElse(null);
        int targetSeq = target != null && target.getSequenceNo() != null ? target.getSequenceNo() : 0;

        Boolean taskCheck = taskSequenceCheck(driver.getId(), reqVO.getStationId(), execution.getCurrentStationId());
        if (taskCheck == null) {
            // 非任务段：必须属于班次线路 + 按 sequence 顺序推进（防跳站）
            if (target == null) {
                throw exception(DRIVER_STATION_NOT_IN_ROUTE);
            }
            int currentSeq = sequenceNoOf(routeStations, execution.getCurrentStationId());
            if (targetSeq < currentSeq || targetSeq > currentSeq + 1) {
                throw exception(DRIVER_STATION_ORDER_ILLEGAL);
            }
        } else if (!taskCheck) {
            // 任务段内但非期望下一站（跳站）
            throw exception(DRIVER_STATION_ORDER_ILLEGAL);
        }
        // taskCheck == true：任务段期望下一站，放行
        execution.setCurrentStationId(reqVO.getStationId());
        // 到达线路终点站（route_station 最大 sequence_no）：执行记录置已完成（绕行村站 targetSeq=0 不触发）
        int maxSeq = routeStations.stream().map(RouteStationDO::getSequenceNo)
                .filter(Objects::nonNull).max(Integer::compareTo).orElse(0);
        if (targetSeq >= maxSeq) {
            execution.setArriveTime(LocalDateTime.now());
            execution.setStatus(EXEC_STATUS_COMPLETED);
        }
        shiftExecutionMapper.updateById(execution);
        // Phase 8：同步任务段明细状态（后端为源）——本站 ARRIVED，之前 COMPLETED，之后 PENDING
        syncTaskItemStatusOnArrive(driver.getId(), reqVO.getStationId());
    }

    /**
     * 到站后按任务段（经停顺序）推进明细状态：当前站 ARRIVED、更早经停 COMPLETED、其后 PENDING。
     * 状态以后端为最终来源，前端不做布尔替代。
     */
    private void syncTaskItemStatusOnArrive(Long driverId, Long stationId) {
        List<DispatchPlanItemDO> items = dispatchPlanItemMapper.selectList(new LambdaQueryWrapperX<DispatchPlanItemDO>()
                .eq(DispatchPlanItemDO::getDriverId, driverId)
                .orderByAsc(DispatchPlanItemDO::getVisitSequence));
        if (items.isEmpty()) {
            return;
        }
        Set<Long> planIds = items.stream().map(DispatchPlanItemDO::getPlanId).collect(Collectors.toSet());
        Map<Long, Integer> planStatusMap = planStatusMap(planIds);
        // 命中当前站的明细：取其 (planId, vehicleId) 作为该车任务段上下文
        DispatchPlanItemDO anchor = items.stream()
                .filter(i -> Objects.equals(i.getStationId(), stationId))
                .findFirst().orElse(null);
        if (anchor == null) {
            return;
        }
        for (DispatchPlanItemDO item : items) {
            if (!Objects.equals(item.getPlanId(), anchor.getPlanId())
                    || !Objects.equals(item.getVehicleId(), anchor.getVehicleId())) {
                continue;
            }
            if (!isIssued(planStatusMap.get(item.getPlanId()))) {
                continue;
            }
            Integer newStatus;
            if (Objects.equals(item.getStationId(), stationId)) {
                newStatus = TaskItemStatusEnum.ARRIVED.getStatus();
            } else if (item.getVisitSequence() != null && anchor.getVisitSequence() != null
                    && item.getVisitSequence() < anchor.getVisitSequence()) {
                newStatus = TaskItemStatusEnum.COMPLETED.getStatus();
            } else {
                newStatus = TaskItemStatusEnum.PENDING.getStatus();
            }
            if (!Objects.equals(item.getStatus(), newStatus)) {
                DispatchPlanItemDO upd = new DispatchPlanItemDO();
                upd.setId(item.getId());
                upd.setStatus(newStatus);
                dispatchPlanItemMapper.updateById(upd);
            }
        }
    }

    /** 站点在经停序列中的序号；未设置当前站时为 0（发车后应从首站开始到达） */
    private static int sequenceNoOf(List<RouteStationDO> routeStations, Long stationId) {
        if (stationId == null) {
            return 0;
        }
        return routeStations.stream()
                .filter(rs -> Objects.equals(rs.getStationId(), stationId))
                .map(rs -> rs.getSequenceNo() != null ? rs.getSequenceNo() : 0)
                .findFirst().orElse(0);
    }

    /**
     * 任务段顺序检查（到站双模式校验的第一分支）。
     * 返回 null = 目标站不在司机任何已下发任务段内（走班次线路校验）；
     *       true = 目标站是任务段期望下一站（放行）；
     *       false = 目标站在任务段内但非期望下一站（跳站，报 DRIVER_STATION_ORDER_ILLEGAL）。
     *
     * 期望下一站 = 该方案中 visitSequence 紧随 currentStationId 之后的首个经停；
     * 未到过任务段站（currentStationId 为空或不在任务段）时取 visitSequence 最小的首个经停。
     */
    private Boolean taskSequenceCheck(Long driverId, Long stationId, Long currentStationId) {
        List<DispatchPlanItemDO> items = dispatchPlanItemMapper.selectList(new LambdaQueryWrapperX<DispatchPlanItemDO>()
                .eq(DispatchPlanItemDO::getDriverId, driverId)
                .orderByAsc(DispatchPlanItemDO::getVisitSequence));
        if (items == null || items.isEmpty()) {
            return null;
        }
        Map<Long, Integer> planStatusMap = planStatusMap(items.stream().map(DispatchPlanItemDO::getPlanId)
                .collect(Collectors.toSet()));
        // 目标站所属的已下发方案
        DispatchPlanItemDO anchor = items.stream()
                .filter(i -> Objects.equals(i.getStationId(), stationId))
                .filter(i -> isIssued(planStatusMap.get(i.getPlanId())))
                .findFirst().orElse(null);
        if (anchor == null) {
            return null;
        }
        // 该方案内按 visitSequence 排序的经停
        List<DispatchPlanItemDO> planItems = items.stream()
                .filter(i -> Objects.equals(i.getPlanId(), anchor.getPlanId()))
                .filter(i -> isIssued(planStatusMap.get(i.getPlanId())))
                .sorted(Comparator.comparing(i -> i.getVisitSequence() == null ? Integer.MAX_VALUE : i.getVisitSequence()))
                .collect(Collectors.toList());
        // 期望下一站：currentStationId 之后（visitSequence 更大）的首个经停；未到过任务段站时取首个
        DispatchPlanItemDO expect = null;
        if (currentStationId != null) {
            Integer currentSeq = planItems.stream()
                    .filter(i -> Objects.equals(i.getStationId(), currentStationId))
                    .map(DispatchPlanItemDO::getVisitSequence)
                    .findFirst().orElse(null);
            if (currentSeq != null) {
                expect = planItems.stream()
                        .filter(i -> i.getVisitSequence() != null && i.getVisitSequence() > currentSeq)
                        .findFirst().orElse(null);
            }
        }
        if (expect == null) {
            expect = planItems.isEmpty() ? null : planItems.get(0);
        }
        if (expect == null) {
            return false;
        }
        return Objects.equals(expect.getStationId(), stationId);
    }

    @Override
    @Transactional
    public void pickupConfirm(AppDriverOrderActionReqVO reqVO) {
        DriverDO driver = requireCurrentDriver(reqVO.getDriverId());
        TransportOrderDO order = validateOrderExists(reqVO.getOrderId());
        // 待调度/已入池/已分配/已发车 → 已发车（depart 已把已分配订单推进为已发车，此处放行已发车实现「先发车后装车」）
        if (!PICKUP_CONFIRM_STATUSES.contains(order.getStatus())) {
            throw exception(DRIVER_ORDER_STATUS_ILLEGAL);
        }
        // 订单归属校验：必须在当前司机已下发/执行中的调度方案明细里
        DispatchPlanItemDO planItem = validateOrderAssignedToDriver(order.getId(), driver.getId());
        // 货运散件强制司机收件照片（快递总站核对"这是哪家货"的凭证，缺照片不能装车）
        if (Objects.equals(order.getOrderType(), ORDER_TYPE_CARGO) && StrUtil.isBlank(reqVO.getDriverPhotoUrl())) {
            throw exception(DRIVER_CARGO_PHOTO_REQUIRED);
        }
        if (StrUtil.isNotBlank(reqVO.getDriverPhotoUrl())) {
            CargoOrderDO cargo = cargoOrderMapper.selectOne(CargoOrderDO::getOrderId, order.getId());
            if (cargo != null) {
                CargoOrderDO upd = new CargoOrderDO();
                upd.setId(cargo.getId());
                upd.setDriverPhotoUrl(reqVO.getDriverPhotoUrl());
                cargoOrderMapper.updateById(upd);
            }
        }
        // 执行记录：智能派单的经停明细不绑定固定班次（shift_id 为空），按"司机今天实际发车的那条"兜底，
        // 否则「一键演示生成的方案」在司机端扫码装车会直接报"班次执行记录不存在"，装车走不下去。
        ShiftExecutionDO execution = resolveActiveExecution(driver.getId(), planItem);
        if (execution == null) {
            throw exception(DRIVER_SHIFT_EXECUTION_NOT_EXISTS);
        }
        VehicleDO vehicle = vehicleMapper.selectById(execution.getVehicleId());
        int capacity = vehicle != null && vehicle.getCargoCapacity() != null ? vehicle.getCargoCapacity() : -1;
        int loaded = execution.getLoadedCount() != null ? execution.getLoadedCount() : 0;
        if (capacity >= 0 && loaded >= capacity) {
            throw exception(DRIVER_CARGO_FULL);
        }
        // CAS 推进订单状态：可确认装车状态内更新（已发车订单装车后保持 3，幂等），防非法状态装车
        TransportOrderDO updateObj = new TransportOrderDO();
        updateObj.setStatus(TransportOrderStatusEnum.DEPARTED.getStatus());
        int affected = transportOrderMapper.update(updateObj, new LambdaQueryWrapperX<TransportOrderDO>()
                .eq(TransportOrderDO::getId, order.getId())
                .in(TransportOrderDO::getStatus, PICKUP_CONFIRM_STATUSES));
        if (affected == 0) {
            throw exception(DRIVER_ORDER_STATUS_ILLEGAL);
        }
        // 已装件数 +1
        ShiftExecutionDO loadedUpdate = new ShiftExecutionDO();
        loadedUpdate.setId(execution.getId());
        loadedUpdate.setLoadedCount(loaded + 1);
        shiftExecutionMapper.updateById(loadedUpdate);
    }

    @Override
    @Transactional
    public void deliver(AppDriverOrderActionReqVO reqVO) {
        DriverDO driver = requireCurrentDriver(reqVO.getDriverId());
        TransportOrderDO order = validateOrderExists(reqVO.getOrderId());
        // 邮快件不走妥投：由收件人凭取件码核销（pickup-verify）
        if (Objects.equals(order.getOrderType(), ORDER_TYPE_POSTAL)) {
            throw exception(DRIVER_ORDER_STATUS_ILLEGAL);
        }
        // 已发车 → 已完成
        if (!Objects.equals(order.getStatus(), TransportOrderStatusEnum.DEPARTED.getStatus())) {
            throw exception(DRIVER_ORDER_STATUS_ILLEGAL);
        }
        // 订单归属校验：必须在当前司机已下发/执行中的调度方案明细里
        DispatchPlanItemDO planItem = validateOrderAssignedToDriver(order.getId(), driver.getId());
        // CAS 推进订单状态：仅已发车可更新，防重复妥投
        TransportOrderDO updateObj = new TransportOrderDO();
        updateObj.setStatus(TransportOrderStatusEnum.COMPLETED.getStatus());
        int affected = transportOrderMapper.update(updateObj, new LambdaQueryWrapperX<TransportOrderDO>()
                .eq(TransportOrderDO::getId, order.getId())
                .eq(TransportOrderDO::getStatus, TransportOrderStatusEnum.DEPARTED.getStatus()));
        if (affected == 0) {
            throw exception(DRIVER_ORDER_STATUS_ILLEGAL);
        }
        // 已装件数 -1（地板 0）：执行记录口径同装车（明细没写班次时按司机实际发车记录兜底）
        ShiftExecutionDO execution = resolveActiveExecution(driver.getId(), planItem);
        if (execution != null && execution.getLoadedCount() != null && execution.getLoadedCount() > 0) {
            ShiftExecutionDO loadedUpdate = new ShiftExecutionDO();
            loadedUpdate.setId(execution.getId());
            loadedUpdate.setLoadedCount(execution.getLoadedCount() - 1);
            shiftExecutionMapper.updateById(loadedUpdate);
        }
        // 方案内订单全部完成 → 方案置为已完成（P1-003：补 COMPLETED 终态流转）
        maybeCompletePlan(planItem.getPlanId());
        orderEventService.record(order.getId(), TransportOrderEventTypeEnum.COMPLETED, "货物已妥投，订单完成");
        userNotificationService.sendToOrderUser(order.getId(), TransportOrderEventTypeEnum.COMPLETED,
                "订单已完成", "您的货物已送达，感谢使用");
    }

    // ==================== 商城订单（同理寄货）：司机端装车 → 妥投 ====================

    @Override
    @Transactional
    public void productLoad(AppDriverOrderActionReqVO reqVO) {
        DriverDO driver = requireCurrentDriver(reqVO.getDriverId());
        Long vehicleId = resolveVehicleId(driver.getId());
        productOrderService.driverLoad(driver.getId(), vehicleId, reqVO.getOrderId(), reqVO.getDriverPhotoUrl());
    }

    @Override
    @Transactional
    public void productDeliver(AppDriverOrderActionReqVO reqVO) {
        DriverDO driver = requireCurrentDriver(reqVO.getDriverId());
        Long vehicleId = resolveVehicleId(driver.getId());
        productOrderService.driverDeliver(driver.getId(), vehicleId, reqVO.getOrderId(), reqVO.getDriverPhotoUrl());
    }

    @Override
    @Transactional
    public void pickupVerify(AppDriverOrderActionReqVO reqVO) {
        DriverDO driver = requireCurrentDriver(reqVO.getDriverId());
        TransportOrderDO order = validateOrderExists(reqVO.getOrderId());
        // 仅邮快件（order_type=3）支持取件核销
        if (!Objects.equals(order.getOrderType(), ORDER_TYPE_POSTAL)) {
            throw exception(DRIVER_ORDER_STATUS_ILLEGAL);
        }
        // 订单归属校验：必须在当前司机已下发/执行中的调度方案明细里
        DispatchPlanItemDO planItem = validateOrderAssignedToDriver(order.getId(), driver.getId());
        // 邮快件子表 + 取件码校验（防错领）
        PostalOrderDO postal = postalOrderMapper.selectOne(PostalOrderDO::getOrderId, order.getId());
        if (postal == null || !Objects.equals(postal.getPickupCode(), reqVO.getPickupCode())) {
            throw exception(POSTAL_PICKUP_CODE_INVALID);
        }
        if (Objects.equals(postal.getPickupStatus(), 1)) {
            throw exception(POSTAL_ALREADY_PICKED);
        }
        // CAS 主表已发车(3) → 已完成(4)，防重复核销
        TransportOrderDO updateObj = new TransportOrderDO();
        updateObj.setStatus(TransportOrderStatusEnum.COMPLETED.getStatus());
        int affected = transportOrderMapper.update(updateObj, new LambdaQueryWrapperX<TransportOrderDO>()
                .eq(TransportOrderDO::getId, order.getId())
                .eq(TransportOrderDO::getStatus, TransportOrderStatusEnum.DEPARTED.getStatus()));
        if (affected == 0) {
            throw exception(DRIVER_ORDER_STATUS_ILLEGAL);
        }
        // 子表核销落库
        PostalOrderDO upd = new PostalOrderDO();
        upd.setId(postal.getId());
        upd.setPickupStatus(1);
        upd.setPickedUpTime(LocalDateTime.now());
        upd.setPickerMemberUserId(SecurityFrameworkUtils.getLoginUserId());
        postalOrderMapper.updateById(upd);
        // 已装件数 -1（地板 0），与 deliver 的回减口径一致
        Long shiftId = planItem.getShiftId();
        if (shiftId != null) {
            ShiftExecutionDO execution = shiftExecutionMapper.selectByShiftAndDriverAndDate(
                    shiftId, driver.getId(), LocalDate.now());
            if (execution != null && execution.getLoadedCount() != null && execution.getLoadedCount() > 0) {
                ShiftExecutionDO loadedUpdate = new ShiftExecutionDO();
                loadedUpdate.setId(execution.getId());
                loadedUpdate.setLoadedCount(execution.getLoadedCount() - 1);
                shiftExecutionMapper.updateById(loadedUpdate);
            }
        }
        // 方案内订单全部完成 → 方案置为已完成（P1-003：补 COMPLETED 终态流转）
        maybeCompletePlan(planItem.getPlanId());
        orderEventService.record(order.getId(), TransportOrderEventTypeEnum.COMPLETED, "邮快件已取件核销，订单完成");
        userNotificationService.sendToOrderUser(order.getId(), TransportOrderEventTypeEnum.COMPLETED,
                "取件成功", "您的邮快件已取件，感谢使用");
    }

    /**
     * 方案内全部订单已完成时，将方案从执行中流转为已完成并写日志（P1-003 终态）。
     * 仅处理执行中(RUNNING)方案，幂等：已完成/作废/待审核方案不处理。
     */
    private void maybeCompletePlan(Long planId) {
        if (planId == null) {
            return;
        }
        DispatchPlanDO plan = dispatchPlanMapper.selectById(planId);
        if (plan == null || !Objects.equals(plan.getStatus(), DispatchPlanStatusEnum.RUNNING.getStatus())) {
            return;
        }
        List<DispatchPlanItemDO> items = dispatchPlanItemMapper.selectList(new LambdaQueryWrapperX<DispatchPlanItemDO>()
                .eq(DispatchPlanItemDO::getPlanId, planId)
                .isNotNull(DispatchPlanItemDO::getOrderId));
        if (items.isEmpty()) {
            return;
        }
        Set<Long> orderIds = items.stream().map(DispatchPlanItemDO::getOrderId).collect(Collectors.toSet());
        List<TransportOrderDO> orders = transportOrderMapper.selectBatchIds(orderIds);
        boolean allDone = orders.stream()
                .allMatch(o -> Objects.equals(o.getStatus(), TransportOrderStatusEnum.COMPLETED.getStatus()));
        if (!allDone) {
            return;
        }
        plan.setStatus(DispatchPlanStatusEnum.COMPLETED.getStatus());
        dispatchPlanMapper.updateById(plan);
        dispatchPlanLogMapper.insert(DispatchPlanLogDO.builder()
                .planId(planId)
                .fromStatus(DispatchPlanStatusEnum.RUNNING.getStatus())
                .toStatus(DispatchPlanStatusEnum.COMPLETED.getStatus())
                .operator(String.valueOf(SecurityFrameworkUtils.getLoginUserId()))
                .reason("方案内全部订单已完成")
                .build());
    }

    @Override
    @Transactional
    public void reportLocation(AppDriverLocationReqVO reqVO) {
        DriverDO driver = requireCurrentDriver(reqVO.getDriverId());
        Long vehicleId = resolveVehicleId(driver.getId());
        // P1-H：shiftId 允许为空——算法派单/运输段模式的司机不一定有班次；缺省用当前活跃段的班次兜底
        Long shiftId = reqVO.getShiftId() != null ? reqVO.getShiftId() : currentLegShiftId(driver.getId());
        LocalDateTime reportTime = LocalDateTime.now();
        // 每车一行，按车辆 upsert
        VehicleLocationDO location = vehicleLocationMapper.selectByVehicleId(vehicleId);
        if (location == null) {
            vehicleLocationMapper.insert(VehicleLocationDO.builder()
                    .vehicleId(vehicleId)
                    .shiftId(shiftId)
                    .longitude(reqVO.getLongitude())
                    .latitude(reqVO.getLatitude())
                    .speedKmh(reqVO.getSpeedKmh())
                    .reportTime(reportTime)
                    .build());
        } else {
            location.setShiftId(shiftId);
            location.setLongitude(reqVO.getLongitude());
            location.setLatitude(reqVO.getLatitude());
            location.setSpeedKmh(reqVO.getSpeedKmh());
            location.setReportTime(reportTime);
            vehicleLocationMapper.updateById(location);
        }
        // 仅班次在途（shiftId 非空）才追加历史轨迹，与最新位置同一份上报值
        if (shiftId != null) {
            vehicleLocationTrackMapper.insert(VehicleLocationTrackDO.builder()
                    .vehicleId(vehicleId)
                    .shiftId(shiftId)
                    .longitude(reqVO.getLongitude())
                    .latitude(reqVO.getLatitude())
                    .speedKmh(reqVO.getSpeedKmh())
                    .reportTime(reportTime)
                    .build());
        }
        // P1-F：车辆接近目的站时给下单用户发"即将送达"站内通知（按 订单+距离档位 幂等，同一档只推一次）
        if (reqVO.getLongitude() != null && reqVO.getLatitude() != null) {
            notifyApproachingOrders(vehicleId, reqVO.getLongitude().doubleValue(), reqVO.getLatitude().doubleValue());
        }
    }

    /** P1-H：司机当前活跃段的班次（没有班次时返回 null，位置仍按车辆落库） */
    private Long currentLegShiftId(Long driverId) {
        return transportLegMapper.selectActiveByDriverId(driverId).stream()
                .map(TransportLegDO::getShiftId).filter(Objects::nonNull).findFirst().orElse(null);
    }

    /**
     * P1-F：车辆接近目的站时给下单用户发站内提醒。
     *
     * 只处理本车"方向经停"（送客 2 / 派送 3 / 揽收 4）关联的在途订单（已分配/已发车）；
     * 按距离分级（2km/1km/0.5km）触发，eventId = CARRIER_APPROACHING:ORDER-{id}:STAGE-{档}，
     * 同一订单同一档位只落一条通知（transport_user_notification 唯一键去重）。
     */
    private void notifyApproachingOrders(Long vehicleId, double longitude, double latitude) {
        if (vehicleId == null || dispatchPlanItemMapper == null) {
            return;
        }
        List<DispatchPlanItemDO> items = dispatchPlanItemMapper.selectList(new LambdaQueryWrapperX<DispatchPlanItemDO>()
                .eq(DispatchPlanItemDO::getVehicleId, vehicleId)
                .in(DispatchPlanItemDO::getActionType, 2, 3, 4));
        if (items.isEmpty()) {
            return;
        }
        // 取该车各订单的方向经停站（同一订单取一条），仅保留在途订单
        Map<Long, DispatchPlanItemDO> orderItem = new java.util.HashMap<>();
        for (DispatchPlanItemDO item : items) {
            if (item.getOrderId() != null && item.getStationId() != null) {
                orderItem.putIfAbsent(item.getOrderId(), item);
            }
        }
        for (Map.Entry<Long, DispatchPlanItemDO> e : orderItem.entrySet()) {
            Long orderId = e.getKey();
            DispatchPlanItemDO item = e.getValue();
            TransportOrderDO order = transportOrderMapper.selectById(orderId);
            if (order == null || !(Objects.equals(order.getStatus(), TransportOrderStatusEnum.ASSIGNED.getStatus())
                    || Objects.equals(order.getStatus(), TransportOrderStatusEnum.DEPARTED.getStatus()))) {
                continue; // 非在途订单不提醒
            }
            StationDO station = stationMapper.selectById(item.getStationId());
            if (station == null || station.getLongitude() == null || station.getLatitude() == null) {
                continue;
            }
            double km = GeoDistanceUtil.haversineKm(longitude, latitude,
                    station.getLongitude().doubleValue(), station.getLatitude().doubleValue());
            String stage = approachStage(km);
            if (stage == null) {
                continue; // 还没进入 2km 范围
            }
            // 幂等键：同一订单 + 同一距离档位只推一次
            String eventId = "CARRIER_APPROACHING:ORDER-" + orderId + ":STAGE-" + stage;
            userNotificationService.sendToOrderUser(orderId, TransportOrderEventTypeEnum.CARRIER_APPROACHING,
                    cn.iocoder.yudao.module.transport.enums.notification.NotificationLevelEnum.ACTION_REQUIRED,
                    true, "您的包裹即将送达",
                    "您的包裹即将送达「" + station.getStationName() + "」，"
                            + (item.getDriverId() != null ? driverName(item.getDriverId()) + " " : "")
                            + "约 " + (km <= approachDistArriving ? 1 : (km <= approachDist1Km ? 2 : 5)) + " 分钟后到达",
                    eventId);
        }
    }

    /** P1-G：按距离分级返回接近阶段；超过 2km 返回 null */
    private String approachStage(double distanceKm) {
        if (distanceKm <= approachDistArriving) {
            return "ARRIVING";
        }
        if (distanceKm <= approachDist1Km) {
            return "NEAR_1KM";
        }
        if (distanceKm <= approachDist2Km) {
            return "NEAR_2KM";
        }
        return null;
    }

    @Override
    public AppDriverPositionRespVO getPosition(Long driverId) {
        DriverDO driver = requireCurrentDriver(driverId);
        Long vehicleId = resolveVehicleId(driver.getId());
        AppDriverPositionRespVO vo = new AppDriverPositionRespVO();
        // 真实上报位置（司机 GPS）
        VehicleLocationDO loc = vehicleLocationMapper.selectByVehicleId(vehicleId);
        if (loc != null && loc.getLongitude() != null && loc.getLatitude() != null) {
            vo.setLongitude(loc.getLongitude().doubleValue());
            vo.setLatitude(loc.getLatitude().doubleValue());
        }
        // 模拟运营引擎位置（管理端模拟驱动；simulationEnabled=false 时 tick 返回 null）
        SimulationEngine.SimTick sim = simulationEngine.tick(vehicleId);
        if (sim != null) {
            vo.setSimRunning(true);
            vo.setSimLongitude(sim.getLongitude());
            vo.setSimLatitude(sim.getLatitude());
            vo.setCurrentStationName(sim.getStationName());
            vo.setArrived(sim.isArrived());
            vo.setSimSeconds(sim.getSimSeconds());
            SimulationEngine.SimRun run = simulationEngine.getRun(vehicleId);
            if (run != null) {
                vo.setTotalSimSeconds(run.getTotalSimSeconds());
            }
        }
        // 当前生效源：REAL 优先，其次 SIMULATED，否则 NONE
        if (vo.getLongitude() != null) {
            vo.setDataSource("REAL");
        } else if (Boolean.TRUE.equals(vo.getSimRunning())) {
            vo.setDataSource("SIMULATED");
        } else {
            vo.setDataSource("NONE");
        }
        return vo;
    }

    @Override
    public List<AppDriverHandoverRespVO> handovers(Long driverId) {
        DriverDO driver = requireCurrentDriver(driverId);
        return handoverService.getPendingByDriver(driver.getId()).stream()
                .map(this::toHandoverVO)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void handoverConfirm(AppDriverHandoverConfirmReqVO reqVO) {
        DriverDO driver = requireCurrentDriver(reqVO.getDriverId());
        // 归属校验在 service 内完成：接收/交出司机可确认；接收司机未分配时可认领
        handoverService.confirmHandover(reqVO.getHandoverId(), driver.getId(), reqVO.getPhotoUrl());
    }

    @Override
    public List<AppDriverLegRespVO> legs(Long driverId) {
        DriverDO driver = requireCurrentDriver(driverId);
        List<TransportLegDO> legs = transportLegMapper.selectListByDriverId(driver.getId());
        if (legs.isEmpty()) {
            return List.of();
        }
        Map<Long, String> stationNames = stationNames(legs);
        Map<Long, String> plateNos = plateNos(legs);
        Map<Long, String> orderNos = orderNos(legs);
        return legs.stream().map(leg -> toLegVO(leg, stationNames, plateNos, orderNos)).collect(Collectors.toList());
    }

    @Override
    public AppDriverLegRespVO currentLeg(Long driverId) {
        DriverDO driver = requireCurrentDriver(driverId);
        // 司机只能看到自己"进行中"的段（需求 §56：绝不能把前序段当成自己的起点）
        List<TransportLegDO> active = transportLegMapper.selectActiveByDriverId(driver.getId());
        // P1-E：只认"已下发/执行中"方案的段。历史缺陷：selectActiveByDriverId 只按段状态过滤，
        // 会把「待审核方案」的段派到司机端（实测司机 3 拿到 plan 29 状态 0 待审核的段）。
        List<TransportLegDO> issued = active.stream()
                .filter(leg -> isPlanIssuedOrRunning(leg.getPlanId()))
                .sorted(Comparator.comparing(TransportLegDO::getEstimatedDeparture,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(TransportLegDO::getLegSequence,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        if (issued.isEmpty()) {
            return null; // 无已下发方案的活跃段：等待派单
        }
        TransportLegDO leg = issued.get(0);
        List<TransportLegDO> one = List.of(leg);
        return toLegVO(leg, stationNames(one), plateNos(one), orderNos(one));
    }

    /** P1-E：方案是否"已下发(1)/执行中(2)"；无方案的段不进入司机端（待审核方案不可见） */
    private boolean isPlanIssuedOrRunning(Long planId) {
        if (planId == null || dispatchPlanMapper == null) {
            return false;
        }
        DispatchPlanDO plan = dispatchPlanMapper.selectById(planId);
        return plan != null
                && (Objects.equals(plan.getStatus(), DispatchPlanStatusEnum.ISSUED.getStatus())
                    || Objects.equals(plan.getStatus(), DispatchPlanStatusEnum.RUNNING.getStatus()));
    }

    @Override
    @Transactional
    public void legAction(String action, AppDriverLegActionReqVO reqVO) {
        DriverDO driver = requireCurrentDriver(reqVO.getDriverId());
        TransportLegDO leg = multiLegService.getLeg(reqVO.getLegId());
        // 归属校验：司机只能操作自己的运输段
        if (!Objects.equals(leg.getDriverId(), driver.getId())) {
            throw exception(LEG_NOT_ASSIGNED);
        }
        switch (action) {
            case "accept" -> multiLegService.advanceLegStatus(leg.getId(), TransportLegStatusEnum.DRIVER_ACCEPTED);
            case "navigate" -> multiLegService.advanceLegStatus(leg.getId(), TransportLegStatusEnum.NAVIGATING);
            case "arrive-origin" -> multiLegService.advanceLegStatus(leg.getId(), TransportLegStatusEnum.ARRIVED_ORIGIN);
            case "load" -> multiLegService.advanceLegStatus(leg.getId(), TransportLegStatusEnum.LOADING);
            case "start" -> multiLegService.advanceLegStatus(leg.getId(), TransportLegStatusEnum.IN_TRANSIT);
            case "arrive-dest" -> arriveDestination(leg);
            case "handover-start" -> {
                TransportHandoverDO handover = requireHandoverOfLeg(leg);
                handoverService.startHandover(handover.getId(), driver.getId());
            }
            case "handover-confirm" -> {
                TransportHandoverDO handover = requireHandoverOfLeg(leg);
                handoverService.confirmHandover(handover.getId(), driver.getId(), reqVO.getPhotoUrl());
            }
            case "complete" -> completeLeg(leg);
            case "exception" -> reportLegException(leg, reqVO.getRemark());
            default -> throw exception(BAD_REQUEST);
        }
    }

    /** 司机上报异常（车辆故障/道路中断等，需求 §108）：段置异常 + 订单置异常 + 后台告警 */
    private void reportLegException(TransportLegDO leg, String remark) {
        multiLegService.forceLegStatus(leg.getId(), TransportLegStatusEnum.EXCEPTION,
                remark != null ? remark : "司机上报异常");
        updateOrderStatus(leg.getOrderId(), TransportOrderStatusEnum.EXCEPTION);
        orderEventService.record(leg.getOrderId(), TransportOrderEventTypeEnum.ORDER_EXCEPTION,
                "第 " + leg.getLegSequence() + " 段异常：" + (remark != null ? remark : "司机上报"));
        userNotificationService.sendToAdmin(TransportOrderEventTypeEnum.ORDER_EXCEPTION,
                cn.iocoder.yudao.module.transport.enums.notification.NotificationLevelEnum.EXCEPTION,
                "运输段异常", "订单 " + leg.getOrderId() + " 第 " + leg.getLegSequence()
                        + " 段异常：" + (remark != null ? remark : "司机上报") + "，请重调度",
                leg.getOrderId(), leg.getId());
        userNotificationService.sendToOrderUser(leg.getOrderId(), TransportOrderEventTypeEnum.ORDER_EXCEPTION,
                cn.iocoder.yudao.module.transport.enums.notification.NotificationLevelEnum.WARNING, false,
                "运输异常提醒", "您的货物运输出现异常，平台正在重新调度车辆");
    }

    /** 到达终点：需换乘 → 通知后序司机接货；最终段 → 派送中并通知用户取货（需求 §60/§86） */
    private void arriveDestination(TransportLegDO leg) {
        if (Boolean.TRUE.equals(leg.getHandoverRequired())) {
            multiLegService.advanceLegStatus(leg.getId(), TransportLegStatusEnum.ARRIVED_DESTINATION);
            handoverService.markSourceArrived(leg.getOrderId(), leg.getId());
            return;
        }
        multiLegService.advanceLegStatus(leg.getId(), TransportLegStatusEnum.ARRIVED_DESTINATION);
        multiLegService.advanceLegStatus(leg.getId(), TransportLegStatusEnum.DELIVERING);
        updateOrderStatus(leg.getOrderId(), TransportOrderStatusEnum.DELIVERING);
        orderEventService.record(leg.getOrderId(), TransportOrderEventTypeEnum.ORDER_ARRIVED,
                "货物已到达目的服务站，等待用户取货");
        userNotificationService.sendToOrderUser(leg.getOrderId(), TransportOrderEventTypeEnum.ORDER_ARRIVED,
                cn.iocoder.yudao.module.transport.enums.notification.NotificationLevelEnum.ACTION_REQUIRED, true,
                "货物已到达，请取货", "您的货物已到达目的服务站，请携带取件码前往取货");
    }

    /** 完成配送/取货：最后一段完成 → 订单完成 + 通知（需求 §6 禁止第一段完成即整单完成） */
    private void completeLeg(TransportLegDO leg) {
        multiLegService.advanceLegStatus(leg.getId(), TransportLegStatusEnum.COMPLETED);
        List<TransportLegDO> all = multiLegService.getLegsByOrderId(leg.getOrderId());
        boolean allDone = all.stream().allMatch(l -> Objects.equals(l.getStatus(),
                TransportLegStatusEnum.COMPLETED.getStatus()));
        if (!allDone) {
            return; // 还有后续段：订单保持"部分完成/运输中"，绝不置完成
        }
        updateOrderStatus(leg.getOrderId(), TransportOrderStatusEnum.COMPLETED);
        orderEventService.record(leg.getOrderId(), TransportOrderEventTypeEnum.COMPLETED, "全部运输段完成，订单完成");
        userNotificationService.sendToOrderUser(leg.getOrderId(), TransportOrderEventTypeEnum.COMPLETED,
                cn.iocoder.yudao.module.transport.enums.notification.NotificationLevelEnum.SUCCESS, false,
                "订单已完成", "您的货物已完成全部运输段，感谢使用");
    }

    private TransportHandoverDO requireHandoverOfLeg(TransportLegDO leg) {
        TransportHandoverDO handover = handoverService.getByLeg(leg.getId());
        if (handover == null) {
            throw exception(HANDOVER_NOT_EXISTS);
        }
        return handover;
    }

    private void updateOrderStatus(Long orderId, TransportOrderStatusEnum target) {
        TransportOrderDO update = new TransportOrderDO();
        update.setStatus(target.getStatus());
        transportOrderMapper.update(update, new LambdaQueryWrapperX<TransportOrderDO>()
                .eq(TransportOrderDO::getId, orderId)
                .notIn(TransportOrderDO::getStatus, TransportOrderStatusEnum.COMPLETED.getStatus(),
                        TransportOrderStatusEnum.CANCELLED.getStatus()));
    }

    private AppDriverLegRespVO toLegVO(TransportLegDO leg, Map<Long, String> stationNames,
                                       Map<Long, String> plateNos, Map<Long, String> orderNos) {
        AppDriverLegRespVO vo = new AppDriverLegRespVO();
        vo.setId(leg.getId());
        vo.setOrderId(leg.getOrderId());
        vo.setOrderNo(orderNos.get(leg.getOrderId()));
        vo.setLegSequence(leg.getLegSequence());
        vo.setFromStationId(leg.getFromStationId());
        vo.setFromStationName(stationNames.get(leg.getFromStationId()));
        vo.setToStationId(leg.getToStationId());
        vo.setToStationName(stationNames.get(leg.getToStationId()));
        vo.setStatus(leg.getStatus());
        vo.setStatusName(TransportLegStatusEnum.nameOf(leg.getStatus()));
        vo.setEstimatedDeparture(leg.getEstimatedDeparture());
        vo.setEstimatedArrival(leg.getEstimatedArrival());
        vo.setActualArrival(leg.getActualArrival());
        vo.setVehicleId(leg.getVehicleId());
        vo.setPlateNo(plateNos.get(leg.getVehicleId()));
        vo.setDistanceKm(leg.getDistanceKm());
        vo.setDurationMinutes(leg.getDurationMinutes());
        vo.setNavigationSource(leg.getNavigationSource());
        vo.setHandoverRequired(leg.getHandoverRequired());
        // 换乘交接信息（需交接的段）
        if (Boolean.TRUE.equals(leg.getHandoverRequired())) {
            TransportHandoverDO handover = handoverService.getByLeg(leg.getId());
            if (handover != null) {
                vo.setHandoverId(handover.getId());
                vo.setHandoverStatusName(TransportHandoverStatusEnum.nameOf(handover.getStatus()));
            }
        }
        return vo;
    }

    private AppDriverHandoverRespVO toHandoverVO(TransportHandoverDO handover) {
        AppDriverHandoverRespVO vo = new AppDriverHandoverRespVO();
        vo.setId(handover.getId());
        vo.setOrderId(handover.getOrderId());
        vo.setOrderNo(orderNos(handover.getOrderId()));
        vo.setStationId(handover.getStationId());
        StationDO station = handover.getStationId() != null ? stationMapper.selectById(handover.getStationId()) : null;
        vo.setStationName(station != null ? station.getStationName() : null);
        vo.setFromDriverId(handover.getFromDriverId());
        vo.setFromDriverName(driverName(handover.getFromDriverId()));
        vo.setToDriverId(handover.getToDriverId());
        vo.setToDriverName(driverName(handover.getToDriverId()));
        vo.setItemCount(handover.getItemCount());
        vo.setWeightKg(handover.getWeightKg());
        vo.setPhotoUrl(handover.getPhotoUrl());
        vo.setStatus(handover.getStatus());
        vo.setStatusName(TransportHandoverStatusEnum.nameOf(handover.getStatus()));
        vo.setHandoverTime(handover.getHandoverTime());
        vo.setRemark(handover.getRemark());
        if (handover.getOrderId() != null) {
            CargoOrderDO cargo = cargoOrderMapper.selectOne(CargoOrderDO::getOrderId, handover.getOrderId());
            vo.setGoodsName(cargo != null ? cargo.getGoodsName() : null);
            if (vo.getItemCount() == null && cargo != null) {
                vo.setItemCount(cargo.getItemCount());
            }
            if (vo.getWeightKg() == null && cargo != null) {
                vo.setWeightKg(cargo.getWeightKg());
            }
        }
        return vo;
    }

    private String orderNos(Long orderId) {
        if (orderId == null) {
            return null;
        }
        TransportOrderDO order = transportOrderMapper.selectById(orderId);
        return order != null ? order.getOrderNo() : null;
    }

    private Map<Long, String> orderNos(List<TransportLegDO> legs) {
        Set<Long> orderIds = legs.stream().map(TransportLegDO::getOrderId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (orderIds.isEmpty()) {
            return Map.of();
        }
        return transportOrderMapper.selectBatchIds(orderIds).stream()
                .filter(o -> o.getId() != null)
                .collect(Collectors.toMap(TransportOrderDO::getId, o -> o.getOrderNo() != null ? o.getOrderNo() : "",
                        (a, b) -> a));
    }

    private String driverName(Long driverId) {
        if (driverId == null) {
            return null;
        }
        DriverDO driver = driverMapper.selectById(driverId);
        return driver != null ? driver.getName() : null;
    }

    private Map<Long, String> stationNames(List<TransportLegDO> legs) {
        Set<Long> stationIds = new HashSet<>();
        legs.forEach(l -> {
            if (l.getFromStationId() != null) stationIds.add(l.getFromStationId());
            if (l.getToStationId() != null) stationIds.add(l.getToStationId());
        });
        if (stationIds.isEmpty()) {
            return Map.of();
        }
        return stationMapper.selectBatchIds(stationIds).stream()
                .filter(s -> s.getId() != null)
                .collect(Collectors.toMap(StationDO::getId, s -> s.getStationName() != null ? s.getStationName() : "",
                        (a, b) -> a));
    }

    private Map<Long, String> plateNos(List<TransportLegDO> legs) {
        Set<Long> vehicleIds = legs.stream().map(TransportLegDO::getVehicleId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (vehicleIds.isEmpty()) {
            return Map.of();
        }
        return vehicleMapper.selectBatchIds(vehicleIds).stream()
                .filter(v -> v.getId() != null)
                .collect(Collectors.toMap(VehicleDO::getId, v -> v.getPlateNo() != null ? v.getPlateNo() : "",
                        (a, b) -> a));
    }

    /**
     * 以登录会员身份解析当前司机（member_user.mobile → transport_driver.mobile）。
     * 客户端传入的 driverId 仅做一致性校验，不作为身份来源。
     */
    private DriverDO requireCurrentDriver(Long clientDriverId) {
        DriverDO driver = currentDriverOrNull();
        if (driver == null) {
            throw exception(DRIVER_NOT_FOUND);
        }
        if (clientDriverId != null && !Objects.equals(clientDriverId, driver.getId())) {
            throw exception(DRIVER_IDENTITY_MISMATCH);
        }
        return driver;
    }

    /** 按登录会员手机号匹配司机档案；未登录/非司机返回 null */
    private DriverDO currentDriverOrNull() {
        Long memberId = SecurityFrameworkUtils.getLoginUserId();
        if (memberId == null) {
            return null;
        }
        MemberUserRespDTO user = memberUserApi.getUser(memberId);
        if (user == null || StrUtil.isBlank(user.getMobile())) {
            return null;
        }
        return driverMapper.selectByMobile(user.getMobile().trim());
    }

    /** 校验订单归属当前司机：必须在该司机已下发(1)/执行中(2)的调度方案明细里，返回对应明细 */
    private DispatchPlanItemDO validateOrderAssignedToDriver(Long orderId, Long driverId) {
        DispatchPlanItemDO item = findIssuedPlanItem(orderId, driverId);
        if (item == null) {
            throw exception(DRIVER_ORDER_NOT_ASSIGNED);
        }
        return item;
    }

    /** 当前司机已下发/执行中调度方案里的订单编号集合（供待装车列表过滤） */
    private Set<Long> listAssignedOrderIds(Long driverId) {
        List<DispatchPlanItemDO> items = dispatchPlanItemMapper.selectListByDriverId(driverId);
        return items.stream()
                .filter(item -> isIssued(planStatusMap(planIdsOf(items)).get(item.getPlanId())))
                .map(DispatchPlanItemDO::getOrderId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private DispatchPlanItemDO findIssuedPlanItem(Long orderId, Long driverId) {
        List<DispatchPlanItemDO> items = dispatchPlanItemMapper.selectList(new LambdaQueryWrapperX<DispatchPlanItemDO>()
                .eq(DispatchPlanItemDO::getOrderId, orderId)
                .eq(DispatchPlanItemDO::getDriverId, driverId));
        if (items.isEmpty()) {
            return null;
        }
        Map<Long, Integer> planStatusMap = planStatusMap(planIdsOf(items));
        return items.stream()
                .filter(item -> isIssued(planStatusMap.get(item.getPlanId())))
                .findFirst()
                .orElse(null);
    }

    private Set<Long> planIdsOf(List<DispatchPlanItemDO> items) {
        return items.stream().map(DispatchPlanItemDO::getPlanId).collect(Collectors.toSet());
    }

    private Map<Long, Integer> planStatusMap(Set<Long> planIds) {
        if (planIds.isEmpty()) {
            return Map.of();
        }
        return dispatchPlanMapper.selectList(new LambdaQueryWrapperX<DispatchPlanDO>()
                        .in(DispatchPlanDO::getId, planIds))
                .stream().collect(Collectors.toMap(DispatchPlanDO::getId, DispatchPlanDO::getStatus));
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

    /**
     * 取司机"当前在执行的那条班次记录"：优先派单明细上绑定的班次；明细未绑定班次
     * （智能派单一键生成的方案就是这种）时，回退到司机今天实际发车产生的执行记录（取最新一条）。
     * 返回 null 表示司机还没发车。
     */
    private ShiftExecutionDO resolveActiveExecution(Long driverId, DispatchPlanItemDO planItem) {
        LocalDate today = LocalDate.now();
        if (planItem != null && planItem.getShiftId() != null) {
            ShiftExecutionDO bound = shiftExecutionMapper.selectByShiftAndDriverAndDate(
                    planItem.getShiftId(), driverId, today);
            if (bound != null) {
                return bound;
            }
        }
        return shiftExecutionMapper.selectListByDriverAndDate(driverId, today).stream().findFirst().orElse(null);
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
