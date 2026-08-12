package cn.iocoder.yudao.module.transport.controller.admin.monitoring.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
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

    @Schema(description = "执行状态：0 未发车，1 在途，2 已完成（有执行记录时以司机端真实落库为准）")
    private Integer status;

    @Schema(description = "执行司机编号")
    private Long driverId;
    @Schema(description = "执行司机姓名")
    private String driverName;
    @Schema(description = "执行车辆编号")
    private Long vehicleId;
    @Schema(description = "执行车牌号")
    private String plateNo;
    @Schema(description = "当前所在站点编号")
    private Long currentStationId;
    @Schema(description = "当前所在站点名称")
    private String currentStationName;
    @Schema(description = "已装车件数")
    private Integer loadedCount;
    @Schema(description = "实际发车时间")
    private LocalDateTime departTime;
    @Schema(description = "到达终点时间")
    private LocalDateTime arriveTime;
}
