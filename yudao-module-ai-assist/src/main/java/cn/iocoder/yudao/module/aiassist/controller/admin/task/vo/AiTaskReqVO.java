package cn.iocoder.yudao.module.aiassist.controller.admin.task.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * AI 辅助任务的统一入参（契约 §7）。
 *
 * <p>故意做成"一个 VO 六个口共用"：这些口<b>都不写业务数据</b>，字段就是"给模型看的材料"，
 * 拆成六个 VO 只会多出六个没人校验的字段。用到的字段各自在 Service 里校验（缺了就报，不静默）。
 */
@Schema(description = "管理后台 - AI 辅助任务 Request VO（只产出参考意见/草稿，不写业务数据）")
@Data
public class AiTaskReqVO {

    @Schema(description = "材料正文：上报描述 / 反馈摘要 / 简报事实底稿", example = "路上塌方了，过不去")
    private String text;

    @Schema(description = "现场照片地址（仅「照片是否支撑描述」用）")
    private String photoUrl;

    @Schema(description = "候选分类；留空则用后台在册的那五项（没有候选集的分类等于让模型自由发挥）")
    private List<String> categories;

    @Schema(description = "多条上报文本（同义归并用，至少两条）")
    private List<String> items;

    @Schema(description = "样本条数（反馈聚类用；必须为正，0 条不做聚类）")
    private Integer sampleCount;

    @Schema(description = "查哪一天的情景提示；留空按明天（赶集要提前准备，今天问今天已经晚了）")
    private LocalDate day;
}
