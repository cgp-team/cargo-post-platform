package cn.iocoder.yudao.module.transport.controller.admin.transport.order.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "管理后台 - 商城订单 Response VO")
@Data
public class ProductOrderRespVO {

    @Schema(description = "订单编号", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long id;
    @Schema(description = "业务订单号", requiredMode = Schema.RequiredMode.REQUIRED)
    private String orderNo;
    @Schema(description = "购买会员编号")
    private Long userId;
    @Schema(description = "购买会员手机号")
    private String userMobile;
    @Schema(description = "订单总额")
    private BigDecimal totalAmount;
    @Schema(description = "订单状态(0待发货 1已发货 2已完成 3已取消)")
    private Integer status;
    @Schema(description = "订单状态名")
    private String statusName;
    @Schema(description = "收货人")
    private String receiverName;
    @Schema(description = "收货电话")
    private String receiverMobile;
    @Schema(description = "收货地址")
    private String receiverAddress;
    @Schema(description = "订单备注")
    private String remark;
    // --- 司机执行闭环（商城订单同样走 装车 → 到站 → 妥投） ---
    @Schema(description = "承运车辆编号")
    private Long vehicleId;
    @Schema(description = "承运车辆车牌")
    private String vehiclePlate;
    @Schema(description = "承运班次编号")
    private Long shiftId;
    @Schema(description = "承运班次编码")
    private String shiftCode;
    @Schema(description = "承运司机编号")
    private Long driverId;
    @Schema(description = "承运司机姓名")
    private String driverName;
    @Schema(description = "承运司机电话")
    private String driverMobile;
    @Schema(description = "交付/自提站点名称（发货时=班次线路终点站）")
    private String deliverStationName;
    @Schema(description = "司机装车照片URL（装车核验凭证）")
    private String loadPhotoUrl;
    @Schema(description = "司机装车时间")
    private LocalDateTime loadTime;
    @Schema(description = "司机妥投照片URL（交付凭证）")
    private String deliverPhotoUrl;
    @Schema(description = "司机妥投时间")
    private LocalDateTime deliverTime;
    @Schema(description = "下单时间")
    private LocalDateTime createTime;
    @Schema(description = "订单明细")
    private List<ProductOrderItemRespVO> items;
}
