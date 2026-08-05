package cn.iocoder.yudao.module.transport.controller.admin.transport.driver.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 司机精简信息 Response VO")
@Data
public class DriverSimpleRespVO {
    @Schema(description = "司机编号", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long id;
    @Schema(description = "姓名", requiredMode = Schema.RequiredMode.REQUIRED)
    private String name;
    @Schema(description = "手机号")
    private String mobile;
}
