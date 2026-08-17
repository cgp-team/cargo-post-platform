package cn.iocoder.yudao.module.transport.controller.admin.transport.notice.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 平台公告 Response VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class NoticeRespVO extends NoticeBaseVO {
    @Schema(description = "公告编号", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long id;
    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
