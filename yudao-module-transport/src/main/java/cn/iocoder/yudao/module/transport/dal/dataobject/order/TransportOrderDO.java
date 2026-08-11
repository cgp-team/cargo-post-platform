package cn.iocoder.yudao.module.transport.dal.dataobject.order;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("transport_order")
@KeySequence("transport_order_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransportOrderDO extends TenantBaseDO {
    @TableId
    private Long id;
    private String orderNo;
    private Integer orderType;
    private Long pickupStationId;
    private Long deliveryStationId;
    private LocalDateTime earliestPickupTime;
    private LocalDateTime latestDeliveryTime;
    private Integer status;
    private BigDecimal totalAmount;
    /** 下单会员编号（小程序寄货） */
    private Long memberUserId;
}
