package cn.iocoder.yudao.module.transport.controller.app.transport.order.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

@Schema(description = "用户 APP - 商城订单明细 Response VO")
@Data
public class AppProductOrderItemRespVO {

    @Schema(description = "商品编号", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long productId;
    @Schema(description = "商品名称（下单快照）", requiredMode = Schema.RequiredMode.REQUIRED)
    private String productName;
    @Schema(description = "商品图(emoji)")
    private String productImage;
    @Schema(description = "下单单价")
    private BigDecimal productPrice;
    @Schema(description = "购买数量", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer quantity;
    @Schema(description = "小计金额")
    private BigDecimal amount;
}
