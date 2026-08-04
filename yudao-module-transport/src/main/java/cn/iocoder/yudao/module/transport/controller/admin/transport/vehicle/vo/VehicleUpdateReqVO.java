package cn.iocoder.yudao.module.transport.controller.admin.transport.vehicle.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;

@Schema(description="Vehicle Update")
@Data @EqualsAndHashCode(callSuper=true) @ToString(callSuper=true)
public class VehicleUpdateReqVO extends VehicleBaseVO {
    @Schema(description="ID", requiredMode=Schema.RequiredMode.REQUIRED)
    @NotNull
    private Long id;
}
