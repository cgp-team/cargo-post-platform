package cn.iocoder.yudao.module.transport.util;

import cn.iocoder.yudao.module.transport.dal.dataobject.station.StationDO;

/**
 * 站点可达性判定（核心原则：站点启用 ≠ 车辆可进入 ≠ 可用于调度）。
 *
 * 三个维度互相独立：
 * - userAccess：用户能否到达该站（步行可达取送点）；
 * - vehicleAccess：运输车辆能否进入该站（校园禁行区等，{@code enabled=true} 不代表车辆能进）；
 * - dispatchEnabled：该站能否作为调度场站/换乘站。
 *
 * 字段为空时按 true 处理（向后兼容历史数据）；演示/业务数据显式配置，避免"新增站点自动获得车辆权限"。
 */
public final class StationAccessUtil {

    private StationAccessUtil() {
    }

    /** 站点是否启用（status: 0=启用 1=停用） */
    public static boolean enabled(StationDO station) {
        return station != null && (station.getStatus() == null || station.getStatus() == 0);
    }

    /** 用户可达（可推荐给用户送站/取货） */
    public static boolean userAccessible(StationDO station) {
        return enabled(station) && !Boolean.FALSE.equals(station.getUserAccess());
    }

    /** 车辆可达（车辆能开进去装卸货） */
    public static boolean vehicleAccessible(StationDO station) {
        return enabled(station) && !Boolean.FALSE.equals(station.getVehicleAccess());
    }

    /** 可用于调度（可作场站/换乘站） */
    public static boolean dispatchEnabled(StationDO station) {
        return enabled(station) && !Boolean.FALSE.equals(station.getDispatchEnabled());
    }

}
