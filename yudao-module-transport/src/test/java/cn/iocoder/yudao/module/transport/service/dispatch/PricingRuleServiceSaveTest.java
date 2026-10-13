package cn.iocoder.yudao.module.transport.service.dispatch;

import cn.iocoder.yudao.module.transport.controller.admin.pricing.vo.PricingRuleSaveReqVO;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.PricingRuleDO;
import cn.iocoder.yudao.module.transport.dal.mysql.dispatch.PricingRuleMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 计价规则保存的两支形状：库里没行 ⇒ 只能 insert（不许 updateById 一个不存在的 id），
 * 库里已有行 ⇒ 只能 update 那一行（不许每次都插一条新的，因为 selectFirst 永远取最早那条，
 * 多插等于让后台"保存了但没生效"）。
 */
@ExtendWith(MockitoExtension.class)
class PricingRuleServiceSaveTest {

    @Mock private PricingRuleMapper pricingRuleMapper;
    @InjectMocks private PricingRuleService pricingRuleService;

    private PricingRuleSaveReqVO req() {
        PricingRuleSaveReqVO vo = new PricingRuleSaveReqVO();
        vo.setPassengerPricePerKm(new BigDecimal("1.20"));
        vo.setCargoPricePerItem(new BigDecimal("6.00"));
        vo.setPostalPricePerItem(new BigDecimal("3.50"));
        vo.setVehicleCostPerKm(new BigDecimal("2.80"));
        vo.setAvgSpeedKmh(new BigDecimal("30"));
        vo.setStopServiceMinutes(4);
        return vo;
    }

    @Test
    void saveRule_withoutExistingRow_insertsOnce() {
        when(pricingRuleMapper.selectFirst()).thenReturn(null);
        pricingRuleService.saveRule(req());
        ArgumentCaptor<PricingRuleDO> cap = ArgumentCaptor.forClass(PricingRuleDO.class);
        verify(pricingRuleMapper).insert(cap.capture());
        verify(pricingRuleMapper, never()).updateById(any(PricingRuleDO.class));
        assertNull(cap.getValue().getId(), "新行不该带 id");
        assertEquals(new BigDecimal("1.20"), cap.getValue().getPassengerPricePerKm());
        assertEquals(4, cap.getValue().getStopServiceMinutes());
    }

    @Test
    void saveRule_withExistingRow_updatesThatRow() {
        when(pricingRuleMapper.selectFirst()).thenReturn(PricingRuleDO.builder().id(7L).build());
        pricingRuleService.saveRule(req());
        ArgumentCaptor<PricingRuleDO> cap = ArgumentCaptor.forClass(PricingRuleDO.class);
        verify(pricingRuleMapper).updateById(cap.capture());
        verify(pricingRuleMapper, never()).insert(any(PricingRuleDO.class));
        assertEquals(7L, cap.getValue().getId(), "必须改的是当前生效那一行");
        assertEquals(new BigDecimal("30"), cap.getValue().getAvgSpeedKmh());
    }

    @Test
    void getConfiguredRule_returnsNullWhenNothingConfigured() {
        when(pricingRuleMapper.selectFirst()).thenReturn(null);
        assertNull(pricingRuleService.getConfiguredRule(), "没配就是没配，不许在这里悄悄兜成默认值");
    }
}
