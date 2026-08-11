package cn.iocoder.yudao.module.transport.controller.app.transport.order.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Schema(description = "用户 APP - 商城订单创建 Request VO")
@Data
public class AppProductOrderCreateReqVO {

    @Schema(description = "商品编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "商品编号不能为空")
    private Long productId;

    @Schema(description = "购买数量", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "购买数量不能为空")
    @Min(value = 1, message = "购买数量不能小于 1")
    private Integer quantity;

    @Schema(description = "收货人", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "收货人不能为空")
    private String receiverName;

    @Schema(description = "收货电话", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "收货电话不能为空")
    private String receiverMobile;

    @Schema(description = "收货地址", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "收货地址不能为空")
    private String receiverAddress;

    @Schema(description = "订单备注")
    private String remark;

    @Schema(description = "购买会员手机号（下单时快照）")
    private String userMobile;
}
