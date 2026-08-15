package cn.iocoder.yudao.module.transport.controller.app.transport.bus.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Schema(description = "用户 APP - 实时公交线路 Response VO（含经停点与该线车辆）")
@Data
public class AppBusLineRespVO {

    @Schema(description = "线路编号")
    private Long routeId;

    @Schema(description = "线路编码")
    private String routeCode;

    @Schema(description = "线路名称")
    private String routeName;

    @Schema(description = "起点站")
    private String startStation;

    @Schema(description = "终点站")
    private String endStation;

    @Schema(description = "线路里程(km)")
    private Double distanceKm;

    @Schema(description = "途经站点（按访问顺序，含坐标，供绘制线路轨迹）")
    private List<Point> points;

    @Schema(description = "该线路当前在线车辆")
    private List<AppBusRespVO> buses;

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
