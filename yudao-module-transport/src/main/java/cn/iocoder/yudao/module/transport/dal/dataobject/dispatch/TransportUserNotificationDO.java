package cn.iocoder.yudao.module.transport.dal.dataobject.dispatch;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;
import java.time.LocalDateTime;

@TableName("transport_user_notification")
@KeySequence("transport_user_notification_seq")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransportUserNotificationDO {

    @TableId
    private Long id;
    private Long userId;
    /** 接收方类型：USER / DRIVER / ADMIN（NotificationRecipientTypeEnum） */
    private String recipientType;
    /** 业务事件唯一标识（幂等键：同一事件重复推送只落一条） */
    private String eventId;
    private String eventType;
    private String title;
    private String content;
    private Long orderId;
    private Long planId;
    private Long legId;
    /** 级别：INFO/SUCCESS/ACTION_REQUIRED/WARNING/EXCEPTION（NotificationLevelEnum） */
    private String level;
    /** 是否需要接收方操作（前端强提醒） */
    private Boolean actionRequired;
    private Integer readStatus;
    private LocalDateTime readTime;
    private Long tenantId;
    private LocalDateTime createTime;
    private Boolean deleted;
}
