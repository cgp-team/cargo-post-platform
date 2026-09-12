package cn.iocoder.yudao.module.transport.controller.admin.dispatch.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 调度方案「真实道路地图数据」：按车辆 + 经停序号给出每段道路轨迹。
 *
 * <p>调度可视化页面用它与方案明细（站点/动作/时间）对齐绘制：
 * {@code provider=AMAP} 表示真实道路（实线 + 方向箭头），{@code EUCLIDEAN} 表示两点直线兜底（虚线，不伪装）。</p>
 */
@Schema(description = "管理后台 - 调度方案道路轨迹 Response VO")
@Data
public class DispatchRoadmapRespVO {

    @Schema(description = "方案编号")
    private Long planId;

    @Schema(description = "整体轨迹来源：AMAP / EUCLIDEAN / MIXED")
    private String provider;

    @Schema(description = "分段口径：LEG=由本方案的运输段（多段联运）聚合；ITEM=由方案经停明细聚合（无段的历史/手工方案）",
            example = "LEG")
    private String source;

    @Schema(description = "分段道路轨迹（每段 = 上一站 → 本站）")
    private List<Segment> segments;

    @Schema(description = "单段道路轨迹")
    @Data
    public static class Segment {

        @Schema(description = "车辆编号")
        private Long vehicleId;

        @Schema(description = "段序号（该车在本方案内的第几段，从 1 开始；与前端「该车第 N 段」对齐）")
        private Integer visitSequence;

        @Schema(description = "运输段编号（source=LEG 时有值，与订单视角 topology/order 的 leg 一一对应）")
        private Long legId;

        @Schema(description = "订单编号（source=LEG 时有值）")
        private Long orderId;

        @Schema(description = "该订单内的段序（source=LEG 时有值）")
        private Integer legSequence;

        @Schema(description = "本段是否需要在终点换乘交接")
        private Boolean handoverRequired;

        @Schema(description = "起点站编号")
        private Long fromStationId;

        @Schema(description = "终点站编号")
        private Long toStationId;

        @Schema(description = "起点站名")
        private String fromStationName;

        @Schema(description = "终点站名")
        private String toStationName;

        @Schema(description = "轨迹来源：AMAP 真实道路 / EUCLIDEAN 直线兜底")
        private String provider;

        @Schema(description = "道路轨迹点（GCJ-02）")
        private List<Point> points;
    }

    @Schema(description = "道路轨迹点")
    @Data
    public static class Point {

        @Schema(description = "经度")
        private Double longitude;

        @Schema(description = "纬度")
        private Double latitude;
    }
}
