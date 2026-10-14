package cn.iocoder.yudao.module.transport.controller.admin.transport.vehicle.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
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

    // 这里是"0 座车"唯一的出生地：DDL 是 `passenger_capacity int NOT NULL DEFAULT 0`
    // （sql/mysql/transport-schema.sql:8），而算法契约要求 ≥ 1（algorithm/app/models.py:56）。
    // 不挡住就得到一个看不懂的下游错误：要么派单在算法侧 422，要么被 buildPlanRequest 整台剔除
    // （错误码 1005008012）—— 录入时一句"核定载客数至少为 1"比事后查那台车便宜得多。
    @Schema(description="passenger capacity", requiredMode=Schema.RequiredMode.REQUIRED)
    @NotNull(message="核定载客数不能为空")
    @Min(value=1, message="核定载客数至少为 1（0 座的车不能载客，也不会被派单）")
    private Integer passengerCapacity;

    // 0/空 = "未核定载重"，是合法状态而不是上限：下发时会被折算成"不传这个约束"，
    // 算法退回按货仓件数卡（DispatchServiceImpl.buildPlanRequest 的 cargoWeightCapacityKg）。
    // 负数没有物理意义，且会静默变成"什么都装不下"，所以在入口就拒绝。
    @Schema(description="cargo capacity kg, 0 or empty means not assessed")
    @Min(value=0, message="载货重量上限不能为负数")
    private BigDecimal cargoCapacityKg;

    @Schema(description="cargo capacity in items (algorithm capacity constraint)")
    @Min(value=0, message="货仓件数上限不能为负数")
    private Integer cargoCapacity;

    @Schema(description="insurance expire date")
    private LocalDate insuranceExpireDate;

    @Schema(description="vehicle status: 0 available, 1 disabled")
    private Integer status;

    @Schema(description="实时运营状态：0 空闲 1 在途 2 故障 3 离线（随运输段开始/完成同步）")
    private Integer realtimeStatus;
}
