package cn.iocoder.yudao.module.transport.controller.admin.pricing.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

@Schema(description = "管理后台 - 运输计价规则 Response VO（单行配置）")
@Data
public class PricingRuleRespVO {

    @Schema(description = "规则编号；库里没有配置行时为 null", example = "1")
    private Long id;

    @Schema(description = "客运人公里单价(元)", requiredMode = Schema.RequiredMode.REQUIRED)
    private BigDecimal passengerPricePerKm;

    @Schema(description = "货运件单价(元)", requiredMode = Schema.RequiredMode.REQUIRED)
    private BigDecimal cargoPricePerItem;

    @Schema(description = "邮快件件单价(元)", requiredMode = Schema.RequiredMode.REQUIRED)
    private BigDecimal postalPricePerItem;

    @Schema(description = "车辆公里成本(元)", requiredMode = Schema.RequiredMode.REQUIRED)
    private BigDecimal vehicleCostPerKm;

    @Schema(description = "班线平均时速(km/h)", requiredMode = Schema.RequiredMode.REQUIRED)
    private BigDecimal avgSpeedKmh;

    @Schema(description = "作业站停站分钟", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer stopServiceMinutes;

    /**
     * false = 库里**没有**配置行，上面六个数是服务侧兜底默认值。
     * 不带这个旗标，界面就会把默认值念成"运营配过的值"（本仓 D-090/#57 同族）。
     */
    @Schema(description = "这六个数是否真来自库里配置行（false = 全默认值兜底）",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "false")
    private Boolean configured;
}
