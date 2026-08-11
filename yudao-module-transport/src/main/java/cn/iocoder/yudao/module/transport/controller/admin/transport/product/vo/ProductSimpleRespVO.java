package cn.iocoder.yudao.module.transport.controller.admin.transport.product.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

@Schema(description = "管理后台 - 商品精简信息 Response VO")
@Data
public class ProductSimpleRespVO {
    @Schema(description = "商品编号", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long id;
    @Schema(description = "商品名称", requiredMode = Schema.RequiredMode.REQUIRED)
    private String name;
    @Schema(description = "商品图(emoji)")
    private String image;
    @Schema(description = "售价")
    private BigDecimal price;
    @Schema(description = "计价单位")
    private String unit;
}
