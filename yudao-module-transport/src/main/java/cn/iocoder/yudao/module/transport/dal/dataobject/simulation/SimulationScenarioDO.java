package cn.iocoder.yudao.module.transport.dal.dataobject.simulation;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * 模拟场景定义 DO
 */
@TableName("simulation_scenario")
@KeySequence("simulation_scenario_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SimulationScenarioDO extends TenantBaseDO {

    @TableId
    private Long id;
    /** 场景名称 */
    private String name;
    /** 场景描述 */
    private String description;
    /** 场景类型 */
    private String scenarioType;
    /** 严重级别：0正常 1警告 2严重 */
    private Integer severity;
    /** 是否内置 */
    private Boolean builtin;
    /** 是否启用 */
    private Boolean enabled;
    /** 场景配置(JSON) */
    private String configJson;
}
