package cn.iocoder.yudao.module.transport.controller.admin.dispatch.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;
import java.util.List;

import static cn.iocoder.yudao.framework.common.util.date.DateUtils.FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND;

@Schema(description = "管理后台 - 订单归集 Request VO")
@Data
public class DispatchCollectReqVO {

    @Schema(description = "待归集订单编号列表（推荐：前端勾选订单后按 ID 归集）")
    private List<Long> orderIds;

    @Schema(description = "一键归集全部：true=把当前所有「待入池」订单全部入池（演示/批量场景，无需勾选）")
    private Boolean all;

    @Schema(description = "批次区间开始（兼容按时间范围归集，可选；LocalDateTime 按项目全局时间戳序列化）")
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND)
    private LocalDateTime batchStart;

    @Schema(description = "批次区间结束（兼容按时间范围归集，可选）")
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND)
    private LocalDateTime batchEnd;

}
