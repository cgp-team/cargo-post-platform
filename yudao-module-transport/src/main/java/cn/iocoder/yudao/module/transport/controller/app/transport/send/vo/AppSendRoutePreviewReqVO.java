package cn.iocoder.yudao.module.transport.controller.app.transport.send.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Schema(description = "用户 APP - 寄货路线预览 Request VO")
@Data
public class AppSendRoutePreviewReqVO {

    @Schema(description = "取货站点编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "取货站点不能为空")
    private Long pickupStationId;

    @Schema(description = "送达站点编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "送达站点不能为空")
    private Long deliveryStationId;

}
