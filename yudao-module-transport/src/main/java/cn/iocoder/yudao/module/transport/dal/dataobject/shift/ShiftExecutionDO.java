package cn.iocoder.yudao.module.transport.dal.dataobject.shift;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 班次执行 DO：司机端发车/到站落地的当天执行记录（transport_shift 为模板，仅计划字段）。
 */
@TableName("transport_shift_execution")
@KeySequence("transport_shift_execution_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShiftExecutionDO extends TenantBaseDO {
    @TableId
    private Long id;
    private Long shiftId;
    private Long driverId;
    private Long vehicleId;
    /** 执行日期 */
    private LocalDate execDate;
    /** 实际发车时间 */
    private LocalDateTime departTime;
    /** 到达终点时间 */
    private LocalDateTime arriveTime;
    /** 当前所在站点编号 */
    private Long currentStationId;
    /** 已装车件数（行李舱运力，受 vehicle.cargo_capacity 约束） */
    private Integer loadedCount;
    /** 执行状态：0 在途，1 已完成 */
    private Integer status;
}
