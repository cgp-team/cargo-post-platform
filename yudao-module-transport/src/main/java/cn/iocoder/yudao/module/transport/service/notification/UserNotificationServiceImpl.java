package cn.iocoder.yudao.module.transport.service.notification;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.transport.controller.admin.transport.notification.vo.NotificationPageReqVO;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.TransportUserNotificationDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.TransportOrderDO;
import cn.iocoder.yudao.module.transport.dal.mysql.dispatch.TransportUserNotificationMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.order.TransportOrderMapper;
import cn.iocoder.yudao.module.transport.enums.dispatch.TransportOrderEventTypeEnum;
import cn.iocoder.yudao.module.transport.enums.notification.NotificationLevelEnum;
import cn.iocoder.yudao.module.transport.enums.notification.NotificationRecipientTypeEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDateTime;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.NOTIFICATION_NOT_EXISTS;
import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.NOTIFICATION_NOT_YOURS;

/**
 * 用户通知服务实现：写 transport_user_notification。通知写入失败不阻断主流程（只记日志）。
 */
@Service
@Validated
@Slf4j
public class UserNotificationServiceImpl implements UserNotificationService {

    /** BE-28：通知写入失败累计计数（监控用） */
    private static final java.util.concurrent.atomic.AtomicLong SEND_FAIL_COUNT = new java.util.concurrent.atomic.AtomicLong();

    private static final Integer READ_STATUS_UNREAD = 0;
    private static final Integer READ_STATUS_READ = 1;
    /** 后台通知的默认接收方编号（后台按类型聚合展示，不区分具体管理员） */
    private static final Long ADMIN_RECIPIENT_ID = 0L;

    @Resource
    private TransportUserNotificationMapper notificationMapper;
    @Resource
    private TransportOrderMapper orderMapper;

    @Override
    public Long send(Long userId, TransportOrderEventTypeEnum eventType, String title, String content, Long orderId) {
        return send(NotificationSendDTO.builder()
                .recipientType(NotificationRecipientTypeEnum.USER)
                .recipientId(userId)
                .eventType(eventType)
                .level(NotificationLevelEnum.INFO)
                .actionRequired(false)
                .title(title)
                .content(content)
                .orderId(orderId)
                .build());
    }

    @Override
    public Long send(NotificationSendDTO dto) {
        if (dto == null || dto.getRecipientId() == null || dto.getTitle() == null || dto.getTitle().isBlank()) {
            return null;
        }
        String eventId = dto.getEventId() != null ? dto.getEventId() : buildEventId(dto);
        try {
            // 幂等：唯一键 (event_id, user_id, event_type) 冲突时说明已推送过 → 直接跳过
            TransportUserNotificationDO existing = notificationMapper.selectByEvent(eventId, dto.getRecipientId(),
                    dto.getEventType() != null ? dto.getEventType().getCode() : "SYSTEM");
            if (existing != null) {
                return existing.getId();
            }
            TransportUserNotificationDO notification = TransportUserNotificationDO.builder()
                    .userId(dto.getRecipientId())
                    .recipientType(dto.getRecipientType() != null
                            ? dto.getRecipientType().name() : NotificationRecipientTypeEnum.USER.name())
                    .eventId(eventId)
                    .eventType(dto.getEventType() != null ? dto.getEventType().getCode() : "SYSTEM")
                    .title(dto.getTitle())
                    .content(dto.getContent())
                    .orderId(dto.getOrderId())
                    .planId(dto.getPlanId())
                    .legId(dto.getLegId())
                    .level(dto.getLevel() != null ? dto.getLevel().name() : NotificationLevelEnum.INFO.name())
                    .actionRequired(Boolean.TRUE.equals(dto.getActionRequired()))
                    .readStatus(READ_STATUS_UNREAD)
                    .build();
            notificationMapper.insert(notification);
            return notification.getId();
        } catch (Exception ex) {
            // BE-28：失败计数与留痕——通知丢失通常无感，靠计数可在监控里发现异常增长
            log.warn("[user-notification] 接收方 {} 通知写入失败（累计第 {} 次）：{}",
                    dto.getRecipientId(), SEND_FAIL_COUNT.incrementAndGet(), ex.getMessage());
            return null;
        }
    }

    /** 幂等键：事件类型 + 接收方 + 订单/段（同一次业务事件只推一次） */
    private static String buildEventId(NotificationSendDTO dto) {
        return (dto.getEventType() != null ? dto.getEventType().getCode() : "SYSTEM")
                + ":" + dto.getRecipientType() + "-" + dto.getRecipientId()
                + (dto.getOrderId() != null ? ":ORDER-" + dto.getOrderId() : "")
                + (dto.getLegId() != null ? ":LEG-" + dto.getLegId() : "");
    }

    @Override
    public Long sendToOrderUser(Long orderId, TransportOrderEventTypeEnum eventType, String title, String content) {
        return sendToOrderUser(orderId, eventType, NotificationLevelEnum.INFO, false, title, content);
    }

    @Override
    public Long sendToOrderUser(Long orderId, TransportOrderEventTypeEnum eventType, NotificationLevelEnum level,
                                boolean actionRequired, String title, String content) {
        return sendToOrderUser(orderId, eventType, level, actionRequired, title, content, null);
    }

