package cn.iocoder.yudao.module.transport.controller.app.transport.feedback.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "用户 APP - 意见反馈创建 Request VO")
@Data
public class AppFeedbackCreateReqVO {
    @Schema(description = "反馈内容", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "反馈内容不能为空")
    @Size(max = 500, message = "反馈内容长度不能超过 500 个字符")
    private String content;
    @Schema(description = "联系人姓名")
    @Size(max = 30, message = "联系人姓名长度不能超过 30 个字符")
    private String name;
    @Schema(description = "联系电话")
    @Size(max = 11, message = "联系电话长度不能超过 11 个字符")
    private String mobile;
}
