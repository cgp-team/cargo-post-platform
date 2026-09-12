package cn.iocoder.yudao.module.transport.controller.admin.transport.product.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

@Schema(description = "管理后台 - 商品 Base VO")
@Data
public class ProductBaseVO {
    @Schema(description = "商品名称", requiredMode = Schema.RequiredMode.REQUIRED)
    private String name;
    @Schema(description = "产地村庄")
    private String fromVillage;
    @Schema(description = "售价")
    private BigDecimal price;
    @Schema(description = "计价单位")
    private String unit;
    @Schema(description = "商品图(emoji)")
    private String image;
    @Schema(description = "商品图片 URL（后台上传；为空时回落 emoji/本地图）")
    private String imageUrl;
    @Schema(description = "角标文案")
    private String badge;
    @Schema(description = "商品描述")
    private String description;
    @Schema(description = "库存")
    private Integer stock;
    @Schema(description = "状态(0上架 1下架)")
    private Integer status;
    @Schema(description = "排序值(越小越靠前)")
    private Integer sort;
}
