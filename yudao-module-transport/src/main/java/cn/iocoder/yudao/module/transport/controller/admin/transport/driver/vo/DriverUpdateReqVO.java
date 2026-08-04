package cn.iocoder.yudao.module.transport.controller.admin.transport.driver.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;

@Schema(description="Driver Update")
@Data @EqualsAndHashCode(callSuper=true) @ToString(callSuper=true)
public class DriverUpdateReqVO extends DriverBaseVO {
    @Schema(description="ID", requiredMode=Schema.RequiredMode.REQUIRED)
    @NotNull
    private Long id;
}
