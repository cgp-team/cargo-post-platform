package cn.iocoder.yudao.module.transport.service.wallet;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.transport.dal.dataobject.wallet.WalletAccountDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.wallet.WalletTransactionDO;
import cn.iocoder.yudao.module.transport.dal.mysql.wallet.WalletAccountMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.wallet.WalletTransactionMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class WalletServiceImpl implements WalletService {

    @Resource
    private WalletAccountMapper accountMapper;
    @Resource
    private WalletTransactionMapper transactionMapper;

    @Override
    public WalletAccountDO getOrCreateAccount(Long userId) {
        WalletAccountDO account = accountMapper.selectByUserId(userId);
        if (account == null) {
            account = WalletAccountDO.builder()
                    .userId(userId)
                    .balance(BigDecimal.ZERO)
                    .frozenAmount(BigDecimal.ZERO)
                    .totalRecharge(BigDecimal.ZERO)
                    .totalSpent(BigDecimal.ZERO)
                    .status(0)
                    .build();
            accountMapper.insert(account);
        }
        return account;
    }

    @Override
    public BigDecimal getBalance(Long userId) {
        return getOrCreateAccount(userId).getBalance();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WalletTransactionDO recharge(Long userId, BigDecimal amount, String payMethod, String refNo) {
        WalletAccountDO account = getOrCreateAccount(userId);
        account.setBalance(account.getBalance().add(amount));
        account.setTotalRecharge(account.getTotalRecharge().add(amount));
        accountMapper.updateById(account);

        return createTransaction(userId, "recharge", amount, account.getBalance(),
                "充值", refNo, payMethod, 1);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WalletTransactionDO consume(Long userId, BigDecimal amount, String title, String refNo) {
        WalletAccountDO account = getOrCreateAccount(userId);
        if (account.getBalance().compareTo(amount) < 0) {
            throw new IllegalArgumentException("余额不足");
        }
        BigDecimal negativeAmount = amount.negate();
        account.setBalance(account.getBalance().subtract(amount));
        account.setTotalSpent(account.getTotalSpent().add(amount));
        accountMapper.updateById(account);

        return createTransaction(userId, "consume", negativeAmount, account.getBalance(),
                title, refNo, "balance", 1);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WalletTransactionDO refund(Long userId, BigDecimal amount, String title, String refNo) {
        WalletAccountDO account = getOrCreateAccount(userId);
        account.setBalance(account.getBalance().add(amount));
        accountMapper.updateById(account);

        return createTransaction(userId, "refund", amount, account.getBalance(),
                title, refNo, "balance", 1);
    }

    @Override
    public List<WalletTransactionDO> getRecentTransactions(Long userId, int limit) {
        return transactionMapper.selectRecentByUserId(userId, limit);
    }

    @Override
    public PageResult<WalletTransactionDO> getTransactionPage(Long userId, PageParam pageParam) {
        return transactionMapper.selectPage(userId, pageParam);
    }

    private WalletTransactionDO createTransaction(Long userId, String type, BigDecimal amount,
                                                   BigDecimal balanceAfter, String title,
                                                   String refNo, String payMethod, int status) {
        String txNo = "W" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
        WalletTransactionDO tx = WalletTransactionDO.builder()
                .transactionNo(txNo)
                .userId(userId)
                .type(type)
                .amount(amount)
                .balanceAfter(balanceAfter)
                .title(title)
                .refNo(refNo)
                .payMethod(payMethod)
                .status(status)
                .build();
        transactionMapper.insert(tx);
        return tx;
    }
}
