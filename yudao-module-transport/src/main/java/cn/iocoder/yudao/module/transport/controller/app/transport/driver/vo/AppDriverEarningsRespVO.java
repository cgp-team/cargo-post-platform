package cn.iocoder.yudao.module.transport.controller.app.transport.driver.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Schema(description = "用户 APP - 司机运营统计 Response VO")
@Data
public class AppDriverEarningsRespVO {

    @Schema(description = "今日计划班次（启用班次数）", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long shiftCount;
    @Schema(description = "今日货运订单数", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long todayOrders;
    @Schema(description = "今日货运订单总额", requiredMode = Schema.RequiredMode.REQUIRED)
    private BigDecimal todayAmount;
    @Schema(description = "累计货运订单数", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long totalOrders;
    @Schema(description = "累计货运订单总额", requiredMode = Schema.RequiredMode.REQUIRED)
    private BigDecimal totalAmount;
    @Schema(description = "待装车任务数", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long pendingCount;
    @Schema(description = "最近货运订单明细")
    private List<AppDriverEarningsRecordRespVO> records;
}
