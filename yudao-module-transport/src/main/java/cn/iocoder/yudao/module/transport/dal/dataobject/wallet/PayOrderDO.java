package cn.iocoder.yudao.module.transport.dal.dataobject.wallet;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.math.BigDecimal;

/**
 * 支付订单：记录每笔充值/付款的第三方支付状态。
 * 充值流程：创建 pay_order(pending) → 调起支付 → 回调成功 → 加余额 + 更新 pay_order(paid)
 */
@TableName("transport_pay_order")
@KeySequence("transport_pay_order_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayOrderDO extends TenantBaseDO {
    @TableId
    private Long id;
    /** 支付订单号（业务生成） */
    private String orderNo;
    /** 会员用户 ID */
    private Long userId;
    /** 渠道：alipay / wechat */
    private String channel;
    /** 金额（元） */
    private BigDecimal amount;
    /** 第三方交易号（支付宝 trade_no / 微信 transaction_id） */
    private String channelTradeNo;
    /** 状态 0=待支付 1=已支付 2=已退款 3=已关闭 */
    private Integer status;
    /** 关联业务：recharge=充值 / send_order=寄货 / product_order=商城 */
    private String bizType;
    /** 关联业务单号 */
    private String bizNo;
    /** 回调原始内容（用于对账） */
    private String notifyBody;
}
