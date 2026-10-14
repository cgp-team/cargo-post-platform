package cn.iocoder.yudao.module.transport.service.wallet;

import cn.iocoder.yudao.module.transport.dal.dataobject.wallet.PayOrderDO;
import cn.iocoder.yudao.module.transport.dal.mysql.wallet.PayOrderMapper;
import cn.iocoder.yudao.module.transport.framework.pay.PayProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 充值支付订单的两条形状，逐条对着「不接外部接口也能成立」的部分判：
 *
 * 一、幂等键（requestNo）：同一 (userId, requestNo) 重复调用只能有一行订单。
 *     原实现每次 createPayOrder 都无条件 insert，而加余额只在回调里做 ⇒ 用户点两次 = 库里两笔 pending，
 *     多出来的脏单没有东西能证明它们是同一笔意图。这里钉的是"第二次不再插行"，不是"回调会不会加两次钱"
 *     （那一格由 handleAlipayNotify 的 status==1 早退管，另说）。
 *
 * 二、归属校验（queryAlipayOrder）：查单这一支会去支付宝查证并**触发放款**，
 *     原来只按 orderNo 查、不看是谁在查 ⇒ 任何人拿别人的订单号轮询一次就替别人触发一次放款。
 *     别人的单与不存在的单必须给同一个读数（not_found），否则这个接口就成了枚举订单号的判 oracle。
 *
 * 支付宝 SDK 在本轮不参与：alipayClient 只在 @PostConstruct 里赋值，单测不启动容器 ⇒ 恒为 null，
 * 所以下面所有断言都走"渠道未配置"那一支，正好也是云上当前真实状态（无商户资质）。
 */
@ExtendWith(MockitoExtension.class)
class PayOrderIdempotencyAndOwnershipTest {

    @Mock private PayProperties payProps;
    @Mock private PayOrderMapper payOrderMapper;
    @Mock private WalletService walletService;
    @InjectMocks private PayService payService;

    private static final BigDecimal AMOUNT = new BigDecimal("50.00");

    private PayOrderDO order(Long userId, String orderNo, Integer status, BigDecimal amount) {
        return PayOrderDO.builder()
                .id(1L).orderNo(orderNo).userId(userId).channel("alipay")
                .amount(amount).status(status).bizType("recharge").build();
    }

    // ---------- 一、幂等键 ----------

    @Test
    void createPayOrder_newRequestNo_insertsOneRowCarryingTheKey() {
        when(payOrderMapper.selectByRequestNo(9L, "R-1")).thenReturn(null);
        when(payProps.isAlipayEnabled()).thenReturn(false);

        payService.createPayOrder(9L, AMOUNT, "alipay", "R-1");

        ArgumentCaptor<PayOrderDO> cap = ArgumentCaptor.forClass(PayOrderDO.class);
        verify(payOrderMapper).insert(cap.capture());
        assertEquals("R-1", cap.getValue().getRequestNo(), "键必须落在行上，否则下次查不到自己");
        assertEquals(9L, cap.getValue().getUserId());
        assertEquals(0, cap.getValue().getStatus(), "新单只能是待支付");
    }

    @Test
    void createPayOrder_blankRequestNo_insertsAndLeavesKeyNull() {
        // 空串在这里当"没传"：库里若存 '' 会被 (user_id, request_no) 唯一键撞成一条，
        // 于是"所有没传键的调用"互相冲突 —— 所以必须落 NULL。
        when(payProps.isAlipayEnabled()).thenReturn(false);

        payService.createPayOrder(9L, AMOUNT, "alipay", "   ");

        ArgumentCaptor<PayOrderDO> cap = ArgumentCaptor.forClass(PayOrderDO.class);
        verify(payOrderMapper).insert(cap.capture());
        assertNull(cap.getValue().getRequestNo(), "空白键要存成 NULL，不是存成空串");
    }

    @Test
    void createPayOrder_sameRequestNo_reusesPendingRow_andNeverInsertsAgain() {
        when(payOrderMapper.selectByRequestNo(9L, "R-2")).thenReturn(order(9L, "P-EXIST", 0, AMOUNT));
        when(payProps.isAlipayEnabled()).thenReturn(false);

        var result = payService.createPayOrder(9L, AMOUNT, "alipay", "R-2");

        verify(payOrderMapper, never()).insert(any(PayOrderDO.class));
        assertEquals("P-EXIST", result.get("orderNo"), "同一笔意图必须回到同一个订单号");
        assertEquals("true", result.get("reused"), "复用要写在回执里，不能让用户以为拿到了新单");
    }

