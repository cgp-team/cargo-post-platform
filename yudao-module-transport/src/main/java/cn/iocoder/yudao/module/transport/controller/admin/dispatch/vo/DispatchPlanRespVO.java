package cn.iocoder.yudao.module.transport.controller.admin.dispatch.vo;

import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.DispatchPlanItemDO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "管理后台 - 调度方案 Response VO")
@Data
public class DispatchPlanRespVO {

    @Schema(description = "方案编号", example = "1")
    private Long id;

    @Schema(description = "调度任务编号", example = "1")
    private Long taskId;

    @Schema(description = "方案版本", example = "1")
    private Integer planVersion;

    @Schema(description = "派单方式：0 手工 1 智能", example = "1")
    private Integer mode;

    @Schema(description = "算法版本")
    private String algorithmVersion;

    @Schema(description = "参数版本")
    private String parameterVersion;

    @Schema(description = "总里程(算法产出)")
    private BigDecimal totalDistance;

    @Schema(description = "方案状态：0 待审核 1 已下发 2 执行中 3 已完成 4 已作废", example = "0")
    private Integer status;

    @Schema(description = "审核人")
    private Long approvedBy;

    @Schema(description = "审核时间")
    private LocalDateTime approvedTime;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "方案明细（经停序列）")
    private List<DispatchPlanItemDO> items;

}
