package cn.iocoder.yudao.module.transport.dal.dataobject.dispatch;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

@TableName("transport_dispatch_task")
@KeySequence("transport_dispatch_task_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DispatchTaskDO extends TenantBaseDO {
    @TableId
    private Long id;
    private String taskNo;
    private String snapshotId;
    private LocalDateTime planningTime;
    private LocalDateTime batchStart;
    private LocalDateTime batchEnd;
    private String algorithmJobId;
    private String scenario;
    private Integer status;
    private String errorMessage;
}
