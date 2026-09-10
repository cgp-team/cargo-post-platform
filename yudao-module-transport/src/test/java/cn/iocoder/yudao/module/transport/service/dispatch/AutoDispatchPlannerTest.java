package cn.iocoder.yudao.module.transport.service.dispatch;

import cn.iocoder.yudao.module.transport.dal.dataobject.order.TransportOrderDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.station.StationDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.vehicle.VehicleDO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 一键智能调度"自动选择"规则单测：管理员不选场站、不选车辆、不填参数也能出方案（产品定义）。
 */
class AutoDispatchPlannerTest {

    private static StationDO station(long id, String name, int level, double lng, double lat) {
        return StationDO.builder().id(id).stationName(name).stationLevel(level)
                .longitude(BigDecimal.valueOf(lng)).latitude(BigDecimal.valueOf(lat)).status(0).build();
    }

    /** 场站级：1 县城客运中心（西）、4 青山镇站（东）；村级：5 大湾村站 */
    private static final List<StationDO> STATIONS = List.of(
            station(1L, "县城客运中心", 1, 104.0657, 30.5723),
            station(4L, "青山镇站", 1, 104.2345, 30.6234),
            station(5L, "大湾村站", 3, 104.2567, 30.6456));

    private static TransportOrderDO order(long id, Long pickup, Long delivery) {
        return TransportOrderDO.builder().id(id).orderNo("TP" + id)
                .pickupStationId(pickup).deliveryStationId(delivery).build();
    }

    @Test
    void selectDepot_picksNearestDepotToWholeBatch() {
        // 订单集中在东侧（青山镇/大湾村）→ 应自动选青山镇站，而不是县城客运中心
        List<TransportOrderDO> orders = List.of(order(1L, 4L, 5L), order(2L, 5L, 4L));

        StationDO depot = AutoDispatchPlanner.selectDepot(orders, STATIONS);

        assertNotNull(depot);
        assertEquals(4L, depot.getId());
        assertEquals("青山镇站", depot.getStationName());
    }

    @Test
    void selectDepot_ignoresVillageLevelAndDisabledStations() {
        // 只有场站级站点参与候选：村级 5 更近也不选；停用站点（status=1）同样不选
        StationDO disabledDepot = station(4L, "青山镇站", 1, 104.2345, 30.6234);
        disabledDepot.setStatus(1);
        List<StationDO> stations = List.of(disabledDepot, station(5L, "大湾村站", 3, 104.2567, 30.6456));
        List<TransportOrderDO> orders = List.of(order(1L, 5L, 5L));

        StationDO depot = AutoDispatchPlanner.selectDepot(orders, stations);

        assertNotNull(depot);
        assertEquals(5L, depot.getId()); // 无可用场站级站点 → 退化为全部启用站点
    }

    @Test
    void selectDepot_tieBreaksByStationId() {
        List<StationDO> stations = List.of(
                station(9L, "场站B", 1, 104.1, 30.6), station(3L, "场站A", 1, 104.1, 30.6));
        assertEquals(3L, AutoDispatchPlanner.selectDepot(List.of(order(1L, null, null)), stations).getId());
    }

    @Test
    void selectVehicles_takesCapacityFirstThenIdAndSkipsDisabled() {
        List<VehicleDO> vehicles = List.of(
                VehicleDO.builder().id(1L).plateNo("川A1").status(0).cargoCapacity(20).passengerCapacity(19).build(),
                VehicleDO.builder().id(2L).plateNo("川A2").status(0).cargoCapacity(50).passengerCapacity(30).build(),
                VehicleDO.builder().id(3L).plateNo("川A3").status(0).cargoCapacity(20).passengerCapacity(19).build(),
                VehicleDO.builder().id(4L).plateNo("川A4").status(1).cargoCapacity(99).build()); // 维修停用

        List<VehicleDO> selected = AutoDispatchPlanner.selectVehicles(vehicles, 2);

        // 运力优先（2 号），同运力按 ID 升序（1 号）；停用车辆不参与
        assertEquals(List.of(2L, 1L), selected.stream().map(VehicleDO::getId).toList());
    }

    @Test
    void defaultAlgorithmConfig_hasDocumentedHacoDefaults() {
        Map<String, Object> config = AutoDispatchPlanner.defaultAlgorithmConfig();
        assertEquals(30, config.get("ant_count"));
        assertEquals(100, config.get("max_iterations"));
        assertEquals(1.0, config.get("alpha"));
        assertEquals(3.0, config.get("beta"));
        assertEquals(0.1, config.get("rho"));
        assertEquals(100, config.get("Q"));
        assertEquals(20, config.get("convergence_threshold"));
    }

    @Test
    void mergeAlgorithmConfig_advancedOverridesDefaultsOnly() {
        Map<String, Object> merged = AutoDispatchPlanner.mergeAlgorithmConfig(Map.of("max_iterations", 500));
        assertEquals(500, merged.get("max_iterations")); // 高级设置覆盖
        assertEquals(30, merged.get("ant_count"));       // 其余仍走默认
    }
}
