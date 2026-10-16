package cn.iocoder.yudao.module.aiassist.controller.admin.briefing.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 运营简报生成 Request VO（契约 §6 第 4 项）。
 *
 * <p>"定时聚合"这一步本期不在这里：聚合要读既有业务数据，等既有接口把聚合读数交出来即可复用本模板；
 * 本接口只负责把**已经取到的数字**排成一份可读、可存档的模板文档（合同 §0：只输出文本）。
 */
@Schema(description = "管理后台 - 运营简报生成 Request VO")
@Data
public class OperationalBriefingReqVO {

    @Schema(description = "简报标题", requiredMode = Schema.RequiredMode.REQUIRED, example = "石桥镇 · 早班运营简报")
    @NotBlank(message = "请填写简报标题")
    @Size(max = 128, message = "标题长度不能超过 128 个字符")
    private String title;

    @Schema(description = "统计周期文字", example = "2026-10-14 ~ 2026-10-20")
    @Size(max = 64, message = "周期文字长度不能超过 64 个字符")
    private String periodLabel;

    @Schema(description = "指标项", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "至少填写一项指标")
    @Valid
    private List<Metric> metrics;

    @Schema(description = "文字备注")
    private List<String> notes;

    @Schema(description = "单项指标")
    @Data
    public static class Metric {

        @Schema(description = "指标名", requiredMode = Schema.RequiredMode.REQUIRED, example = "未分配订单数")
        @NotBlank(message = "指标名不能为空")
        @Size(max = 64, message = "指标名长度不能超过 64 个字符")
        private String name;

        @Schema(description = "数值（用文字承载，避免把「缺值」折叠成 0）", requiredMode = Schema.RequiredMode.REQUIRED,
                example = "17")
        @NotBlank(message = "指标数值不能为空；缺值请写「未取到」而不是留空或写 0")
        @Size(max = 64, message = "指标数值长度不能超过 64 个字符")
        private String value;

        @Schema(description = "单位", example = "单")
        @Size(max = 16, message = "单位长度不能超过 16 个字符")
        private String unit;

        @Schema(description = "备注", example = "集中在石桥镇")
        @Size(max = 128, message = "指标备注长度不能超过 128 个字符")
        private String note;

    }

}
