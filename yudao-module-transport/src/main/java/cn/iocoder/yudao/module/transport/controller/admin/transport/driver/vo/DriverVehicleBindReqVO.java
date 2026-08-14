package cn.iocoder.yudao.module.transport.controller.admin.transport.driver.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Schema(description = "管理后台 - 司机车辆绑定 Request VO")
@Data
public class DriverVehicleBindReqVO {

    @Schema(description = "司机编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "司机不能为空")
    private Long driverId;

    @Schema(description = "车辆编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "车辆不能为空")
    private Long vehicleId;

}
