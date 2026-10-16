package cn.iocoder.yudao.module.aiassist.core;

import cn.iocoder.yudao.module.aiassist.config.AiAssistProperties;
import cn.iocoder.yudao.module.aiassist.dal.dataobject.invocation.AiInvocationLogDO;
import cn.iocoder.yudao.module.aiassist.service.invocation.AiInvocationLogService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 唯一外呼口的四条行为（契约 §0 / §4 / §5）—— 用**本机临时 HTTP 服务**跑真请求，不用 mock 框架：
 * 要验的正是"请求真的发出去了、头里带对了、超时与非 2xx 怎么落地"，mock 掉 HTTP 就等于没验。
 */
class AiGatewayTest {

    private HttpServer server;
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<String> body = new AtomicReference<>("{}");
    private final AtomicReference<String> seenAuth = new AtomicReference<>();
    private final AtomicReference<String> seenBody = new AtomicReference<>();
    private String base;
    private List<AiInvocationLogDO> rows;
    private AiAssistProperties props;

    /** 只收集、不落库的日志替身（record 的契约是"永不抛"，这里也照此办理）。 */
    private AiInvocationLogService logService() {
        return new AiInvocationLogService() {
            @Override
            public boolean record(AiInvocationLogDO log) {
                rows.add(log);
                return true;
            }

            @Override
            public cn.iocoder.yudao.framework.common.pojo.PageResult<AiInvocationLogDO> getPage(
                    cn.iocoder.yudao.module.aiassist.controller.admin.invocation.vo.AiInvocationLogPageReqVO reqVO) {
                return new cn.iocoder.yudao.framework.common.pojo.PageResult<>(rows, (long) rows.size());
            }
        };
    }

    @BeforeEach
    void setUp() throws Exception {
        rows = new ArrayList<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", ex -> {
            seenAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            seenBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = body.get().getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status.get(), out.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        props = new AiAssistProperties();
        props.setEnabled(true);
        props.setBaseUrl(base);
        props.setApiKey("sk-test-should-never-be-logged");
        props.setModel("some-text-model");
        props.setVisionModel("some-vision-model");
        props.setTimeoutMs(5000);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void 端点齐备时真的发一次并回文本与token() {
        status.set(200);
        body.set("{\"choices\":[{\"message\":{\"content\":\"分类：道路阻断\\n依据：塌方\"}}],"
                + "\"usage\":{\"total_tokens\":77}}");
        AiGateway.Reply r = new AiGateway(props, logService())
                .complete(AiTasks.SCENE_ORDER_CLASSIFY, "路上塌方了", false);
        assertEquals("分类：道路阻断\n依据：塌方", r.getText());
        assertEquals(Integer.valueOf(77), r.getTokens());
        assertEquals("some-text-model", r.getModel());
        assertTrue(seenAuth.get().startsWith("Bearer sk-test-"));
        assertTrue(seenBody.get().contains("some-text-model"));
        // 留痕：一次调用一条，状态 ok，且**密钥没进日志**
        assertEquals(1, rows.size());
        assertEquals("ok", rows.get(0).getStatus());
        assertEquals(Integer.valueOf(77), rows.get(0).getTokens());
        assertFalse(String.valueOf(rows).contains("sk-test-"), "日志里出现了密钥：" + rows);
    }

    @Test
    void 开关关掉时抛未就绪并留下一条not_ready() {
        props.setEnabled(false);
        AiGateway.NotReady e = assertThrows(AiGateway.NotReady.class, () -> new AiGateway(props, logService())
                .complete(AiTasks.SCENE_ORDER_CLASSIFY, "路上塌方了", false));
        assertTrue(e.getMessage().contains("没有产生任何 AI 输出"), e.getMessage());
        assertEquals(1, rows.size());
        assertEquals("not_ready", rows.get(0).getStatus());
    }

    @Test
    void 没配密钥也算未就绪而不是拿空串去发() {
        props.setApiKey("  ");
        assertThrows(AiGateway.NotReady.class, () -> new AiGateway(props, logService())
                .complete(AiTasks.SCENE_FEEDBACK_TOPIC, "摘要", false));
    }

    @Test
    void 视觉项没配视觉模型时单独报未就绪() {
        props.setVisionModel("");
        AiGateway.NotReady e = assertThrows(AiGateway.NotReady.class, () -> new AiGateway(props, logService())
                .complete(AiTasks.SCENE_PHOTO_SUPPORT, "描述", true));
        assertTrue(e.getMessage().contains("vision-model") || e.getMessage().contains("模型名"), e.getMessage());
    }

    @Test
    void 非2xx时失败并记下状态码不冒充成功() {
        status.set(429);
        body.set("too many requests");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new AiGateway(props, logService())
                .complete(AiTasks.SCENE_REPORT_MERGE, "清单", false));
        assertTrue(e.getMessage().contains("429"), e.getMessage());
        assertEquals("http_error", rows.get(0).getStatus());
        assertTrue(rows.get(0).getErrorBrief().contains("429"));
    }

    @Test
    void 回了2xx但没内容也算失败而不是空建议() {
        status.set(200);
        body.set("{\"choices\":[]}");
        assertThrows(IllegalStateException.class, () -> new AiGateway(props, logService())
                .complete(AiTasks.SCENE_BRIEFING_POLISH, "底稿", false));
        assertEquals("empty", rows.get(0).getStatus());
    }

    @Test
    void 输入超长时截断并在日志里留痕() {
        status.set(200);
        body.set("{\"choices\":[{\"message\":{\"content\":\"分类：货物异常\\n依据：x\"}}]}");
        props.setMaxInputChars(20);
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            big.append("很长的上报内容");
        }
        new AiGateway(props, logService()).complete(AiTasks.SCENE_ORDER_CLASSIFY, big.toString(), false);
        assertTrue(seenBody.get().contains("输入已截断"), "没截断就发出去了");
        assertNotNull(rows.get(0).getMaskedInput());
        assertTrue(rows.get(0).getMaskedInput().length() <= 1001);
    }
}
