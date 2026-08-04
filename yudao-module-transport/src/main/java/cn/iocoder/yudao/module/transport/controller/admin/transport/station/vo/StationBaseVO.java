package cn.iocoder.yudao.module.transport.controller.admin.transport.station.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import java.math.BigDecimal;

@Schema(description="Station Base VO")
@Data
public class StationBaseVO {
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
}
