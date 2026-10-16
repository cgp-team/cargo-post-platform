package cn.iocoder.yudao.module.aiassist.service.capability;

import cn.iocoder.yudao.module.aiassist.config.AiAssistProperties;
import cn.iocoder.yudao.module.aiassist.controller.admin.capability.vo.AiCapabilityStatusRespVO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI 能力状态判定（契约 §7）。
 *
 * <p>三层就绪线，缺一层就得红：开关关 / 端点空 / 两者齐。
 */
class AiCapabilityServiceImplTest {

    private static AiAssistProperties props(boolean enabled, String baseUrl, String model) {
        AiAssistProperties p = new AiAssistProperties();
        p.setEnabled(enabled);
        p.setBaseUrl(baseUrl);
        p.setModel(model);
        return p;
    }

    /** 没开开关 ⇒ 未就绪，且原因必须点明是开关那一步。 */
    @Test
    void disabledIsNotReadyAndBlamesSwitch() {
        AiCapabilityStatusRespVO vo = new AiCapabilityServiceImpl(props(false, "http://x", "m")).status();
        assertFalse(vo.getReady());
        assertFalse(vo.getEnabled());
        assertTrue(vo.getReason().contains("buspost.ai.enabled"), vo.getReason());
    }

    /** 开了开关但没配端点 ⇒ 仍未就绪，且原因必须点明是端点那一步（不能笼统说"不可用"）。 */
    @Test
    void enabledButNoEndpointIsNotReadyAndBlamesEndpoint() {
        AiCapabilityStatusRespVO vo = new AiCapabilityServiceImpl(props(true, "  ", "m")).status();
        assertFalse(vo.getReady());
        assertTrue(vo.getEnabled());
        assertFalse(vo.getEndpointConfigured());
        assertTrue(vo.getReason().contains("buspost.ai.base-url"), vo.getReason());
    }

    /** 开关 + 端点齐 ⇒ 就绪。 */
    @Test
    void enabledWithEndpointIsReady() {
        AiCapabilityStatusRespVO vo = new AiCapabilityServiceImpl(props(true, "http://endpoint", "m1")).status();
        assertTrue(vo.getReady());
        assertTrue(vo.getEndpointConfigured());
        assertEquals("m1", vo.getModel());
    }

}
