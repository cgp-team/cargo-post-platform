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
    void selectVehicles_skipsVehiclesBusyWithAnotherPlan() {
        // 同一台车不能同时跑两套方案：多片区各出一套方案时按"在途占用"避让
        List<VehicleDO> vehicles = List.of(
                VehicleDO.builder().id(1L).plateNo("川A1").status(0).cargoCapacity(20).passengerCapacity(19).build(),
                VehicleDO.builder().id(2L).plateNo("川A2").status(0).cargoCapacity(50).passengerCapacity(30).build());

        List<VehicleDO> free = AutoDispatchPlanner.selectVehicles(vehicles, 3, java.util.Set.of(1L));
        assertEquals(List.of(2L), free.stream().map(VehicleDO::getId).toList());

        // 全部在途：返回空 → 调用方回退"不排除"，保证一定出方案
        assertTrue(AutoDispatchPlanner.selectVehicles(vehicles, 3, java.util.Set.of(1L, 2L)).isEmpty());
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

    // ==================== 一键调度"订单批次选择"（片区分批） ====================

    /** 成都片区（104.x/30.5x）+ 重庆邮电大学片区（106.5x/29.5x），相距 250km+ */
    private static final Map<Long, StationDO> REGION_STATIONS = Map.of(
            1L, station(1L, "县城客运中心", 1, 104.0657, 30.5723),
            2L, station(2L, "红花村站", 2, 104.1234, 30.6012),
            4L, station(4L, "青山镇站", 1, 104.2345, 30.6234),
            101L, station(101L, "重庆邮电大学站", 2, 106.5765, 29.5325),
            102L, station(102L, "黄桷垭站", 1, 106.5748, 29.5370));

    private static TransportOrderDO timedOrder(long id, Long pickup, Long delivery, String createTime) {
        TransportOrderDO order = TransportOrderDO.builder().id(id).orderNo("TP" + id)
                .pickupStationId(pickup).deliveryStationId(delivery)
                .build();
        order.setCreateTime(java.time.LocalDateTime.parse(createTime));
        return order;
    }

    @Test
    void selectAutoBatch_newestOrderFirstAndSameRegionOnly() {
        // 最新的一单在重邮片区 → 本批只含重邮片区订单；成都片区留在池里等下一套方案
        List<TransportOrderDO> orders = List.of(
                timedOrder(1L, 1L, 4L, "2026-07-01T08:00:00"),   // 成都，旧
                timedOrder(2L, 2L, 1L, "2026-07-02T08:00:00"),   // 成都
                timedOrder(3L, 101L, 102L, "2026-07-09T09:00:00")); // 重邮，最新

        List<TransportOrderDO> batch = AutoDispatchPlanner.selectAutoBatch(orders, REGION_STATIONS, 25);

        assertEquals(List.of(3L), batch.stream().map(TransportOrderDO::getId).toList());
    }

    @Test
    void selectAutoBatch_keepsWholeRegionAndRespectsMax() {
        // 最新一单在成都片区 → 成都订单成批；跨片区的重邮订单不进本批
        List<TransportOrderDO> orders = List.of(
                timedOrder(1L, 1L, 4L, "2026-07-08T08:00:00"),
                timedOrder(2L, 2L, 1L, "2026-07-07T08:00:00"),
                timedOrder(4L, 4L, 2L, "2026-07-06T08:00:00"),
                timedOrder(3L, 101L, 102L, "2026-07-05T09:00:00"));

        List<TransportOrderDO> batch = AutoDispatchPlanner.selectAutoBatch(orders, REGION_STATIONS, 25);
        assertEquals(List.of(1L, 2L, 4L), batch.stream().map(TransportOrderDO::getId).toList());

        // 单批上限：算法上限内按最新优先截断
        List<TransportOrderDO> capped = AutoDispatchPlanner.selectAutoBatch(orders, REGION_STATIONS, 2);
        assertEquals(List.of(1L, 2L), capped.stream().map(TransportOrderDO::getId).toList());
    }

    @Test
    void selectAutoBatch_missingCoordinatesCannotProveOtherRegion_keepsOrder() {
        // 站点表里没有该订单的站点 → 无法证明跨片区 → 不丢弃（避免订单永远排不进方案）
        Map<Long, StationDO> partial = Map.of(101L, REGION_STATIONS.get(101L));
        List<TransportOrderDO> orders = List.of(
                timedOrder(1L, 101L, 102L, "2026-07-09T09:00:00"),
                timedOrder(2L, 1L, 4L, "2026-07-01T08:00:00"));

        List<TransportOrderDO> batch = AutoDispatchPlanner.selectAutoBatch(orders, partial, 25);

        assertEquals(List.of(1L, 2L), batch.stream().map(TransportOrderDO::getId).toList());
    }
}
