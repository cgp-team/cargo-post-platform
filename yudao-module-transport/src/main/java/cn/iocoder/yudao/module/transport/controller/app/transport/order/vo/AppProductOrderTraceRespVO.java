package cn.iocoder.yudao.module.transport.controller.app.transport.order.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "用户 APP - 商城订单溯源 Response VO")
@Data
public class AppProductOrderTraceRespVO {
    @Schema(description = "订单编号", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long orderId;
    @Schema(description = "商品名称(订单首个商品)")
    private String productName;
    @Schema(description = "承运车牌号")
    private String vehiclePlate;
    @Schema(description = "承运班次编码")
    private String shiftCode;
    @Schema(description = "线路名称")
    private String routeName;
    @Schema(description = "起点站")
    private String startStation;
    @Schema(description = "终点站")
    private String endStation;
    @Schema(description = "班次线路站点序列")
    private List<Point> points;
    @Schema(description = "车辆轨迹(当天,时间升序)")
    private List<TrackPoint> track;
    @Schema(description = "最新位置经度")
    private BigDecimal currentLongitude;
    @Schema(description = "最新位置纬度")
    private BigDecimal currentLatitude;
    @Schema(description = "最新上报时间")
    private LocalDateTime lastReportTime;

    @Schema(description = "线路站点")
    @Data
    public static class Point {
        @Schema(description = "访问顺序")
        private Integer sequenceNo;
        @Schema(description = "站点名称")
        private String stationName;
        @Schema(description = "经度")
        private BigDecimal longitude;
        @Schema(description = "纬度")
        private BigDecimal latitude;
        @Schema(description = "从线路起点计划分钟数")
        private Integer plannedMinutes;
    }

    @Schema(description = "轨迹点")
    @Data
    public static class TrackPoint {
        @Schema(description = "经度")
        private BigDecimal longitude;
        @Schema(description = "纬度")
        private BigDecimal latitude;
        @Schema(description = "速度(km/h)")
        private BigDecimal speedKmh;
        @Schema(description = "上报时间")
        private LocalDateTime reportTime;
    }
}
