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

    /** 线路状态：0=启用 1=停用（停用线路不得用于附近公交/地图/调度/导航，需求 §38） */
    private Integer status;

    /** 数据来源：REAL 现实公交线路 / PROJECT 项目自建货运线路（需求 §11/§12/§13） */
    private String sourceType;

    /** 服务类型：PASSENGER 客运 / CARGO 货运 / MIXED 客货邮 */
    private String serviceType;

    /** 是否可用于调度（作干线/接驳线路） */
    private Boolean dispatchEnabled;

    /** 真实道路轨迹："lng,lat;lng,lat;..."（高德取到后落库，长期复用；为空时前端直线暂替） */
    private String navigationPolyline;

    /** 轨迹来源：AMAP=真实道路；NULL=未取到 */
    private String navigationSource;
}
