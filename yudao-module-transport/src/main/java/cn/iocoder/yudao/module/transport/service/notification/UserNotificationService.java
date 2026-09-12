package cn.iocoder.yudao.module.transport.service.notification;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.transport.controller.admin.transport.notification.vo.NotificationPageReqVO;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.TransportUserNotificationDO;
import cn.iocoder.yudao.module.transport.enums.dispatch.TransportOrderEventTypeEnum;
import cn.iocoder.yudao.module.transport.enums.notification.NotificationLevelEnum;
import cn.iocoder.yudao.module.transport.enums.notification.NotificationRecipientTypeEnum;

/**
 * 用户通知服务：订单事件发生时写 transport_user_notification，供小程序「消息中心」展示。
 */
public interface UserNotificationService {

    /** 发送通知给指定会员，返回通知编号 */
    Long send(Long userId, TransportOrderEventTypeEnum eventType, String title, String content, Long orderId);

    /** 统一发送入口（幂等：同一 eventId 只落一条；接收方可以是用户/司机/后台） */
    Long send(NotificationSendDTO dto);

    /**
     * 发送通知给订单所属会员（按 transport_order.member_user_id 解析）。
     * 订单非小程序下单（无会员）时静默跳过。
     */
    Long sendToOrderUser(Long orderId, TransportOrderEventTypeEnum eventType, String title, String content);

    /** 发送通知给订单所属会员（带级别与是否需操作） */
    Long sendToOrderUser(Long orderId, TransportOrderEventTypeEnum eventType, NotificationLevelEnum level,
                         boolean actionRequired, String title, String content);

    /** 发送通知给司机（换乘接驳提醒、新任务等） */
    Long sendToDriver(Long driverId, TransportOrderEventTypeEnum eventType, NotificationLevelEnum level,
                      boolean actionRequired, String title, String content,
                      Long orderId, Long planId, Long legId);

    /** 发送通知给后台（超时/异常告警） */
    Long sendToAdmin(TransportOrderEventTypeEnum eventType, NotificationLevelEnum level, String title,
                     String content, Long orderId, Long legId);

    /** 标记单条通知已读（校验归属：同时比对接收方类型与编号，司机 id 与会员 id 同空间，只比 id 会串） */
    void markAsRead(Long notificationId, NotificationRecipientTypeEnum recipientType, Long recipientId);

    /** 批量标记已读：orderId 为空时标记该用户全部未读 */
    void markAllAsRead(Long userId, Long orderId);

    /** 本人通知分页 */
    PageResult<TransportUserNotificationDO> getMyPage(Long userId, Integer readStatus, PageParam pageParam);

    /** 本人未读数 */
    Long getUnreadCount(Long userId);

    /** 司机消息分页 */
    PageResult<TransportUserNotificationDO> getDriverPage(Long driverId, Integer readStatus, PageParam pageParam);

    /** 司机未读数 */
    Long getDriverUnreadCount(Long driverId);

    /** 管理端分页 */
    PageResult<TransportUserNotificationDO> getPage(NotificationPageReqVO reqVO);

    /** 按编号查通知（不存在抛 NOTIFICATION_NOT_EXISTS） */
    TransportUserNotificationDO get(Long id);

}
