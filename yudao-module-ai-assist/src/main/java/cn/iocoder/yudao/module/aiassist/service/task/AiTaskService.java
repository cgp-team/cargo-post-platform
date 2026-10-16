package cn.iocoder.yudao.module.aiassist.service.task;

import cn.iocoder.yudao.module.aiassist.config.AiAssistProperties;
import cn.iocoder.yudao.module.aiassist.core.AiGateway;
import cn.iocoder.yudao.module.aiassist.core.AiTasks;
import cn.iocoder.yudao.module.transport.service.dispatch.TownEventCalendarService;
import cn.iocoder.yudao.module.transport.util.SensitiveDataMask;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.TownEventCalendarDO;
import cn.iocoder.yudao.module.aiassist.controller.admin.task.vo.AiTaskReqVO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 五个需要模型的活儿 + 一条不依赖模型的情景提示（契约 §6 第 2 项 / §7）。
 *
 * <p>结构上只有三件事：拼提示（{@link AiTasks} 的纯函数）→ 走唯一外呼口（{@link AiGateway}）→ 校验回包。
 * <b>这里没有任何一处写业务表</b>：返回值全是 {@link AiTasks.Draft}（草稿），
 * 值班员看完自己决定要不要去既有页面操作（§0「AI 是增强不是主干」「人工终审」）。
 *
 * <p>未就绪时的行为：{@link AiGateway.NotReady} 被翻成 {@code ready=false + reason}，
 * 页面显示"能力未就绪"，<b>不返回任何像结论的句子</b>；开关关掉后后台其余页面零变化。
 */
@Service
public class AiTaskService {

    @Resource
    private AiGateway gateway;
    @Resource
    private AiAssistProperties props;
    /** 日历是主线那一份（契约 §6.1）：本模块只读它，不再建第二张表。 */
    @Resource
    private TownEventCalendarService townEventCalendarService;

    /** 基础5：工单/上报的语义分类建议。 */
    public AiTasks.Draft classifyReport(AiTaskReqVO reqVO) {
        requireText(reqVO.getText(), "上报描述");
        List<String> cats = CollectionUtils.isEmpty(reqVO.getCategories())
                ? List.of("车辆故障", "道路阻断", "收件人不在", "货物异常", "其它") : reqVO.getCategories();
        return call(AiTasks.SCENE_ORDER_CLASSIFY,
                AiTasks.orderClassifyPrompt(mask(reqVO.getText()), cats), false,
                t -> AiTasks.parseOrderClassify(AiTasks.SCENE_ORDER_CLASSIFY, t, cats));
    }

    /** 基础5：照片是否支撑描述（需要视觉模型；没配就明说未就绪，不退化成"只看文字猜"）。 */
    public AiTasks.Draft checkPhotoSupport(AiTaskReqVO reqVO) {
        requireText(reqVO.getText(), "事件描述");
        requireText(reqVO.getPhotoUrl(), "照片地址");
        if (!props.visionReady()) {
            return notReady(AiTasks.SCENE_PHOTO_SUPPORT,
                    "视觉能力未就绪：需要配置 buspost.ai.vision-model（没配时这一格不出结论，避免只看文字猜照片）");
        }
        String prompt = AiTasks.photoSupportPrompt(mask(reqVO.getText()), reqVO.getPhotoUrl().trim());
        // 视觉模型要的是"图 + 文"，这里把地址一并写进提示：自部署网关大多支持 image_url，
        // 不支持的会回"看不清"，而不是被我们假装成"支撑"。
        return call(AiTasks.SCENE_PHOTO_SUPPORT, prompt, true,
                t -> AiTasks.parsePhotoSupport(AiTasks.SCENE_PHOTO_SUPPORT, t));
    }

    /** 创新2：口语上报的同义归并（少于 2 条直接拒 —— 一条没什么可归的）。 */
    public AiTasks.Draft mergeReports(AiTaskReqVO reqVO) {
        if (CollectionUtils.isEmpty(reqVO.getItems()) || reqVO.getItems().size() < 2) {
            throw new IllegalArgumentException("至少给两条上报（少于两条请人工看，别交给模型）");
        }
        List<String> masked = new ArrayList<>();
        for (String s : reqVO.getItems()) {
            if (StringUtils.hasText(s)) {
                masked.add(mask(s));
            }
        }
        int n = masked.size();
        return call(AiTasks.SCENE_REPORT_MERGE, AiTasks.reportMergePrompt(masked), false,
                t -> AiTasks.parseReportMerge(AiTasks.SCENE_REPORT_MERGE, t, n));
    }

    /** 创新4：反馈主题聚类（先量过样本再调；0 条不做聚类）。 */
    public AiTasks.Draft clusterFeedback(AiTaskReqVO reqVO) {
        requireText(reqVO.getText(), "反馈摘要");
        int sample = reqVO.getSampleCount() == null ? 0 : reqVO.getSampleCount();
        if (sample <= 0) {
            throw new IllegalArgumentException("样本数必须为正：0 条反馈上做聚类是自欺");
        }
        String digest = mask(reqVO.getText());
        return call(AiTasks.SCENE_FEEDBACK_TOPIC, AiTasks.feedbackTopicPrompt(digest, sample), false,
                t -> AiTasks.parseFeedbackTopic(AiTasks.SCENE_FEEDBACK_TOPIC, t, sample));
    }

