package cn.iocoder.yudao.module.transport.controller.admin.transport.townevent.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Schema(description = "管理后台 - 乡镇事件日历分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class TownEventPageReqVO extends PageParam {

    @Schema(description = "乡镇/场镇名（模糊）")
    private String town;

    @Schema(description = "类型：MARKET/FARMING/HOLIDAY/EVENT")
    private String eventType;

    @Schema(description = "只看某天起之后的事件（含）", example = "2026-10-01")
    private java.time.LocalDate from;
}
