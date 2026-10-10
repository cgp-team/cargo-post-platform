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

    @Schema(description = "路线来源：AMAP / OSM_GRAPHHOPPER / OSM_ROAD_GRAPH / VALHALLA / MIXED / none（直线兜底不存在于此接口）")
    private String routeProvider;

    @Schema(description = "折线是否覆盖全部任务段；false=中途某段没有真实道路，折线已在那里截断（未补线）")
    private Boolean routeComplete;

    /** 全路线 polyline：逐段真实道路；某段缺路网则整条线在该段起点前截断，绝不两点连线 */
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
