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
    /**
     * 实际开始/到达时间（V029 之前是死列：全仓零写入、零读取）。
     * 现由 `DispatchPlanReconcileJob` 从明细的实际上报时刻回填 —— 只在该方案**确有**明细实际时刻时才写，
     * 一条都没有就留 NULL（不拿计划值或作业运行时刻冒充实际执行）。
     */
    private LocalDateTime actualStartTime;
    private LocalDateTime actualArrivalTime;
    /**
     * 本批未派发的货运单与原因码（V029），JSON 数组：
     * <pre>[{"orderId":12,"reasonCode":"CAPACITY_OVERFLOW"}]</pre>
     * 算法侧是 `unassignedOrderIds` + warnings 原因码；此前只用来判空和拼一句解释文案，
     * 事后查不到"这批为什么少带两单"，也无法回流成度量台的题（#33 语料缺口）。
     * 乘客单受业务底线保护永不舍弃，因此永不出现在这里。NULL = 本批没有未派单。
     */
    private String unassignedOrders;
}
