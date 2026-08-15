package cn.iocoder.yudao.module.transport.controller.admin.dispatch.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Schema(description = "管理后台 - 智能派单前约束校验 Request VO")
@Data
public class DispatchValidateReqVO {

    @Schema(description = "场站编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "场站不能为空")
    private Long depotStationId;

    @Schema(description = "可用车辆编号列表（不超过 3 台）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "车辆不能为空")
    @Size(max = 3, message = "可用车辆不能超过 3 台")
    private List<Long> vehicleIds;

}
