package cn.iocoder.yudao.module.transport.dal.dataobject.order;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.math.BigDecimal;

@TableName("transport_cargo_order")
@KeySequence("transport_cargo_order_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CargoOrderDO extends TenantBaseDO {
    @TableId
    private Long id;
    private Long orderId;
    private String cargoCategory;
    private Boolean freshFlag;
    private Integer itemCount;
    private BigDecimal weightKg;
    private BigDecimal volumeM3;
}
