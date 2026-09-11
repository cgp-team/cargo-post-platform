package cn.iocoder.yudao.module.transport.service.dispatch;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.transport.controller.admin.transport.handover.vo.HandoverPageReqVO;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.TransportHandoverDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.TransportLegDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.TransportOrderDO;
import cn.iocoder.yudao.module.transport.dal.mysql.dispatch.TransportHandoverMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.dispatch.TransportLegMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.order.TransportOrderMapper;
import cn.iocoder.yudao.module.transport.enums.dispatch.TransportHandoverStatusEnum;
import cn.iocoder.yudao.module.transport.enums.dispatch.TransportLegStatusEnum;
import cn.iocoder.yudao.module.transport.enums.dispatch.TransportOrderEventTypeEnum;
import cn.iocoder.yudao.module.transport.enums.dispatch.TransportOrderStatusEnum;
import cn.iocoder.yudao.module.transport.enums.notification.NotificationLevelEnum;
import cn.iocoder.yudao.module.transport.service.notification.UserNotificationService;
import cn.iocoder.yudao.module.transport.service.order.OrderEventService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.*;

/**
 * 货物交接服务实现（多段联运换乘站交接，需求 §8/§9/§60~§63）。
 *
 * 状态机：WAITING → SOURCE_ARRIVED →（TARGET_WAITING）→ HANDOVER → COMPLETED；
 * 超时 TIMEOUT / 争议 EXCEPTION 需人工介入。
 *
 * 核心规则：**前序司机未确认到达（SOURCE_ARRIVED）时，后序司机不能确认收到货**。
 * 交接完成必须原子推进：Leg1=已完成 + Handover=已完成 + Leg2=运输中 + 车辆/司机状态 + 订单状态。
 */
@Service
@Validated
@Slf4j
public class HandoverServiceImpl implements HandoverService {

    @Resource private TransportHandoverMapper handoverMapper;
    @Resource private TransportLegMapper legMapper;
    @Resource private TransportOrderMapper orderMapper;
    @Resource private OrderEventService orderEventService;
    @Resource private UserNotificationService userNotificationService;
    @Resource private MultiLegService multiLegService;

    @Override
    @Transactional
    public Long createHandover(Long orderId, Long legFromId, Long legToId, Integer itemCount,
                               BigDecimal weightKg, String remark) {
        TransportOrderDO order = orderMapper.selectById(orderId);
        if (order == null) {
            throw exception(ORDER_NOT_EXISTS);
        }
        TransportLegDO legFrom = legMapper.selectById(legFromId);
        if (legFrom == null) {
            throw exception(LEG_NOT_EXISTS);
        }
        TransportLegDO legTo = legMapper.selectById(legToId);
        if (legTo == null) {
            throw exception(LEG_NOT_EXISTS);
        }
        TransportHandoverDO handover = TransportHandoverDO.builder()
                .orderId(orderId)
                .planId(legFrom.getPlanId())
                .legFromId(legFromId)
                .legToId(legToId)
                // 交接站点 = 来源段的目的站（换乘站）
                .stationId(legFrom.getToStationId())
                .fromDriverId(legFrom.getDriverId())
                .toDriverId(legTo.getDriverId())
                .fromVehicleId(legFrom.getVehicleId())
                .toVehicleId(legTo.getVehicleId())
                .itemCount(itemCount != null ? itemCount : 0)
                .weightKg(weightKg)
                .status(TransportHandoverStatusEnum.WAITING.getStatus())
                .handoverTime(LocalDateTime.now())
                .remark(remark)
                .build();
        handoverMapper.insert(handover);
        orderEventService.record(orderId, TransportOrderEventTypeEnum.HANDOVER_CREATED,
                "在换乘站发起货物交接（第 " + legFrom.getLegSequence() + " 段 → 第 " + legTo.getLegSequence() + " 段）");
        return handover.getId();
    }

