package cn.iocoder.yudao.module.transport.dal.dataobject.dispatch;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.math.BigDecimal;
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
    /** 分段路网行驶秒数（到达本站的上一站→本站；高德真实时长或直线÷均速估算，估算层回写） */
    private Integer segmentDurationSeconds;
    /** 分段里程(km)（高德路网公里或 Haversine 直线公里，估算层回写） */
    private BigDecimal segmentDistanceKm;
    /** 计划离站时间（= 预计到达 + 本站作业时长，估算层回写） */
    private LocalDateTime plannedDepartureTime;
    /** 本站作业时长(秒)（接/送/派/揽计停站作业，估算层回写） */
    private Integer serviceDurationSeconds;
    /** 数量（BOARD/ALIGHT=人数，PICKUP/DELIVERY=件数；估算层按订单子表回写） */
    private Integer quantity;
    /** 任务段明细状态（TaskItemStatusEnum：0待执行 1行驶中 2已到站 3上车中 4下车中 5揽收中 6派送中 7已完成 8失败；后端为源） */
    private Integer status;
    /** 算法解释-服务方式（ServiceModeEnum.code，仅货运/揽收经停） */
    private String serviceMode;
    /** 算法解释-服务点站点编号（替代交接时推荐） */
    private Long servicePointStationId;
    /** 算法解释-绕行距离(km)（相对公交骨架，骨架站为 0） */
    private BigDecimal detourDistanceKm;
    /** 算法解释-绕行时长(秒) */
    private Integer detourDurationSeconds;
    /** 算法解释-乘客影响(秒)：绕行对车上乘客的额外乘车时长（passenger-level，空车绕行为 null） */
    private Integer passengerImpactSeconds;
    /** 算法解释-未接受原因码（ReviewReasonCodeEnum.code；已接受为 null） */
    private String reasonCode;

    // ========== 展示用字段（不落库）：后台"方案详情 / 调度结果可视化"直接拿到站点名与订单号 ==========
    @TableField(exist = false)
    private String stationName;
    @TableField(exist = false)
    private String orderNo;
}
