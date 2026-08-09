package cn.iocoder.yudao.module.transport.controller.admin.dispatch.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Schema(description = "管理后台 - 发车核验 Request VO")
@Data
public class DispatchCheckReqVO {

    @Schema(description = "方案编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "方案编号不能为空")
    private Long planId;

    @Schema(description = "车辆编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "车辆不能为空")
    private Long vehicleId;

    @Schema(description = "是否通过", requiredMode = Schema.RequiredMode.REQUIRED, example = "true")
    @NotNull(message = "核验结论不能为空")
    private Boolean pass;

    @Schema(description = "核验备注")
    private String remark;

}