    @Override
    @Transactional
    public Long markSourceArrived(Long orderId, Long legFromId) {
        TransportLegDO legFrom = legMapper.selectById(legFromId);
        if (legFrom == null) {
            throw exception(LEG_NOT_EXISTS);
        }
        TransportHandoverDO handover = handoverMapper.selectByLegFrom(legFromId);
        if (handover == null) {
            TransportLegDO legTo = nextLeg(orderId, legFrom.getLegSequence());
            if (legTo == null) {
                return null; // 最终段无换乘交接
            }
            Long id = createHandover(orderId, legFromId, legTo.getId(), null, null, null);
            handover = handoverMapper.selectById(id);
        }
        if (Objects.equals(handover.getStatus(), TransportHandoverStatusEnum.WAITING.getStatus())) {
            handoverMapper.updateById(TransportHandoverDO.builder()
                    .id(handover.getId())
                    .status(TransportHandoverStatusEnum.SOURCE_ARRIVED.getStatus())
                    .arrivedAt(LocalDateTime.now())
                    .build());
        }
        // 前序段到达换乘站；订单进入"换乘中"
        multiLegService.forceLegStatus(legFromId, TransportLegStatusEnum.ARRIVED_DESTINATION, "到达换乘站");
        updateOrderStatus(orderId, TransportOrderStatusEnum.TRANSFERRING);
        orderEventService.record(orderId, TransportOrderEventTypeEnum.HANDOVER_REQUIRED,
                "前序司机已到达换乘站，等待接收司机接货");
        // 通知后序司机提前前往接驳（需求 §59/§83）
        if (handover.getToDriverId() != null) {
            userNotificationService.sendToDriver(handover.getToDriverId(),
                    TransportOrderEventTypeEnum.HANDOVER_REQUIRED, NotificationLevelEnum.ACTION_REQUIRED, true,
                    "联运接驳提醒",
                    "前序司机已到达换乘站，请前往接货并确认接收",
                    orderId, legFrom.getPlanId(), handover.getLegToId());
        }
        userNotificationService.sendToOrderUser(orderId, TransportOrderEventTypeEnum.HANDOVER_REQUIRED,
                NotificationLevelEnum.INFO, false, "货物正在换乘交接",
                "您的货物已到达换乘站，正在进行司机交接");
        return handover.getId();
    }

    @Override
    @Transactional
    public void startHandover(Long handoverId, Long driverId) {
        TransportHandoverDO handover = get(handoverId);
        Integer status = handover.getStatus();
        // 核心规则：前序未到达时接收方不能开始接货
        if (Objects.equals(status, TransportHandoverStatusEnum.WAITING.getStatus())) {
            throw exception(HANDOVER_SOURCE_NOT_ARRIVED);
        }
        if (Objects.equals(status, TransportHandoverStatusEnum.COMPLETED.getStatus())
                || Objects.equals(status, TransportHandoverStatusEnum.TIMEOUT.getStatus())
                || Objects.equals(status, TransportHandoverStatusEnum.CANCELLED.getStatus())) {
            throw exception(HANDOVER_STATUS_ILLEGAL);
        }
        if (driverId != null && handover.getToDriverId() == null) {
            handoverMapper.updateById(TransportHandoverDO.builder()
                    .id(handoverId).toDriverId(driverId).build());
        }
        handoverMapper.updateById(TransportHandoverDO.builder()
                .id(handoverId)
                .status(TransportHandoverStatusEnum.HANDOVER.getStatus())
                .handoverStartedAt(LocalDateTime.now())
                .build());
        if (handover.getLegToId() != null) {
            multiLegService.forceLegStatus(handover.getLegToId(),
                    TransportLegStatusEnum.ARRIVED_ORIGIN, "接收司机已到达换乘站");
        }
        orderEventService.record(handover.getOrderId(), TransportOrderEventTypeEnum.HANDOVER_STARTED,
                "交接双方到场，开始核对货物");
    }

