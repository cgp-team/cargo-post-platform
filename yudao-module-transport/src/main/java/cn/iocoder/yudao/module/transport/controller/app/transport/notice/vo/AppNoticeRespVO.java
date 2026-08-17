package cn.iocoder.yudao.module.transport.controller.app.transport.notice.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "用户 APP - 平台公告 Response VO")
@Data
public class AppNoticeRespVO {
    @Schema(description = "公告编号", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long id;
    @Schema(description = "公告标题", requiredMode = Schema.RequiredMode.REQUIRED)
    private String title;
    @Schema(description = "公告内容")
    private String content;
}
