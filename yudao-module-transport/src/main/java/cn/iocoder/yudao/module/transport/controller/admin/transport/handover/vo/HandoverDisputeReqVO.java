package cn.iocoder.yudao.module.transport.controller.admin.transport.handover.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Schema(description = "管理后台 - 交接争议标记 Request VO")
@Data
public class HandoverDisputeReqVO {

    @Schema(description = "交接编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "交接编号不能为空")
    private Long id;

    @Schema(description = "争议原因", requiredMode = Schema.RequiredMode.REQUIRED)
    private String remark;

}
