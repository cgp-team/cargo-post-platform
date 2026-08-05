package cn.iocoder.yudao.module.transport.controller.admin.transport.shift.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalTime;

@Schema(description = "管理后台 - 班次 Base VO")
@Data
public class ShiftBaseVO {
    @Schema(description = "班次编码", requiredMode = Schema.RequiredMode.REQUIRED)
    private String shiftCode;
    @Schema(description = "线路编号", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long routeId;
    @Schema(description = "计划发车时间", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalTime plannedDepartureTime;
    @Schema(description = "计划时长(分钟)")
    private Integer plannedDurationMinutes;
    @Schema(description = "班次状态")
    private Integer status;
}
