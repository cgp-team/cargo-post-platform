package cn.iocoder.yudao.module.transport.controller.admin.transport.topology.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单运输拓扑（一次返回 Order + Plan + Legs + Handovers + 候选方案解释 + 事件时间线）。
 * 前端不得自行拼装 Order/Driver/Vehicle/Route/Station（需求 §92）。
 */
@Schema(description = "订单运输拓扑 Response VO")
@Data
public class OrderTopologyRespVO {

    @Schema(description = "订单编号")
    private Long orderId;
    @Schema(description = "订单号")
    private String orderNo;
    @Schema(description = "订单状态")
    private Integer orderStatus;
    @Schema(description = "订单状态名")
    private String orderStatusName;
    @Schema(description = "取货站名称")
    private String originStationName;
    @Schema(description = "送达站名称")
    private String destinationStationName;

    @Schema(description = "组织方式：DIRECT / MULTI_LEG")
    private String planningMode;
    @Schema(description = "组织方式名")
    private String planningModeName;
    @Schema(description = "方案号")
    private String planNo;
    @Schema(description = "总段数")
    private Integer totalLegs;
    @Schema(description = "换乘次数")
    private Integer transferCount;
    @Schema(description = "总里程(km)")
    private BigDecimal totalDistanceKm;
    @Schema(description = "总时长(分钟)")
    private Integer totalDurationMinutes;
    @Schema(description = "方案解释（为什么直达/为什么联运）")
    private String planReason;

    @Schema(description = "候选方案对比（直达/两段/三段）")
    private List<Candidate> candidates;
    @Schema(description = "运输段")
    private List<Leg> legs;
    @Schema(description = "换乘交接")
    private List<Handover> handovers;
    @Schema(description = "事件时间线")
    private List<Timeline> timeline;

    @Schema(description = "候选方案")
    @Data
    public static class Candidate {
        private String mode;
        private String modeName;
        private Integer legCount;
        private Integer transferCount;
        private Double distanceKm;
        private Integer durationMinutes;
        private Double score;
        private String reason;
        /** 是否为最终推荐方案 */
        private Boolean chosen;
    }

    @Schema(description = "运输段详情")
    @Data
    public static class Leg {
        private Long id;
        private Integer legSequence;
        private String fromStationName;
        private String toStationName;
        @Schema(description = "起点经度")
        private Double fromLongitude;
        @Schema(description = "起点纬度")
        private Double fromLatitude;
        @Schema(description = "终点经度")
        private Double toLongitude;
        @Schema(description = "终点纬度")
        private Double toLatitude;
        private String driverName;
        private String plateNo;
        private Integer status;
        private String statusName;
        private BigDecimal distanceKm;
        private Integer durationMinutes;
        private String navigationSource;
        private LocalDateTime estimatedDeparture;
        private LocalDateTime estimatedArrival;
        private LocalDateTime actualArrival;
        private Boolean handoverRequired;
    }

    @Schema(description = "换乘交接详情")
    @Data
    public static class Handover {
        private Long id;
        private String stationName;
        private String fromDriverName;
        private String toDriverName;
        private String fromPlateNo;
        private String toPlateNo;
        private Integer itemCount;
        private Integer status;
        private String statusName;
        private LocalDateTime arrivedAt;
        private LocalDateTime handoverStartedAt;
        private LocalDateTime handoverCompletedAt;
        private String exceptionReason;
    }

    @Schema(description = "事件时间线")
    @Data
    public static class Timeline {
        private String eventType;
        private String eventTypeName;
        private LocalDateTime eventTime;
        private String operator;
        private String detail;
    }

}
