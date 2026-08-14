package cn.iocoder.yudao.module.transport.controller.app.transport.driver.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Schema(description = "用户 APP - 司机订单操作 Request VO")
@Data
public class AppDriverOrderActionReqVO {

    @Schema(description = "司机编号（可选，仅用于与登录态校验，身份以登录会员为准）", example = "1")
    private Long driverId;

    @Schema(description = "订单编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "订单编号不能为空")
    private Long orderId;

    @Schema(description = "取件码（pickup-verify 邮快件核销用，6位数字）", example = "123456")
    private String pickupCode;

    @Schema(description = "司机收件照片URL（pickup-confirm 货运装车强制上传，快递总站核对凭证）")
    private String driverPhotoUrl;

}