    @Test
    void createPayOrder_sameRequestNoAlreadyPaid_returnsAlreadyPaid_withoutTouchingTheTable() {
        when(payOrderMapper.selectByRequestNo(9L, "R-3")).thenReturn(order(9L, "P-PAID", 1, AMOUNT));

        var result = payService.createPayOrder(9L, AMOUNT, "alipay", "R-3");

        verify(payOrderMapper, never()).insert(any(PayOrderDO.class));
        verify(payOrderMapper, never()).updateById(any(PayOrderDO.class));
        assertEquals("P-PAID", result.get("orderNo"));
        assertEquals("true", result.get("alreadyPaid"), "已付过的键要回执已付，不能再给一份调起参数让人二次支付");
        assertNull(result.get("reused"), "已支付不是「复用待支付单」，两种读数不能混");
    }

    @Test
    void createPayOrder_sameRequestNoDifferentAmount_refusesInsteadOfSilentlyReusing() {
        // 幂等命中的是"50 元那一笔"，这次要的是 60 元：静默按旧单收钱 = 收了 60 只充 50。
        when(payOrderMapper.selectByRequestNo(9L, "R-4")).thenReturn(order(9L, "P-50", 0, new BigDecimal("50.00")));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> payService.createPayOrder(9L, new BigDecimal("60.00"), "alipay", "R-4"));

        assertTrue(e.getMessage().contains("requestNo"), "拒绝理由要指名是幂等键冲突，不是含糊的「参数错误」");
        verify(payOrderMapper, never()).insert(any(PayOrderDO.class));
        verify(payOrderMapper, never()).updateById(any(PayOrderDO.class));
    }

    @Test
    void createPayOrder_uniqueKeyWinsTheRace_fallsBackToReuse() {
        // 「先查后插」的 TOCTOU 窗口：两次并发都查不到，第二次 insert 被 uk_pay_order_user_request 挡下 ⇒
        // 库错误不能抛给用户，重查一次走复用（V027 对 transport_leg 用的是同一手法）。
        when(payOrderMapper.selectByRequestNo(9L, "R-5")).thenReturn(null, order(9L, "P-RACE", 0, AMOUNT));
        doThrow(new DuplicateKeyException("uk_pay_order_user_request")).when(payOrderMapper).insert(any(PayOrderDO.class));
        when(payProps.isAlipayEnabled()).thenReturn(false);

        var result = payService.createPayOrder(9L, AMOUNT, "alipay", "R-5");

        assertEquals("P-RACE", result.get("orderNo"));
        assertEquals("true", result.get("reused"));
        verify(payOrderMapper, times(2)).selectByRequestNo(9L, "R-5");
    }

    // ---------- 二、归属校验 ----------

    @Test
    void queryPayOrder_otherUsersOrder_returnsNotFound_andTriggersNoRecharge() {
        when(payOrderMapper.selectByOrderNo("P-OTHER")).thenReturn(order(2L, "P-OTHER", 0, AMOUNT));

        var result = payService.queryAlipayOrder("P-OTHER", 1L);

        assertEquals("not_found", result.get("status"), "别人的单要和不存在的单同一个读数，不能泄露「这个号存在」");
        assertNull(result.get("amount"), "not_found 里不许带金额");
        verify(walletService, never()).recharge(any(), any(), any(), any());
        verify(payOrderMapper, never()).updateById(any(PayOrderDO.class));
    }

    @Test
    void queryPayOrder_ownPendingOrder_returnsPending() {
        // 上一条的同行正对照：单号、库里的行都一样，只换"谁在查" ⇒ 红绿必须跟着归属走。
        when(payOrderMapper.selectByOrderNo("P-MINE")).thenReturn(order(1L, "P-MINE", 0, AMOUNT));
        when(payProps.isAlipayEnabled()).thenReturn(false);

        var result = payService.queryAlipayOrder("P-MINE", 1L);

        assertEquals("pending", result.get("status"));
        assertEquals(AMOUNT, result.get("amount"));
    }

    @Test
    void queryPayOrder_missingOrder_stillNotFound() {
        when(payOrderMapper.selectByOrderNo("P-NONE")).thenReturn(null);

        var result = payService.queryAlipayOrder("P-NONE", 1L);

        assertEquals("not_found", result.get("status"));
        verify(payOrderMapper, never()).updateById(any(PayOrderDO.class));
    }

    @Test
    void queryPayOrder_ownPaidOrder_returnsPaid_withoutRechargingAgain() {
        when(payOrderMapper.selectByOrderNo("P-PAID")).thenReturn(order(1L, "P-PAID", 1, AMOUNT));

        var result = payService.queryAlipayOrder("P-PAID", 1L);

        assertEquals("paid", result.get("status"));
        verify(walletService, never()).recharge(any(), any(), any(), any());
    }
}