    @Override
    public Long sendToOrderUser(Long orderId, TransportOrderEventTypeEnum eventType, NotificationLevelEnum level,
                                boolean actionRequired, String title, String content, String eventId) {
        if (orderId == null) {
            return null;
        }
        TransportOrderDO order = orderMapper.selectById(orderId);
        if (order == null || order.getMemberUserId() == null) {
            // 后台代下单/无会员归属：无用户可通知，静默跳过
            return null;
        }
        return send(NotificationSendDTO.builder()
                .recipientType(NotificationRecipientTypeEnum.USER)
                .recipientId(order.getMemberUserId())
                .eventType(eventType)
                .level(level)
                .actionRequired(actionRequired)
                .title(title)
                .content(content)
                .orderId(orderId)
                .eventId(eventId)
                .build());
    }

    @Override
    public Long sendToDriver(Long driverId, TransportOrderEventTypeEnum eventType, NotificationLevelEnum level,
                             boolean actionRequired, String title, String content,
                             Long orderId, Long planId, Long legId) {
        return send(NotificationSendDTO.builder()
                .recipientType(NotificationRecipientTypeEnum.DRIVER)
                .recipientId(driverId)
                .eventType(eventType)
                .level(level)
                .actionRequired(actionRequired)
                .title(title)
                .content(content)
                .orderId(orderId)
                .planId(planId)
                .legId(legId)
                .build());
    }

    @Override
    public Long sendToAdmin(TransportOrderEventTypeEnum eventType, NotificationLevelEnum level, String title,
                            String content, Long orderId, Long legId) {
        return send(NotificationSendDTO.builder()
                .recipientType(NotificationRecipientTypeEnum.ADMIN)
                .recipientId(ADMIN_RECIPIENT_ID)
                .eventType(eventType)
                .level(level)
                .actionRequired(true)
                .title(title)
                .content(content)
                .orderId(orderId)
                .legId(legId)
                .build());
    }

    @Override
    public void markAsRead(Long notificationId, NotificationRecipientTypeEnum recipientType, Long recipientId) {
        TransportUserNotificationDO notification = notificationMapper.selectById(notificationId);
        if (notification == null) {
            throw exception(NOTIFICATION_NOT_EXISTS);
        }
        // P2-L：归属校验同时比对接收方类型与编号。司机 id 与会员 id 共用同一编号空间，
        // 只比 user_id 会让司机把用户的单条通知标成已读（或反之）。
        if (recipientType != null && !recipientType.name().equals(notification.getRecipientType())) {
            throw exception(NOTIFICATION_NOT_YOURS);
        }
        if (recipientId != null && !recipientId.equals(notification.getUserId())) {
            throw exception(NOTIFICATION_NOT_YOURS);
        }
        if (READ_STATUS_READ.equals(notification.getReadStatus())) {
            return; // 幂等：已读不重复写
        }
        notificationMapper.updateById(TransportUserNotificationDO.builder()
                .id(notificationId)
                .readStatus(READ_STATUS_READ)
                .readTime(LocalDateTime.now())
                .build());
    }

    @Override
    public void markAllAsRead(Long userId, Long orderId) {
        if (userId == null) {
            return;
        }
        notificationMapper.update(TransportUserNotificationDO.builder()
                        .readStatus(READ_STATUS_READ)
                        .readTime(LocalDateTime.now())
                        .build(),
                new LambdaQueryWrapperX<TransportUserNotificationDO>()
                        .eq(TransportUserNotificationDO::getUserId, userId)
                        .eq(TransportUserNotificationDO::getRecipientType, "USER")
                        .eq(TransportUserNotificationDO::getReadStatus, READ_STATUS_UNREAD)
                        .eqIfPresent(TransportUserNotificationDO::getOrderId, orderId));
    }

    @Override
    public PageResult<TransportUserNotificationDO> getMyPage(Long userId, Integer readStatus, PageParam pageParam) {
        return notificationMapper.selectPageByUserId(userId, readStatus, pageParam);
    }

    @Override
    public Long getUnreadCount(Long userId) {
        if (userId == null) {
            return 0L;
        }
        return notificationMapper.selectUnreadCount(userId);
    }

    @Override
    public PageResult<TransportUserNotificationDO> getDriverPage(Long driverId, Integer readStatus,
                                                                 PageParam pageParam) {
        return notificationMapper.selectPageByDriverId(driverId, readStatus, pageParam);
    }

    @Override
    public Long getDriverUnreadCount(Long driverId) {
        if (driverId == null) {
            return 0L;
        }
        return notificationMapper.selectUnreadCountByDriver(driverId);
    }

    @Override
    public PageResult<TransportUserNotificationDO> getPage(NotificationPageReqVO reqVO) {
        return notificationMapper.selectPage(reqVO);
    }

    @Override
    public TransportUserNotificationDO get(Long id) {
        TransportUserNotificationDO notification = notificationMapper.selectById(id);
        if (notification == null) {
            throw exception(NOTIFICATION_NOT_EXISTS);
        }
        return notification;
    }

}
