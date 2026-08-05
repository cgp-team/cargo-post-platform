package cn.iocoder.yudao.module.transport.controller.admin.transport.shift.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 班次 Response VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class ShiftRespVO extends ShiftBaseVO {
    @Schema(description = "班次编号", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long id;
    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
