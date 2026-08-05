package cn.iocoder.yudao.module.transport.controller.admin.transport.vehicle.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 车辆精简信息 Response VO")
@Data
public class VehicleSimpleRespVO {
    @Schema(description = "车辆编号", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long id;
    @Schema(description = "车牌号", requiredMode = Schema.RequiredMode.REQUIRED)
    private String plateNo;
}
