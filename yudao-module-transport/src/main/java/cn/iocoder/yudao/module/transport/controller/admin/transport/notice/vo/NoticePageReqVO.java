package cn.iocoder.yudao.module.transport.controller.admin.transport.notice.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Schema(description = "管理后台 - 平台公告分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class NoticePageReqVO extends PageParam {
    @Schema(description = "公告标题")
    private String title;
    @Schema(description = "状态(0下架 1上架)")
    private Integer status;
}
