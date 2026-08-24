package cn.iocoder.yudao.module.transport.controller.app.transport.send.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

@Schema(description = "用户 APP - 寄货路线预览 Response VO")
@Data
public class RoutePreviewRespVO {

    @Schema(description = "是否可用：false=路线不可达/服务不可用", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean available;

    @Schema(description = "真实道路距离(km)，恒为公里；不可用时为 null", requiredMode = Schema.RequiredMode.REQUIRED)
    private BigDecimal distanceKm;

    @Schema(description = "预计行驶时间(分钟)")
    private Integer durationMinutes;

    @Schema(description = "路网来源：amap=高德真实路网 / euclidean=直线估算降级", requiredMode = Schema.RequiredMode.REQUIRED)
    private String provider;

    @Schema(description = "提示：高德不可用/直线估算时的说明")
    private String warning;

}
