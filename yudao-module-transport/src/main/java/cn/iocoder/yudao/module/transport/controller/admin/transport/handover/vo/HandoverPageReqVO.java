package cn.iocoder.yudao.module.transport.controller.admin.transport.handover.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

import static cn.iocoder.yudao.framework.common.util.date.DateUtils.FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND;

@Schema(description = "管理后台 - 货物交接分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class HandoverPageReqVO extends PageParam {

    @Schema(description = "运输订单编号")
    private Long orderId;

    @Schema(description = "交接站点编号")
    private Long stationId;

    @Schema(description = "交出司机编号")
    private Long fromDriverId;

    @Schema(description = "接收司机编号")
    private Long toDriverId;

    @Schema(description = "状态：0待确认 1已确认 2有争议")
    private Integer status;

    @Schema(description = "创建时间")
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND)
    private LocalDateTime[] createTime;

}
