package cn.iocoder.yudao.module.transport.controller.app.transport.order.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "用户 APP - 商城订单 Response VO")
@Data
public class AppProductOrderRespVO {

    @Schema(description = "订单编号", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long id;
    @Schema(description = "业务订单号", requiredMode = Schema.RequiredMode.REQUIRED)
    private String orderNo;
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
    @Schema(description = "下单时间")
    private LocalDateTime createTime;
    @Schema(description = "订单明细")
    private List<AppProductOrderItemRespVO> items;
}
