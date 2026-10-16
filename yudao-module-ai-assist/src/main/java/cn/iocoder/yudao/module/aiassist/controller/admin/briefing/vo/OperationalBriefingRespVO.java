package cn.iocoder.yudao.module.aiassist.controller.admin.briefing.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 运营简报生成 Response VO")
@Data
public class OperationalBriefingRespVO {

    @Schema(description = "简报标题")
    private String title;

    @Schema(description = "生成时间")
    private LocalDateTime generatedAt;

    @Schema(description = "Markdown 文本（可直接贴进文档或另存）")
    private String markdown;

    @Schema(description = "指标条数")
    private Integer metricCount;

}
