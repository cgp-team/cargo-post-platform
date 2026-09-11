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
import cn.iocoder.yudao.module.transport.dal.mysql.shift.ShiftExecutionMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.dispatch.DispatchPlanMapper;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.DispatchPlanDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.shift.ShiftExecutionDO;
import cn.iocoder.yudao.module.transport.enums.dispatch.DispatchPlanStatusEnum;
import cn.iocoder.yudao.module.transport.dal.mysql.station.StationMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.vehicle.VehicleMapper;
import cn.iocoder.yudao.module.transport.enums.dispatch.TransportLegStatusEnum;
import cn.iocoder.yudao.module.transport.enums.dispatch.TransportOrderEventTypeEnum;
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
    @Resource private DriverVehicleMapper driverVehicleMapper;
    @Resource private VehicleMapper vehicleMapper;
    @Resource private TransportDriverStatusMapper driverStatusMapper;
    @Resource private MultiLegPlanner multiLegPlanner;
    @Resource private LegConflictService legConflictService;
    @Resource private ShiftExecutionMapper shiftExecutionMapper;
    @Resource private DispatchPlanMapper dispatchPlanMapper;
    @Resource private OrderEventService orderEventService;
    @Resource private UserNotificationService userNotificationService;

    @Override
    public List<TransportLegDO> planLegs(Long orderId) {
        return planLegs(orderId, null);
    }

    @Override
    // REQUIRES_NEW：调用方 DispatchServiceImpl.createSmartPlan 会吞掉段规划异常（多段是增强能力，
    // 不该让整单调度失败）。若参与外层事务，异常会把共享事务标记 rollback-only，导致外层提交时
    // 抛 UnexpectedRollbackException；独立事务可保证"段规划失败只回滚自己，不影响已生成的直达方案"。
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<TransportLegDO> planLegs(Long orderId, Long planId) {
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
                stationMapper.selectList(), routeStationMapper.selectList());

        List<TransportLegDO> legs = new ArrayList<>();
        LocalDateTime cursor = LocalDateTime.now().plusMinutes(MultiLegPlanner.PREPARE_MINUTES);
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
                    .estimatedDeparture(cursor)
                    .estimatedArrival(cursor.plusMinutes(draft.durationMinutes()))
                    .build();
            legs.add(leg);
            cursor = leg.getEstimatedArrival().plusMinutes(MultiLegPlanner.HANDOVER_DWELL_MINUTES);
        }
        assignVehicles(legs);
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
                stationMapper.selectList(), routeStationMapper.selectList());
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
    private void assignVehicles(List<TransportLegDO> legs) {
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
            // 轮转起点：让相邻段优先用不同车辆（bindings 已按 vehicleId 升序）
            DriverVehicleDO chosen = null;
            for (int k = 0; k < bindings.size(); k++) {
                DriverVehicleDO binding = bindings.get((i + k) % bindings.size());
                boolean conflict = legConflictService != null && (legConflictService.vehicleConflicts(
                        binding.getVehicleId(), leg.getEstimatedDeparture(), leg.getEstimatedArrival(), assigned, null)
                        || legConflictService.driverConflicts(binding.getDriverId(), leg.getEstimatedDeparture(),
                        leg.getEstimatedArrival(), assigned, null));
                if (!conflict) {
                    chosen = binding;
                    break;
                }
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

}
