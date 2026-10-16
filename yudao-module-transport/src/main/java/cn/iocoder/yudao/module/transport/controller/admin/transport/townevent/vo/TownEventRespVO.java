package cn.iocoder.yudao.module.transport.controller.admin.transport.townevent.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Schema(description = "管理后台 - 乡镇事件日历 Response VO")
@Data
public class TownEventRespVO {

    @Schema(description = "编号")
    private Long id;
    @Schema(description = "乡镇/场镇名")
    private String town;
    @Schema(description = "类型")
    private String eventType;
    @Schema(description = "起始日")
    private LocalDate startDate;
    @Schema(description = "结束日")
    private LocalDate endDate;
    @Schema(description = "重复规则：NONE / EVERY_N_DAYS")
    private String recurrence;
    @Schema(description = "间隔天数")
    private Integer intervalDays;
    @Schema(description = "备注")
    private String note;
    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
