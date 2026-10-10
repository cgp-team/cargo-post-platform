package cn.iocoder.yudao.module.transport.dal.dataobject.wallet;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.math.BigDecimal;

@TableName("transport_wallet_transaction")
@KeySequence("transport_wallet_transaction_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WalletTransactionDO extends TenantBaseDO {
    @TableId
    private Long id;
    /** 交易流水号 */
    private String transactionNo;
    /** 会员用户 ID */
    private Long userId;
    /** 类型：recharge=充值 / consume=消费 / refund=退款 / withdraw=提现 */
    private String type;
    /** 金额（正数=收入，负数=支出） */
    private BigDecimal amount;
    /** 交易后余额 */
    private BigDecimal balanceAfter;
    /** 标题 */
    private String title;
    /** 关联业务单号（订单号等） */
    private String refNo;
    /** 支付方式：balance=余额 / alipay=支付宝 / wechat=微信 */
    private String payMethod;
    /** 状态 0=处理中 1=成功 2=失败 */
    private Integer status;
}
