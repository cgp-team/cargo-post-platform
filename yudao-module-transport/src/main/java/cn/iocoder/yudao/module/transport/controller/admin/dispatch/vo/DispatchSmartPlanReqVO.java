package cn.iocoder.yudao.module.transport.controller.admin.dispatch.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Schema(description = "管理后台 - 智能派单 Request VO")
@Data
public class DispatchSmartPlanReqVO {

    @Schema(description = "场站编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "场站不能为空")
    private Long depotStationId;

    @Schema(description = "可用车辆编号列表（不超过 3 台）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "车辆不能为空")
    @Size(max = 3, message = "可用车辆不能超过 3 台")
    private List<Long> vehicleIds;

    @Schema(description = "班次编号（可选）：指定后该批派单车辆按班次线路公交骨架经停，货运作为绕行插入（联合调度）")
    private Long shiftId;

    @Schema(description = "算法超参数，不传用算法默认值")
    private Map<String, Object> algorithmConfig;

    @Schema(description = "规划场景（仅供 Mock 联调透传）")
    private String scenario;

}
