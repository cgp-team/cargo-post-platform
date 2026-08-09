package cn.iocoder.yudao.module.transport.dal.dataobject.dispatch;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

@TableName("transport_dispatch_plan_log")
@KeySequence("transport_dispatch_plan_log_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DispatchPlanLogDO extends TenantBaseDO {
    @TableId
    private Long id;
    private Long planId;
    private Integer fromStatus;
    private Integer toStatus;
    private String operator;
    private String reason;
}
