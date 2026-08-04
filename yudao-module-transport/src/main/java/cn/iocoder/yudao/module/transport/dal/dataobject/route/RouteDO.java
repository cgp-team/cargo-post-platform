package cn.iocoder.yudao.module.transport.dal.dataobject.route;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;
import java.math.BigDecimal;

@TableName("transport_route")
@KeySequence("transport_route_seq")
@Data
@EqualsAndHashCode(callSuper=true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RouteDO extends TenantBaseDO {
    @TableId
    private Long id;
    private String routeCode;

    private String routeName;

    private Long startStationId;

    private Long endStationId;

    private BigDecimal distanceKm;
}
