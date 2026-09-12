package cn.iocoder.yudao.module.transport.controller.admin.dispatch.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;

@Schema(description = "管理后台 - 智能派单 Request VO")
@Data
public class DispatchSmartPlanReqVO {

    @Schema(description = "自动模式：true=一键智能调度（后端自动选场站/车辆，忽略下面人工参数）；false/不传=人工高级模式")
    private Boolean auto;

    @Schema(description = "场站编号（人工高级模式必填；auto=true 时忽略，由后端自动推导）", example = "1")
    private Long depotStationId;

    @Schema(description = "可用车辆编号列表（人工高级模式必填；auto=true 时忽略，由后端自动挑候选车辆）")
    private List<Long> vehicleIds;

    @Schema(description = "班次编号（可选）：指定后该批派单车辆按班次线路公交骨架经停，货运作为绕行插入（联合调度）")
    private Long shiftId;

    @Schema(description = "算法超参数，不传用算法默认值")
    private Map<String, Object> algorithmConfig;

    @Schema(description = "规划场景（仅供 Mock 联调透传）")
    private String scenario;

    /**
     * 任务窗口开始（当天时刻，如 08:00）。与 windowEnd 同时传才生效。
     *
     * <p>业务含义：本次派单是给"这个时间段"排任务（例：早上 8-10 点这一班）。
     * 窗口决定了 ①哪些订单来得及取送（时间窗与任务窗口无交集的订单本批不派）；
     * ②每台车在窗口开始时已经开到线路的哪一站——**已经开过的站不再派它掉头回去取货**。
     * 不传时沿用系统默认批次窗口（当前时间起一个班次）。</p>
     */
    @Schema(description = "任务窗口开始（当天时刻，如 08:00；不传用当前批次窗口）", example = "08:00:00")
    private LocalTime windowStart;

    @Schema(description = "任务窗口结束（当天时刻，如 10:00；不传用当前批次窗口）", example = "10:00:00")
    private LocalTime windowEnd;

}
