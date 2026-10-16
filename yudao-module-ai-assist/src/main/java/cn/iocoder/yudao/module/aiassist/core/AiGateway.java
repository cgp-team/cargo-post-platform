package cn.iocoder.yudao.module.aiassist.core;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.module.aiassist.config.AiAssistProperties;
import cn.iocoder.yudao.module.aiassist.dal.dataobject.invocation.AiInvocationLogDO;
import cn.iocoder.yudao.module.aiassist.service.invocation.AiInvocationLogService;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 唯一的 AI 外呼出口（契约 §0 / §4 / §5）。
 *
 * <p>为什么要收成一个口：脱敏、超时、留痕、"未就绪就说未就绪"这四条纪律，
 * 只要允许调用方自己发 HTTP，就一定会出现某条绕过其中一条的路径。
 *
 * <p>三条硬行为：
 * <ol>
 *   <li><b>没配端点就抛 {@link NotReady}</b>：绝不用模板话术冒充模型输出（本仓红线：不许假能力）。
 *       上层把它翻译成"能力未就绪 + 原因"，页面照常可用；</li>
 *   <li><b>每次外呼都留一条 {@code ai_invocation_log}</b>（成功、失败、超时都留）：
 *       留痕走旁路 —— 落库失败只打 error 日志，不影响本次结果（§5）；
 *       库里存的是<b>已脱敏的输入摘要</b>与输出，<b>密钥永不入库、不入日志</b>；</li>
 *   <li><b>超时是真的传给客户端</b>（connectTimeout + request timeout），对面挂掉时不占着后台线程等。</li>
 * </ol>
 */
@Component
public class AiGateway {

    private static final Logger log = LoggerFactory.getLogger(AiGateway.class);

    /** 端点未配置 / 开关未开：不是错误，是"这一格现在没有这个能力"。 */
    @Getter
    public static class NotReady extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public NotReady(String why) {
            super(why);
        }
    }

    /** 一次调用的结果：文本 + 对面报的 token 数（拿不到就是 null，不填 0 冒充"0 token"）。 */
    @Getter
    public static class Reply {
        private final String text;
        private final Integer tokens;
        private final String model;
        private final int latencyMs;

        Reply(String text, Integer tokens, String model, int latencyMs) {
            this.text = text;
            this.tokens = tokens;
            this.model = model;
            this.latencyMs = latencyMs;
        }
    }

    private final AiAssistProperties props;
    private final AiInvocationLogService invocationLog;
    private final HttpClient http;

    public AiGateway(AiAssistProperties props, AiInvocationLogService invocationLog) {
        this.props = props;
        this.invocationLog = invocationLog;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(1000, props.getTimeoutMs())))
                .build();
    }

    /**
     * 发一次对话补全。
     *
     * @param scene        场景标识（进日志，供事后数"这类调用做了多少次"）
     * @param maskedInput  **必须已过 §4 脱敏出口**的输入文本；本方法只负责再截一次长度
     * @param wantVision   是否走视觉模型（照片支撑度那一类）
     */
    public Reply complete(String scene, String maskedInput, boolean wantVision) {
        if (!props.endpointReady()) {
            record(scene, maskedInput, null, null, 0, "not_ready",
                    wantVision ? "开关/端点/密钥未齐备（视觉项还需 vision-model）" : "开关/端点/密钥未齐备");
            throw new NotReady("推理端点未配置或总开关未打开，本次没有产生任何 AI 输出");
        }
        String model = wantVision ? props.getVisionModel() : props.getModel();
        if (!StringUtils.hasText(model)) {
            record(scene, maskedInput, null, null, 0, "not_ready", "模型名为空");
            throw new NotReady("模型名未配置（model / vision-model），本次没有产生任何 AI 输出");
        }
        String input = clamp(maskedInput, props.getMaxInputChars());
        long begin = System.currentTimeMillis();
        try {
            JSONObject body = JSONUtil.createObj()
                    .set("model", model)
                    .set("messages", JSONUtil.createArray().set(JSONUtil.createObj()
                            .set("role", "user").set("content", input)))
                    .set("temperature", 0.2);
            HttpRequest req = HttpRequest.newBuilder(URI.create(props.chatCompletionsUrl()))
                    .timeout(Duration.ofMillis(Math.max(1000, props.getTimeoutMs())))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("Authorization", "Bearer " + props.getApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int latency = (int) (System.currentTimeMillis() - begin);
            if (resp.statusCode() / 100 != 2) {
                // 回显体截断到 200 字：对面给的常常是 HTML 错误页，整段入库会把日志撑爆
                String brief = "HTTP " + resp.statusCode() + " " + trim(resp.body(), 200);
                record(scene, input, null, null, latency, "http_error", brief);
                throw new IllegalStateException("推理端点返回 HTTP " + resp.statusCode() + "，本次没有 AI 输出");
            }
            JSONObject json = JSONUtil.parseObj(resp.body());
            String text = json.getByPath("choices[0].message.content", String.class);
            Integer tokens = json.getByPath("usage.total_tokens", Integer.class);
            if (!StringUtils.hasText(text)) {
                record(scene, input, null, tokens, latency, "empty", "响应里没有 choices[0].message.content");
                throw new IllegalStateException("推理端点回了 2xx 但没有内容（不拿空串冒充 AI 输出）");
            }
            record(scene, input, trim(text, 2000), tokens, latency, "ok", null);
            return new Reply(text, tokens, model, latency);
        } catch (NotReady | IllegalStateException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            record(scene, input, null, null, (int) (System.currentTimeMillis() - begin), "interrupted", e.getMessage());
            throw new IllegalStateException("AI 调用被中断，本次没有输出", e);
        } catch (Exception e) {
            record(scene, input, null, null, (int) (System.currentTimeMillis() - begin), "error",
                    e.getClass().getSimpleName() + ": " + trim(String.valueOf(e.getMessage()), 160));
            throw new IllegalStateException("AI 调用失败（" + e.getClass().getSimpleName() + "），本次没有输出", e);
        }
    }

    private String clamp(String s, int max) {
        String v = s == null ? "" : s;
        if (max > 0 && v.length() > max) {
            return v.substring(0, max) + "\n…（输入已截断，原长 " + v.length() + " 字）";
        }
        return v;
    }

    private static String trim(String s, int max) {
        if (s == null) {
            return null;
        }
        String one = s.replaceAll("\\s+", " ").trim();
        return one.length() <= max ? one : one.substring(0, max) + "…";
    }

    /** 旁路留痕：失败只打日志，绝不把主流程带倒（§5）。 */
    private void record(String scene, String input, String output, Integer tokens, Integer latency, String status, String err) {
        try {
            AiInvocationLogDO row = new AiInvocationLogDO();
            row.setScene(scene);
            row.setMaskedInput(trim(input, 1000));
            row.setOutput(output);
            row.setTokens(tokens);
            row.setLatencyMs(latency == null || latency == 0 ? null : latency);
            row.setStatus(status);
            row.setErrorBrief(err);
            invocationLog.record(row);
        } catch (Exception e) {
            // 留痕失败必须可见（打点），但不许改变本次调用的结果
            log.error("[ai] 调用日志写入失败 scene={} status={}", scene, status, e);
        }
    }
}
