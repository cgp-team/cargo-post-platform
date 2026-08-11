package cn.iocoder.yudao.module.transport.controller.app.transport.driver.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "用户 APP - 司机班次经停站点 Response VO")
@Data
public class AppDriverStationRespVO {

    @Schema(description = "站点编号", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long stationId;
    @Schema(description = "站点名称", requiredMode = Schema.RequiredMode.REQUIRED)
    private String stationName;
    @Schema(description = "访问顺序")
    private Integer sequenceNo;
    @Schema(description = "距线路起点计划分钟数")
    private Integer plannedMinutes;
    @Schema(description = "经度")
    private Double longitude;
    @Schema(description = "纬度")
    private Double latitude;
}
