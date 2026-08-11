package cn.iocoder.yudao.module.transport.dal.dataobject.order;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.math.BigDecimal;

@TableName("transport_product_order_item")
@KeySequence("transport_product_order_item_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductOrderItemDO extends TenantBaseDO {
    @TableId
    private Long id;
    /** 订单编号 */
    private Long orderId;
    /** 商品编号 */
    private Long productId;
    /** 商品名称（下单快照） */
    private String productName;
    /** 商品图(emoji)（下单快照） */
    private String productImage;
    /** 下单单价 */
    private BigDecimal productPrice;
    /** 购买数量 */
    private Integer quantity;
    /** 小计金额 */
    private BigDecimal amount;
}
