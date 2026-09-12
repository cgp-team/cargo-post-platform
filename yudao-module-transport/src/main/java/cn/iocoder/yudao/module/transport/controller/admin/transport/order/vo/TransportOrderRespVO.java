package cn.iocoder.yudao.module.transport.controller.admin.transport.order.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Schema(description = "管理后台 - 订单 Response VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class TransportOrderRespVO extends TransportOrderBaseVO {
    @Schema(description = "订单编号", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long id;
    @Schema(description = "订单号")
    private String orderNo;
    @Schema(description = "取货站点名称")
    private String pickupStationName;
    @Schema(description = "送达站点名称")
    private String deliveryStationName;
    @Schema(description = "创建时间")
    private LocalDateTime createTime;
    // --- 货运寄货信息（来自货运子表） ---
    @Schema(description = "货物名称")
    private String goodsName;
    @Schema(description = "货物备注")
    private String goodsNote;
    @Schema(description = "货物重量（kg）")
    private BigDecimal cargoWeightKg;
    @Schema(description = "货物照片（村民寄货拍）")
    private String photoUrl;
    @Schema(description = "司机收件照片（装车强制拍，快递总站核对凭证）")
    private String driverPhotoUrl;
    @Schema(description = "货运审核状态：0待审核 1已通过 2已拒绝")
    private Integer auditStatus;
    @Schema(description = "拒绝原因")
    private String rejectReason;
    @Schema(description = "收货人")
    private String receiverName;
    @Schema(description = "收货电话")
    private String receiverMobile;
    @Schema(description = "收货地址")
    private String receiverAddress;
    // --- 寄货服务链路（村民在哪寄 / 车辆去哪接 / 怎么交接）：后台订单管理与审核页直接可见 ---
    @Schema(description = "用户原始寄货地址（小程序定位或手填，如 重庆邮电大学明志苑）")
    private String originalAddress;
    @Schema(description = "用户原始纬度(GCJ-02)")
    private BigDecimal originalLatitude;
    @Schema(description = "用户原始经度(GCJ-02)")
    private BigDecimal originalLongitude;
    @Schema(description = "取货服务方式：DOOR_PICKUP 上门 / NEAREST_STATION 最近站点 / CUSTOMER_TO_STATION 客户送站 / STATION_TO_STATION 站到站")
    private String pickupServiceMode;
    @Schema(description = "送达服务方式")
    private String deliveryServiceMode;
    @Schema(description = "交接服务站点编号")
    private Long servicePointStationId;
    @Schema(description = "交接服务站点名称")
    private String servicePointStationName;
    @Schema(description = "承运审核结果：0待审核 1通过 2需客户操作 3需人工审核 4不承运")
    private Integer reviewStatus;
    @Schema(description = "承运审核原因码（逗号分隔，如 USER_LOCATION_UNREACHABLE）")
    private String reviewReasonCodes;
    @Schema(description = "货物类别")
    private String cargoCategory;
    @Schema(description = "是否生鲜/需冷链")
    private Boolean freshFlag;
    @Schema(description = "货物件数")
    private Integer cargoItemCount;
    @Schema(description = "货物体积(m³)")
    private BigDecimal cargoVolumeM3;
    // --- 邮快件信息（来自邮快件子表） ---
    @Schema(description = "快递单号")
    private String mailNo;
    @Schema(description = "取件码（6位数字）")
    private String pickupCode;
    @Schema(description = "取件状态：0待取件 1已取件")
    private Integer pickupStatus;
}
