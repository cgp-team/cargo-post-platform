package cn.iocoder.yudao.module.transport.controller.app.transport.driver.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 用户 APP - 调度任务 Response VO（完整连续任务段的一个经停动作，Phase 4 任务段模型）。
 * 同一 planId 下按 visitSequence 有序即为完整任务段（orderedStops）；司机端按任务段整体展示。
 */
@Schema(description = "用户 APP - 调度任务 Response VO（任务段经停动作）")
@Data
public class AppDriverTaskRespVO {

    @Schema(description = "任务段(方案)编号", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long planId;
    @Schema(description = "任务段窗口开始")
    private LocalDateTime taskWindowStart;
    @Schema(description = "任务段窗口结束")
    private LocalDateTime taskWindowEnd;
    @Schema(description = "访问顺序(从 1 开始)")
    private Integer visitSequence;

    @Schema(description = "调度明细编号", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long id;
    @Schema(description = "经停站点编号")
    private Long stationId;
    @Schema(description = "经停站点名称")
    private String stationName;
    @Schema(description = "动作类型(0出发 1接客 2送客 3派送 4揽收 5返回)")
    private Integer actionType;
    @Schema(description = "动作类型名")
    private String actionName;
    @Schema(description = "关联订单编号")
    private Long orderId;
    @Schema(description = "业务订单号")
    private String orderNo;
    @Schema(description = "预计到达时间")
    private LocalDateTime estimatedArrivalTime;
    @Schema(description = "计划离站时间(=到达+本站作业时长)")
    private LocalDateTime plannedDepartureTime;
    @Schema(description = "本站作业时长(秒)")
    private Integer serviceDurationSeconds;
    @Schema(description = "数量(BOARD/ALIGHT=人数，PICKUP/DELIVERY=件数)")
    private Integer quantity;
    @Schema(description = "分段行驶秒数")
    private Integer segmentDurationSeconds;
    @Schema(description = "分段里程(km)")
    private BigDecimal segmentDistanceKm;
    @Schema(description = "任务状态(TaskItemStatusEnum)：0待执行 1行驶中 2已到站 3上车中 4下车中 5揽收中 6派送中 7已完成 8失败")
    private Integer status;
    @Schema(description = "任务状态名")
    private String statusName;
}
