package cn.iocoder.yudao.module.transport.dal.dataobject.dispatch;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

@TableName("transport_dispatch_plan_item")
@KeySequence("transport_dispatch_plan_item_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DispatchPlanItemDO extends TenantBaseDO {
    @TableId
    private Long id;
    private Long planId;
    private Long vehicleId;
    private Long driverId;
    private Long shiftId;
    private Long orderId;
    private Long stationId;
    private Integer visitSequence;
    private Integer actionType;
    private LocalDateTime estimatedArrivalTime;
}
