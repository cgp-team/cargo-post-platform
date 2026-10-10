package cn.iocoder.yudao.module.transport.controller.app.transport.send.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "用户 APP - 多段运输进度 Response VO")
@Data
public class AppSendLegRespVO {

    @Schema(description = "段序号（1=第一段…）")
    private Integer legSequence;

    @Schema(description = "起始站点名称")
    private String fromStationName;

    @Schema(description = "目的站点名称")
    private String toStationName;

    @Schema(description = "起始站点经度（站点档案无坐标时为 null，前端不得据此画直线补线）")
    private Double fromLongitude;

    @Schema(description = "起始站点纬度")
    private Double fromLatitude;

    @Schema(description = "目的站点经度（站点档案无坐标时为 null）")
    private Double toLongitude;

    @Schema(description = "目的站点纬度")
    private Double toLatitude;

    /** 取值见 TransportLegStatusEnum（0 已规划 … 11 已完成，99 异常），不是订单状态码 */
    @Schema(description = "状态：见 TransportLegStatusEnum（0已规划 1已分配 2司机已接单 3等待发车 4前往起点 "
            + "5已到达起点 6装货中 7运输中 8已到达终点 9交接中 10派送中 11已完成 99异常）")
    private Integer status;

    @Schema(description = "状态名")
    private String statusName;

    @Schema(description = "预计出发时间")
    private LocalDateTime estimatedDeparture;

    @Schema(description = "预计到达时间")
    private LocalDateTime estimatedArrival;

    @Schema(description = "实际到达时间")
    private LocalDateTime actualArrival;

    @Schema(description = "承运司机姓名")
    private String driverName;

    @Schema(description = "承运车牌号")
    private String plateNo;

    @Schema(description = "本段里程(km)：未测算时为 null，前端不得显示为 0")
    private BigDecimal distanceKm;

    @Schema(description = "本段计划耗时(分钟)，是计划口径不是剩余时间；未测算时为 null")
    private Integer durationMinutes;

    @Schema(description = "里程/时长来源：AMAP 真实道路 / ESTIMATED 估算（估算时前端必须标注）")
    private String navigationSource;

    @Schema(description = "本段真实道路轨迹（GCJ-02）；transport_leg.navigation_polyline 为空或非法时为 null，"
            + "前端只能显示「路线不可用」，不得用起终点坐标补直线")
    private List<RoadPoint> navigationPolyline;

    @Schema(description = "本段是否需要换乘交接（非最终段 true）")
    private Boolean handoverRequired;

    @Schema(description = "道路轨迹点")
    @Data
    public static class RoadPoint {
        @Schema(description = "经度")
        private Double longitude;
        @Schema(description = "纬度")
        private Double latitude;
    }

}
