package cn.iocoder.yudao.module.aiassist.service.invocation;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.aiassist.controller.admin.invocation.vo.AiInvocationLogPageReqVO;
import cn.iocoder.yudao.module.aiassist.dal.dataobject.invocation.AiInvocationLogDO;

/**
 * AI 调用日志 Service（契约 §5）。
 *
 * <p>{@link #record} 的契约是**旁路**：调用方（将来的 AI 外壳）不因留痕失败而报错。
 */
public interface AiInvocationLogService {

    /**
     * 尽力记录一条调用日志。**永不抛异常** —— 留痕失败只记本地 error 日志，绝不阻断页面。
     *
     * @param log 待落库日志（maskedInput 必须已过 §4 脱敏出口）
     * @return 是否落库成功（供测试与将来的度量用；调用方无需据此改行为）
     */
    boolean record(AiInvocationLogDO log);

    PageResult<AiInvocationLogDO> getPage(AiInvocationLogPageReqVO reqVO);

}
