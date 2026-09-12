package cn.iocoder.yudao.module.transport.controller.admin.transport.station.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import org.springframework.format.annotation.DateTimeFormat;
import java.time.LocalDateTime;
import static cn.iocoder.yudao.framework.common.util.date.DateUtils.FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND;

@Schema(description="Station Page")
@Data @EqualsAndHashCode(callSuper=true)
public class StationPageReqVO extends PageParam {
    @Schema(description="station code")
    private String stationCode;

    @Schema(description="station name")
    private String stationName;

    @Schema(description="站点类型：BUS_STOP/CARGO_STATION/MIXED")
    private String stationType;

    @Schema(description="数据来源：REAL/PROJECT/SIMULATION")
    private String sourceType;

    @Schema(description="是否可用于调度")
    private Boolean dispatchEnabled;

    @Schema(description="站点状态：0=启用 1=停用")
    private Integer status;

    @DateTimeFormat(pattern=FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND)
    @Schema(description="createTime")
    private LocalDateTime[] createTime;
}
