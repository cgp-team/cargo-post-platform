package cn.iocoder.yudao.module.transport.controller.admin.transport.feedback.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 意见反馈 Response VO")
@Data
public class FeedbackRespVO {
    @Schema(description = "反馈编号", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long id;
    @Schema(description = "会员编号")
    private Long userId;
    @Schema(description = "联系人姓名")
    private String name;
    @Schema(description = "联系电话")
    private String mobile;
    @Schema(description = "反馈内容")
    private String content;
    @Schema(description = "状态(0待处理 1已回复)")
    private Integer status;
    @Schema(description = "回复内容")
    private String reply;
    @Schema(description = "回复时间")
    private LocalDateTime replyTime;
    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
