package cn.iocoder.yudao.module.transport.dal.dataobject.dispatch;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("transport_leg")
@KeySequence("transport_leg_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransportLegDO extends TenantBaseDO {

    @TableId
    private Long id;
    private Long orderId;
    /** 所属运输方案（transport_dispatch_plan.id，多段联运方案聚合） */
    private Long planId;
    private Integer legSequence;
    private Long fromStationId;
    private Long toStationId;
    private Long vehicleId;
    private Long driverId;
    private Long shiftId;
    /** 承运线路（route.id，本段所走线路） */
    private Long routeId;
    private Long planItemId;
    private Integer status;
    private LocalDateTime estimatedDeparture;
    private LocalDateTime estimatedArrival;
    private LocalDateTime actualDeparture;
    private LocalDateTime actualArrival;
    /** 本段里程(km) */
    private BigDecimal distanceKm;
    /** 本段预计耗时(分钟) */
    private Integer durationMinutes;
    /** 导航路线来源：AMAP 真实道路 / ESTIMATED 估算 / PROJECT 项目线路（不伪装实时导航） */
    private String navigationSource;
    /** 导航 polyline（JSON 坐标串，AMAP 或估算） */
    private String navigationPolyline;
    /** 本段货物件数/重量（多段联运逐段核对） */
    private Integer cargoCount;
    private BigDecimal cargoWeight;
    /** 是否需要换乘交接（非最终段为 true） */
    private Boolean handoverRequired;
}
