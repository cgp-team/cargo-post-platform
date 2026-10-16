package cn.iocoder.yudao.module.aiassist.service.capability;

import cn.iocoder.yudao.module.aiassist.config.AiAssistProperties;
import cn.iocoder.yudao.module.aiassist.controller.admin.capability.vo.AiCapabilityStatusRespVO;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

/**
 * AI 能力状态实现（契约 §7）。
 *
 * <p>判定只有两步：开关是否打开、端点是否配置。两步都满足才 ready。原因文案必须指出**缺哪一步**，
 * 不能笼统一句"不可用"（运维与接手的人都靠这句话定位）。
 */
@Service
@Validated
public class AiCapabilityServiceImpl implements AiCapabilityService {

    private final AiAssistProperties properties;

    public AiCapabilityServiceImpl(AiAssistProperties properties) {
        this.properties = properties;
    }

    @Override
    public AiCapabilityStatusRespVO status() {
        boolean endpointConfigured = StringUtils.hasText(properties.getBaseUrl());
        boolean ready = properties.isEnabled() && endpointConfigured;

        AiCapabilityStatusRespVO vo = new AiCapabilityStatusRespVO();
        vo.setEnabled(properties.isEnabled());
        vo.setEndpointConfigured(endpointConfigured);
        vo.setReady(ready);
        vo.setModel(properties.getModel());
        if (!properties.isEnabled()) {
            vo.setReason("能力未就绪：开关 buspost.ai.enabled 未打开（服务端未启用 AI）。");
        } else if (!endpointConfigured) {
            vo.setReason("能力未就绪：服务端未配置推理端点（buspost.ai.base-url 为空）。");
        } else {
            vo.setReason("端点与开关均已配置。本接口只报状态，不产生任何 AI 输出。");
        }
        return vo;
    }

}
