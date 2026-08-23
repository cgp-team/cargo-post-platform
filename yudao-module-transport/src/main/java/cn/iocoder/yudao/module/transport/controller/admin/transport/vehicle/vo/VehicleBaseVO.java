package cn.iocoder.yudao.module.transport.controller.admin.transport.vehicle.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;

@Schema(description="Vehicle Base VO")
@Data
public class VehicleBaseVO {
    @Schema(description="plate number")
    private String plateNo;

    @Schema(description="vehicle type")
    private Integer vehicleType;

    @Schema(description="passenger capacity")
    private Integer passengerCapacity;

    @Schema(description="cargo capacity kg")
    private BigDecimal cargoCapacityKg;

    @Schema(description="cargo capacity in items (algorithm capacity constraint)")
    private Integer cargoCapacity;

    @Schema(description="insurance expire date")
    private LocalDate insuranceExpireDate;

    @Schema(description="vehicle status: 0 available, 1 disabled")
    private Integer status;
}
