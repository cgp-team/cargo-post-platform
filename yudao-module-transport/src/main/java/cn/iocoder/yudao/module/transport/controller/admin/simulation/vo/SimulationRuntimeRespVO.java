package cn.iocoder.yudao.module.transport.controller.admin.simulation.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 模拟运行时完整状态 VO（地图+统计+事件一体化）
 */
@Schema(description = "模拟运行时状态 Response VO")
@Data
public class SimulationRuntimeRespVO {

    @Schema(description = "车辆编号")
    private Long vehicleId;
    @Schema(description = "车牌号")
    private String plateNo;
    @Schema(description = "方案编号")
    private Long planId;
    @Schema(description = "状态：0待启动 1运行中 2已暂停 3已完成 4已终止")
    private Integer status;
    @Schema(description = "状态名")
    private String statusName;
    @Schema(description = "倍速")
    private Double multiplier;
    @Schema(description = "已模拟秒数")
    private Long simSeconds;
    @Schema(description = "总模拟秒数")
    private Long totalSimSeconds;
    @Schema(description = "当前经度")
    private Double longitude;
    @Schema(description = "当前纬度")
    private Double latitude;
    @Schema(description = "当前速度(km/h)")
    private Double speedKmh;
    @Schema(description = "数据来源：REAL/SIMULATED/OFFLINE")
    private String dataSource;
    @Schema(description = "当前站ID")
    private Long currentStationId;
    @Schema(description = "当前站名")
    private String currentStationName;
    @Schema(description = "是否到站停靠中")
    private Boolean arrived;
    @Schema(description = "下一站ID")
    private Long nextStationId;
    @Schema(description = "下一站名")
    private String nextStationName;
    @Schema(description = "距下一站距离(km)")
    private Double distanceToNextStation;
    @Schema(description = "到下一站ETA(分钟)")
    private Double etaMinutes;
    @Schema(description = "当前场景名")
    private String scenarioName;
    @Schema(description = "路线polyline(经纬度对)")
    private List<List<Double>> polyline;
    @Schema(description = "经停站点列表")
    private List<StopInfo> stops;
    @Schema(description = "当前所在段索引")
    private Integer segmentIndex;

    @Schema(description = "经停站点信息")
    @Data
    public static class StopInfo {
        private Long stationId;
        private String stationName;
        private Double longitude;
        private Double latitude;
        private Integer visitSequence;
        private String actionName;
        private Integer status;
        private String statusName;
    }
}
