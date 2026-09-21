package cn.iocoder.yudao.module.transport.controller.app.transport.driver.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 用户 APP - 司机车辆位置 Response VO。
 *
 * 仅返回真实上报位置（司机 GPS）。
 */
@Schema(description = "用户 APP - 司机车辆位置 Response VO")
@Data
public class AppDriverPositionRespVO {

    @Schema(description = "真实上报经度（司机 GPS，REAL）")
    private Double longitude;
    @Schema(description = "真实上报纬度（司机 GPS，REAL）")
    private Double latitude;
    @Schema(description = "当前生效位置源：REAL=真实上报 / NONE=无位置")
    private String dataSource;
}
