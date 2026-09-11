package cn.iocoder.yudao.module.transport.service.dispatch;

import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.TransportLegDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.driver.DriverVehicleDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.TransportOrderDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.route.RouteStationDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.station.StationDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.vehicle.VehicleDO;
import cn.iocoder.yudao.module.transport.dal.mysql.dispatch.TransportLegMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.driver.DriverVehicleMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.driver.TransportDriverStatusMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.order.TransportOrderMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.route.RouteStationMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.route.RouteMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.shift.ShiftExecutionMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.dispatch.DispatchPlanMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.dispatch.DispatchPlanItemMapper;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.DispatchPlanDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.shift.ShiftExecutionDO;
import cn.iocoder.yudao.module.transport.enums.dispatch.DispatchPlanStatusEnum;
import cn.iocoder.yudao.module.transport.dal.mysql.station.StationMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.vehicle.VehicleMapper;
import cn.iocoder.yudao.module.transport.enums.dispatch.TransportLegStatusEnum;
import cn.iocoder.yudao.module.transport.enums.dispatch.TransportOrderEventTypeEnum;
import cn.iocoder.yudao.module.transport.integration.algorithm.AlgorithmClient;
import cn.iocoder.yudao.module.transport.integration.algorithm.dto.AlgorithmRouteReqDTO;
import cn.iocoder.yudao.module.transport.integration.algorithm.dto.AlgorithmRouteRespDTO;
import cn.iocoder.yudao.module.transport.service.notification.UserNotificationService;
import cn.iocoder.yudao.module.transport.service.order.OrderEventService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.*;

/**
 * 多段联运服务实现：规划（委托 {@link MultiLegPlanner}）→ 落库运输段 → 状态机推进 + 资源状态同步。
 */
@Service
@Validated
@Slf4j
public class MultiLegServiceImpl implements MultiLegService {

    @Resource private TransportLegMapper legMapper;
    @Resource private TransportOrderMapper orderMapper;
    @Resource private StationMapper stationMapper;
    @Resource private RouteStationMapper routeStationMapper;
    @Resource private RouteMapper routeMapper;
    @Resource private DriverVehicleMapper driverVehicleMapper;
    @Resource private VehicleMapper vehicleMapper;
    @Resource private TransportDriverStatusMapper driverStatusMapper;
    @Resource private MultiLegPlanner multiLegPlanner;
    @Resource private LegConflictService legConflictService;
    @Resource private ShiftExecutionMapper shiftExecutionMapper;
    @Resource private DispatchPlanMapper dispatchPlanMapper;
    @Resource private DispatchPlanItemMapper dispatchPlanItemMapper;
    @Resource private AlgorithmClient algorithmClient;
    @Resource private OrderEventService orderEventService;
    @Resource private UserNotificationService userNotificationService;

    @Override
    public List<TransportLegDO> planLegs(Long orderId) {
        return planLegs(orderId, null, null, null);
    }

    @Override
    public List<TransportLegDO> planLegs(Long orderId, Long planId) {
        return planLegs(orderId, planId, null, null);
    }

