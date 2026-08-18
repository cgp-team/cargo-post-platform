package cn.iocoder.yudao.module.transport.controller.admin.transport.order.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Schema(description = "管理后台 - 商城订单发货 Request VO")
@Data
public class ProductOrderShipReqVO {
    @Schema(description = "订单编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "订单编号不能为空")
    private Long id;
    @Schema(description = "承运车辆编号(选填,溯源用)")
    private Long vehicleId;
    @Schema(description = "承运班次编号(选填,溯源用)")
    private Long shiftId;
}
