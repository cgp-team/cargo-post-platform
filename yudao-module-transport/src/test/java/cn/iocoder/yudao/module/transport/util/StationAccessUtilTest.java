package cn.iocoder.yudao.module.transport.util;

import cn.iocoder.yudao.module.transport.dal.dataobject.station.StationDO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 站点可达性单测：站点启用 ≠ 用户可达 ≠ 车辆可达 ≠ 可用于调度（需求 §30/§32/§118）。
 */
class StationAccessUtilTest {

    @Test
    void campus_station_is_user_accessible_but_not_vehicle_accessible() {
        StationDO campus = StationDO.builder().id(101L).stationName("重庆邮电大学站").status(0)
                .userAccess(true).vehicleAccess(false).dispatchEnabled(false).build();
        assertTrue(StationAccessUtil.enabled(campus));
        assertTrue(StationAccessUtil.userAccessible(campus));
        assertFalse(StationAccessUtil.vehicleAccessible(campus), "校园站点车辆不可进入");
        assertFalse(StationAccessUtil.dispatchEnabled(campus), "校园站点不开放调度");
    }

    @Test
    void freight_station_all_access() {
        StationDO freight = StationDO.builder().id(107L).stationName("重邮南门货运站").status(0)
                .userAccess(true).vehicleAccess(true).dispatchEnabled(true).build();
        assertTrue(StationAccessUtil.userAccessible(freight));
        assertTrue(StationAccessUtil.vehicleAccessible(freight));
        assertTrue(StationAccessUtil.dispatchEnabled(freight));
    }

    @Test
    void disabled_station_is_not_accessible_even_if_flags_true() {
        StationDO disabled = StationDO.builder().id(9L).status(1)
                .userAccess(true).vehicleAccess(true).dispatchEnabled(true).build();
        assertFalse(StationAccessUtil.userAccessible(disabled));
        assertFalse(StationAccessUtil.vehicleAccessible(disabled));
        assertFalse(StationAccessUtil.dispatchEnabled(disabled));
    }

    @Test
    void null_flags_are_backward_compatible() {
        StationDO legacy = StationDO.builder().id(1L).status(0).build();
        assertTrue(StationAccessUtil.userAccessible(legacy));
        assertTrue(StationAccessUtil.vehicleAccessible(legacy));
        assertTrue(StationAccessUtil.dispatchEnabled(legacy));
    }

}
