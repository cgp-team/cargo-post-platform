package cn.iocoder.yudao.module.transport.controller.app.transport.driver.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 用户 APP - 司机车辆位置 Response VO。
 *
 * 同时返回真实上报位置（司机 GPS）与模拟运营引擎位置（管理端模拟驱动），
 * 前端按「模拟模式」开关选择消费哪一路：模拟模式用 simLongitude/simLatitude，
 * 否则用 longitude/latitude。
 */
@Schema(description = "用户 APP - 司机车辆位置 Response VO")
@Data
public class AppDriverPositionRespVO {

    @Schema(description = "真实上报经度（司机 GPS，REAL）")
    private Double longitude;
    @Schema(description = "真实上报纬度（司机 GPS，REAL）")
    private Double latitude;
    @Schema(description = "当前生效位置源：REAL=真实上报 / SIMULATED=模拟引擎 / NONE=无位置")
    private String dataSource;

    @Schema(description = "模拟运营是否运行中（管理端已启动该车模拟）")
    private Boolean simRunning;
    @Schema(description = "模拟引擎经度")
    private Double simLongitude;
    @Schema(description = "模拟引擎纬度")
    private Double simLatitude;
    @Schema(description = "模拟当前经停站名")
    private String currentStationName;
    @Schema(description = "模拟是否已到站（停靠作业中）")
    private Boolean arrived;
    @Schema(description = "模拟已推进秒数")
    private Long simSeconds;
    @Schema(description = "模拟总秒数")
    private Long totalSimSeconds;
}
