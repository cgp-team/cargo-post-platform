package cn.iocoder.yudao.module.transport.controller.admin.transport.notification.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Schema(description = "管理后台 - 发送用户通知 Request VO")
@Data
public class NotificationSendReqVO {

    @Schema(description = "用户编号（会员ID）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "用户编号不能为空")
    private Long userId;

    @Schema(description = "事件类型", example = "SYSTEM")
    private String eventType;

    @Schema(description = "通知标题", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "通知标题不能为空")
    private String title;

    @Schema(description = "通知内容")
    private String content;

    @Schema(description = "关联订单编号")
    private Long orderId;

}
