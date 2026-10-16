package cn.iocoder.yudao.module.aiassist.controller.admin.savedquery.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 保存的查询条件 Response VO")
@Data
public class AiSavedQueryRespVO {

    @Schema(description = "编号", example = "1")
    private Long id;

    @Schema(description = "名称", example = "本周未分配订单")
    private String name;

    @Schema(description = "业务场景：ORDER / DISPATCH / FEEDBACK", example = "DISPATCH")
    private String scene;

    @Schema(description = "结构化筛选条件（JSON 文本）")
    private String conditionsJson;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

}
