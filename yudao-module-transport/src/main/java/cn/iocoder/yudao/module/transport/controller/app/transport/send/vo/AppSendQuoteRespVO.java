package cn.iocoder.yudao.module.transport.controller.app.transport.send.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

@Schema(description = "用户 APP - 寄货试算 Response VO")
@Data
public class AppSendQuoteRespVO {

    @Schema(description = "应付金额(元)：件单价×件数 + 里程费", requiredMode = Schema.RequiredMode.REQUIRED)
    private BigDecimal amount;

    @Schema(description = "件数费(元)")
    private BigDecimal itemFee;

    @Schema(description = "里程费(元)：取送站计价里程 × 里程单价")
    private BigDecimal distanceFee;

    @Schema(description = "计价里程(km)：直线距离 × 路网系数；站点缺坐标时为 null")
    private BigDecimal distanceKm;

    @Schema(description = "件数")
    private Integer itemCount;

    @Schema(description = "件单价(元/件)")
    private BigDecimal pricePerItem;

    @Schema(description = "里程单价(元/km)")
    private BigDecimal pricePerKm;

}
