package cn.iocoder.yudao.module.transport.dal.dataobject.dispatch;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("transport_dispatch_plan")
@KeySequence("transport_dispatch_plan_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DispatchPlanDO extends TenantBaseDO {
    @TableId
    private Long id;
    private Long taskId;
    private Integer planVersion;
    private Integer mode;
    private String algorithmVersion;
    private String parameterVersion;
    private BigDecimal score;
    private BigDecimal totalDistance;
    /** 预计耗时(分钟，DispatchEstimationService 估算) */
    private Integer estDurationMinutes;
    /** 预计收入(元，按计价规则估算) */
    private BigDecimal estRevenue;
    /** 预计成本(元，按计价规则估算) */
    private BigDecimal estCost;
    private Integer status;
    private Long approvedBy;
    private LocalDateTime approvedTime;
}
