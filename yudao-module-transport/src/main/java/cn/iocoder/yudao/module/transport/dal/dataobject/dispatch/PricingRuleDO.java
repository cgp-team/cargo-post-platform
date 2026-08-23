package cn.iocoder.yudao.module.transport.dal.dataobject.dispatch;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.math.BigDecimal;

/**
 * 运输计价规则（单行配置）。算法服务不持有定价模型（契约 Q13），由业务后端按本规则估算方案收入/成本。
 */
@TableName("transport_pricing_rule")
@KeySequence("transport_pricing_rule_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PricingRuleDO extends TenantBaseDO {
    @TableId
    private Long id;
    /** 客运人公里单价(元) */
    private BigDecimal passengerPricePerKm;
    /** 货运件单价(元) */
    private BigDecimal cargoPricePerItem;
    /** 邮快件件单价(元) */
    private BigDecimal postalPricePerItem;
    /** 车辆公里成本(元) */
    private BigDecimal vehicleCostPerKm;
    /** 班线平均时速(km/h)，ETA 与耗时估算口径 */
    private BigDecimal avgSpeedKmh;
    /** 作业站停站分钟（接/送/派/揽） */
    private Integer stopServiceMinutes;
}
