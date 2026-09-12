package cn.iocoder.yudao.module.transport.controller.app.transport.driver.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

@Schema(description = "用户 APP - 待装车任务 Response VO")
@Data
public class AppDriverPickupRespVO {

    @Schema(description = "订单编号", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long orderId;
    @Schema(description = "业务来源：CARGO 货运寄件 / POSTAL 邮快件 / PRODUCT 商城订单（司机端据此走对应确认接口）")
    private String bizType;
    @Schema(description = "业务订单号", requiredMode = Schema.RequiredMode.REQUIRED)
    private String orderNo;
    @Schema(description = "订单类型：2货运 3邮快件 4商城订单（仅司机端展示用）")
    private Integer orderType;
    @Schema(description = "货物名称")
    private String goodsName;
    @Schema(description = "货物重量(kg)")
    private BigDecimal weightKg;
    @Schema(description = "收货人")
    private String receiverName;
    @Schema(description = "收货电话")
    private String receiverMobile;
    @Schema(description = "收货地址")
    private String receiverAddress;
    @Schema(description = "快递单号（邮快件）")
    private String mailNo;
    @Schema(description = "取件码（邮快件，6位数字）")
    private String pickupCode;
}