    @Override
    // REQUIRES_NEW：调用方 DispatchServiceImpl.createSmartPlan 会吞掉段规划异常（多段是增强能力，
    // 不该让整单调度失败）。若参与外层事务，异常会把共享事务标记 rollback-only，导致外层提交时
    // 抛 UnexpectedRollbackException；独立事务可保证"段规划失败只回滚自己，不影响已生成的直达方案"。
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<TransportLegDO> planLegs(Long orderId, Long planId, Long planVehicleId, Long planDriverId) {
        TransportOrderDO order = orderMapper.selectById(orderId);
        if (order == null) {
            throw exception(ORDER_NOT_EXISTS);
        }
        List<TransportLegDO> existing = legMapper.selectListByOrderId(orderId);
        if (!existing.isEmpty()) {
            return existing; // 幂等：重复调度不重复拆段
        }
        StationDO pickup = stationMapper.selectById(order.getPickupStationId());
        StationDO delivery = stationMapper.selectById(order.getDeliveryStationId());
        if (pickup == null || delivery == null) {
            throw exception(STATION_NOT_EXISTS);
        }
        MultiLegPlanner.PlanResult result = multiLegPlanner.plan(order, pickup, delivery,
                stationMapper.selectList(), routeStationsForPlanning());

        List<TransportLegDO> legs = new ArrayList<>();
        for (MultiLegPlanner.LegDraft draft : result.legs()) {
            TransportLegDO leg = TransportLegDO.builder()
                    .orderId(order.getId())
                    .planId(planId)
                    .legSequence(draft.sequence())
                    .fromStationId(draft.fromStationId())
                    .toStationId(draft.toStationId())
                    .distanceKm(BigDecimal.valueOf(draft.distanceKm()))
                    .durationMinutes(draft.durationMinutes())
                    .navigationSource("ESTIMATED")
                    .status(TransportLegStatusEnum.PLANNED.getStatus())
                    .handoverRequired(draft.handoverRequired())
                    .build();
            legs.add(leg);
        }
        // 真实道路：逐段取高德路网（距离/时长/polyline），失败保持 ESTIMATED（不伪装真实道路，需求 §73/§141）
        enrichWithRoadRoute(legs);
        // 预计时间基于最终时长（可能是路网时长）顺序推进
        LocalDateTime cursor = LocalDateTime.now().plusMinutes(MultiLegPlanner.PREPARE_MINUTES);
        for (TransportLegDO leg : legs) {
            int minutes = leg.getDurationMinutes() != null ? leg.getDurationMinutes()
                    : MultiLegPlanner.travelMinutes(leg.getDistanceKm() == null ? 0 : leg.getDistanceKm().doubleValue());
            leg.setEstimatedDeparture(cursor);
            leg.setEstimatedArrival(cursor.plusMinutes(minutes));
            cursor = leg.getEstimatedArrival().plusMinutes(MultiLegPlanner.HANDOVER_DWELL_MINUTES);
        }
        assignVehicles(legs, orderId, planId, planVehicleId, planDriverId);
        for (TransportLegDO leg : legs) {
            legMapper.insert(leg);
        }
        orderEventService.record(orderId, TransportOrderEventTypeEnum.PLAN_CREATED,
                result.reason(), "{\"legCount\":" + result.legCount() + ",\"transferCount\":"
                        + result.transferCount() + ",\"mode\":\"" + result.mode() + "\"}");
        userNotificationService.sendToOrderUser(orderId, TransportOrderEventTypeEnum.PLAN_CREATED,
                "已生成运输方案", result.reason());
        return legs;
    }

