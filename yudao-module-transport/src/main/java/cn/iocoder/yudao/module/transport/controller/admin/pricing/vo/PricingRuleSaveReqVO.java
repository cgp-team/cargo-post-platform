package cn.iocoder.yudao.module.transport.controller.admin.pricing.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Schema(description = "管理后台 - 运输计价规则保存 Request VO")
@Data
public class PricingRuleSaveReqVO {

    @Schema(description = "客运人公里单价(元)", requiredMode = Schema.RequiredMode.REQUIRED, example = "1.00")
    @NotNull(message = "客运人公里单价不能为空")
    @DecimalMin(value = "0.01", message = "客运人公里单价必须大于 0")
    private BigDecimal passengerPricePerKm;

    @Schema(description = "货运件单价(元)", requiredMode = Schema.RequiredMode.REQUIRED, example = "5.00")
    @NotNull(message = "货运件单价不能为空")
    @DecimalMin(value = "0.01", message = "货运件单价必须大于 0")
    private BigDecimal cargoPricePerItem;

    @Schema(description = "邮快件件单价(元)", requiredMode = Schema.RequiredMode.REQUIRED, example = "3.00")
    @NotNull(message = "邮快件件单价不能为空")
    @DecimalMin(value = "0.01", message = "邮快件件单价必须大于 0")
    private BigDecimal postalPricePerItem;

    @Schema(description = "车辆公里成本(元)", requiredMode = Schema.RequiredMode.REQUIRED, example = "2.50")
    @NotNull(message = "车辆公里成本不能为空")
    @DecimalMin(value = "0", message = "车辆公里成本不能为负")
    private BigDecimal vehicleCostPerKm;

    @Schema(description = "班线平均时速(km/h)，ETA 与耗时估算口径", requiredMode = Schema.RequiredMode.REQUIRED, example = "25")
    @NotNull(message = "平均时速不能为空")
    @DecimalMin(value = "0.1", message = "平均时速必须大于 0，否则 ETA 会算成无穷大")
    private BigDecimal avgSpeedKmh;

    @Schema(description = "作业站停站分钟（接/送/派/揽）", requiredMode = Schema.RequiredMode.REQUIRED, example = "3")
    @NotNull(message = "停站分钟不能为空")
    @Min(value = 0, message = "停站分钟不能为负")
    @Max(value = 180, message = "停站分钟超过 180 一定是填错了")
    private Integer stopServiceMinutes;
}
