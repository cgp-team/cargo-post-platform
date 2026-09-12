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

    /** 运营线路编号（运营范围）：联运分段时优先选"本段起终点都在该线路覆盖内"的车；NULL=不限范围 */
    private Long routeId;

    private LocalDateTime bindTime;

    private LocalDateTime unbindTime;

    /** 绑定状态：1 绑定中，0 已解绑 */
    private Integer status;
}
