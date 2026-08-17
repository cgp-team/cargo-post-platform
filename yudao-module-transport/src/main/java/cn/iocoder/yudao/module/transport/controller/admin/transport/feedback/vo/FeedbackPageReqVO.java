package cn.iocoder.yudao.module.transport.controller.admin.transport.feedback.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Schema(description = "管理后台 - 意见反馈分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class FeedbackPageReqVO extends PageParam {
    @Schema(description = "联系人姓名")
    private String name;
    @Schema(description = "联系电话")
    private String mobile;
    @Schema(description = "状态(0待处理 1已回复)")
    private Integer status;
}
