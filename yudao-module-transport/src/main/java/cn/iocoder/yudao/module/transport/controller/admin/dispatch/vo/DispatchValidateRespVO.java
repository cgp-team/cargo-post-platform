package cn.iocoder.yudao.module.transport.controller.admin.dispatch.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Schema(description = "管理后台 - 智能派单前约束校验 Response VO（对应故事「约束校验/运力预警」）")
@Data
public class DispatchValidateRespVO {

    @Schema(description = "订单统计（订单池按类别汇总）")
    private OrderStats orderStats;

    @Schema(description = "可用车辆容量")
    private List<VehicleItem> vehicles;

    @Schema(description = "运力校验（总容量 vs 总需求，超出即预警）")
    private CapacityCheck capacityCheck;

    @Schema(description = "站点作业标记（上车绿点/下车红点/派送/揽收，供地图可视化）")
    private List<Marker> markers;

    @Schema(description = "客运上下车时序问题（同上站/缺站等）")
    private List<TimeSeqIssue> timeSeqIssues;

    @Schema(description = "订单统计")
    @Data
    public static class OrderStats {
        @Schema(description = "客运人数")
        private Integer passengerCount;
        @Schema(description = "派送订单数（场站→站点卸货）")
        private Integer deliveryCount;
        @Schema(description = "揽收订单数（站点收货→场站）")
        private Integer pickupCount;
        @Schema(description = "包裹总件数（派送+揽收）")
        private Integer parcelCount;
    }

    @Schema(description = "车辆容量")
    @Data
    public static class VehicleItem {
        private Long vehicleId;
        @Schema(description = "车牌号")
        private String plateNo;
        @Schema(description = "核定载客数")
        private Integer passengerCapacity;
        @Schema(description = "货仓件数上限")
        private Integer cargoCapacity;
    }

    @Schema(description = "运力校验")
    @Data
    public static class CapacityCheck {
        @Schema(description = "总载客上限（全部车辆之和）")
        private Integer totalPassengerCapacity;
        @Schema(description = "总载货上限（全部车辆之和）")
        private Integer totalCargoCapacity;
        @Schema(description = "载客超出数（>0 即超员，0 表示不超）")
        private Integer passengerExceed;
        @Schema(description = "载货超出数（>0 即超载，0 表示不超）")
        private Integer cargoExceed;
        @Schema(description = "运力不足（超员或超载任一为 true，故事「运力不足预警」）")
        private Boolean overCapacity;
    }

    @Schema(description = "站点作业标记")
    @Data
    public static class Marker {
        private Long stationId;
        @Schema(description = "站点名称")
        private String stationName;
        @Schema(description = "经度")
        private Double longitude;
        @Schema(description = "纬度")
        private Double latitude;
        @Schema(description = "作业动作集合：BOARD 上车 / ALIGHT 下车 / DELIVER 派送 / PICKUP 揽收")
        private List<String> types;
        @Schema(description = "涉及订单数")
        private Integer orderCount;
    }

    @Schema(description = "客运时序问题")
    @Data
    public static class TimeSeqIssue {
        private Long orderId;
        private String orderNo;
        @Schema(description = "问题描述")
        private String issue;
    }

}
