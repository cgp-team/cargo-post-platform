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
