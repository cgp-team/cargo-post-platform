package cn.iocoder.yudao.module.transport.service.wallet;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.transport.dal.dataobject.wallet.WalletAccountDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.wallet.WalletTransactionDO;

import java.math.BigDecimal;
import java.util.List;

public interface WalletService {

    /** 获取或创建钱包账户 */
    WalletAccountDO getOrCreateAccount(Long userId);

    /** 获取余额 */
    BigDecimal getBalance(Long userId);

    /** 充值（由支付回调触发，直接加余额） */
    WalletTransactionDO recharge(Long userId, BigDecimal amount, String payMethod, String refNo);

    /** 消费扣款（余额足够时扣减） */
    WalletTransactionDO consume(Long userId, BigDecimal amount, String title, String refNo);

    /** 退款 */
    WalletTransactionDO refund(Long userId, BigDecimal amount, String title, String refNo);

    /** 最近交易记录 */
    List<WalletTransactionDO> getRecentTransactions(Long userId, int limit);

    /** 分页交易记录 */
    PageResult<WalletTransactionDO> getTransactionPage(Long userId, PageParam pageParam);
}
