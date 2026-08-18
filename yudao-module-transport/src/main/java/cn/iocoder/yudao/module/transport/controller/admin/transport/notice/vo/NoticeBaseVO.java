package cn.iocoder.yudao.module.transport.controller.admin.transport.notice.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "管理后台 - 平台公告 Base VO")
@Data
public class NoticeBaseVO {
    @Schema(description = "公告标题", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "公告标题不能为空")
    @Size(max = 128, message = "公告标题长度不能超过 128 个字符")
    private String title;
    @Schema(description = "公告内容")
    private String content;
    @Schema(description = "状态(0下架 1上架)", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "状态不能为空")
    private Integer status;
    @Schema(description = "排序(小的在前)")
    private Integer sort;
}
