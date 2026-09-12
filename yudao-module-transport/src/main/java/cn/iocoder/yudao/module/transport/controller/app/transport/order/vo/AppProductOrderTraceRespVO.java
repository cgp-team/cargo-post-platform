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
    @Schema(description = "业务订单号（用户端展示二维码/司机扫码用）")
    private String orderNo;
    @Schema(description = "订单状态(0待发货 1已发货 2已完成 3已取消)")
    private Integer status;
    @Schema(description = "订单状态名")
    private String statusName;
    @Schema(description = "商品名称(订单首个商品)")
    private String productName;
    @Schema(description = "收货人/收货地址（司机端交付核对同一份）")
    private String receiverName;
    @Schema(description = "收货电话")
    private String receiverMobile;
    @Schema(description = "收货地址（自由文本，非站点）")
    private String receiverAddress;
    @Schema(description = "订单总额")
    private BigDecimal totalAmount;
    @Schema(description = "订单备注")
    private String remark;
    @Schema(description = "下单时间")
    private LocalDateTime createTime;
    @Schema(description = "订单明细（商品/单价/数量/小计）：订单详情页直接展示商品清单")
    private List<AppProductOrderItemRespVO> items;
    @Schema(description = "承运车牌号")
    private String vehiclePlate;
    @Schema(description = "承运司机姓名")
    private String driverName;
    @Schema(description = "承运司机电话")
    private String driverMobile;
    @Schema(description = "交付/自提站点名称（发货时=班次线路终点站）")
    private String deliverStationName;
    @Schema(description = "司机是否已到达交付站点（用户端\"司机已到达\"提醒）")
    private Boolean driverArrived;
    @Schema(description = "司机装车时间（装车拍照核验凭证）")
    private LocalDateTime loadTime;
    @Schema(description = "司机装车照片URL")
    private String loadPhotoUrl;
    @Schema(description = "司机妥投时间（交付凭证）")
    private LocalDateTime deliverTime;
    @Schema(description = "司机妥投照片URL")
    private String deliverPhotoUrl;
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
