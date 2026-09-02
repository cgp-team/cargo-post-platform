package cn.iocoder.yudao.module.transport.controller.admin.developer.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 开发者状态响应 VO
 */
@Schema(description = "管理后台 - 开发者状态 Response VO")
@Data
public class DeveloperStatusRespVO {

    @Schema(description = "当前用户是否开启开发者模式")
    private Boolean developerMode;

    @Schema(description = "当前环境是否开启模拟能力（SIMULATION_ENABLED）")
    private Boolean environmentSimulationEnabled;

    @Schema(description = "当前用户是否有模拟查看权限（transport:simulation:view）")
    private Boolean canViewSimulation;

    @Schema(description = "当前用户是否有模拟控制权限（transport:simulation:control）")
    private Boolean canControlSimulation;

}
