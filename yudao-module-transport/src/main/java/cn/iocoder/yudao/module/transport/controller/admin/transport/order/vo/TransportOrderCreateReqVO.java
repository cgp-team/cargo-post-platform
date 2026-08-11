package cn.iocoder.yudao.module.transport.controller.admin.transport.order.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

@Schema(description = "管理后台 - 订单创建 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class TransportOrderCreateReqVO extends TransportOrderBaseVO {
    // --- 客运子表字段 ---
    @Schema(description = "乘客人数")
    private Integer passengerCount;
    @Schema(description = "联系人")
    private String contactName;
    @Schema(description = "联系电话")
    private String contactMobile;

    // --- 货运子表字段 ---
    @Schema(description = "货物类别")
    private String cargoCategory;
    @Schema(description = "是否生鲜")
    private Boolean freshFlag;
    @Schema(description = "货运件数")
    private Integer cargoItemCount;
    @Schema(description = "货运重量(kg)")
    private BigDecimal cargoWeightKg;
    @Schema(description = "货运体积(m³)")
    private BigDecimal cargoVolumeM3;
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

    // --- 邮快件子表字段 ---
    @Schema(description = "邮件/快递单号")
    private String mailNo;
    @Schema(description = "承运商编码")
    private String carrierCode;
    @Schema(description = "邮快件件数")
    private Integer postalItemCount;
    @Schema(description = "邮快件重量(kg)")
    private BigDecimal postalWeightKg;
}
