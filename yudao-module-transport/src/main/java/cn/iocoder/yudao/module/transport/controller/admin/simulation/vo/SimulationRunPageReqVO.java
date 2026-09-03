package cn.iocoder.yudao.module.transport.controller.admin.simulation.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 模拟运行历史分页查询 VO
 */
@Schema(description = "模拟运行历史分页查询 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class SimulationRunPageReqVO extends PageParam {

    @Schema(description = "方案ID")
    private Long planId;
    @Schema(description = "车辆ID")
    private Long vehicleId;
    @Schema(description = "状态：0已创建 1运行中 2已暂停 3已完成 4已终止")
    private Integer status;
}
