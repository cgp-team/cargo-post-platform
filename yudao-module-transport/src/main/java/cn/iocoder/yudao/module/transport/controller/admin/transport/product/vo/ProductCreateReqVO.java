package cn.iocoder.yudao.module.transport.controller.admin.transport.product.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Schema(description = "管理后台 - 商品创建 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class ProductCreateReqVO extends ProductBaseVO {
}
