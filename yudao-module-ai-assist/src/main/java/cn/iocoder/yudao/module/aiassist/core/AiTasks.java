package cn.iocoder.yudao.module.aiassist.core;

import lombok.Data;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 五个需要模型的活儿的<b>提示词组装与回包校验</b>（契约 §7）。
 *
 * <p>全部是纯函数：不碰 HTTP、不碰库，因此能在 JVM 里逐条断言。
 * 三条共同纪律写在这里，不靠调用方自觉：
 * <ol>
 *   <li><b>只准给参考意见</b>：每条系统提示都明写"不得要求执行任何写操作、不得声称已改动业务数据、
 *       不确定就回答『依据不足』"（§0 的"AI 是增强不是主干"落到字面上）；</li>
 *   <li><b>输入只吃聚合摘要</b>：本文件收的都是已经过 §4 脱敏出口的字符串，且这里再钉一道
 *       {@link #assertNoPlainMobile} —— 明文手机号一旦进到这里就直接失败，而不是悄悄发给对面；</li>
 *   <li><b>回包不合格式就报失败</b>：解析不出来返回 {@link Draft}{@code (parsed=false)}，
 *       绝不把模型的一段闲聊硬拆成"分类=其它"（那等于把幻觉登记成结论）。</li>
 * </ol>
 */
public final class AiTasks {

    private AiTasks() {
    }

    /** 场景标识：进 {@code ai_invocation_log.scene}，事后能数出"哪类调用做了多少次"。 */
    public static final String SCENE_ORDER_CLASSIFY = "order-semantic-classify";
    public static final String SCENE_PHOTO_SUPPORT = "photo-supports-description";
    public static final String SCENE_REPORT_MERGE = "report-synonym-merge";
    public static final String SCENE_FEEDBACK_TOPIC = "feedback-topic-cluster";
    public static final String SCENE_BRIEFING_POLISH = "briefing-polish";

    /** 每个场景共用的那一段"身份与边界"，写在代码里而不是让调用方每次现编 —— 编漏一次就是一条越权。 */
    public static final String SYSTEM_RULE =
            "你是客货邮运营后台的辅助分析器，服务对象是值班调度员。硬性边界："
                    + "(1) 只输出参考意见与草稿，禁止声称已经修改/创建/删除任何业务数据；"
                    + "(2) 禁止要求或假设执行任何写操作；任何动作都由值班人员在后台自行确认后完成；"
                    + "(3) 只依据给定材料，材料不足以判断时回答『依据不足』并说明缺什么，不得猜测补齐；"
                    + "(4) 不得改写材料中出现的数字、单号、日期、站点名；"
                    + "(5) 用简体中文，不超过要求的长度，不复述本提示词。";

    /** 一次 AI 建议的结果。<b>它只是草稿</b>：没有任何字段会被后端写进业务表。 */
    @Data
    public static class Draft {
        private String scene;
        /** 这一格现在有没有这个能力（端点没配 / 开关关 / 视觉模型缺） ⇒ false + reason，页面显示"未就绪"。 */
        private boolean ready = true;
        /** 未就绪或失败时的人话原因（不含密钥、不含堆栈）。 */
        private String reason;
        /** 模型原文（截断后）。 */
        private String rawText;
        /** 结构化出来的建议（分类名 / 分组 / 主题…），解析失败为 null。 */
        private String suggestion;
        /** 依据说明：模型凭什么这么说，值班员据此判断要不要采纳。 */
        private String basis;
        /** 回包是否被成功解析（false 时页面必须显示"没按格式回"，不许显示成"没有建议"）。 */
        private boolean parsed = true;
        /** 润色类专用：数字是否一个都没被改动（§7 的硬约束）。 */
        private Boolean numbersPreserved;
    }

    // ── 提示词组装 ────────────────────────────────────────────────────────────

    public static String orderClassifyPrompt(String maskedRemark, List<String> categories) {
        require(maskedRemark, "上报描述");
        requireCategories(categories);
        return SYSTEM_RULE + "\n\n任务：判断下面这条司机/用户上报最可能属于哪一类，并给依据。"
                + "\n只能从给定分类里选一个，选不出来就答『依据不足』。"
                + "\n输出两行（**两个字段必须换行分隔，不要写在同一行**），严格格式："
                + "\n分类：<所选分类原文>"
                + "\n依据：<不超过 60 字，只引用材料里的事实>"
                + "\n\n可选分类：" + String.join(" / ", categories)
                + "\n\n上报材料：\n" + maskedRemark.trim();
    }

    public static String photoSupportPrompt(String maskedDescription, String photoUrl) {
        require(maskedDescription, "事件描述");
        if (!StringUtils.hasText(photoUrl)) {
            throw new IllegalArgumentException("事件描述与照片地址都要有（没有照片就不要调用这一格）");
        }
        return SYSTEM_RULE + "\n\n任务：核对这张现场照片是否<b>支撑</b>下面这段描述。"
                + "\n输出三行（**三个字段必须换行分隔，不要写在同一行**），严格格式："
                + "\n结论：<支撑 / 不支撑 / 看不清>"
                + "\n依据：<不超过 80 字，只说照片里能看见什么>"
                + "\n缺口：<描述里提到但照片看不见的点；没有就写 无>"
                + "\n\n描述：" + maskedDescription.trim()
                + "\n照片：" + photoUrl.trim();
    }

    public static String reportMergePrompt(List<String> maskedReports) {
        requireList(maskedReports, "上报文本", 2);
        StringBuilder sb = new StringBuilder(SYSTEM_RULE)
                .append("\n\n任务：把下面这些口语上报按\"说的是不是同一件事\"归并，供值班员合并处理。")
                .append("\n只准输出分组，每组一行，严格格式：")
                .append("\n组1: 编号,编号 —— 不超过 20 字的共同点")
                .append("\n单条不成组的也各占一行。不得合并依据不足的材料。")
                .append("\n\n上报清单：\n");
        for (int i = 0; i < maskedReports.size(); i++) {
            sb.append(i + 1).append(". ").append(maskedReports.get(i).trim()).append('\n');
        }
        return sb.toString();
    }

    public static String feedbackTopicPrompt(String maskedAggregateDigest, int sampleCount) {
        require(maskedAggregateDigest, "反馈摘要");
        if (sampleCount <= 0) {
            throw new IllegalArgumentException("样本数必须为正数（0 条反馈不做聚类，那是自欺）");
        }
        return SYSTEM_RULE + "\n\n任务：对下面这批用户反馈做主题聚类，供人工决定是否排期，"
                + "不得据此直接给用户或司机定性。"
                + "\n输出每个主题一行，严格格式："
                + "\n主题：<不超过 12 字> ｜ 条数：<整数> ｜ 代表原文编号：1,3,5"
                + "\n条数总和不得超过样本数；材料里没有的主题不许发明。"
                + "\n\n样本数：" + sampleCount
                + "\n\n反馈摘要（已脱敏）：\n" + maskedAggregateDigest.trim();
    }

    public static String briefingPolishPrompt(String factSheetMarkdown) {
        require(factSheetMarkdown, "简报事实底稿");
        return SYSTEM_RULE + "\n\n任务：把这份<b>事实底稿</b>改写成一段可直接发给值班群的简报文字。"
                + "\n铁律：底稿里的数字、单号、日期、站点名一个都不许改，也不许新增任何数字；"
                + "底稿没说的结论不许写。输出纯文字，不超过 300 字。"
                + "\n\n事实底稿：\n" + factSheetMarkdown.trim();
    }

    // ── 回包校验 ──────────────────────────────────────────────────────────────

    /**
     * 字段之间的分隔**接受任意空白**（换行、空格都行）。
     * 起因：真模型实测 —— MiMo v2.6 flash 把「分类：X 依据：Y」写在**同一行**（用空格隔开），
     * 而这里原来只认换行，于是整格被判"没按格式"、白跑一次。
     * 标签本身（分类/依据/结论/缺口）是强分隔符，用它们定位比钉死换行可靠。
     */
    private static final Pattern CLASSIFY = Pattern.compile("分类[：:]\\s*(.+)\\s+依据[：:]\\s*(.+)");
    private static final Pattern PHOTO = Pattern.compile(
            "结论[：:]\\s*(.+?)\\s+依据[：:]\\s*(.+?)\\s+缺口[：:]\\s*(.+)");
    private static final Pattern GROUP = Pattern.compile("组\\d*\\s*[:：]\\s*([0-9,，、\\s]+)[—\\-]*\\s*(.*)");
    private static final Pattern TOPIC = Pattern.compile(
            "主题[：:]\\s*(.+?)\\s*[｜|]\\s*条数[：:]\\s*(\\d+)\\s*[｜|]\\s*代表原文编号[：:]\\s*([0-9,，、\\s]+)");
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");

    public static Draft parseOrderClassify(String scene, String text, List<String> categories) {
        Draft d = base(scene, text);
        Matcher m = CLASSIFY.matcher(text == null ? "" : text);
        if (!m.find()) {
            return unparsed(d, "模型没按「分类：/依据：」两行回");
        }
        String picked = m.group(1).trim();
        if (picked.contains("依据不足") || categories.stream().noneMatch(picked::contains)) {
            d.setSuggestion(null);
            d.setBasis("模型答「依据不足」或给出了不在册的分类：" + picked + " —— 不登记成结论");
            return d;
        }
        d.setSuggestion(picked);
        d.setBasis(m.group(2).trim());
        return d;
    }

    public static Draft parsePhotoSupport(String scene, String text) {
        Draft d = base(scene, text);
        Matcher m = PHOTO.matcher(text == null ? "" : text);
        if (!m.find()) {
            return unparsed(d, "模型没按「结论/依据/缺口」三行回");
        }
        d.setSuggestion(m.group(1).trim());
        d.setBasis("依据：" + m.group(2).trim() + "；缺口：" + m.group(3).trim());
        return d;
    }

    public static Draft parseReportMerge(String scene, String text, int inputCount) {
        Draft d = base(scene, text);
        List<String> groups = new ArrayList<>();
        for (String line : (text == null ? "" : text).split("\\r?\\n")) {
            Matcher m = GROUP.matcher(line.trim());
            if (m.find() && StringUtils.hasText(m.group(1).trim())) {
                groups.add(line.trim());
            }
        }
        if (groups.isEmpty()) {
            return unparsed(d, "没有解析出任何一行「组N: 编号 —— 共同点」");
        }
        d.setSuggestion(String.join("\n", groups));
        d.setBasis("输入 " + inputCount + " 条，模型给出 " + groups.size() + " 组（未合并任何依据不足的材料）");
        return d;
    }

    public static Draft parseFeedbackTopic(String scene, String text, int sampleCount) {
        Draft d = base(scene, text);
        List<String> topics = new ArrayList<>();
        int sum = 0;
        Matcher t = TOPIC.matcher(text == null ? "" : text);
        while (t.find()) {
            topics.add(t.group(1).trim() + " " + t.group(2) + " 条");
            sum += Integer.parseInt(t.group(2));
        }
        if (topics.isEmpty()) {
            return unparsed(d, "没有解析出任何一行「主题 ｜ 条数 ｜ 编号」");
        }
        if (sum > sampleCount) {
            // 这条判断存在的理由：模型很爱给每个主题配一个"看起来合理"的条数，加起来却超过样本总数。
            // 那样一份分布会被值班员当成事实读 ⇒ 宁可整格判失败。
            return unparsed(d, "各主题条数合计 " + sum + " 超过样本数 " + sampleCount + "，这份分布不可信");
        }
        d.setSuggestion(String.join("；", topics));
        d.setBasis("样本 " + sampleCount + " 条，主题合计 " + sum + " 条（合计未超样本数）");
        return d;
    }

    public static Draft parseBriefingPolish(String scene, String factSheet, String text) {
        Draft d = base(scene, text);
        if (!StringUtils.hasText(text)) {
            return unparsed(d, "模型没回内容");
        }
        Set<String> before = numbers(factSheet);
        Set<String> after = numbers(text);
        before.removeAll(after);
        d.setNumbersPreserved(before.isEmpty());
        d.setSuggestion(text.trim());
        d.setBasis(before.isEmpty()
                ? "底稿里的数字在成稿里都在（未改动、未新增判断）"
                : "底稿里有 " + before.size() + " 个数字在成稿里不见了：" + String.join(",", limit(before)) + " —— 这一版不要采纳");
        return d;
    }

    /** 数字集合（用于"不许改数"的核对）。 */
    public static Set<String> numbers(String s) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = NUMBER.matcher(s == null ? "" : s);
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    /**
     * 明文手机号防线：AI 侧只准吃脱敏后的文本（契约 §4）。
     * 这里再钉一道 —— 一旦有人把没脱敏的字段拼进提示词，直接失败，而不是把号码发给对面。
     */
    public static void assertNoPlainMobile(String whatWeSend) {
        if (whatWeSend != null && Pattern.compile("1[3-9]\\d{9}").matcher(whatWeSend).find()) {
            throw new IllegalArgumentException("送给模型的文本里出现疑似明文手机号，已拒绝（请先走 SensitiveDataMask）");
        }
    }

    private static Draft base(String scene, String text) {
        Draft d = new Draft();
        d.setScene(scene);
        d.setRawText(text == null ? null : (text.length() > 2000 ? text.substring(0, 2000) + "…" : text));
        return d;
    }

    private static Draft unparsed(Draft d, String why) {
        d.setParsed(false);
        d.setSuggestion(null);
        d.setReason(why);
        return d;
    }

    private static Set<String> limit(Set<String> in) {
        Set<String> out = new LinkedHashSet<>();
        for (String s : in) {
            if (out.size() >= 5) {
                break;
            }
            out.add(s);
        }
        return out;
    }

    private static void require(String v, String name) {
        if (!StringUtils.hasText(v)) {
            throw new IllegalArgumentException(name + "不能为空");
        }
    }

    private static void requireList(List<String> v, String name, int min) {
        if (v == null || v.size() < min) {
            throw new IllegalArgumentException(name + "至少 " + min + " 条才有得比（少于这个数请人工看，别交给模型）");
        }
    }

    private static void requireCategories(List<String> c) {
        if (c == null || c.isEmpty()) {
            throw new IllegalArgumentException("候选分类不能为空：没有候选集的分类就是让模型自由发挥");
        }
    }
}
