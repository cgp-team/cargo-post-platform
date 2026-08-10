package cn.iocoder.yudao.module.transport.controller.admin.transport.vehicle.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import org.springframework.format.annotation.DateTimeFormat;
import java.time.LocalDateTime;
import static cn.iocoder.yudao.framework.common.util.date.DateUtils.FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND;

@Schema(description="Vehicle Page")
@Data @EqualsAndHashCode(callSuper=true)
public class VehiclePageReqVO extends PageParam {
    @Schema(description="plate number")
    private String plateNo;

    @Schema(description="vehicle status: 0 available, 1 disabled")
    private Integer status;

    @DateTimeFormat(pattern=FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND)
    @Schema(description="createTime")
    private LocalDateTime[] createTime;
}
