package cn.iocoder.yudao.module.transport.controller.admin.transport.product.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Schema(description = "管理后台 - 商品更新 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class ProductUpdateReqVO extends ProductBaseVO {
    @Schema(description = "商品编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "商品编号不能为空")
    private Long id;
}
