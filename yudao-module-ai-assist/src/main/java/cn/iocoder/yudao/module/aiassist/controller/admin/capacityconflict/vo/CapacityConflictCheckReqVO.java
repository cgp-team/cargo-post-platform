package cn.iocoder.yudao.module.aiassist.controller.admin.capacityconflict.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 客货空间冲突检视 Request VO（契约 docs/AI_ASSIST_BOUNDARY.md §6 第 3 项）。
 *
 * <p>本检视是**确定性约束校验**，不是概率猜测：把"核定载客/载货"与"预报量"两组数字放一起比大小。
 * 输入只收聚合后的数字（车辆核定容量 + 分段预报量），不含任何姓名/电话/完整地址，
 * 也不需要模型（契约 §0：AI 出口只输出"文本 + 草稿"）。
 *
 * <p>⚠ 为什么车辆容量用可空类型：DB 里 `passenger_capacity` 是 `NOT NULL DEFAULT 0`，
 * "没录"和"真的是 0 座"在库里是同一个 0。这里把"没录"显式传 null，服务层据此判"该维度不可判"，
 * 绝不把"未录"当成 0 座 —— 和 `DISPATCH_VEHICLE_PROFILE_INCOMPLETE` 是同一条纪律。
 */
@Schema(description = "管理后台 - 客货空间冲突检视 Request VO")
@Data
public class CapacityConflictCheckReqVO {

    @Schema(description = "场景标识（班次/线路的文字标签，仅用于报告文本）", example = "早班 06:30 石桥线")
    private String sceneLabel;

    @Schema(description = "参与车辆（核定容量的来源）", requiredMode = Schema.RequiredMode.REQUIRED)
    @Valid
    private List<Vehicle> vehicles;

    @Schema(description = "分段的预报量（客流/货量的来源）", requiredMode = Schema.RequiredMode.REQUIRED)
    @Valid
    private List<Segment> segments;

    @Schema(description = "车辆核定容量")
    @Data
    public static class Vehicle {

        @Schema(description = "车辆标识（车牌/编号，用于报告文本）", example = "渝A·12345")
        @NotBlank(message = "车辆标识不能为空")
        private String label;

        @Schema(description = "核定载客数；未录传 null，不传 0")
        private Integer passengerCapacity;

        @Schema(description = "核定货仓件数上限；未录传 null")
        private Integer cargoCapacityItems;

        @Schema(description = "核定载重上限（kg）；未录传 null")
        private BigDecimal cargoCapacityKg;

    }

    @Schema(description = "一段行程的预报量")
    @Data
    public static class Segment {

        @Schema(description = "分段名称（站点/区间）", example = "石桥镇 → 县城")
        @NotBlank(message = "分段名称不能为空")
        private String name;

        @Schema(description = "预报客位数（人）", example = "18")
        private Integer forecastPassenger;

        @Schema(description = "预报货件数（件）", example = "26")
        private Integer forecastCargoItems;

        @Schema(description = "预报货重（kg）", example = "310.5")
        private BigDecimal forecastCargoKg;

    }

}