    @Override
    @Transactional
    public void confirmHandover(Long handoverId, Long toDriverId, String photoUrl) {
        TransportHandoverDO handover = get(handoverId);
        Integer status = handover.getStatus();
        if (Objects.equals(status, TransportHandoverStatusEnum.COMPLETED.getStatus())) {
            return; // 幂等：重复确认直接返回
        }
        // 核心规则：前序未确认到达 → 不允许确认接货
        if (Objects.equals(status, TransportHandoverStatusEnum.WAITING.getStatus())) {
            throw exception(HANDOVER_SOURCE_NOT_ARRIVED);
        }
        if (Objects.equals(status, TransportHandoverStatusEnum.TIMEOUT.getStatus())
                || Objects.equals(status, TransportHandoverStatusEnum.CANCELLED.getStatus())) {
            throw exception(HANDOVER_STATUS_ILLEGAL);
        }
        if (toDriverId != null && handover.getToDriverId() != null
                && !Objects.equals(toDriverId, handover.getToDriverId())
                && !Objects.equals(toDriverId, handover.getFromDriverId())) {
            throw exception(HANDOVER_NOT_ASSIGNED);
        }
        LocalDateTime now = LocalDateTime.now();
        TransportHandoverDO update = new TransportHandoverDO();
        update.setId(handoverId);
        update.setStatus(TransportHandoverStatusEnum.COMPLETED.getStatus());
        update.setConfirmTime(now);
        update.setHandoverCompletedAt(now);
        update.setConfirmedBy(toDriverId);
        if (handover.getToDriverId() == null && toDriverId != null) {
            update.setToDriverId(toDriverId); // 认领：把接收司机补上
        }
        if (photoUrl != null && !photoUrl.isBlank()) {
            update.setPhotoUrl(photoUrl);
        }
        handoverMapper.updateById(update);

        // 原子推进（需求 §63）：Leg1 = 已完成；Leg2 = 运输中
        multiLegService.forceLegStatus(handover.getLegFromId(),
                TransportLegStatusEnum.COMPLETED, "换乘站交接完成");
        TransportLegDO legTo = handover.getLegToId() != null ? legMapper.selectById(handover.getLegToId()) : null;
        if (legTo != null) {
            multiLegService.forceLegStatus(legTo.getId(), TransportLegStatusEnum.IN_TRANSIT, "接货后继续运输");
        }
        updateOrderStatusOnHandover(handover.getOrderId(), handover.getLegToId());

        orderEventService.record(handover.getOrderId(), TransportOrderEventTypeEnum.HANDOVER_CONFIRMED,
                "换乘站货物交接已确认完成");
        userNotificationService.sendToOrderUser(handover.getOrderId(),
                TransportOrderEventTypeEnum.HANDOVER_CONFIRMED, NotificationLevelEnum.SUCCESS, false,
                "换乘完成", "您的货物已由下一辆运输车辆接收，正在继续运输");
        if (handover.getToDriverId() != null) {
            userNotificationService.sendToDriver(handover.getToDriverId(),
                    TransportOrderEventTypeEnum.LEG_STARTED, NotificationLevelEnum.INFO, false,
                    "交接完成，开始运输", "货物已接收，请按本段路线继续运输",
                    handover.getOrderId(), handover.getPlanId(), handover.getLegToId());
        }
    }

    @Override
    @Transactional
    public void disputeHandover(Long handoverId, String remark) {
        TransportHandoverDO handover = get(handoverId);
        if (Objects.equals(handover.getStatus(), TransportHandoverStatusEnum.COMPLETED.getStatus())) {
            throw exception(HANDOVER_STATUS_ILLEGAL);
        }
        handoverMapper.updateById(TransportHandoverDO.builder()
                .id(handoverId)
                .status(TransportHandoverStatusEnum.EXCEPTION.getStatus())
                .exceptionReason(remark)
                .remark(remark)
                .build());
        updateOrderStatus(handover.getOrderId(), TransportOrderStatusEnum.EXCEPTION);
        orderEventService.record(handover.getOrderId(), TransportOrderEventTypeEnum.HANDOVER_DISPUTED,
                "交接存在异常：" + (remark != null ? remark : "件数不符/货物破损"));
        userNotificationService.sendToOrderUser(handover.getOrderId(),
                TransportOrderEventTypeEnum.HANDOVER_DISPUTED, NotificationLevelEnum.WARNING, true,
                "交接异常提醒", "货物交接存在异常，工作人员正在处理");
        userNotificationService.sendToAdmin(TransportOrderEventTypeEnum.HANDOVER_DISPUTED,
                NotificationLevelEnum.WARNING, "交接异常",
                "订单 " + handover.getOrderId() + " 交接异常：" + remark, handover.getOrderId(), handoverId);
    }

