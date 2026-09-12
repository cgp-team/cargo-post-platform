package cn.iocoder.yudao.module.transport.controller.app.transport.driver.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Schema(description = "用户 APP - 司机上报位置 Request VO")
@Data
public class AppDriverLocationReqVO {

    @Schema(description = "司机编号（可选，仅用于与登录态校验，身份以登录会员为准）", example = "1")
    private Long driverId;

    @Schema(description = "班次编号（可选：算法派单/运输段模式的司机可能没有班次，缺省用当前活跃段的班次兜底）", example = "1")
    private Long shiftId;

    @Schema(description = "经度", requiredMode = Schema.RequiredMode.REQUIRED, example = "120.1234567")
    @NotNull(message = "经度不能为空")
    private BigDecimal longitude;

    @Schema(description = "纬度", requiredMode = Schema.RequiredMode.REQUIRED, example = "30.1234567")
    @NotNull(message = "纬度不能为空")
    private BigDecimal latitude;

    @Schema(description = "速度(km/h)", example = "42.5")
    private BigDecimal speedKmh;

}
