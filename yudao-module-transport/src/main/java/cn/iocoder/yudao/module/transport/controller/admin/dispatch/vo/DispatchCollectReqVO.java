package cn.iocoder.yudao.module.transport.controller.admin.dispatch.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

import static cn.iocoder.yudao.framework.common.util.date.DateUtils.FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND;

@Schema(description = "管理后台 - 订单归集 Request VO")
@Data
public class DispatchCollectReqVO {

    @Schema(description = "批次区间开始", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "批次区间开始不能为空")
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND)
    private LocalDateTime batchStart;

    @Schema(description = "批次区间结束", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "批次区间结束不能为空")
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND)
    private LocalDateTime batchEnd;

}
