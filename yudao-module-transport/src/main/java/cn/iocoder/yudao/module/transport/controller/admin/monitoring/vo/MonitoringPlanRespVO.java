package cn.iocoder.yudao.module.transport.controller.admin.monitoring.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 后台监控 - 车辆任务段详情（后台与司机共享同一 DispatchPlan + RoadSegments，Phase 11）。
 */
@Schema(description = "管理后台 - 车辆任务段详情 Response VO")
@Data
public class MonitoringPlanRespVO {

    @Schema(description = "车辆编号")
    private Long vehicleId;
    @Schema(description = "车牌号")
    private String plateNo;
    @Schema(description = "方案编号")
    private Long planId;
    @Schema(description = "方案状态名")
    private String planStatusName;
    @Schema(description = "任务段窗口开始")
    private LocalDateTime taskWindowStart;
    @Schema(description = "任务段窗口结束")
    private LocalDateTime taskWindowEnd;

    @Schema(description = "当前车辆位置（经度）")
    private Double longitude;
    @Schema(description = "当前车辆位置（纬度）")
    private Double latitude;
    @Schema(description = "位置数据来源：REAL / NO_LOCATION")
    private String dataSource;

    @Schema(description = "路线来源：amap=高德真实道路 / euclidean=直线兜底")
    private String routeProvider;

    /** 全路线 polyline（真实道路；euclidean 兜底为两点直线） */
    @Schema(description = "全路线 polyline")
    private List<Point> polyline;

    @Schema(description = "有序任务段经停（Operational Stops，含乘客/货运动作）")
    private List<Stop> stops;

    @Schema(description = "经停点")
    @Data
    public static class Stop {
        private Long stationId;
        private String stationName;
        private Double longitude;
        private Double latitude;
        private Integer visitSequence;
        private String actionName;
        private Long orderId;
        private String orderNo;
        private Integer quantity;
        private LocalDateTime estimatedArrivalTime;
        private LocalDateTime plannedDepartureTime;
        private Integer status;
        private String statusName;
    }

    @Schema(description = "坐标点")
    @Data
    public static class Point {
        private Double longitude;
        private Double latitude;

        public Point(Double longitude, Double latitude) {
            this.longitude = longitude;
            this.latitude = latitude;
        }
    }
}
