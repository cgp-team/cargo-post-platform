package cn.iocoder.yudao.module.transport.controller.app.transport.driver.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 司机端路线 Response VO：完整任务段的地图数据（运营顺序来自 DispatchPlan，道路轨迹来自 RoadSegments）。
 */
@Schema(description = "用户 APP - 司机路线 Response VO")
@Data
public class AppDriverRouteRespVO {

    @Schema(description = "方案编号")
    private Long planId;
    @Schema(description = "车辆编号")
    private Long vehicleId;

    @Schema(description = "有序经停点（运营顺序）")
    private List<Stop> stops;

    /** 全路线 polyline（GCJ-02 坐标点序列，真实道路；euclidean 兜底为两点直线） */
    @Schema(description = "全路线 polyline（真实道路）")
    private List<Point> polyline;

    @Schema(description = "路线来源：amap=高德真实道路 / euclidean=直线兜底")
    private String routeProvider;

    @Schema(description = "当前偏航：距规划 polyline 最小距离(米)，-1=无法判定")
    private Double deviationMeters;
    @Schema(description = "是否偏航（>100m，Phase 9 只报警不自动改方案）")
    private Boolean deviated;

    @Schema(description = "经停点")
    @Data
    public static class Stop {
        private Long stationId;
        private String stationName;
        private Double longitude;
        private Double latitude;
        private Integer visitSequence;
        private String actionName;
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
