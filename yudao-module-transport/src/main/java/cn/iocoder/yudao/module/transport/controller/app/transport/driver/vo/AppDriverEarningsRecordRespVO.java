package cn.iocoder.yudao.module.transport.controller.app.transport.driver.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Schema(description = "用户 APP - 司机运营统计明细 Response VO")
@Data
public class AppDriverEarningsRecordRespVO {

    @Schema(description = "业务订单号", requiredMode = Schema.RequiredMode.REQUIRED)
    private String orderNo;
    @Schema(description = "货物名称")
    private String goodsName;
    @Schema(description = "货物重量(kg)")
    private BigDecimal weightKg;
    @Schema(description = "订单金额")
    private BigDecimal totalAmount;
    @Schema(description = "订单状态(0待调度 1已入池 2已分配 3已发车 4已完成 5已取消)")
    private Integer status;
    @Schema(description = "订单状态名")
    private String statusName;
    @Schema(description = "下单时间")
    private LocalDateTime createTime;
}
