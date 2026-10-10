package cn.iocoder.yudao.module.transport.controller.app.transport.driver.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
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

    /**
     * 全路线 polyline（GCJ-02 坐标点序列）。
     *
     * <p>只装真实道路几何（线路走廊切片 / 高德驾车路网 / 算法服务路网）。
     * 取不到道路时这里是<b>空列表</b>，并把 {@link #routeAvailable} 置为 false ——
     * 服务端绝不下发"两点直线"，客户端也绝不能自己补线。</p>
     */
    @Schema(description = "全路线 polyline（仅真实道路；为空表示没有路线）")
    private List<Point> polyline;

    @Schema(description = "路线来源：AMAP=高德路网/线路走廊、OSM_GRAPHHOPPER / OSM_ROAD_GRAPH / VALHALLA=本地路网、"
            + "MIXED=逐段来源不同、unavailable=没有真实道路（绝不再出现 euclidean）")
    private String routeProvider;

    @Schema(description = "是否存在真实道路路线（false 时 polyline 为空，客户端须显示『路线不可用』）")
    private Boolean routeAvailable;

    @Schema(description = "路线不可用原因（routeAvailable=false 时给出，供客户端原样展示）")
    private String routeUnavailableReason;

    @Schema(description = "全路线真实道路里程(米)；null=来源没给，客户端不得用直线距离补")
    private Double routeDistanceMeters;

    @Schema(description = "全路线真实道路预计耗时(秒)；null=来源没给")
    private Integer routeDurationSeconds;

    @Schema(description = "polyline 是否覆盖全部任务段（false=中段取不到道路，折线在缺口处截断，绝不连直线补上）")
    private Boolean routeComplete;

    @Schema(description = "当前偏航：距规划 polyline 最小距离(米)，-1=无法判定")
    private Double deviationMeters;
    @Schema(description = "是否偏航（>100m，Phase 9 只报警不自动改方案）")
    private Boolean deviated;

    @Schema(description = "车辆实时位置经度")
    private Double currentLongitude;
    @Schema(description = "车辆实时位置纬度")
    private Double currentLatitude;

    @Schema(description = "下一动作：GO_STOP 去站点 / COMPLETE_ORDER 完成订单 / PICKUP 揽收 / HANDOVER 交接")
    private String nextAction;
    @Schema(description = "下一动作名称（中文）")
    private String nextActionName;
    @Schema(description = "下一站/任务点名称")
    private String nextStopName;
    /**
     * 距下一任务点<b>直线</b>米（Haversine）。
     *
     * <p>保留只为不破坏旧客户端；它不是驾驶距离。正式界面必须读
     * {@link #nextStopRoadDistanceMeters}，为 null 时显示"接口未给"。</p>
     */
    @Schema(description = "距下一任务点直线米（仅兼容/调试，不是道路里程）")
    private Double nextStopDistanceMeters;
    @Schema(description = "到下一任务点预计秒数（按固定均速推算，仅兼容/调试）")
    private Double nextStopEtaSeconds;

    @Schema(description = "距下一任务点真实道路米；null=没有道路数据")
    private Double nextStopRoadDistanceMeters;
    @Schema(description = "到下一任务点真实道路秒；null=没有道路数据")
    private Integer nextStopRoadEtaSeconds;

    @Schema(description = "当前任务段真实道路 polyline（导航用）")
    private List<Point> currentSegmentPolyline;

    @Schema(description = "经停点（运营顺序 + 业务动作，客户端按此画任务标记；顺序由调度层决定，导航层不得重排）")
    @Data
    public static class Stop {
        private Long stationId;
        private String stationName;
        private Double longitude;
        private Double latitude;
        private Integer visitSequence;
        @Schema(description = "动作类型(0出发 1接客 2送客 3派送 4揽收 5返回)")
        private Integer actionType;
        private String actionName;
        @Schema(description = "关联订单编号")
        private Long orderId;
        @Schema(description = "业务订单号")
        private String orderNo;
        @Schema(description = "数量(接客/下车=人数，揽收/派送=件数)")
        private Integer quantity;
        @Schema(description = "预计到达时间")
        private LocalDateTime estimatedArrivalTime;
        @Schema(description = "计划离站时间")
        private LocalDateTime plannedDepartureTime;
        @Schema(description = "任务状态(TaskItemStatusEnum)：0待执行 1行驶中 2已到站 3上车中 4下车中 5揽收中 6派送中 7已完成 8失败")
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
