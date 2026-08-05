package cn.iocoder.yudao.module.transport.controller.admin.transport.shift.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Schema(description = "管理后台 - 班次更新 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class ShiftUpdateReqVO extends ShiftBaseVO {
    @Schema(description = "班次编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "班次编号不能为空")
    private Long id;
}
