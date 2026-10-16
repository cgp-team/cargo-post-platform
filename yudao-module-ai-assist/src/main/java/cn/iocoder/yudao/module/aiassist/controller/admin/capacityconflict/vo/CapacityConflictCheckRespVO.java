package cn.iocoder.yudao.module.aiassist.controller.admin.capacityconflict.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 客货空间冲突检视 Response VO（契约 docs/AI_ASSIST_BOUNDARY.md §6 第 3 项）。
 *
 * <p>只输出"参考 + 文字"，不给任何写口、不改排班（契约 §0/§6）。冲突点按维度分开列，
 * 每一处都带"核定 vs 预报 vs 缺口"，读的人能自己复核这个判断从哪两个数而来 ——
 * 不给"看起来对"的结论。
 */
@Schema(description = "管理后台 - 客货空间冲突检视 Response VO")
@Data
public class CapacityConflictCheckRespVO {

    @Schema(description = "场景标识", example = "早班 06:30 石桥线")
    private String sceneLabel;

    @Schema(description = "是否存在超限冲突（任一维度缺口 > 0 即为 true）")
    private Boolean overCapacity;

    @Schema(description = "保守结论文本：只说数出来的事实")
    private String summary;

    @Schema(description = "合计核定载客数")
    private Integer totalPassengerCapacity;

    @Schema(description = "合计预报客位数")
    private Integer totalPassengerForecast;

    @Schema(description = "合计核定货仓件数上限")
    private Integer totalCargoItemCapacity;

    @Schema(description = "合计预报货件数")
    private Integer totalCargoItemForecast;

    @Schema(description = "合计核定载重上限（kg）")
    private BigDecimal totalCargoKgCapacity;

    @Schema(description = "合计预报货重（kg）")
    private BigDecimal totalCargoKgForecast;

    @Schema(description = "冲突点（超限 BLOCK / 接近上限 WARN）")
    private List<Conflict> conflicts;

    @Schema(description = "缓冲建议")
    private List<String> bufferSuggestions;

    @Schema(description = "未录容量的提示（这些维度不可判，不把「未录」念成「没有」）")
    private List<String> unratedNotes;

    @Schema(description = "一处冲突点")
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Conflict {

        @Schema(description = "维度：PASSENGER 客位 / CARGO_ITEM 货位 / CARGO_WEIGHT 载重")
        private String dimension;

        @Schema(description = "严重度：BLOCK 超限（保存前必须处理）/ WARN 接近上限")
        private String severity;

        @Schema(description = "核定值")
        private BigDecimal rated;

        @Schema(description = "预报值")
        private BigDecimal forecast;

        @Schema(description = "缺口（预报 - 核定，超限时 > 0）")
        private BigDecimal exceed;

        @Schema(description = "人话描述")
        private String message;

    }

}
