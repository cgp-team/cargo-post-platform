package cn.iocoder.yudao.module.aiassist.service.capability;

import cn.iocoder.yudao.module.aiassist.controller.admin.capability.vo.AiCapabilityStatusRespVO;

/**
 * AI 能力状态 Service（契约 §7）。
 */
public interface AiCapabilityService {

    /**
     * 报告当前 AI 能力是否就绪。这是个**只读状态口**，不发起任何模型调用。
     */
    AiCapabilityStatusRespVO status();

}
