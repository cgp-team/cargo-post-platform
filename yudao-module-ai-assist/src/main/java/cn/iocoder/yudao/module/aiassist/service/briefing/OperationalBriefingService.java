package cn.iocoder.yudao.module.aiassist.service.briefing;

import cn.iocoder.yudao.module.aiassist.controller.admin.briefing.vo.OperationalBriefingReqVO;
import cn.iocoder.yudao.module.aiassist.controller.admin.briefing.vo.OperationalBriefingRespVO;

/**
 * 运营简报模板 Service（契约 §6 第 4 项）。
 */
public interface OperationalBriefingService {

    /**
     * 把已取到的指标排成一份 Markdown 模板文档。
     */
    OperationalBriefingRespVO render(OperationalBriefingReqVO reqVO);

}
