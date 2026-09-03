package cn.iocoder.yudao.module.transport.controller.admin.developer.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 开发者中心统计数据 VO
 */
@Schema(description = "开发者中心统计数据 Response VO")
@Data
public class DeveloperStatisticsRespVO {

    @Schema(description = "开发者模式是否开启")
    private Boolean developerMode;

    @Schema(description = "模拟环境是否启用")
    private Boolean simulationEnabled;

    @Schema(description = "当前运行中的模拟任务数")
    private Integer runningSimulations;

    @Schema(description = "模拟车辆数")
    private Integer simulatedVehicles;

    @Schema(description = "测试数据总量")
    private Integer testDataCount;

    @Schema(description = "历史模拟任务数")
    private Integer totalSimulations;

    @Schema(description = "站点总数")
    private Integer stationCount;

    @Schema(description = "车辆总数")
    private Integer vehicleCount;

    @Schema(description = "司机总数")
    private Integer driverCount;

    @Schema(description = "订单总数")
    private Integer orderCount;
}
