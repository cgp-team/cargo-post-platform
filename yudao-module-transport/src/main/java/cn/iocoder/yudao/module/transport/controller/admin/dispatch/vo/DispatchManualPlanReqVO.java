package cn.iocoder.yudao.module.transport.controller.admin.dispatch.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Schema(description = "管理后台 - 手工派单 Request VO")
@Data
public class DispatchManualPlanReqVO {

    @Schema(description = "场站编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "场站不能为空")
    private Long depotStationId;

    @Schema(description = "车辆编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "车辆不能为空")
    private Long vehicleId;

    @Schema(description = "订单编号列表（按经停顺序）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "订单不能为空")
    private List<Long> orderIds;

}
