package cn.iocoder.yudao.module.transport.controller.admin.simulation;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 模拟运行状态（管理端模拟控制页轮询展示）。
 */
@Schema(description = "管理后台 - 模拟运行状态 Response VO")
@Data
public class SimulationStatusRespVO {

    @Schema(description = "车辆编号")
    private Long vehicleId;
    @Schema(description = "方案编号")
    private Long planId;
    @Schema(description = "状态：0待启动 1运行中 2已暂停 3已完成")
    private Integer status;
    @Schema(description = "状态名")
    private String statusName;
    @Schema(description = "倍速")
    private Double multiplier;
    @Schema(description = "已模拟秒数")
    private Long simSeconds;
    @Schema(description = "总模拟秒数")
    private Long totalSimSeconds;
    @Schema(description = "当前经停站点名")
    private String currentStationName;
    @Schema(description = "是否到站停靠中")
    private Boolean arrived;

}
