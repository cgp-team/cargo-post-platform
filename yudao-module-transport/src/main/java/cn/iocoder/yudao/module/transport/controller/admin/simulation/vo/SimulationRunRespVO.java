package cn.iocoder.yudao.module.transport.controller.admin.simulation.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 模拟运行历史 VO
 */
@Schema(description = "模拟运行历史 Response VO")
@Data
public class SimulationRunRespVO {

    @Schema(description = "运行ID")
    private Long id;
    @Schema(description = "方案ID")
    private Long planId;
    @Schema(description = "车辆ID")
    private Long vehicleId;
    @Schema(description = "车牌号")
    private String plateNo;
    @Schema(description = "场景名")
    private String scenarioName;
    @Schema(description = "倍速")
    private Double multiplier;
    @Schema(description = "状态：0已创建 1运行中 2已暂停 3已完成 4已终止")
    private Integer status;
    @Schema(description = "状态名")
    private String statusName;
    @Schema(description = "开始时间")
    private LocalDateTime startTime;
    @Schema(description = "结束时间")
    private LocalDateTime endTime;
    @Schema(description = "总模拟秒数")
    private Long totalSimSeconds;
    @Schema(description = "实际模拟秒数")
    private Long actualSimSeconds;
    @Schema(description = "总站数")
    private Integer stationCount;
    @Schema(description = "完成站数")
    private Integer completedStationCount;
    @Schema(description = "异常事件数")
    private Integer exceptionCount;
    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
