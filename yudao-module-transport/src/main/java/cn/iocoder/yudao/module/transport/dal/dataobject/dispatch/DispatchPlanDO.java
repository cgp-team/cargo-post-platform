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
    /** ETA 路网来源：AMAP=高德路网真实时长 / EUCLIDEAN_FALLBACK=直线÷均速估算（估算时回写） */
    private String routeProvider;
    private Integer status;
    private Long approvedBy;
    private LocalDateTime approvedTime;
    /** 任务段窗口开始（该方案车辆运营起始时刻，默认=批次开始，估算后按经停推进） */
    private LocalDateTime taskWindowStart;
    /** 任务段窗口结束（默认=开始+预计耗时，方案完成后回写实际到达终点时刻） */
    private LocalDateTime taskWindowEnd;
    // ========== 运输方案（TransportPlan）：直达/联运计划与解释 ==========
    /** 方案号（人可读，生成时按 taskNo + 版本落库） */
    private String planNo;
    /** 组织方式：DIRECT 一段直达 / MULTI_LEG 多段联运（DispatchPlanningModeEnum） */
    private String planningMode;
    /** 总运输段数 */
    private Integer totalLegCount;
    /** 换乘次数（= 总段数 - 1） */
    private Integer transferCount;
    /** 方案解释（为什么直达/为什么联运，后台"调度结果解释"直接展示） */
    private String planReason;
    /** 预计开始时间（方案内最早段出发） */
    private LocalDateTime estimatedStartTime;
    /** 预计到达时间（方案内最晚段到达） */
    private LocalDateTime estimatedArrivalTime;
    /** 实际开始/到达时间 */
    private LocalDateTime actualStartTime;
    private LocalDateTime actualArrivalTime;
}
