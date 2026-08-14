package cn.iocoder.yudao.module.transport.controller.admin.transport.order.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Schema(description = "管理后台 - 货运订单审核 Request VO")
@Data
public class OrderAuditReqVO {

    @Schema(description = "订单编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "订单编号不能为空")
    private Long orderId;

    @Schema(description = "是否通过：true通过 false拒绝", requiredMode = Schema.RequiredMode.REQUIRED, example = "true")
    @NotNull(message = "审核结论不能为空")
    private Boolean pass;

    @Schema(description = "拒绝原因（拒绝时必填，如危险品/违禁品）", example = "疑似易燃易爆物品")
    private String rejectReason;

}
