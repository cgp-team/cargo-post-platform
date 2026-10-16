package cn.iocoder.yudao.module.aiassist.service.briefing;

import cn.iocoder.yudao.module.aiassist.controller.admin.briefing.vo.OperationalBriefingReqVO;
import cn.iocoder.yudao.module.aiassist.controller.admin.briefing.vo.OperationalBriefingRespVO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 运营简报模板的排版口径（契约 §6 第 4 项）。
 *
 * <p>钉的是"静默失真"：数值被悄悄改写成 0、竖线把表格撑破、缺值被留空——这三种都不会抛异常，
 * 只会让存档的简报读起来像是另一回事。
 */
class OperationalBriefingServiceImplTest {

    private final OperationalBriefingServiceImpl service = new OperationalBriefingServiceImpl();

    private static OperationalBriefingReqVO.Metric metric(String name, String value, String unit, String note) {
        OperationalBriefingReqVO.Metric m = new OperationalBriefingReqVO.Metric();
        m.setName(name);
        m.setValue(value);
        m.setUnit(unit);
        m.setNote(note);
        return m;
    }

    @Test
    void rendersTitlePeriodAndEachMetricRow() {
        OperationalBriefingReqVO req = new OperationalBriefingReqVO();
        req.setTitle("石桥镇 · 早班运营简报");
        req.setPeriodLabel("2026-10-14 ~ 2026-10-20");
        req.setMetrics(List.of(
                metric("未分配订单数", "17", "单", "集中在石桥镇"),
                metric("平均载货率", "82", "%", null)));
        req.setNotes(List.of("本周逢集，农产寄件偏多。"));

        OperationalBriefingRespVO resp = service.render(req);

        assertEquals(2, resp.getMetricCount());
        assertTrue(resp.getMarkdown().startsWith("# 石桥镇 · 早班运营简报"), resp.getMarkdown());
        assertTrue(resp.getMarkdown().contains("统计周期：2026-10-14 ~ 2026-10-20"), resp.getMarkdown());
        assertTrue(resp.getMarkdown().contains("| 未分配订单数 | 17 | 单 | 集中在石桥镇 |"), resp.getMarkdown());
        assertTrue(resp.getMarkdown().contains("- 本周逢集，农产寄件偏多。"), resp.getMarkdown());
    }

    /** 缺值必须原样留在表里（"未取到"不能被折叠成 0，也不能留空）。 */
    @Test
    void missingValueIsPreservedNotFoldedToZero() {
        OperationalBriefingReqVO req = new OperationalBriefingReqVO();
        req.setTitle("简报");
        req.setMetrics(List.of(metric("今日货量", "未取到", "件", null)));

        String md = service.render(req).getMarkdown();

        assertTrue(md.contains("| 今日货量 | 未取到 | 件 |  |"), md);
    }

    /** 竖线会把 Markdown 表格撑破，必须替换成全角，行数不能被破坏。 */
    @Test
    void pipeInValueDoesNotBreakTable() {
        OperationalBriefingReqVO req = new OperationalBriefingReqVO();
        req.setTitle("简报");
        req.setMetrics(List.of(metric("备注|带竖线", "1|2", null, null)));

        String md = service.render(req).getMarkdown();

        assertTrue(md.contains("备注｜带竖线"), md);
        assertTrue(md.contains("1｜2"), md);
    }

}
