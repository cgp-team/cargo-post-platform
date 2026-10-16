package cn.iocoder.yudao.module.transport.controller.admin.transport.notification.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 用户通知 Response VO")
@Data
public class NotificationRespVO {

    @Schema(description = "通知编号")
    private Long id;

    @Schema(description = "用户编号（会员ID）")
    private Long userId;

    // #92：这三类接收方**共用编号空间**（USER 的会员 id、DRIVER 的司机 id、ADMIN 的常量 0），
    // 少了这一列，后台那张表就把"用户 7 号的通知"和"司机 7 号的告警"画成同一类东西，
    // 也没法解释"管理员未处理"那个数是从哪些行来的。
    @Schema(description = "接收方类型：USER/DRIVER/ADMIN")
    private String recipientType;

    @Schema(description = "级别：INFO/SUCCESS/ACTION_REQUIRED/WARNING/EXCEPTION")
    private String level;

    @Schema(description = "事件类型")
    private String eventType;

    @Schema(description = "通知标题")
    private String title;

    @Schema(description = "通知内容")
    private String content;

    @Schema(description = "关联订单编号")
    private Long orderId;

    @Schema(description = "阅读状态：0未读 1已读")
    private Integer readStatus;

    @Schema(description = "阅读时间")
    private LocalDateTime readTime;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

}