    /**
     * 逐段补真实道路轨迹（需求 §73/§74/§141）：
     * 高德路网可用 → navigationSource=AMAP + 存储 polyline（"lon,lat;..." 紧凑串）+ 用真实距离/时长覆盖估算；
     * 失败/不可用 → 保持 ESTIMATED，界面按"估算值"展示，绝不伪装成实时道路导航。
     * 段数 ≤3，且算法侧有 24h 路网缓存，成本可控。
     */
    private void enrichWithRoadRoute(List<TransportLegDO> legs) {
        if (algorithmClient == null || legs.isEmpty()) {
            return;
        }
        java.util.Map<Long, StationDO> stationMap = legs.stream()
                .flatMap(l -> java.util.stream.Stream.of(l.getFromStationId(), l.getToStationId()))
                .filter(Objects::nonNull).distinct()
                .map(stationMapper::selectById).filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toMap(StationDO::getId, s -> s, (a, b) -> a));
        for (TransportLegDO leg : legs) {
            StationDO from = stationMap.get(leg.getFromStationId());
            StationDO to = stationMap.get(leg.getToStationId());
            if (from == null || to == null || from.getLongitude() == null || from.getLatitude() == null
                    || to.getLongitude() == null || to.getLatitude() == null) {
                continue;
            }
            try {
                AlgorithmRouteRespDTO route = algorithmClient.route(AlgorithmRouteReqDTO.builder()
                        .origin(AlgorithmRouteReqDTO.RoutePoint.builder()
                                .latitude(from.getLatitude().doubleValue())
                                .longitude(from.getLongitude().doubleValue()).build())
                        .destination(AlgorithmRouteReqDTO.RoutePoint.builder()
                                .latitude(to.getLatitude().doubleValue())
                                .longitude(to.getLongitude().doubleValue()).build())
                        .build());
                if (route == null || route.getPolyline() == null || route.getPolyline().isEmpty()) {
                    continue;
                }
                leg.setNavigationPolyline(route.getPolyline().stream()
                        .map(p -> p.getLongitude() + "," + p.getLatitude())
                        .collect(java.util.stream.Collectors.joining(";")));
                leg.setNavigationSource("amap".equalsIgnoreCase(route.getProvider()) ? "AMAP" : "ESTIMATED");
                if (route.getDistanceKm() != null) {
                    leg.setDistanceKm(BigDecimal.valueOf(Math.round(route.getDistanceKm() * 100) / 100.0));
                }
                if (route.getDurationSeconds() != null) {
                    leg.setDurationMinutes(Math.max(1, (int) Math.round(route.getDurationSeconds() / 60)));
                }
            } catch (Exception ex) {
                log.debug("[multi-leg] 订单 {} 第 {} 段真实道路不可用，保留估算：{}",
                        leg.getOrderId(), leg.getLegSequence(), ex.getMessage());
            }
        }
    }

    @Override
    public MultiLegPlanner.PlanResult preview(Long orderId) {
        TransportOrderDO order = orderMapper.selectById(orderId);
        if (order == null) {
            throw exception(ORDER_NOT_EXISTS);
        }
        StationDO pickup = stationMapper.selectById(order.getPickupStationId());
        StationDO delivery = stationMapper.selectById(order.getDeliveryStationId());
        if (pickup == null || delivery == null) {
            throw exception(STATION_NOT_EXISTS);
        }
        return multiLegPlanner.plan(order, pickup, delivery,
                stationMapper.selectList(), routeStationsForPlanning());
    }

    /**
     * 供规划使用的线路站点：只保留"启用且可用于调度"的线路（需求 §38：
     * 停用线路不得用于调度/导航），避免拿停用线路当换乘通道。
     */
    private List<RouteStationDO> routeStationsForPlanning() {
        List<RouteStationDO> all = routeStationMapper.selectList();
        if (routeMapper == null) {
            return all;
        }
        java.util.Set<Long> enabledRouteIds = new java.util.HashSet<>(routeMapper.selectEnabledDispatchRouteIds());
        return all.stream().filter(rs -> enabledRouteIds.contains(rs.getRouteId())).toList();
    }

    @Override
    public List<TransportLegDO> getLegsByOrderId(Long orderId) {
        return orderId == null ? List.of() : legMapper.selectListByOrderId(orderId);
    }

    @Override
    public List<TransportLegDO> getLegsByPlanId(Long planId) {
        return planId == null ? List.of() : legMapper.selectListByPlanId(planId);
    }

    @Override
    public TransportLegDO getLeg(Long legId) {
        TransportLegDO leg = legMapper.selectById(legId);
        if (leg == null) {
            throw exception(LEG_NOT_EXISTS);
        }
        return leg;
    }

    @Override
    @Transactional
    public void advanceLegStatus(Long legId, TransportLegStatusEnum targetStatus) {
        TransportLegDO leg = getLeg(legId);
        TransportLegStatusEnum current = TransportLegStatusEnum.of(leg.getStatus());
        if (current == targetStatus) {
            return; // 幂等
        }
        // 状态机守卫：禁止跳级（需求 §6/§115，如"运输中"不能直接"已完成"）
        if (!TransportLegStatusEnum.canTransit(current, targetStatus)) {
            throw exception(LEG_TRANSITION_ILLEGAL,
                    (current == null ? "未知" : current.getName()) + " → " + targetStatus.getName());
        }
        applyLegStatus(leg, targetStatus, null);
    }

    @Override
    @Transactional
    public void forceLegStatus(Long legId, TransportLegStatusEnum targetStatus, String reason) {
        TransportLegDO leg = getLeg(legId);
        if (Objects.equals(leg.getStatus(), targetStatus.getStatus())) {
            return;
        }
        applyLegStatus(leg, targetStatus, reason);
    }

    @Override
    public boolean needMultiLeg(Long orderId) {
        return preview(orderId).isMultiLeg();
    }

    @Override
    @Transactional
    public TransportLegDO replanLeg(Long legId, String reason) {
        TransportLegDO leg = getLeg(legId);
        List<DriverVehicleDO> bindings = driverVehicleMapper == null
                ? List.of() : driverVehicleMapper.selectActiveBindings();
        DriverVehicleDO chosen = null;
        for (DriverVehicleDO binding : bindings) {
            // 排除当前（故障/异常）资源，并在同一时段无冲突
            if (Objects.equals(binding.getVehicleId(), leg.getVehicleId())
                    && Objects.equals(binding.getDriverId(), leg.getDriverId())) {
                continue;
            }
            boolean conflict = legConflictService != null && (legConflictService.vehicleConflicts(
                    binding.getVehicleId(), leg.getEstimatedDeparture(), leg.getEstimatedArrival(), List.of(), legId)
                    || legConflictService.driverConflicts(binding.getDriverId(), leg.getEstimatedDeparture(),
                    leg.getEstimatedArrival(), List.of(), legId));
            if (!conflict) {
                chosen = binding;
                break;
            }
        }
        if (chosen == null) {
            // 方案置异常，等人工介入（需求 §108）
            if (dispatchPlanMapper != null && leg.getPlanId() != null) {
                DispatchPlanDO planUpdate = new DispatchPlanDO();
                planUpdate.setId(leg.getPlanId());
                planUpdate.setStatus(DispatchPlanStatusEnum.EXCEPTION.getStatus());
                dispatchPlanMapper.updateById(planUpdate);
            }
            orderEventService.record(leg.getOrderId(), TransportOrderEventTypeEnum.ORDER_EXCEPTION,
                    "第 " + leg.getLegSequence() + " 段重调度失败：当前无可调度车辆/司机");
            userNotificationService.sendToAdmin(TransportOrderEventTypeEnum.ORDER_EXCEPTION,
                    cn.iocoder.yudao.module.transport.enums.notification.NotificationLevelEnum.EXCEPTION,
                    "重调度失败", "订单 " + leg.getOrderId() + " 第 " + leg.getLegSequence() + " 段无可调度资源",
                    leg.getOrderId(), legId);
            throw exception(NO_AVAILABLE_RESOURCE);
        }
        Long fromVehicle = leg.getVehicleId();
        Long fromDriver = leg.getDriverId();
        TransportLegDO update = new TransportLegDO();
        update.setId(legId);
        update.setVehicleId(chosen.getVehicleId());
        update.setDriverId(chosen.getDriverId());
        update.setStatus(TransportLegStatusEnum.ASSIGNED.getStatus());
        legMapper.updateById(update);
        // 异常资源释放
        releaseVehicle(fromVehicle);
        // 分配新资源后写事件 + 多方通知（用户/新司机/后台）
        orderEventService.record(leg.getOrderId(), TransportOrderEventTypeEnum.PLAN_REPLANNED,
                "第 " + leg.getLegSequence() + " 段已重新调度（车辆 "
                        + (fromVehicle == null ? "无" : fromVehicle) + " → " + chosen.getVehicleId()
                        + (reason != null ? "，原因：" + reason : "") + "）");
        userNotificationService.sendToOrderUser(leg.getOrderId(), TransportOrderEventTypeEnum.PLAN_REPLANNED,
                cn.iocoder.yudao.module.transport.enums.notification.NotificationLevelEnum.WARNING, false,
                "运输方案已调整", "您的订单第 " + leg.getLegSequence() + " 段已重新安排车辆，预计时间可能略有变化");
        userNotificationService.sendToDriver(chosen.getDriverId(), TransportOrderEventTypeEnum.LEG_ASSIGNED,
                cn.iocoder.yudao.module.transport.enums.notification.NotificationLevelEnum.ACTION_REQUIRED, true,
                "新的运输任务", "订单 " + leg.getOrderId() + " 第 " + leg.getLegSequence() + " 段已分配给您，请接单",
                leg.getOrderId(), leg.getPlanId(), legId);
        userNotificationService.sendToAdmin(TransportOrderEventTypeEnum.PLAN_REPLANNED,
                cn.iocoder.yudao.module.transport.enums.notification.NotificationLevelEnum.WARNING,
                "已完成重调度", "订单 " + leg.getOrderId() + " 第 " + leg.getLegSequence() + " 段已换车",
                leg.getOrderId(), legId);
        return legMapper.selectById(legId);
    }

    private void releaseVehicle(Long vehicleId) {
        if (vehicleId == null || vehicleMapper == null) {
            return;
        }
        VehicleDO upd = new VehicleDO();
        upd.setId(vehicleId);
        upd.setRealtimeStatus(0); // 释放回空闲
        vehicleMapper.updateById(upd);
    }

    // ==================== 内部 ====================

    private void applyLegStatus(TransportLegDO leg, TransportLegStatusEnum target, String reason) {
        LocalDateTime now = LocalDateTime.now();
        TransportLegDO update = new TransportLegDO();
        update.setId(leg.getId());
        update.setStatus(target.getStatus());
        if (target == TransportLegStatusEnum.IN_TRANSIT && leg.getActualDeparture() == null) {
            update.setActualDeparture(now);
        }
        if (target == TransportLegStatusEnum.ARRIVED_DESTINATION || target == TransportLegStatusEnum.COMPLETED) {
            if (leg.getActualArrival() == null) {
                update.setActualArrival(now);
            }
        }
        legMapper.updateById(update);
        syncResourceStatus(leg, target);
        syncShiftExecution(leg, target);
        recordLegEvent(leg, target, reason);
    }

    /**
     * 段与班次执行同步（需求 §125）：段开始 → 司机当天执行记录置在途；
     * 段完成且该司机当天已无其他进行中段 → 执行记录置已完成。
     * 避免"Leg=已完成 而 ShiftExecution 仍在途"的系统分裂。
     */
    private void syncShiftExecution(TransportLegDO leg, TransportLegStatusEnum target) {
        if (shiftExecutionMapper == null || leg.getDriverId() == null) {
            return;
        }
        List<ShiftExecutionDO> executions = shiftExecutionMapper.selectListByDriverAndDate(
                leg.getDriverId(), java.time.LocalDate.now());
        if (executions.isEmpty()) {
            return;
        }
        ShiftExecutionDO execution = executions.get(0);
        if (target == TransportLegStatusEnum.IN_TRANSIT) {
            ShiftExecutionDO upd = new ShiftExecutionDO();
            upd.setId(execution.getId());
            upd.setStatus(0); // 在途
            if (execution.getDepartTime() == null) {
                upd.setDepartTime(LocalDateTime.now());
            }
            shiftExecutionMapper.updateById(upd);
        } else if (target == TransportLegStatusEnum.COMPLETED) {
            boolean otherActive = legMapper.selectActiveByDriverId(leg.getDriverId()).stream()
                    .anyMatch(l -> !Objects.equals(l.getId(), leg.getId()));
            if (otherActive) {
                return;
            }
            ShiftExecutionDO upd = new ShiftExecutionDO();
            upd.setId(execution.getId());
            upd.setStatus(1); // 已完成
            if (execution.getArriveTime() == null) {
                upd.setArriveTime(LocalDateTime.now());
            }
            shiftExecutionMapper.updateById(upd);
        }
    }

    /** 车辆/司机状态与运输段同步（需求 §123/§124）：在途 → 车辆在途/司机忙碌；完成 → 空闲/在线 */
    private void syncResourceStatus(TransportLegDO leg, TransportLegStatusEnum target) {
        boolean busy = target == TransportLegStatusEnum.IN_TRANSIT
                || target == TransportLegStatusEnum.NAVIGATING
                || target == TransportLegStatusEnum.LOADING
                || target == TransportLegStatusEnum.HANDOVER
                || target == TransportLegStatusEnum.DELIVERING;
        if (leg.getVehicleId() != null && vehicleMapper != null) {
            VehicleDO upd = new VehicleDO();
            upd.setId(leg.getVehicleId());
            upd.setRealtimeStatus(target == TransportLegStatusEnum.COMPLETED ? 0 : (busy ? 1 : 0));
            vehicleMapper.updateById(upd);
        }
        if (leg.getDriverId() != null && driverStatusMapper != null) {
            var status = driverStatusMapper.selectByDriverId(leg.getDriverId());
            if (status == null) {
                driverStatusMapper.insert(cn.iocoder.yudao.module.transport.dal.dataobject.driver.TransportDriverStatusDO
                        .builder()
                        .driverId(leg.getDriverId())
                        .onlineStatus(busy ? 2 : 1)
                        .currentVehicleId(leg.getVehicleId())
                        .lastHeartbeat(nowOrNull())
                        .build());
            } else {
                var upd = new cn.iocoder.yudao.module.transport.dal.dataobject.driver.TransportDriverStatusDO();
                upd.setId(status.getId());
                upd.setOnlineStatus(busy ? 2 : 1);
                upd.setCurrentVehicleId(leg.getVehicleId());
                upd.setLastHeartbeat(nowOrNull());
                driverStatusMapper.updateById(upd);
            }
        }
    }

    private static LocalDateTime nowOrNull() {
        return LocalDateTime.now();
    }

    private void recordLegEvent(TransportLegDO leg, TransportLegStatusEnum target, String reason) {
        String prefix = "第 " + leg.getLegSequence() + " 段";
        switch (target) {
            case DRIVER_ACCEPTED -> orderEventService.record(leg.getOrderId(),
                    TransportOrderEventTypeEnum.LEG_ACCEPTED, prefix + "司机已接单");
            case IN_TRANSIT -> orderEventService.record(leg.getOrderId(),
                    TransportOrderEventTypeEnum.LEG_STARTED, prefix + "已开始运输");
            case ARRIVED_DESTINATION -> orderEventService.record(leg.getOrderId(),
                    TransportOrderEventTypeEnum.LEG_ARRIVED, prefix + "已到达" + (Boolean.TRUE.equals(leg.getHandoverRequired()) ? "换乘站" : "目的站"));
            case COMPLETED -> orderEventService.record(leg.getOrderId(),
                    TransportOrderEventTypeEnum.LEG_COMPLETED, prefix + "已完成" + (reason != null ? "：" + reason : ""));
            case EXCEPTION -> orderEventService.record(leg.getOrderId(),
                    TransportOrderEventTypeEnum.ORDER_EXCEPTION, prefix + "异常" + (reason != null ? "：" + reason : ""));
            default -> { /* 其余状态无需单独记事件 */ }
        }
    }

    /**
     * 给待分配段绑定车辆/司机（需求 §48/§49/§113）：
     * 1. 取当前有效人车绑定，尽量让相邻段用不同车辆（换乘的意义）；
     * 2. **必须无时间冲突**：车辆/司机在该时段已被其他段占用则跳过该绑定；
     * 3. 全部绑定都冲突 → 保持"已规划/未分配"，由人工改派（并在日志中明确提示，绝不硬塞冲突车辆）。
     */
    private void assignVehicles(List<TransportLegDO> legs, Long orderId, Long planId,
                                Long planVehicleId, Long planDriverId) {
        // 优先采用**调度算法给出的车辆/司机分配**（transport_dispatch_plan_item）：
        // 算法会把同一片区的多张订单拼到同一辆车上（拼单/共载），这里必须沿用它的分配，
        // 否则会出现"为某一单单独派一辆车"的假象，与真实运营（一车多单）不符。
        java.util.Map<Long, cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.DispatchPlanItemDO> byStation =
                new java.util.HashMap<>();
        // 该订单在算法方案里所属的车辆（取派送明细中最靠前的一条）：拼单共载时用它给取货段派车
        cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.DispatchPlanItemDO orderVehicleItem = null;
        if (dispatchPlanItemMapper != null && orderId != null) {
            List<cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.DispatchPlanItemDO> items =
                    dispatchPlanItemMapper.selectList(
                            new cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX
                                    <cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.DispatchPlanItemDO>()
                                    .eq(cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.DispatchPlanItemDO::getOrderId, orderId)
                                    .eqIfPresent(cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.DispatchPlanItemDO::getPlanId, planId));
            items.stream().filter(i -> i.getStationId() != null).forEach(i -> byStation.putIfAbsent(i.getStationId(), i));
            orderVehicleItem = items.stream()
                    .filter(i -> i.getVehicleId() != null)
                    .min(java.util.Comparator.comparing(
                            cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.DispatchPlanItemDO::getVisitSequence,
                            java.util.Comparator.nullsLast(Integer::compareTo)))
                    .orElse(null);
        }
        // 调用方（调度）直接传入的算法分配：优先于库内查询（独立事务里查不到未提交的明细）
        if (orderVehicleItem == null && planVehicleId != null) {
            orderVehicleItem = cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.DispatchPlanItemDO.builder()
                    .orderId(orderId).planId(planId).vehicleId(planVehicleId).driverId(planDriverId).build();
        }
        if (driverVehicleMapper == null) {
            return;
        }
        List<DriverVehicleDO> bindings = driverVehicleMapper.selectActiveBindings();
        if (bindings == null || bindings.isEmpty()) {
            return;
        }
        List<TransportLegDO> assigned = new ArrayList<>();
        for (int i = 0; i < legs.size(); i++) {
            TransportLegDO leg = legs.get(i);
            // 1) 算法已分配（该订单在该经停上的车辆/司机）→ 直接沿用（实现"一车多单"）
            var planned = byStation.get(leg.getFromStationId());
            if (planned == null && !Boolean.TRUE.equals(leg.getHandoverRequired())) {
                // 最后一段：取"算法分配给该订单送达站"的车辆（一车多单继续沿用同一辆车）
                planned = byStation.get(leg.getToStationId());
            }
            if (planned == null && i == 0) {
                // 取货段：算法把取货视作"场站预装"（明细里只有派送站）→ 用该订单所属车辆，
                // 这样同一辆车上的多张订单在取货段就落在同一辆车上（真实拼单，不是专车）
                planned = orderVehicleItem;
            }
            // 注意：这里**不做时段冲突判断**——算法在派单时已按容量/时间窗校验过，
            // 而"同一辆车在同一时段承运多张订单"正是拼单/共载的正常形态，再判冲突会把共载挡掉。
            if (planned != null && planned.getVehicleId() != null) {
                leg.setVehicleId(planned.getVehicleId());
                leg.setDriverId(planned.getDriverId());
                leg.setShiftId(planned.getShiftId());
                leg.setPlanItemId(planned.getId());
                leg.setStatus(TransportLegStatusEnum.ASSIGNED.getStatus());
                assigned.add(leg);
                continue;
            }
            // 2) 算法未覆盖（多段中转站没有明细）→ 按人车绑定兜底，并避让时段冲突
            // 相邻段优先用**不同**车辆（换乘的意义，需求 §111：Leg1.vehicle != Leg2.vehicle）：
            // 第一轮只挑"本方案还没用过且无时段冲突"的绑定；没有才退而求其次允许复用。
            java.util.Set<Long> usedVehicles = assigned.stream().map(TransportLegDO::getVehicleId)
                    .filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
            DriverVehicleDO chosen = pickBinding(bindings, leg, assigned, usedVehicles, true);
            if (chosen == null) {
                chosen = pickBinding(bindings, leg, assigned, usedVehicles, false);
            }
            if (chosen == null) {
                log.warn("[multi-leg] 订单 {} 第 {} 段在 {}~{} 无可用车辆/司机（时段冲突），保持未分配",
                        leg.getOrderId(), leg.getLegSequence(), leg.getEstimatedDeparture(), leg.getEstimatedArrival());
                continue;
            }
            leg.setVehicleId(chosen.getVehicleId());
            leg.setDriverId(chosen.getDriverId());
            leg.setStatus(TransportLegStatusEnum.ASSIGNED.getStatus());
            assigned.add(leg);
        }
    }

    /** 选一个可用绑定；strictUnused=true 时跳过本方案已用车辆（保证相邻段不同车） */
    private DriverVehicleDO pickBinding(List<DriverVehicleDO> bindings, TransportLegDO leg,
                                        List<TransportLegDO> assigned, java.util.Set<Long> usedVehicles,
                                        boolean strictUnused) {
        for (DriverVehicleDO binding : bindings) {
            if (strictUnused && usedVehicles.contains(binding.getVehicleId())) {
                continue;
            }
            boolean conflict = legConflictService != null && (legConflictService.vehicleConflicts(
                    binding.getVehicleId(), leg.getEstimatedDeparture(), leg.getEstimatedArrival(), assigned, null)
                    || legConflictService.driverConflicts(binding.getDriverId(), leg.getEstimatedDeparture(),
                    leg.getEstimatedArrival(), assigned, null));
            if (!conflict) {
                return binding;
            }
        }
        return null;
    }

}
