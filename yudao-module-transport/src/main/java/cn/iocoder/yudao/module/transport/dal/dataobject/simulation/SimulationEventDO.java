package cn.iocoder.yudao.module.transport.dal.dataobject.simulation;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.math.BigDecimal;

/**
 * 模拟事件 DO
 */
@TableName("simulation_event")
@KeySequence("simulation_event_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SimulationEventDO extends TenantBaseDO {

    @TableId
    private Long id;
    /** 模拟运行ID */
    private Long runId;
    /** 车辆ID */
    private Long vehicleId;
    /** 事件类型 */
    private String eventType;
    /** 严重级别：0信息 1警告 2严重 */
    private Integer severity;
    /** 事件标题 */
    private String title;
    /** 事件内容 */
    private String content;
    /** 模拟时刻(秒) */
    private Long simSeconds;
    /** 相关站点ID */
    private Long stationId;
    /** 相关站点名 */
    private String stationName;
    /** 经度 */
    private BigDecimal longitude;
    /** 纬度 */
    private BigDecimal latitude;
    /** 扩展数据(JSON) */
    private String extraData;
}
