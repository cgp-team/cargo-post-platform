package cn.iocoder.yudao.module.transport.controller.admin.transport.order.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

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
    @Schema(description = "货物照片（村民寄货拍）")
    private String photoUrl;
    @Schema(description = "司机收件照片（装车强制拍，快递总站核对凭证）")
    private String driverPhotoUrl;
    @Schema(description = "收货人")
    private String receiverName;
    @Schema(description = "收货电话")
    private String receiverMobile;
    @Schema(description = "收货地址")
    private String receiverAddress;
    // --- 邮快件信息（来自邮快件子表） ---
    @Schema(description = "快递单号")
    private String mailNo;
    @Schema(description = "取件码（6位数字）")
    private String pickupCode;
    @Schema(description = "取件状态：0待取件 1已取件")
    private Integer pickupStatus;
}
