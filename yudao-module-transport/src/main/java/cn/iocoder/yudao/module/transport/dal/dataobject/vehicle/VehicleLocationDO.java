package cn.iocoder.yudao.module.transport.dal.dataobject.vehicle;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 车辆最新位置 DO：每车一行，司机端上报 upsert。
 */
@TableName("transport_vehicle_location")
@KeySequence("transport_vehicle_location_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VehicleLocationDO extends TenantBaseDO {
    @TableId
    private Long id;
    private Long vehicleId;
    private Long shiftId;
    /** 经度 */
    private BigDecimal longitude;
    /** 纬度 */
    private BigDecimal latitude;
    /** 速度(km/h) */
    private BigDecimal speedKmh;
    /** 上报时间 */
    private LocalDateTime reportTime;
}
