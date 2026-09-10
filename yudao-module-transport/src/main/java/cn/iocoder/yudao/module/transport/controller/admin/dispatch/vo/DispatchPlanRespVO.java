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

    @Schema(description = "总里程(km，按经停坐标 Haversine 换算)")
    private BigDecimal totalDistance;

    @Schema(description = "预计耗时(分钟，业务后端估算)")
    private Integer estDurationMinutes;

    @Schema(description = "预计收入(元，按计价规则估算)")
    private BigDecimal estRevenue;

    @Schema(description = "预计成本(元，按计价规则估算)")
    private BigDecimal estCost;

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

    // ========== 摘要（getPlan 计算）：供"智能调度完成"结果卡与列表展示，避免前端再拉明细 ==========

    @Schema(description = "调度场站名称（取方案首条明细的站点）")
    private String depotStationName;

    @Schema(description = "方案覆盖订单数（去重）")
    private Integer orderCount;

    @Schema(description = "方案使用车辆数（去重）")
    private Integer vehicleCount;

}
