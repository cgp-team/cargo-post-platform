package cn.iocoder.yudao.module.transport.controller.admin.transport.feedback.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "管理后台 - 意见反馈回复 Request VO")
@Data
public class FeedbackReplyReqVO {
    @Schema(description = "反馈编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "反馈编号不能为空")
    private Long id;
    @Schema(description = "回复内容", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "回复内容不能为空")
    @Size(max = 500, message = "回复内容长度不能超过 500 个字符")
    private String reply;
}
