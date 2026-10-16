package cn.iocoder.yudao.module.aiassist.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * AI 辅助的开关与端点配置（契约 docs/AI_ASSIST_BOUNDARY.md §7）。
 *
 * <p>关键纪律：这里**只读**配置，且全部带默认值 —— 服务端没有 yaml、没有环境变量时，
 * 开关默认 **false**、端点默认 **空**，于是能力状态天然是"未就绪"，页面绝不会显示"AI 生成中"。
 * 这正好落实 §11.1：「先做外壳与开关」，没有端点就不假装有。
 *
 * <p>⚠ 密钥（api-key）不在这里读：它只应来自环境变量、且不该被任何接口回显（§7.1 / §12）。
 */
@Component
@Getter
@Setter
public class AiAssistProperties {

    /** 总开关；默认关 —— 没有推理端点时不许有任何 AI 行为。 */
    @Value("${buspost.ai.enabled:false}")
    private boolean enabled;

    /** 推理端点基址；默认空 ⇒ 能力未就绪。 */
    @Value("${buspost.ai.base-url:}")
    private String baseUrl;

    /** 模型名；仅用于状态展示，默认为空。 */
    @Value("${buspost.ai.model:}")
    private String model;

    /** 超时（毫秒），仅登记，本期不发起调用。 */
    @Value("${buspost.ai.timeout-ms:8000}")
    private int timeoutMs;

}
