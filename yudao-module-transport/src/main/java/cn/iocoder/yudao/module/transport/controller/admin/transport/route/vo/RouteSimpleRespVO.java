package cn.iocoder.yudao.module.transport.controller.admin.transport.route.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 线路精简信息 Response VO")
@Data
public class RouteSimpleRespVO {
    @Schema(description = "线路编号", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long id;
    @Schema(description = "线路编码", requiredMode = Schema.RequiredMode.REQUIRED)
    private String routeCode;
    @Schema(description = "线路名称", requiredMode = Schema.RequiredMode.REQUIRED)
    private String routeName;
}
