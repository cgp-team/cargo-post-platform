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

    @Schema(description="实时运营状态：0 空闲 1 在途 2 故障 3 离线（随运输段开始/完成同步）")
    private Integer realtimeStatus;
}
