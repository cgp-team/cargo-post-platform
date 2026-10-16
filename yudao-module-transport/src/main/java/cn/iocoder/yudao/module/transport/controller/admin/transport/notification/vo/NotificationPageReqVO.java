package cn.iocoder.yudao.module.transport.controller.admin.transport.notification.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

import static cn.iocoder.yudao.framework.common.util.date.DateUtils.FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND;

@Schema(description = "管理后台 - 用户通知分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class NotificationPageReqVO extends PageParam {

    @Schema(description = "用户编号（会员ID）")
    private Long userId;

    // #92：这一张表里三类接收方（USER/DRIVER/ADMIN）共用编号空间，`userId` 单独当过滤条件时，
    // 「管理员还没处理的告警」根本查不出来 —— 不填 userId 就把用户、司机、管理员混在一张平铺表里。
    // 不传时保持原行为（不加这个条件），免得已有的页面一下变空。
    @Schema(description = "接收方类型：USER/DRIVER/ADMIN（不传=不按接收方过滤）")
    private String recipientType;

    @Schema(description = "事件类型")
    private String eventType;

    @Schema(description = "关联订单编号")
    private Long orderId;

    @Schema(description = "阅读状态：0未读 1已读")
    private Integer readStatus;

    @Schema(description = "创建时间")
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND)
    private LocalDateTime[] createTime;

}
