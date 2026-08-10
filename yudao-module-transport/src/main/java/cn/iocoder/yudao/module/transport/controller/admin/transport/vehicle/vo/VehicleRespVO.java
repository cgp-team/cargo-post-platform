package cn.iocoder.yudao.module.transport.controller.admin.transport.vehicle.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Schema(description="Vehicle Response")
@Data @EqualsAndHashCode(callSuper=true) @ToString(callSuper=true)
public class VehicleRespVO extends VehicleBaseVO {
    @Schema(description="ID")
    private Long id;
    @Schema(description="plate number")
    private String plateNo;

    @Schema(description="vehicle type")
    private Integer vehicleType;

    @Schema(description="passenger capacity")
    private Integer passengerCapacity;

    @Schema(description="cargo capacity kg")
    private BigDecimal cargoCapacityKg;

    @Schema(description="vehicle status: 0 available, 1 disabled")
    private Integer status;

    @Schema(description="Create time")
    private LocalDateTime createTime;
}
