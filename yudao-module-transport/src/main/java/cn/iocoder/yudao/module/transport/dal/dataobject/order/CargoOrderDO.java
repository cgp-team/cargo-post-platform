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
    /** 审核状态：0待审核 1已通过 2已拒绝（村民寄货散件需管理端审核） */
    private Integer auditStatus;
    /** 拒绝原因（审核拒绝时） */
    private String rejectReason;
    /** 承运审核结果（ReviewStatusEnum：0待审 1通过 2需客户操作 3需人工 4拒运；自动审核落库） */
    private Integer reviewStatus;
    /** 承运审核原因码（ReviewReasonCodeEnum，逗号分隔多个） */
    private String reviewReasonCodes;
    /** 取货服务方式（ServiceModeEnum） */
    private String pickupServiceMode;
    /** 送达服务方式（ServiceModeEnum） */
    private String deliveryServiceMode;
    /** 建议服务站点编号（替代交接：客户送站/最近站点时推荐） */
    private Long servicePointStationId;
    /** 收货人 */
    private String receiverName;
    /** 收货电话 */
    private String receiverMobile;
    /** 收货地址 */
    private String receiverAddress;
}