    /** 创新3：把简报事实底稿润色成文字（数字一个都不许改，改了就判不合格）。 */
    public AiTasks.Draft polishBriefing(AiTaskReqVO reqVO) {
        requireText(reqVO.getText(), "简报事实底稿");
        String sheet = mask(reqVO.getText());
        return call(AiTasks.SCENE_BRIEFING_POLISH, AiTasks.briefingPolishPrompt(sheet), false,
                t -> AiTasks.parseBriefingPolish(AiTasks.SCENE_BRIEFING_POLISH, sheet, t));
    }

    /**
     * 创新1：<b>不依赖模型</b>的情景提示 —— 赶集/农忙当天该提前留舱。
     *
     * <p>为什么它不算 AI 功能：日历 + 既有班次统计就够出这句话，规则比模型可靠也可解释。
     * 这也是"关掉 AI 整套后台仍完整可用"那条红线的活样本：这一格在 {@code buspost.ai.enabled=false}
     * 时照样出结果（{@code polished=false}），开着 AI 只是多一层措辞润色。
     */
    public AiTasks.Draft scenarioHints(LocalDate day) {
        LocalDate when = day == null ? LocalDate.now().plusDays(1) : day;
        List<TownEventCalendarDO> rows;
        try {
            rows = townEventCalendarService.listEffectiveOn(when);
        } catch (Exception e) {
            // 读不回日历时不许显示成"这一天没有事件"（缺值不是结论）
            AiTasks.Draft d = new AiTasks.Draft();
            d.setScene(AiTasks.SCENE_FEEDBACK_TOPIC);
            d.setReady(false);
            d.setParsed(false);
            d.setReason("日历没读到（" + e.getClass().getSimpleName() + "）：这一格是「没取到」，不是「没有事件」");
            return d;
        }
        AiTasks.Draft d = new AiTasks.Draft();
        d.setScene("scenario-capacity-hint");
        if (CollectionUtils.isEmpty(rows)) {
            d.setReady(true);
            d.setParsed(true);
            d.setSuggestion(null);
            d.setBasis(when + " 没读到生效事件。两种可能：日历上确实没登记，或这一天落在周期规则之外 —— "
                    + "平台不替这一格下结论，请人工核对后再决定是否留舱");
            return d;
        }
        List<String> lines = new ArrayList<>();
        for (TownEventCalendarDO r : rows) {
            String rule = "EVERY_N_DAYS".equals(r.getRecurrence())
                    ? "每 " + (r.getIntervalDays() == null ? "?" : r.getIntervalDays()) + " 天那一轮"
                    : "登记的那一天";
            lines.add(r.getTown() + "：" + typeName(r.getEventType()) + "（" + rule
                    + (StringUtils.hasText(r.getNote()) ? "；备注：" + r.getNote() : "") + "）→ 建议提前与调度确认留舱量");
        }
        d.setReady(true);
        d.setParsed(true);
        d.setSuggestion(String.join("\n", lines));
        d.setBasis("依据：乡镇事件日历 " + rows.size() + " 条在 " + when + " 生效（规则展开，未经模型；"
                + "留舱量仍需值班员按班次实载核定）");
        return d;
    }

    private static String typeName(String t) {
        if (t == null) {
            return "未标类型";
        }
        switch (t) {
            case "MARKET": return "赶集";
            case "FARMING": return "农忙";
            case "HOLIDAY": return "节假日";
            case "EVENT": return "活动";
            default: return t;
        }
    }

    /** 送出去之前一律走 §4 的唯一出口；本模块不再自己写手机号正则（那正是第 13 本闸抓的形状）。 */
    static String mask(String s) {
        String out = SensitiveDataMask.maskEmbeddedMobiles(s);
        AiTasks.assertNoPlainMobile(out);
        return out;
    }

    private interface Parse {
        AiTasks.Draft of(String text);
    }

    private AiTasks.Draft call(String scene, String prompt, boolean vision, Parse parse) {
        AiTasks.assertNoPlainMobile(prompt);
        try {
            AiGateway.Reply r = gateway.complete(scene, prompt, vision);
            return parse.of(r.getText());
        } catch (AiGateway.NotReady e) {
            return notReady(scene, e.getMessage());
        } catch (IllegalStateException e) {
            // 端点回了错 / 回了空：说清"这次没有输出"，不拿上一次的结果冒充
            AiTasks.Draft d = notReady(scene, "调用未成功：" + e.getMessage());
            d.setParsed(false);
            return d;
        }
    }

    private static AiTasks.Draft notReady(String scene, String why) {
        AiTasks.Draft d = new AiTasks.Draft();
        d.setScene(scene);
        d.setReady(false);
        d.setParsed(false);
        d.setReason(why);
        return d;
    }

    private static void requireText(String v, String name) {
        if (!StringUtils.hasText(v)) {
            throw new IllegalArgumentException(name + "不能为空");
        }
    }
}
