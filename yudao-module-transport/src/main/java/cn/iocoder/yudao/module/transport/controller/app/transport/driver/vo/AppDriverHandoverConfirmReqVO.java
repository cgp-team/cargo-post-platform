package cn.iocoder.yudao.module.transport.controller.app.transport.driver.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Schema(description = "用户 APP - 司机交接确认 Request VO")
@Data
public class AppDriverHandoverConfirmReqVO {

    @Schema(description = "司机编号（可选，仅用于与登录态校验，身份以登录会员为准）")
    private Long driverId;

    @Schema(description = "交接记录编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "交接记录编号不能为空")
    private Long handoverId;

    @Schema(description = "交接照片URL（核验凭证，建议上传）")
    private String photoUrl;

}
