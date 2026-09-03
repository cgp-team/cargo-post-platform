package cn.iocoder.yudao.module.transport.controller.admin.simulation.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 模拟场景 VO
 */
@Schema(description = "模拟场景 Response VO")
@Data
public class SimulationScenarioRespVO {

    @Schema(description = "场景ID")
    private Long id;
    @Schema(description = "场景名称")
    private String name;
    @Schema(description = "场景描述")
    private String description;
    @Schema(description = "场景类型")
    private String scenarioType;
    @Schema(description = "严重级别：0正常 1警告 2严重")
    private Integer severity;
    @Schema(description = "是否内置")
    private Boolean builtin;
    @Schema(description = "是否启用")
    private Boolean enabled;
}
