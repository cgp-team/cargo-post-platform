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
    @Schema(description = "货物照片")
    private String photoUrl;
    @Schema(description = "收货人")
    private String receiverName;
    @Schema(description = "收货电话")
    private String receiverMobile;
    @Schema(description = "收货地址")
    private String receiverAddress;
}
