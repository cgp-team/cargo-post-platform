package cn.iocoder.yudao.module.aiassist.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 提示词组装与回包校验（契约 §0 / §4 / §7 的可执行版本）。
 *
 * <p>这一本用例钉的是"AI 只会给参考意见、格式不对就不给结论、明文手机号送不出去"这三句话，
 * 而不是"模型答得好不好"（后者要真端点，归运行时验）。
 */
class AiTasksTest {

    private static final List<String> CATS = List.of("车辆故障", "道路阻断", "收件人不在", "货物异常");

    @Test
    void 每条提示都带上只出参考意见的边界() {
        String p = AiTasks.orderClassifyPrompt("路上塌方了过不去", CATS);
        assertTrue(p.contains("禁止声称已经修改"), p);
        assertTrue(p.contains("禁止要求或假设执行任何写操作"), p);
        assertTrue(p.contains("依据不足"), p);
        assertTrue(p.contains("路上塌方了过不去"), p);
    }

    @Test
    void 没有候选集就不让做分类() {
        assertThrows(IllegalArgumentException.class, () -> AiTasks.orderClassifyPrompt("塌方", List.of()));
        assertThrows(IllegalArgumentException.class, () -> AiTasks.orderClassifyPrompt("  ", CATS));
    }

    @Test
    void 少于两条上报不做归并() {
        assertThrows(IllegalArgumentException.class, () -> AiTasks.reportMergePrompt(List.of("只有一条")));
        assertTrue(AiTasks.reportMergePrompt(List.of("塌方", "路断了")).contains("组1"));
    }

    @Test
    void 零条反馈不做聚类() {
        assertThrows(IllegalArgumentException.class, () -> AiTasks.feedbackTopicPrompt("无", 0));
    }

    @Test
    void 分类回包合格式时给出建议与依据() {
        AiTasks.Draft d = AiTasks.parseOrderClassify(AiTasks.SCENE_ORDER_CLASSIFY,
                "分类：道路阻断\n依据：材料提到塌方、车辆无法通行", CATS);
        assertTrue(d.isParsed());
        assertEquals("道路阻断", d.getSuggestion());
        assertTrue(d.getBasis().contains("塌方"));
    }

    /** 模型爱造一个不在册的分类名；那不能变成结论。 */
    @Test
    void 分类不在候选集里就不登记成结论() {
        AiTasks.Draft d = AiTasks.parseOrderClassify(AiTasks.SCENE_ORDER_CLASSIFY,
                "分类：司机心情不好\n依据：语气消极", CATS);
        assertNull(d.getSuggestion());
        assertTrue(d.getBasis().contains("不在册"));
    }

    /**
     * **真模型实测**（2026-10-17，MiMo v2.6 flash）：它把「分类：X 依据：Y」写在**同一行**（空格隔开），
     * 而原来只认换行 ⇒ 整格被判"没按格式"、白跑一次真调用。
     * 于是把**分隔符**放宽成任意空白，但标签、候选集校验、"依据不足就不登记"一概不动。
     */
    @Test
    void 真模型把分类与依据写在同一行时也解析得出() {
        AiTasks.Draft d = AiTasks.parseOrderClassify(AiTasks.SCENE_ORDER_CLASSIFY,
                "分类：道路阻断 依据：材料称路上塌方了、车过不去，属道路通行受阻，需联系处理。", CATS);
        assertTrue(d.isParsed());
        assertEquals("道路阻断", d.getSuggestion());
        assertTrue(d.getBasis().contains("塌方"));
    }

    /** 同一个根因，照片那格照做：三个字段写在一行也要吃下来。 */
    @Test
    void 照片三段写在同一行时也解析得出() {
        AiTasks.Draft d = AiTasks.parsePhotoSupport(AiTasks.SCENE_PHOTO_SUPPORT,
                "结论：不支撑 依据：只见路面 缺口：未见塌方");
        assertTrue(d.isParsed());
        assertEquals("不支撑", d.getSuggestion());
        assertTrue(d.getBasis().contains("未见塌方"));
    }

    @Test
    void 回包不是要求的格式时判未解析而不是空建议() {
        AiTasks.Draft d = AiTasks.parsePhotoSupport(AiTasks.SCENE_PHOTO_SUPPORT, "这张照片看起来还行。");
        assertFalse(d.isParsed());
        assertNull(d.getSuggestion());
        assertNotNull(d.getReason());
    }

    @Test
    void 照片三行格式解析出结论与缺口() {
        AiTasks.Draft d = AiTasks.parsePhotoSupport(AiTasks.SCENE_PHOTO_SUPPORT,
                "结论：支撑\n依据：可见路面塌方约半幅\n缺口：未见车辆受损");
        assertTrue(d.isParsed());
        assertEquals("支撑", d.getSuggestion());
        assertTrue(d.getBasis().contains("未见车辆受损"));
    }

    @Test
    void 归并结果按行取回并登记输入条数() {
        AiTasks.Draft d = AiTasks.parseReportMerge(AiTasks.SCENE_REPORT_MERGE,
                "组1: 1,3 —— 都指同一段塌方\n组2: 2 —— 单独一件事", 3);
        assertTrue(d.isParsed());
        assertTrue(d.getSuggestion().contains("组1"));
        assertTrue(d.getBasis().contains("输入 3 条"));
    }

    @Test
    void 聚类条数超过样本数时整格判失败() {
        AiTasks.Draft d = AiTasks.parseFeedbackTopic(AiTasks.SCENE_FEEDBACK_TOPIC,
                "主题：取件慢 ｜ 条数：7 ｜ 代表原文编号：1,2\n主题：丢件 ｜ 条数：6 ｜ 代表原文编号：3", 5);
        assertFalse(d.isParsed());
        assertTrue(d.getReason().contains("超过样本数"));
    }

    @Test
    void 聚类条数不超时给出主题分布() {
        AiTasks.Draft d = AiTasks.parseFeedbackTopic(AiTasks.SCENE_FEEDBACK_TOPIC,
                "主题：取件慢 ｜ 条数：3 ｜ 代表原文编号：1,2\n主题：丢件 ｜ 条数：1 ｜ 代表原文编号：3", 5);
        assertTrue(d.isParsed());
        assertTrue(d.getSuggestion().contains("取件慢 3 条"));
    }

    /** 润色最危险的一种坏法是"顺手把数字改圆"。这一条钉的就是它。 */
    @Test
    void 润色改了数字就判不合格并点名少了哪个() {
        String sheet = "本班 12 单，货量 3.5 吨，尾单 011C02。";
        AiTasks.Draft ok = AiTasks.parseBriefingPolish(AiTasks.SCENE_BRIEFING_POLISH, sheet,
                "本班共完成 12 单，货量 3.5 吨，含尾单 011C02。");
        assertTrue(ok.getNumbersPreserved(), ok.getBasis());

        AiTasks.Draft bad = AiTasks.parseBriefingPolish(AiTasks.SCENE_BRIEFING_POLISH, sheet,
                "本班运行顺利，货量充足。");
        assertFalse(bad.getNumbersPreserved());
        assertTrue(bad.getBasis().contains("不要采纳"), bad.getBasis());
        Set<String> gone = AiTasks.numbers(sheet);
        gone.removeAll(AiTasks.numbers("本班运行顺利，货量充足。"));
        assertTrue(gone.contains("3.5"));
    }

    @Test
    void 明文手机号送不出去() {
        assertThrows(IllegalArgumentException.class,
                () -> AiTasks.assertNoPlainMobile("司机 13800138000 说路断了"));
        AiTasks.assertNoPlainMobile("司机 138****8000 说路断了");
    }
}
