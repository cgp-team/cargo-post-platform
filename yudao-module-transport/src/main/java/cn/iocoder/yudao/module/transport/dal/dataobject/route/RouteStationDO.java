package cn.iocoder.yudao.module.transport.dal.dataobject.route;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

@TableName("transport_route_station")
@KeySequence("transport_route_station_seq")
@Data
@EqualsAndHashCode(callSuper=true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RouteStationDO extends TenantBaseDO {
    @TableId
    private Long id;
    private Long routeId;

    private Long stationId;

    /** 访问顺序，从 1 开始 */
    private Integer sequenceNo;

    /** 从线路起点计划分钟数（累计） */
    private Integer plannedMinutes;
}
