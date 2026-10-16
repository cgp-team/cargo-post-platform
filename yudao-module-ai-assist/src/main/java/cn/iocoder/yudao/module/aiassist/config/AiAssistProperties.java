package cn.iocoder.yudao.module.aiassist.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * AI 辅助的开关与端点配置（契约 docs/AI_ASSIST_BOUNDARY.md §7）。
 *
 * <p>一条设计口径：**这一层是"可配置的推理端点"，不是"某家厂商的 SDK"**。
 * 请求体走 OpenAI 兼容的 {@code /chat/completions}，所以：
 * 云上 API（DashScope / 智谱 / OpenAI / DeepSeek 的兼容模式）与自部署（vLLM、Ollama 的 openai 兼容层、
 * 内网自建网关）都只需要改三个值：{@code base-url} / {@code api-key} / {@code model}。
 * 不引任何厂商 SDK —— 多一个依赖就多一处会被上游改动打断的地方。
 *
 * <p>⚠ 三条纪律（都是被判据钉着的形状，不是注释里的愿望）：
 * <ol>
 *   <li>默认值一律"关 + 空"：没有端点时能力状态天然是未就绪，页面不许出现"AI 生成中"这种假忙；</li>
 *   <li>{@code api-key} 只从环境变量来（{@code ${BUSPOST_AI_API_KEY:}}），
 *       **不写进仓库、不写进 yaml 明文、不回显给任何接口、不进日志**（见 {@code AiGateway} 里那句"日志只存摘要"）；</li>
 *   <li>{@code timeoutMs} 是真的传给 HTTP 客户端的超时，不是登记着好看 ——
 *       没有超时的外呼等于把后台的线程交给对面。</li>
 * </ol>
 */
@Component
@Getter
@Setter
public class AiAssistProperties {

    /** 总开关；默认关 —— 没有推理端点时不许有任何 AI 行为。 */
    @Value("${buspost.ai.enabled:false}")
    private boolean enabled;

    /** 推理端点基址（到 {@code /v1} 那一层为止），默认空 ⇒ 能力未就绪。 */
    @Value("${buspost.ai.base-url:}")
    private String baseUrl;

    /** 密钥：只从环境变量注入。任何接口都不回显它，日志里也不许出现。 */
    @Value("${buspost.ai.api-key:${BUSPOST_AI_API_KEY:}}")
    private String apiKey;

    /** 文本模型名（分类 / 归并 / 聚类 / 润色都用它）。 */
    @Value("${buspost.ai.model:}")
    private String model;

    /** 视觉模型名；留空 ⇒ "照片是否支撑描述"这一项直接报未就绪，不退化成"只看文字猜"。 */
    @Value("${buspost.ai.vision-model:}")
    private String visionModel;

    /** 单次外呼超时（毫秒），真的传给 HTTP 客户端。 */
    @Value("${buspost.ai.timeout-ms:8000}")
    private int timeoutMs;

    /** 送给模型的输入上限（字符）：超了先截并说明截过，而不是让对面拿到一整段日志。 */
    @Value("${buspost.ai.max-input-chars:4000}")
    private int maxInputChars;

    /** 端点是否可用（三件齐备才算齐：开关、基址、密钥）。 */
    public boolean endpointReady() {
        return enabled && notBlank(baseUrl) && notBlank(apiKey);
    }

    /** 视觉这一项是否可用：在端点齐备之上还要有视觉模型名。 */
    public boolean visionReady() {
        return endpointReady() && notBlank(visionModel);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    /** 拼出 chat/completions 的完整地址；容忍 base-url 末尾带或不带斜杠。 */
    public String chatCompletionsUrl() {
        String b = baseUrl == null ? "" : baseUrl.trim();
        if (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        return b + "/chat/completions";
    }
}
