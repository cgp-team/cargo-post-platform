package cn.iocoder.yudao.module.transport.dal.dataobject.driver;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("transport_driver_status")
@KeySequence("transport_driver_status_seq")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransportDriverStatusDO {

    @TableId
    private Long id;
    private Long driverId;
    private Integer onlineStatus;
    private Long currentVehicleId;
    private Long currentPlanId;
    private LocalDateTime lastHeartbeat;
    private BigDecimal lastLatitude;
    private BigDecimal lastLongitude;
    private Long tenantId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
