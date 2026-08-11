package cn.iocoder.yudao.module.transport.dal.dataobject.order;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.math.BigDecimal;

@TableName("transport_product_order")
@KeySequence("transport_product_order_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductOrderDO extends TenantBaseDO {
    @TableId
    private Long id;
    /** 业务订单号 */
    private String orderNo;
    /** 购买会员编号（member_user.id） */
    private Long userId;
    /** 购买会员手机号 */
    private String userMobile;
    /** 订单总额 */
    private BigDecimal totalAmount;
    /** 订单状态(0待发货 1已发货 2已完成 3已取消) */
    private Integer status;
    /** 收货人 */
    private String receiverName;
    /** 收货电话 */
    private String receiverMobile;
    /** 收货地址 */
    private String receiverAddress;
    /** 订单备注 */
    private String remark;
}
