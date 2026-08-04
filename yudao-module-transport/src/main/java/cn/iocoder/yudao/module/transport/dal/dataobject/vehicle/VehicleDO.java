package cn.iocoder.yudao.module.transport.dal.dataobject.vehicle;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;
import java.math.BigDecimal;

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
}
