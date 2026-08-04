package cn.iocoder.yudao.module.transport.controller.admin.transport.station.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Schema(description="Station Response")
@Data @EqualsAndHashCode(callSuper=true) @ToString(callSuper=true)
public class StationRespVO extends StationBaseVO {
    @Schema(description="ID")
    private Long id;
    @Schema(description="station code")
    private String stationCode;

    @Schema(description="station name")
    private String stationName;

    @Schema(description="station level")
    private Integer stationLevel;

    @Schema(description="longitude")
    private BigDecimal longitude;

    @Schema(description="latitude")
    private BigDecimal latitude;

    @Schema(description="address")
    private String address;
    @Schema(description="Create time")
    private LocalDateTime createTime;
}
