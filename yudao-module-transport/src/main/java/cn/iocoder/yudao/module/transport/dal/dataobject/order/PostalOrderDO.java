package cn.iocoder.yudao.module.transport.dal.dataobject.order;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("transport_postal_order")
@KeySequence("transport_postal_order_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PostalOrderDO extends TenantBaseDO {
    @TableId
    private Long id;
    private Long orderId;
    private String mailNo;
    private String carrierCode;
    private Integer itemCount;
    private BigDecimal weightKg;
    /** 收件人（快递进村取件核销） */
    private String receiverName;
    private String receiverMobile;
    private String receiverAddress;
    /** 取件码（6位数字，收件人凭码取件） */
    private String pickupCode;
    /** 取件状态：0待取件 1已取件 */
    private Integer pickupStatus;
    /** 取件时间 */
    private LocalDateTime pickedUpTime;
    /** 核销人会员编号 */
    private Long pickerMemberUserId;
}
