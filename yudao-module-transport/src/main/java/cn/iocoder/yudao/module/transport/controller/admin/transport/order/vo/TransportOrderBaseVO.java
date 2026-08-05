package cn.iocoder.yudao.module.transport.controller.admin.transport.order.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Schema(description = "管理后台 - 订单 Base VO")
@Data
public class TransportOrderBaseVO {
    @Schema(description = "订单类型", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Integer orderType;
    @Schema(description = "取货/上车站点编号", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long pickupStationId;
    @Schema(description = "送达/下车站点编号", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long deliveryStationId;
    @Schema(description = "最早取货时间")
    private LocalDateTime earliestPickupTime;
    @Schema(description = "最迟送达时间")
    private LocalDateTime latestDeliveryTime;
    @Schema(description = "订单状态")
    private Integer status;
    @Schema(description = "订单金额")
    private BigDecimal totalAmount;
}
