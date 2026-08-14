package cn.iocoder.yudao.module.transport.dal.dataobject.order;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.math.BigDecimal;

@TableName("transport_cargo_order")
@KeySequence("transport_cargo_order_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CargoOrderDO extends TenantBaseDO {
    @TableId
    private Long id;
    private Long orderId;
    private String cargoCategory;
    private Boolean freshFlag;
    private Integer itemCount;
    private BigDecimal weightKg;
    private BigDecimal volumeM3;
    /** 货物名称（小程序寄货） */
    private String goodsName;
    /** 货物备注 */
    private String goodsNote;
    /** 货物照片（村民寄货时拍） */
    private String photoUrl;
    /** 司机收件照片（装车时强制拍，快递总站核对"这是哪家货"的凭证） */
    private String driverPhotoUrl;
    /** 收货人 */
    private String receiverName;
    /** 收货电话 */
    private String receiverMobile;
    /** 收货地址 */
    private String receiverAddress;
}
