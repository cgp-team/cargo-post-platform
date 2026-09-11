package cn.iocoder.yudao.module.transport.controller.app.transport.driver.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Schema(description = "用户 APP - 司机运输段操作 Request VO（接受/导航/到达/装货/发车/交接/完成）")
@Data
public class AppDriverLegActionReqVO {

    @Schema(description = "司机编号（可选，仅用于与登录态校验，身份以登录会员为准）")
    private Long driverId;

    @Schema(description = "运输段编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "运输段编号不能为空")
    private Long legId;

    @Schema(description = "交接照片URL（交接确认用）")
    private String photoUrl;

    @Schema(description = "备注（异常/争议原因）")
    private String remark;

}
