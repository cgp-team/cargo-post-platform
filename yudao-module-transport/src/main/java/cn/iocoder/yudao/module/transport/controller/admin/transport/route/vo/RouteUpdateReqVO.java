package cn.iocoder.yudao.module.transport.controller.admin.transport.route.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;

@Schema(description="Route Update")
@Data @EqualsAndHashCode(callSuper=true) @ToString(callSuper=true)
public class RouteUpdateReqVO extends RouteBaseVO {
    @Schema(description="ID", requiredMode=Schema.RequiredMode.REQUIRED)
    @NotNull
    private Long id;
}
