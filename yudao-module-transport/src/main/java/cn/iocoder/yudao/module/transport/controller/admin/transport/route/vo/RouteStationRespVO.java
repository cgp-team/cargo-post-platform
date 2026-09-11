package cn.iocoder.yudao.module.transport.controller.admin.transport.route.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 线路经停站点（站序编辑用）：站点 + 顺序 + 计划分钟 + 坐标（地图上直接看走向）。
 */
@Schema(description = "管理后台 - 线路经停站点 Response VO")
@Data
public class RouteStationRespVO {

    @Schema(description = "站点编号")
    private Long stationId;

    @Schema(description = "站点名称")
    private String stationName;

    @Schema(description = "站点地址")
    private String address;

    @Schema(description = "经度")
    private Double longitude;

    @Schema(description = "纬度")
    private Double latitude;

    @Schema(description = "站序（从 1 开始）")
    private Integer sequenceNo;

    @Schema(description = "从起点累计计划分钟")
    private Integer plannedMinutes;
}
