package cn.iocoder.yudao.module.transport.controller.admin.simulation.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 异常注入请求 VO
 */
@Schema(description = "异常注入 Request VO")
@Data
public class SimulationInjectReqVO {

    @NotNull(message = "车辆ID不能为空")
    @Schema(description = "车辆ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long vehicleId;

    @NotEmpty(message = "事件类型不能为空")
    @Schema(description = "事件类型：FAULT/GPS_LOST/DRIVER_OFFLINE/CONGESTION/DELAY/ORDER_CANCEL/ORDER_ADD", requiredMode = Schema.RequiredMode.REQUIRED)
    private String eventType;

    @Schema(description = "持续时长(秒)，null表示手动恢复")
    private Long durationSeconds;

    @Schema(description = "备注")
    private String remark;
}
