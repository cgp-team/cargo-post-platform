package cn.iocoder.yudao.module.transport.dal.dataobject.wallet;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.math.BigDecimal;

@TableName("transport_wallet_account")
@KeySequence("transport_wallet_account_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WalletAccountDO extends TenantBaseDO {
    @TableId
    private Long id;
    /** 会员用户 ID */
    private Long userId;
    /** 余额（元） */
    private BigDecimal balance;
    /** 冻结金额（元） */
    private BigDecimal frozenAmount;
    /** 累计充值（元） */
    private BigDecimal totalRecharge;
    /** 累计消费（元） */
    private BigDecimal totalSpent;
    /** 状态 0=正常 1=冻结 */
    private Integer status;
}
