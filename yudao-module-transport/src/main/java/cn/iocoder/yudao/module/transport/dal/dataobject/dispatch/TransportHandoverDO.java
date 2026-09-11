package cn.iocoder.yudao.module.transport.dal.dataobject.dispatch;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("transport_handover")
@KeySequence("transport_handover_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransportHandoverDO extends TenantBaseDO {

    @TableId
    private Long id;
    private Long orderId;
    /** 所属运输方案 */
    private Long planId;
    private Long legFromId;
    private Long legToId;
    private Long stationId;
    private Long fromDriverId;
    private Long toDriverId;
    /** 交出/接收车辆（换乘双方车辆） */
    private Long fromVehicleId;
    private Long toVehicleId;
    private Integer itemCount;
    private BigDecimal weightKg;
    /** 交接体积(m³) */
    private BigDecimal cargoVolumeM3;
    private String photoUrl;
    private Integer status;
    private LocalDateTime handoverTime;
    private LocalDateTime confirmTime;
    private String remark;
    /** 状态机时间点：前序到达 / 开始交接 / 完成 / 确认人 */
    private LocalDateTime arrivedAt;
    private LocalDateTime handoverStartedAt;
    private LocalDateTime handoverCompletedAt;
    private Long confirmedBy;
    /** 超时/异常原因 */
    private String exceptionReason;
}
