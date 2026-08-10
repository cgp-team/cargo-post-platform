package cn.iocoder.yudao.module.transport.controller.admin.monitoring.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Schema(description = "管理后台 - 车辆监控地图数据 Response VO")
@Data
public class MonitoringMapDataRespVO {

    @Schema(description = "站点列表")
    private List<Station> stations;

    @Schema(description = "线路列表（含站点序列坐标，供绘制折线）")
    private List<Route> routes;

    @Schema(description = "站点")
    @Data
    public static class Station {
        @Schema(description = "站点编号")
        private Long id;
        @Schema(description = "站点编码")
        private String stationCode;
        @Schema(description = "站点名称")
        private String stationName;
        @Schema(description = "站点层级")
        private Integer stationLevel;
        @Schema(description = "经度")
        private Double longitude;
        @Schema(description = "纬度")
        private Double latitude;
        @Schema(description = "地址")
        private String address;
    }

    @Schema(description = "线路")
    @Data
    public static class Route {
        @Schema(description = "线路编号")
        private Long id;
        @Schema(description = "线路编码")
        private String routeCode;
        @Schema(description = "线路名称")
        private String routeName;
        @Schema(description = "线路里程(km)")
        private Double distanceKm;
        @Schema(description = "途经站点（按访问顺序）")
        private List<Point> points;
    }

    @Schema(description = "线路途经点")
    @Data
    public static class Point {
        @Schema(description = "访问顺序")
        private Integer sequenceNo;
        @Schema(description = "站点编号")
        private Long stationId;
        @Schema(description = "站点名称")
        private String stationName;
        @Schema(description = "经度")
        private Double longitude;
        @Schema(description = "纬度")
        private Double latitude;
        @Schema(description = "从线路起点计划分钟数（累计）")
        private Integer plannedMinutes;
    }
}
