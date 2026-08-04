package cn.iocoder.yudao.module.transport.controller.admin.transport.route.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Schema(description="Route Response")
@Data @EqualsAndHashCode(callSuper=true) @ToString(callSuper=true)
public class RouteRespVO extends RouteBaseVO {
    @Schema(description="ID")
    private Long id;
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
    @Schema(description="Create time")
    private LocalDateTime createTime;
}
