package cn.iocoder.yudao.module.aiassist.service.briefing;

import cn.iocoder.yudao.module.aiassist.controller.admin.briefing.vo.OperationalBriefingReqVO;
import cn.iocoder.yudao.module.aiassist.controller.admin.briefing.vo.OperationalBriefingRespVO;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDateTime;

/**
 * 运营简报模板实现（契约 §6 第 4 项）。
 *
 * <p>纯字符串拼装：把已取到的指标排成一份可读、可存档的 Markdown。纪律两条：
 * <ol>
 *   <li>数值用**文字**承载（"未取到"就写"未取到"），绝不把缺值折叠成 0 —— 与金额那一族同一条口径；</li>
 *   <li>只排版，不润色事实、不加"形势喜人"这类没有依据的话（它将来接模型润色时，这份模板仍是事实底稿）。</li>
 * </ol>
 */
@Service
@Validated
public class OperationalBriefingServiceImpl implements OperationalBriefingService {

    @Override
    public OperationalBriefingRespVO render(OperationalBriefingReqVO reqVO) {
        LocalDateTime now = LocalDateTime.now();
        StringBuilder md = new StringBuilder();
        md.append("# ").append(escape(reqVO.getTitle())).append("\n\n");
        if (StringUtils.hasText(reqVO.getPeriodLabel())) {
            md.append("> 统计周期：").append(escape(reqVO.getPeriodLabel())).append("\n\n");
        }
        md.append("> 生成时间：").append(now).append("\n\n");
        md.append("## 指标\n\n");
        md.append("| 指标 | 数值 | 单位 | 备注 |\n");
        md.append("| --- | --- | --- | --- |\n");
        for (OperationalBriefingReqVO.Metric m : reqVO.getMetrics()) {
            md.append("| ").append(escape(m.getName()))
                    .append(" | ").append(escape(m.getValue()))
                    .append(" | ").append(escape(nullToEmpty(m.getUnit())))
                    .append(" | ").append(escape(nullToEmpty(m.getNote())))
                    .append(" |\n");
        }
        if (!CollectionUtils.isEmpty(reqVO.getNotes())) {
            md.append("\n## 备注\n\n");
            for (String note : reqVO.getNotes()) {
                if (StringUtils.hasText(note)) {
                    md.append("- ").append(escape(note)).append("\n");
                }
            }
        }

        OperationalBriefingRespVO resp = new OperationalBriefingRespVO();
        resp.setTitle(reqVO.getTitle());
        resp.setGeneratedAt(now);
        resp.setMarkdown(md.toString());
        resp.setMetricCount(reqVO.getMetrics().size());
        return resp;
    }

    /** Markdown 表格里的竖线与换行会破坏排版，替换为全角竖线/空格。 */
    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("|", "｜").replace("\r", " ").replace("\n", " ");
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

}
