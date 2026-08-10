package cn.iocoder.yudao.module.transport.controller.admin.monitoring.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalTime;

@Schema(description = "管理后台 - 班次执行状态 Response VO")
@Data
public class MonitoringShiftRespVO {

    @Schema(description = "班次编号")
    private Long shiftId;

    @Schema(description = "班次编码")
    private String shiftCode;

    @Schema(description = "线路名称")
    private String routeName;

    @Schema(description = "计划发车时间")
    private LocalTime plannedDepartureTime;

    @Schema(description = "计划时长(分钟)")
    private Integer plannedDurationMinutes;

    @Schema(description = "执行状态：0 未发车，1 在途，2 已完成")
    private Integer status;
}
