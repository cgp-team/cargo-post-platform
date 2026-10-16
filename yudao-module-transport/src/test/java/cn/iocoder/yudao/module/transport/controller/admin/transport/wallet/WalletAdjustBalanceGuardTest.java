package cn.iocoder.yudao.module.transport.controller.admin.transport.wallet;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.transport.service.wallet.WalletService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.WALLET_ADJUST_AMOUNT_EMPTY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 后台"手动调整余额"那一格的三档出口（队列 #89 / 台账 D-136）。
 *
 * 钉的是一条**会说谎的成功**：改之前 `amount=0` 两个分支都不进，方法照样 `return success("调整成功")`
 * —— 运营看到的是"调成功了"，库里余额一格没动、交易流水一行没加。0 元不是一笔调整，
 * 是一次没发生却被宣布发生的操作，所以它必须报错，不能静默。
 */
@ExtendWith(MockitoExtension.class)
class WalletAdjustBalanceGuardTest {

    @Mock private WalletService walletService;
    @InjectMocks private WalletController controller;

    @Test
    void zeroAmount_isRejectedInsteadOfAnnouncingASuccessThatDidNothing() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.adjustBalance(10L, new BigDecimal("0"), "运营调整"));
        assertEquals(WALLET_ADJUST_AMOUNT_EMPTY.getCode(), ex.getCode());
        verify(walletService, never()).recharge(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
        verify(walletService, never()).consume(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void positiveAmount_rechargesTheBalance() {
        controller.adjustBalance(10L, new BigDecimal("12.50"), "运营调整");
        verify(walletService).recharge(10L, new BigDecimal("12.50"), "balance", "admin-adjust");
        verify(walletService, never()).consume(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    /** 负数走扣款，且金额取反后交给 consume（别让 -12.50 原样进扣款口，那会变成"扣成一笔负数"= 加钱） */
    @Test
    void negativeAmount_consumesTheAbsoluteValue() {
        controller.adjustBalance(10L, new BigDecimal("-12.50"), "赔付扣回");
        verify(walletService).consume(10L, new BigDecimal("12.50"), "赔付扣回", "admin-adjust");
    }
}
