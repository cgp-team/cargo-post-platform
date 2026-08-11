package cn.iocoder.yudao.module.transport.controller.app.transport.send.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Schema(description = "用户 APP - 寄货创建 Request VO")
@Data
public class AppSendOrderCreateReqVO {

    @Schema(description = "取货站点编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "取货站点不能为空")
    private Long pickupStationId;

    @Schema(description = "送达站点编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "送达站点不能为空")
    private Long deliveryStationId;

    @Schema(description = "货物名称", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "货物名称不能为空")
    private String goodsName;

    @Schema(description = "货物重量(kg)", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "货物重量不能为空")
    private BigDecimal goodsWeight;

    @Schema(description = "货物备注")
    private String goodsNote;

    @Schema(description = "货物照片")
    private String photoUrl;

    @Schema(description = "收货人")
    private String receiverName;

    @Schema(description = "收货电话")
    private String receiverMobile;

    @Schema(description = "收货地址")
    private String receiverAddress;

    @Schema(description = "最早取货时间")
    private LocalDateTime earliestPickupTime;
}
