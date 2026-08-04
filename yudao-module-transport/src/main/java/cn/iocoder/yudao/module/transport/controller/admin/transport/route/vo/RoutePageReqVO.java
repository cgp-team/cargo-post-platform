package cn.iocoder.yudao.module.transport.controller.admin.transport.route.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import org.springframework.format.annotation.DateTimeFormat;
import java.time.LocalDateTime;
import static cn.iocoder.yudao.framework.common.util.date.DateUtils.FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND;

@Schema(description="Route Page")
@Data @EqualsAndHashCode(callSuper=true)
public class RoutePageReqVO extends PageParam {
    @Schema(description="route code")
    private String routeCode;

    @Schema(description="route name")
    private String routeName;

    @DateTimeFormat(pattern=FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND)
    @Schema(description="createTime")
    private LocalDateTime[] createTime;
}
