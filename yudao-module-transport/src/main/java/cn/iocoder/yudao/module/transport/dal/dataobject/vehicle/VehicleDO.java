package cn.iocoder.yudao.module.transport.dal.dataobject.vehicle;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@TableName("transport_vehicle")
@KeySequence("transport_vehicle_seq")
@Data
@EqualsAndHashCode(callSuper=true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VehicleDO extends TenantBaseDO {
    @TableId
    private Long id;
    private String plateNo;

    private Integer vehicleType;

    private Integer passengerCapacity;

    private BigDecimal cargoCapacityKg;

    /** 货仓件数上限（算法容量约束按件数） */
    private Integer cargoCapacity;

    /** 保险到期日 */
    private LocalDate insuranceExpireDate;

    /** 车辆状态：0 空闲可用，1 停用维修 */
    private Integer status;
    /** 实时运营状态：0 空闲(AVAILABLE) 1 在途(IN_SERVICE) 2 故障 3 离线（随运输段开始/完成同步，见需求 §123） */
    private Integer realtimeStatus;
}
