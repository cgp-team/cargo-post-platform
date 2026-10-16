package cn.iocoder.yudao.module.aiassist.controller.admin.savedquery.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Schema(description = "管理后台 - 保存的查询条件分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class AiSavedQueryPageReqVO extends PageParam {

    @Schema(description = "名称（模糊）", example = "未分配")
    private String name;

    @Schema(description = "业务场景：ORDER / DISPATCH / FEEDBACK", example = "DISPATCH")
    private String scene;

}
