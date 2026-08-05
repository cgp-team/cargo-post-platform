package cn.iocoder.yudao.module.transport.controller.admin.transport.shift.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalTime;

@Schema(description = "管理后台 - 班次精简信息 Response VO")
@Data
public class ShiftSimpleRespVO {
    @Schema(description = "班次编号", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long id;
    @Schema(description = "班次编码", requiredMode = Schema.RequiredMode.REQUIRED)
    private String shiftCode;
    @Schema(description = "计划发车时间")
    private LocalTime plannedDepartureTime;
}
