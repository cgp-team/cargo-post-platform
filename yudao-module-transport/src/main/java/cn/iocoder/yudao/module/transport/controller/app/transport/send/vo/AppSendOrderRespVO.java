package cn.iocoder.yudao.module.transport.controller.app.transport.send.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Schema(description = "用户 APP - 寄货订单 Response VO")
@Data
public class AppSendOrderRespVO {

    @Schema(description = "订单编号", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long id;
    @Schema(description = "业务订单号", requiredMode = Schema.RequiredMode.REQUIRED)
    private String orderNo;
    @Schema(description = "订单状态(0待调度 1已入池 2已分配 3已发车 4已完成 5已取消)")
    private Integer status;
    @Schema(description = "订单状态名")
    private String statusName;
    @Schema(description = "货物名称")
    private String goodsName;
    @Schema(description = "货物重量(kg)")
    private BigDecimal goodsWeight;
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
    @Schema(description = "下单时间")
    private LocalDateTime createTime;
}
