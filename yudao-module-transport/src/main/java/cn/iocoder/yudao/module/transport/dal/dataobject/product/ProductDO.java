package cn.iocoder.yudao.module.transport.dal.dataobject.product;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.math.BigDecimal;

@TableName("transport_product")
@KeySequence("transport_product_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductDO extends TenantBaseDO {
    @TableId
    private Long id;
    private String name;
    private String fromVillage;
    private BigDecimal price;
    private String unit;
    private String image;
    /** 商品图片 URL（后台表单上传）；为空时前端回落到 image(emoji)/本地图 */
    private String imageUrl;
    private String badge;
    private String description;
    private Integer stock;
    private Integer status;
    private Integer sort;
}
