package cn.iocoder.yudao.module.transport.controller.admin.transport.townevent.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

@Schema(description = "管理后台 - 乡镇事件日历保存 Request VO")
@Data
public class TownEventSaveReqVO {

    @Schema(description = "编号（新增留空）")
    private Long id;

    @Schema(description = "乡镇/场镇名", requiredMode = Schema.RequiredMode.REQUIRED, example = "双龙镇")
    @NotBlank(message = "请填写乡镇名")
    private String town;

    @Schema(description = "类型：MARKET/FARMING/HOLIDAY/EVENT", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "请选择事件类型")
    private String eventType;

    @Schema(description = "起始日", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "请填写起始日")
    private LocalDate startDate;

    @Schema(description = "结束日（含）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "请填写结束日")
    private LocalDate endDate;

    @Schema(description = "重复规则：NONE / EVERY_N_DAYS，留空按 NONE")
    private String recurrence;

    @Schema(description = "EVERY_N_DAYS 的间隔天数")
    private Integer intervalDays;

    @Schema(description = "备注")
    private String note;
}