    @Override
    @Transactional
    public void timeoutHandover(Long handoverId, String remark) {
        TransportHandoverDO handover = get(handoverId);
        if (Objects.equals(handover.getStatus(), TransportHandoverStatusEnum.COMPLETED.getStatus())) {
            return;
        }
        handoverMapper.updateById(TransportHandoverDO.builder()
                .id(handoverId)
                .status(TransportHandoverStatusEnum.TIMEOUT.getStatus())
                .exceptionReason(remark != null ? remark : "换乘超时")
                .build());
        updateOrderStatus(handover.getOrderId(), TransportOrderStatusEnum.EXCEPTION);
        orderEventService.record(handover.getOrderId(), TransportOrderEventTypeEnum.ORDER_EXCEPTION,
                "换乘交接超时：" + (remark != null ? remark : "前序司机长时间未到达"));
        userNotificationService.sendToAdmin(TransportOrderEventTypeEnum.ORDER_EXCEPTION,
                NotificationLevelEnum.EXCEPTION, "换乘交接超时",
                "订单 " + handover.getOrderId() + " 换乘交接超时，需人工介入", handover.getOrderId(), handoverId);
        userNotificationService.sendToOrderUser(handover.getOrderId(),
                TransportOrderEventTypeEnum.ORDER_EXCEPTION, NotificationLevelEnum.WARNING, false,
                "运输异常提醒", "您的货物换乘交接出现延迟，工作人员正在处理");
    }

    @Override
    public TransportHandoverDO get(Long id) {
        TransportHandoverDO handover = handoverMapper.selectById(id);
        if (handover == null) {
            throw exception(HANDOVER_NOT_EXISTS);
        }
        return handover;
    }

    @Override
    public TransportHandoverDO getByLeg(Long legId) {
        if (legId == null) {
            return null;
        }
        TransportHandoverDO handover = handoverMapper.selectByLegFrom(legId);
        return handover != null ? handover : handoverMapper.selectByLegTo(legId);
    }

    @Override
    public List<TransportHandoverDO> getByPlan(Long planId) {
        return planId == null ? List.of() : handoverMapper.selectListByPlanId(planId);
    }

    @Override
    public PageResult<TransportHandoverDO> getPage(HandoverPageReqVO reqVO) {
        return handoverMapper.selectPage(reqVO);
    }

    @Override
    public List<TransportHandoverDO> getByOrder(Long orderId) {
        return orderId == null ? List.of() : handoverMapper.selectListByOrderId(orderId);
    }

    @Override
    public List<TransportHandoverDO> getPendingByDriver(Long driverId) {
        if (driverId == null) {
            return List.of();
        }
        // 待处理 = 未完成/未取消（含等待、前序已到、交接中、异常）
        return handoverMapper.selectListByDriverId(driverId).stream()
                .filter(h -> !Objects.equals(h.getStatus(), TransportHandoverStatusEnum.COMPLETED.getStatus())
                        && !Objects.equals(h.getStatus(), TransportHandoverStatusEnum.CANCELLED.getStatus()))
                .toList();
    }

    // ==================== 内部 ====================

    private TransportLegDO nextLeg(Long orderId, Integer sequence) {
        if (sequence == null) {
            return null;
        }
        return legMapper.selectListByOrderId(orderId).stream()
                .filter(l -> Objects.equals(l.getLegSequence(), sequence + 1))
                .findFirst().orElse(null);
    }

    /**
     * 交接完成后订单状态：后面还有段 → 部分完成；否则（最后一段接货）→ 运输中。
     * 订单"已完成"由最后一段交付/用户取货时写（避免第一段完成就把整单置完成，需求 §6 禁止项）。
     */
    private void updateOrderStatusOnHandover(Long orderId, Long legToId) {
        if (orderId == null) {
            return;
        }
        List<TransportLegDO> legs = legMapper.selectListByOrderId(orderId);
        TransportLegDO legTo = legs.stream().filter(l -> Objects.equals(l.getId(), legToId)).findFirst().orElse(null);
        boolean hasFollowingLeg = legTo != null && legTo.getLegSequence() != null && legs.stream()
                .anyMatch(l -> l.getLegSequence() != null && l.getLegSequence() > legTo.getLegSequence());
        updateOrderStatus(orderId, hasFollowingLeg
                ? TransportOrderStatusEnum.PARTIALLY_COMPLETED
                : TransportOrderStatusEnum.IN_TRANSIT);
    }

    /** 订单状态推进（不动终态订单） */
    private void updateOrderStatus(Long orderId, TransportOrderStatusEnum target) {
        if (orderId == null || target == null) {
            return;
        }
        TransportOrderDO update = new TransportOrderDO();
        update.setStatus(target.getStatus());
        orderMapper.update(update, new LambdaQueryWrapperX<TransportOrderDO>()
                .eq(TransportOrderDO::getId, orderId)
                .notIn(TransportOrderDO::getStatus, TransportOrderStatusEnum.COMPLETED.getStatus(),
                        TransportOrderStatusEnum.CANCELLED.getStatus()));
    }

}
