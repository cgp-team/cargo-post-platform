package cn.iocoder.yudao.module.transport.controller.admin.simulation.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 应用场景请求 VO
 */
@Schema(description = "应用场景 Request VO")
@Data
public class SimulationScenarioApplyReqVO {

    @NotNull(message = "车辆ID不能为空")
    @Schema(description = "车辆ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long vehicleId;

    @NotNull(message = "场景ID不能为空")
    @Schema(description = "场景ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long scenarioId;
}
