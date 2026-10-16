package cn.iocoder.yudao.module.aiassist.service.task;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.aiassist.config.AiAssistProperties;
import cn.iocoder.yudao.module.aiassist.controller.admin.invocation.vo.AiInvocationLogPageReqVO;
import cn.iocoder.yudao.module.aiassist.controller.admin.task.vo.AiTaskReqVO;
import cn.iocoder.yudao.module.aiassist.core.AiGateway;
import cn.iocoder.yudao.module.aiassist.core.AiTasks;
import cn.iocoder.yudao.module.aiassist.dal.dataobject.invocation.AiInvocationLogDO;
import cn.iocoder.yudao.module.aiassist.service.invocation.AiInvocationLogService;
import cn.iocoder.yudao.module.transport.controller.admin.transport.townevent.vo.TownEventPageReqVO;
import cn.iocoder.yudao.module.transport.controller.admin.transport.townevent.vo.TownEventSaveReqVO;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.TownEventCalendarDO;
import cn.iocoder.yudao.module.transport.service.dispatch.TownEventCalendarService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 服务层的三条红线（契约 §7 / §9 第 4 条 / §4）：
 * <ol>
 *   <li><b>开关关掉 ⇒ 五个模型任务一律只回「未就绪」</b>，不回任何像结论的句子（不拿模板文案冒充 AI 输出）；</li>
 *   <li><b>关掉 AI 后台仍完整可用</b>：不依赖模型的情景提示在开关关掉时照样出结果，且一次外呼都没有；</li>
 *   <li><b>送出去之前必须已过脱敏出口</b>：明文手机号不许出现在请求体里（本机临时 HTTP 服务收真请求来验，不 mock）。</li>
 * </ol>
 * 第 1 条单独看没有意义 —— 它必须和第 4 个用例（端点齐备时真发一次并解析成草稿）配成一对，
 * 否则"全都未就绪"也可能是整条链坏掉造成的假绿。
 */
class AiTaskServiceTest {

    private HttpServer server;
    private final AtomicReference<String> body = new AtomicReference<>("{}");
    private final AtomicReference<String> seenBody = new AtomicReference<>();
    private final List<AiInvocationLogDO> rows = new ArrayList<>();
    private String base;

    private AiInvocationLogService logService() {
        return new AiInvocationLogService() {
            @Override
            public boolean record(AiInvocationLogDO log) {
                rows.add(log);
                return true;
            }

            @Override
            public PageResult<AiInvocationLogDO> getPage(AiInvocationLogPageReqVO reqVO) {
                return new PageResult<>(rows, (long) rows.size());
            }
        };
    }

