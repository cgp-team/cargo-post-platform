package cn.iocoder.yudao.module.aiassist.controller.admin.capability.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * AI 能力状态 Response VO（契约 §7）。
 *
 * <p>它只回答一件事：**现在能不能用模型**。绝不返回任何"AI 输出"，也绝不用模板文案冒充。
 */
@Schema(description = "管理后台 - AI 能力状态 Response VO")
@Data
public class AiCapabilityStatusRespVO {

    @Schema(description = "总开关 buspost.ai.enabled 是否打开")
    private Boolean enabled;

    @Schema(description = "是否已配置推理端点（buspost.ai.base-url 非空）")
    private Boolean endpointConfigured;

    @Schema(description = "是否就绪（开关打开 且 端点已配）")
    private Boolean ready;

    @Schema(description = "人话原因（就绪或未就绪都说清是哪一步缺）")
    private String reason;

    @Schema(description = "模型名（未配置时为空）")
    private String model;

}
