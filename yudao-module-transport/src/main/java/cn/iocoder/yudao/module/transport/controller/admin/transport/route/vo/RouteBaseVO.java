package cn.iocoder.yudao.module.transport.controller.admin.transport.route.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import java.math.BigDecimal;

@Schema(description="Route Base VO")
@Data
public class RouteBaseVO {
    @Schema(description="route code")
    private String routeCode;

    @Schema(description="route name")
    private String routeName;

    @Schema(description="start station id")
    private Long startStationId;

    @Schema(description="end station id")
    private Long endStationId;

    @Schema(description="distance km")
    private BigDecimal distanceKm;
}
