package cn.iocoder.yudao.module.transport.dal.dataobject.order;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("transport_product_order")
@KeySequence("transport_product_order_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductOrderDO extends TenantBaseDO {
    @TableId
    private Long id;
    /** 业务订单号 */
    private String orderNo;
    /** 购买会员编号（member_user.id） */
    private Long userId;
    /** 购买会员手机号 */
    private String userMobile;
    /** 订单总额 */
    private BigDecimal totalAmount;
    /** 订单状态(0待发货 1已发货 2已完成 3已取消) */
    private Integer status;
    /** 承运车辆编号(发货时关联,溯源用) */
    private Long vehicleId;
    /** 承运班次编号(发货时关联,溯源用) */
    private Long shiftId;
    /** 承运司机编号(发货时按车辆绑定推导,司机端任务归属) */
    private Long driverId;
    /** 交付/自提站点编号(发货时=班次线路终点站,司机到站提醒用) */
    private Long deliverStationId;
    /** 司机装车照片URL(装车核验凭证) */
    private String loadPhotoUrl;
    /** 司机装车确认时间 */
    private LocalDateTime loadTime;
    /** 司机妥投照片URL(交付凭证) */
    private String deliverPhotoUrl;
    /** 司机妥投完成时间 */
    private LocalDateTime deliverTime;
    /** 收货人 */
    private String receiverName;
    /** 收货电话 */
    private String receiverMobile;
    /** 收货地址 */
    private String receiverAddress;
    /** 订单备注 */
    private String remark;
}
