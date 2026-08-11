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
    private String badge;
    private String description;
    private Integer stock;
    private Integer status;
    private Integer sort;
}
