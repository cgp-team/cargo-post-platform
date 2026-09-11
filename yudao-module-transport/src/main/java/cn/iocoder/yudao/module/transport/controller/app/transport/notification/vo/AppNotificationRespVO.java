package cn.iocoder.yudao.module.transport.controller.app.transport.notification.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "用户 APP - 消息通知 Response VO")
@Data
public class AppNotificationRespVO {

    @Schema(description = "通知编号")
    private Long id;

    @Schema(description = "事件类型")
    private String eventType;

    @Schema(description = "事件类型名")
    private String eventTypeName;

    @Schema(description = "通知标题")
    private String title;

    @Schema(description = "通知内容")
    private String content;

    @Schema(description = "关联订单编号")
    private Long orderId;

    @Schema(description = "阅读状态：0未读 1已读")
    private Integer readStatus;

    @Schema(description = "级别：INFO/SUCCESS/ACTION_REQUIRED/WARNING/EXCEPTION（前端据此配色/强提醒）")
    private String level;

    @Schema(description = "是否需要接收方操作（强提醒）")
    private Boolean actionRequired;

    @Schema(description = "关联运输段编号")
    private Long legId;

    @Schema(description = "阅读时间")
    private LocalDateTime readTime;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

}
