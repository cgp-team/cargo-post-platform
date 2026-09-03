package cn.iocoder.yudao.module.transport.controller.admin.simulation.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 模拟运行报告 VO
 */
@Schema(description = "模拟运行报告 Response VO")
@Data
public class SimulationReportRespVO {

    @Schema(description = "运行ID")
    private Long runId;
    @Schema(description = "方案ID")
    private Long planId;
    @Schema(description = "车辆ID")
    private Long vehicleId;
    @Schema(description = "车牌号")
    private String plateNo;
    @Schema(description = "司机名")
    private String driverName;
    @Schema(description = "场景名")
    private String scenarioName;
    @Schema(description = "倍速")
    private Double multiplier;
    @Schema(description = "状态")
    private Integer status;
    @Schema(description = "状态名")
    private String statusName;

    // 时间
    @Schema(description = "开始时间")
    private LocalDateTime startTime;
    @Schema(description = "结束时间")
    private LocalDateTime endTime;
    @Schema(description = "总模拟秒数")
    private Long totalSimSeconds;
    @Schema(description = "实际模拟秒数")
    private Long actualSimSeconds;

    // 路线
    @Schema(description = "总里程(km)")
    private Double totalDistanceKm;
    @Schema(description = "实际里程(km)")
    private Double actualDistanceKm;

    // 站点
    @Schema(description = "总站数")
    private Integer stationCount;
    @Schema(description = "完成站数")
    private Integer completedStationCount;

    // 订单
    @Schema(description = "总订单数")
    private Integer orderCount;
    @Schema(description = "完成订单数")
    private Integer completedOrderCount;

    // 异常
    @Schema(description = "异常事件数")
    private Integer exceptionCount;
    @Schema(description = "异常事件列表")
    private List<SimulationEventRespVO> events;

    // 运营
    @Schema(description = "准点率(%)")
    private Double onTimeRate;
    @Schema(description = "平均站停时间(秒)")
    private Double avgStopDuration;
}
