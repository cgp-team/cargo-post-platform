package cn.iocoder.yudao.module.transport.controller.admin.transport.product.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Schema(description = "管理后台 - 商品 Base VO")
@Data
public class ProductBaseVO {
    @Schema(description = "商品名称", requiredMode = Schema.RequiredMode.REQUIRED)
    @Size(max = 128, message = "商品名称最多 128 个字符")
    private String name;
    @Schema(description = "产地村庄")
    @Size(max = 64, message = "产地村庄最多 64 个字符")
    private String fromVillage;
    @Schema(description = "售价")
    private BigDecimal price;
    @Schema(description = "计价单位")
    @Size(max = 16, message = "计价单位最多 16 个字符")
    private String unit;
    @Schema(description = "商品图(emoji)")
    @Size(max = 255, message = "商品图（emoji/短地址）最多 255 个字符，图片请填到「图片地址」")
    private String image;
    @Schema(description = "商品图片 URL（后台上传或直接粘贴；为空时回落 emoji/本地图）")
    @Size(max = 1024, message = "图片地址最多 1024 个字符")
    private String imageUrl;
    @Schema(description = "角标文案")
    @Size(max = 64, message = "角标文案最多 64 个字符")
    private String badge;
    @Schema(description = "商品描述")
    @Size(max = 2000, message = "商品描述最多 2000 个字符（超出请精简）")
    private String description;
    @Schema(description = "库存")
    private Integer stock;
    @Schema(description = "状态(0上架 1下架)")
    private Integer status;
    @Schema(description = "排序值(越小越靠前)")
    private Integer sort;
}