    @BeforeEach
    void setUp() throws Exception {
        rows.clear();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", ex -> {
            seenBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = body.get().getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private static AiAssistProperties props(boolean enabled, String baseUrl, String apiKey, String model, String vision) {
        AiAssistProperties p = new AiAssistProperties();
        p.setEnabled(enabled);
        p.setBaseUrl(baseUrl);
        p.setApiKey(apiKey);
        p.setModel(model);
        p.setVisionModel(vision);
        p.setTimeoutMs(3000);
        p.setMaxInputChars(4000);
        return p;
    }

    /** 日历替身：只实现取数那一个方法，其余一律炸出来（本模块只读日历，碰别的就说明越界了）。 */
    private static TownEventCalendarService calendar(List<TownEventCalendarDO> effective) {
        return new TownEventCalendarService() {
            @Override
            public Long create(TownEventSaveReqVO reqVO) {
                throw new UnsupportedOperationException("AI 侧不许写日历");
            }

            @Override
            public void update(TownEventSaveReqVO reqVO) {
                throw new UnsupportedOperationException("AI 侧不许写日历");
            }

            @Override
            public void delete(Long id) {
                throw new UnsupportedOperationException("AI 侧不许写日历");
            }

            @Override
            public TownEventCalendarDO get(Long id) {
                throw new UnsupportedOperationException("AI 侧只按天取生效事件");
            }

            @Override
            public PageResult<TownEventCalendarDO> getPage(TownEventPageReqVO reqVO) {
                throw new UnsupportedOperationException("AI 侧只按天取生效事件");
            }

            @Override
            public List<TownEventCalendarDO> listEffectiveOn(LocalDate day) {
                return effective;
            }
        };
    }

    private AiTaskService service(AiAssistProperties p, TownEventCalendarService cal) throws Exception {
        AiTaskService s = new AiTaskService();
        inject(s, "props", p);
        inject(s, "gateway", new AiGateway(p, logService()));
        inject(s, "townEventCalendarService", cal);
        return s;
    }

    private static void inject(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static AiTaskReqVO req(String text) {
        AiTaskReqVO v = new AiTaskReqVO();
        v.setText(text);
        return v;
    }

    @Test
    void 开关关掉时五个模型任务一律只回未就绪且不回任何像结论的句子() throws Exception {
        AiTaskService s = service(props(false, "", "", "", ""), calendar(List.of()));

        AiTasks.Draft classify = s.classifyReport(req("路上塌方了，联系 13800138000"));
        AiTasks.Draft photo = photoReq(s);
        AiTasks.Draft merge = mergeReq(s);
        AiTasks.Draft topic = topicReq(s, 12);
        AiTasks.Draft polish = s.polishBriefing(req("今日完成 15 单"));

        for (AiTasks.Draft d : List.of(classify, photo, merge, topic, polish)) {
            assertFalse(d.isReady(), "开关关掉还报就绪：" + d.getScene());
            assertFalse(d.isParsed(), "未就绪却报已解析：" + d.getScene());
            assertNull(d.getSuggestion(), "未就绪却给了建议（这就是拿模板冒充 AI 输出）：" + d.getSuggestion());
            assertNull(d.getBasis(), "未就绪却给了依据：" + d.getBasis());
            assertNull(d.getRawText(), "未就绪却带了模型原文：" + d.getRawText());
            assertTrue(d.getReason() != null && !d.getReason().isBlank(), "未就绪必须说清原因：" + d.getScene());
        }
        assertTrue(classify.getReason().contains("没有产生任何 AI 输出"), classify.getReason());
        assertTrue(photo.getReason().contains("视觉能力未就绪"), photo.getReason());

        // 恰好 4 条 not_ready：照片那一格在开关关掉时先判视觉未就绪，连外呼口都不走到
        assertEquals(4, rows.size(), "留痕条数不对：" + rows);
        assertTrue(rows.stream().allMatch(r -> "not_ready".equals(r.getStatus())), String.valueOf(rows));
        assertTrue(rows.stream().noneMatch(r -> AiTasks.SCENE_PHOTO_SUPPORT.equals(r.getScene())), String.valueOf(rows));
    }

    @Test
    void 开关关掉时不依赖模型的情景提示照样出结果且一次外呼都没有() throws Exception {
        TownEventCalendarDO row = new TownEventCalendarDO();
        row.setTown("杨柳镇");
        row.setEventType("MARKET");
        row.setRecurrence("EVERY_N_DAYS");
        row.setIntervalDays(3);
        row.setNote("逢三赶集");
        AiTaskService s = service(props(false, "", "", "", ""), calendar(List.of(row)));

        AiTasks.Draft d = s.scenarioHints(LocalDate.of(2026, 10, 20));

        assertTrue(d.isReady(), d.getReason());
        assertTrue(d.isParsed());
        assertTrue(d.getSuggestion().contains("赶集"), d.getSuggestion());
        assertTrue(d.getSuggestion().contains("杨柳镇"), d.getSuggestion());
        assertTrue(d.getBasis().contains("未经模型"), d.getBasis());
        assertEquals(0, rows.size(), "这一格不该发任何外呼：" + rows);
    }

    @Test
    void 日历读不到时不许演成这一天没有事件() throws Exception {
        // 取数本身炸了的替身：缺值不是结论，页面必须念"没取到"，不能念"这一天没有事件"
        TownEventCalendarService broken = new TownEventCalendarService() {
            @Override
            public Long create(TownEventSaveReqVO reqVO) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void update(TownEventSaveReqVO reqVO) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void delete(Long id) {
                throw new UnsupportedOperationException();
            }

            @Override
            public TownEventCalendarDO get(Long id) {
                throw new UnsupportedOperationException();
            }

            @Override
            public PageResult<TownEventCalendarDO> getPage(TownEventPageReqVO reqVO) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<TownEventCalendarDO> listEffectiveOn(LocalDate day) {
                throw new IllegalStateException("库连不上");
            }
        };
        AiTaskService s = service(props(false, "", "", "", ""), broken);

        AiTasks.Draft d = s.scenarioHints(LocalDate.of(2026, 10, 20));

        assertFalse(d.isReady());
        assertTrue(d.getReason().contains("不是「没有事件」"), d.getReason());
        assertNull(d.getSuggestion());
    }

    @Test
    void 端点齐备时真发一次把回包解析成草稿并且明文手机号没出网() throws Exception {
        body.set("{\"choices\":[{\"message\":{\"content\":\"分类：道路阻断\\n依据：塌方挡住车道\"}}],"
                + "\"usage\":{\"total_tokens\":21}}");
        AiTaskService s = service(props(true, base, "sk-test-not-a-real-key", "some-text-model", ""), calendar(List.of()));

        AiTasks.Draft d = s.classifyReport(req("路上塌方了，联系 13800138000 处理"));

        assertTrue(d.isReady(), d.getReason());
        assertTrue(d.isParsed(), d.getReason());
        assertEquals("道路阻断", d.getSuggestion());
        assertTrue(d.getBasis().contains("塌方"), d.getBasis());
        // 出网内容：号码必须是出口掩过的形状，密钥不许进日志
        assertFalse(seenBody.get().contains("13800138000"), "明文手机号出网了：" + seenBody.get());
        assertTrue(seenBody.get().contains("138****8000"), "没看到掩码形状：" + seenBody.get());
        assertTrue(seenBody.get().contains("some-text-model"));
        assertEquals(1, rows.size(), String.valueOf(rows));
        assertEquals("ok", rows.get(0).getStatus());
        assertFalse(String.valueOf(rows).contains("sk-test-"), "密钥进了日志：" + rows);
    }

    @Test
    void 模型改了底稿里的数字时这一版判不合格() throws Exception {
        body.set("{\"choices\":[{\"message\":{\"content\":\"今日完成 12 单，一切正常。\"}}],"
                + "\"usage\":{\"total_tokens\":9}}");
        AiTaskService s = service(props(true, base, "sk-test-not-a-real-key", "some-text-model", ""), calendar(List.of()));

        AiTasks.Draft d = s.polishBriefing(req("今日完成 15 单，其中异常 2 单"));

        assertTrue(d.isReady(), d.getReason());
        assertEquals(Boolean.FALSE, d.getNumbersPreserved(), "数字被改了还判合格");
        assertTrue(d.getBasis().contains("不要采纳"), d.getBasis());
        assertTrue(d.getBasis().contains("15"), d.getBasis());
    }

    @Test
    void 没配视觉模型时照片那一格报未就绪而不是拿文字模型硬猜() throws Exception {
        AiTaskService s = service(props(true, base, "sk-test-not-a-real-key", "some-text-model", "  "),
                calendar(List.of()));

        AiTasks.Draft d = photoReq(s);

        assertFalse(d.isReady());
        assertTrue(d.getReason().contains("视觉能力未就绪"), d.getReason());
        assertEquals(0, rows.size(), "未就绪却发了外呼：" + rows);
        assertNull(seenBody.get(), "请求真的出去了");
    }

    @Test
    void 材料不够时先拒绝不交给模型() throws Exception {
        AiTaskService s = service(props(true, base, "sk-test-not-a-real-key", "some-text-model", "some-vision-model"),
                calendar(List.of()));

        assertThrows(IllegalArgumentException.class, () -> s.classifyReport(req("  ")));
        assertThrows(IllegalArgumentException.class, () -> s.mergeReports(mergeOne()));
        assertThrows(IllegalArgumentException.class, () -> s.clusterFeedback(topicReqVO(0)));
        assertEquals(0, rows.size(), "被拒的调用不该留下外呼痕迹：" + rows);
    }

    @Test
    void 脱敏出口的二次断言能红() {
        assertThrows(IllegalArgumentException.class, () -> AiTasks.assertNoPlainMobile("联系 13800138000"));
        AiTasks.assertNoPlainMobile("联系 138****8000");
        assertEquals("138****8000",
                cn.iocoder.yudao.module.transport.util.SensitiveDataMask.maskEmbeddedMobiles("13800138000"));
    }

    // ── 用例共用的三件材料 ────────────────────────────────────────────────────

    private static AiTasks.Draft photoReq(AiTaskService s) {
        AiTaskReqVO v = req("路边塌方");
        v.setPhotoUrl("https://oss.example.com/a.jpg");
        return s.checkPhotoSupport(v);
    }

    private static AiTasks.Draft mergeReq(AiTaskService s) {
        AiTaskReqVO v = new AiTaskReqVO();
        v.setItems(List.of("前面路断了过不去", "塌方把车道堵了"));
        return s.mergeReports(v);
    }

    private static AiTaskReqVO mergeOne() {
        AiTaskReqVO v = new AiTaskReqVO();
        v.setItems(List.of("只有一条"));
        return v;
    }

    private static AiTasks.Draft topicReq(AiTaskService s, int sample) {
        return s.clusterFeedback(topicReqVO(sample));
    }

    private static AiTaskReqVO topicReqVO(int sample) {
        AiTaskReqVO v = req("到站不准 5 条；司机态度 3 条");
        v.setSampleCount(sample);
        return v;
    }
}
