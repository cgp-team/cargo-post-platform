package cn.iocoder.yudao.module.transport.controller.admin.transport.driver.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import org.springframework.format.annotation.DateTimeFormat;
import java.time.LocalDateTime;
import static cn.iocoder.yudao.framework.common.util.date.DateUtils.FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND;

@Schema(description="Driver Page")
@Data @EqualsAndHashCode(callSuper=true)
public class DriverPageReqVO extends PageParam {
    @Schema(description="driver name")
    private String name;

    @Schema(description="mobile")
    private String mobile;

    @DateTimeFormat(pattern=FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND)
    @Schema(description="createTime")
    private LocalDateTime[] createTime;
}
