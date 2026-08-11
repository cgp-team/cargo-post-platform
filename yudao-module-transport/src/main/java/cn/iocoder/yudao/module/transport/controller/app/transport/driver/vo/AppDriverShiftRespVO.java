package cn.iocoder.yudao.module.transport.controller.app.transport.driver.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalTime;
import java.util.List;

@Schema(description = "用户 APP - 今日班次 Response VO")
@Data
public class AppDriverShiftRespVO {

    @Schema(description = "班次编号", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long shiftId;
    @Schema(description = "班次编码", requiredMode = Schema.RequiredMode.REQUIRED)
    private String shiftCode;
    @Schema(description = "线路编号")
    private Long routeId;
    @Schema(description = "线路名称")
    private String routeName;
    @Schema(description = "计划发车时间", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalTime plannedDepartureTime;
    @Schema(description = "计划时长(分钟)")
    private Integer plannedDurationMinutes;
    @Schema(description = "班次状态(0未发车 1在途 2已完成)")
    private Integer status;
    @Schema(description = "班次状态名")
    private String statusName;
    @Schema(description = "经停站点序列")
    private List<AppDriverStationRespVO> stops;
}
