package cn.iocoder.yudao.module.aiassist.controller.admin.invocation.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - AI 调用日志 Response VO")
@Data
public class AiInvocationLogRespVO {

    @Schema(description = "编号", example = "1")
    private Long id;

    @Schema(description = "场景", example = "digest")
    private String scene;

    @Schema(description = "操作人编号", example = "1")
    private Long operatorId;

    @Schema(description = "送出去的内容（已脱敏）")
    private String maskedInput;

    @Schema(description = "模型返回的文本（字段名带 Text 是为了避开保留字 output，见 DO 上的注释）")
    private String outputText;

    @Schema(description = "消耗 token 数")
    private Integer tokens;

    @Schema(description = "耗时（毫秒）")
    private Integer latencyMs;

    @Schema(description = "状态：OK / RATE_LIMITED / ERROR / EMPTY", example = "OK")
    private String status;

    @Schema(description = "失败要点")
    private String errorBrief;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

}
