package cn.iocoder.yudao.module.transport.dal.dataobject.simulation;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * 模拟运行记录 DO
 */
@TableName("simulation_run")
@KeySequence("simulation_run_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SimulationRunDO extends TenantBaseDO {

    @TableId
    private Long id;
    /** 调度方案ID */
    private Long planId;
    /** 车辆ID */
    private Long vehicleId;
    /** 司机ID */
    private Long driverId;
    /** 场景ID */
    private Long scenarioId;
    /** 模拟倍速 */
    private Double multiplier;
    /** 状态：0已创建 1运行中 2已暂停 3已完成 4已终止 */
    private Integer status;
    /** 模拟开始时间 */
    private java.time.LocalDateTime startTime;
    /** 模拟结束时间 */
    private java.time.LocalDateTime endTime;
    /** 总模拟秒数 */
    private Long totalSimSeconds;
    /** 实际模拟秒数 */
    private Long actualSimSeconds;
    /** 总里程(km) */
    private Double totalDistanceKm;
    /** 实际行驶里程(km) */
    private Double actualDistanceKm;
    /** 总站点数 */
    private Integer stationCount;
    /** 已完成站点数 */
    private Integer completedStationCount;
    /** 总订单数 */
    private Integer orderCount;
    /** 完成订单数 */
    private Integer completedOrderCount;
    /** 异常事件数 */
    private Integer exceptionCount;
    /** 结果摘要(JSON) */
    private String resultSummary;
}
