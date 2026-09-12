package cn.iocoder.yudao.module.transport.service.notification;

import cn.iocoder.yudao.module.transport.enums.dispatch.TransportOrderEventTypeEnum;
import cn.iocoder.yudao.module.transport.enums.notification.NotificationLevelEnum;
import cn.iocoder.yudao.module.transport.enums.notification.NotificationRecipientTypeEnum;
import lombok.Builder;
import lombok.Data;

/**
 * 通知发送参数（统一入口，避免各 Controller 零散发消息）。
 */
@Data
@Builder
public class NotificationSendDTO {

    /** 接收方类型：USER / DRIVER / ADMIN */
    private NotificationRecipientTypeEnum recipientType;
    /** 接收方编号（会员编号 / 司机编号；ADMIN 可为空） */
    private Long recipientId;
    private TransportOrderEventTypeEnum eventType;
    /** 通知级别 */
    private NotificationLevelEnum level;
    /** 是否需要接收方操作（前端强提醒） */
    private Boolean actionRequired;
    private String title;
    private String content;
    private Long orderId;
    private Long planId;
    private Long legId;
    /**
     * 业务事件唯一标识（幂等键）：同一事件重复推送只落一条。
     * 为空时按 recipientType/recipientId/eventType/orderId/legId 自动生成。
     */
    private String eventId;

}
