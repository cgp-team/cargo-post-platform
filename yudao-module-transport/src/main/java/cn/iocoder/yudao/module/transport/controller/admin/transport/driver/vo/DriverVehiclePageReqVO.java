package cn.iocoder.yudao.module.transport.controller.admin.transport.driver.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

import static cn.iocoder.yudao.framework.common.util.date.DateUtils.FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND;

@Schema(description = "管理后台 - 司机车辆绑定分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class DriverVehiclePageReqVO extends PageParam {

    @Schema(description = "司机编号")
    private Long driverId;

    @Schema(description = "车辆编号")
    private Long vehicleId;

    @Schema(description = "绑定状态：1 绑定中，0 已解绑")
    private Integer status;

    @Schema(description = "绑定时间区间开始")
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND)
    private LocalDateTime bindTimeStart;

    @Schema(description = "绑定时间区间结束")
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND)
    private LocalDateTime bindTimeEnd;

}
