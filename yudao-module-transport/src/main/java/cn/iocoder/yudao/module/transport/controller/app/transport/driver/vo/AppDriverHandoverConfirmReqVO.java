package cn.iocoder.yudao.module.transport.controller.app.transport.driver.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
    @Schema(description = "签收手写签名图片URL（可选；与交接照片是两件事：一个是货与舱的凭证，一个是谁认了这笔交接）")
    @Size(max = 512, message = "签名图片地址长度不能超过 512")
    private String signUrl;

}
