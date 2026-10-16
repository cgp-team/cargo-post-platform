package cn.iocoder.yudao.module.aiassist.controller.admin.savedquery.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "管理后台 - 保存的查询条件 保存 Request VO")
@Data
public class AiSavedQuerySaveReqVO {

    @Schema(description = "编号（新增时为空）", example = "1")
    private Long id;

    @Schema(description = "名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "本周未分配订单")
    @NotBlank(message = "请填写名称")
    @Size(max = 64, message = "名称长度不能超过 64 个字符")
    private String name;

    @Schema(description = "业务场景：ORDER / DISPATCH / FEEDBACK", requiredMode = Schema.RequiredMode.REQUIRED, example = "DISPATCH")
    @NotBlank(message = "请选择业务场景")
    private String scene;

    @Schema(description = "结构化筛选条件（JSON 文本）", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "{\"status\":\"UNASSIGNED\"}")
    @NotBlank(message = "筛选条件不能为空")
    @Size(max = 4000, message = "筛选条件过长（上限 4000 字符）")
    private String conditionsJson;

    @Schema(description = "备注", example = "每周一早上核对")
    @Size(max = 255, message = "备注长度不能超过 255 个字符")
    private String remark;

}
