package cn.iocoder.yudao.module.aiassist.controller.admin.invocation.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Schema(description = "管理后台 - AI 调用日志分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class AiInvocationLogPageReqVO extends PageParam {

    @Schema(description = "场景：nl-query / ticket-review / capacity-brief / risk-card / digest", example = "digest")
    private String scene;

    @Schema(description = "状态：OK / RATE_LIMITED / ERROR / EMPTY", example = "ERROR")
    private String status;

    @Schema(description = "操作人编号", example = "1")
    private Long operatorId;

}
