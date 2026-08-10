package cn.iocoder.yudao.module.transport.dal.dataobject.driver;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

@TableName("transport_driver_vehicle")
@KeySequence("transport_driver_vehicle_seq")
@Data
@EqualsAndHashCode(callSuper=true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DriverVehicleDO extends TenantBaseDO {
    @TableId
    private Long id;
    private Long driverId;

    private Long vehicleId;

    private LocalDateTime bindTime;

    private LocalDateTime unbindTime;

    /** 绑定状态：1 绑定中，0 已解绑 */
    private Integer status;
}
