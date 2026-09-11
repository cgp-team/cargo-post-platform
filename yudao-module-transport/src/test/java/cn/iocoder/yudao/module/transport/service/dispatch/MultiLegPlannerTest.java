package cn.iocoder.yudao.module.transport.service.dispatch;

import cn.iocoder.yudao.module.transport.dal.dataobject.order.TransportOrderDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.route.RouteStationDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.station.StationDO;
import cn.iocoder.yudao.module.transport.enums.dispatch.DispatchPlanningModeEnum;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 多段联运规划器单测：直达优先 / 两段联运 / 三段联运 / 不可达站点不作为换乘站（需求 §109~§112）。
 */
class MultiLegPlannerTest {

    private final MultiLegPlanner planner = new MultiLegPlanner();

    private static StationDO station(long id, String name, double lon, double lat) {
        return StationDO.builder().id(id).stationName(name)
                .longitude(BigDecimal.valueOf(lon)).latitude(BigDecimal.valueOf(lat))
                .status(0).userAccess(true).vehicleAccess(true).dispatchEnabled(true).build();
    }

    private static void onRoute(List<RouteStationDO> list, long routeId, long stationId) {
        list.add(RouteStationDO.builder().routeId(routeId).stationId(stationId).build());
    }

    private static TransportOrderDO order(long pickup, long delivery) {
        return TransportOrderDO.builder().id(1L).pickupStationId(pickup).deliveryStationId(delivery).build();
    }

    @Test
    void direct_when_same_route() {
        StationDO a = station(201, "南门", 106.5765, 29.5325);
        StationDO b = station(205, "南坪", 106.5750, 29.5200);
        List<RouteStationDO> routes = new ArrayList<>();
        onRoute(routes, 304, 201);
        onRoute(routes, 304, 205);
        MultiLegPlanner.PlanResult r = planner.plan(order(201, 205), a, b, List.of(a, b), routes);
        assertEquals(DispatchPlanningModeEnum.DIRECT.getMode(), r.mode());
        assertEquals(1, r.legCount());
        assertEquals(0, r.transferCount());
        assertTrue(r.reason().contains("直达"), r.reason());
    }

    @Test
    void two_leg_when_hub_connects_both_routes() {
        // 301: 201-202 ; 302: 202-203 → 201→203 需在 202 换乘
        StationDO a = station(201, "南门", 106.5765, 29.5325);
        StationDO hub = station(202, "上新街", 106.5900, 29.5250);
        StationDO d = station(203, "学堂湾", 106.6050, 29.5150);
        List<RouteStationDO> routes = new ArrayList<>();
        onRoute(routes, 301, 201);
        onRoute(routes, 301, 202);
        onRoute(routes, 302, 202);
        onRoute(routes, 302, 203);
        MultiLegPlanner.PlanResult r = planner.plan(order(201, 203), a, d, List.of(a, hub, d), routes);
        assertEquals(DispatchPlanningModeEnum.MULTI_LEG.getMode(), r.mode());
        assertEquals(2, r.legCount());
        assertEquals(1, r.transferCount());
        assertEquals(202L, r.transferStationId());
        assertEquals(2, r.legs().size());
        assertTrue(r.legs().get(0).handoverRequired(), "第一段必须需要换乘交接");
        assertFalse(r.legs().get(1).handoverRequired(), "最后一段不需要换乘");
    }

    @Test
    void three_leg_when_no_single_hub_connects() {
        // 301: 201-202 ; 302: 202-203 ; 303: 203-204 → 201→204 需两次换乘
        StationDO a = station(201, "南门", 106.5765, 29.5325);
        StationDO h1 = station(202, "上新街", 106.5900, 29.5250);
        StationDO h2 = station(203, "学堂湾", 106.6050, 29.5150);
        StationDO d = station(204, "工商大学", 106.6200, 29.5050);
        List<RouteStationDO> routes = new ArrayList<>();
        onRoute(routes, 301, 201);
        onRoute(routes, 301, 202);
        onRoute(routes, 302, 202);
        onRoute(routes, 302, 203);
        onRoute(routes, 303, 203);
        onRoute(routes, 303, 204);
        MultiLegPlanner.PlanResult r = planner.plan(order(201, 204), a, d, List.of(a, h1, h2, d), routes);
        assertEquals(DispatchPlanningModeEnum.MULTI_LEG.getMode(), r.mode());
        assertEquals(3, r.legCount());
        assertEquals(2, r.transferCount());
        assertEquals(3, r.legs().size());
        assertTrue(r.legs().get(0).handoverRequired());
        assertTrue(r.legs().get(1).handoverRequired());
        assertFalse(r.legs().get(2).handoverRequired());
    }

    @Test
    void station_without_vehicle_access_is_not_a_hub() {
        // 202 车辆不可达（校园禁行）→ 不能作为换乘站，201→203 只能直达兜底（无可用换乘站）
        StationDO a = station(201, "南门", 106.5765, 29.5325);
        StationDO blocked = station(202, "校内站", 106.5900, 29.5250);
        blocked.setVehicleAccess(false);
        StationDO d = station(203, "学堂湾", 106.6050, 29.5150);
        List<RouteStationDO> routes = new ArrayList<>();
        onRoute(routes, 301, 201);
        onRoute(routes, 301, 202);
        onRoute(routes, 302, 202);
        onRoute(routes, 302, 203);
        MultiLegPlanner.PlanResult r = planner.plan(order(201, 203), a, d, List.of(a, blocked, d), routes);
        assertNotEquals(202L, r.transferStationId());
        assertEquals(1, r.legCount());
    }

    @Test
    void station_without_dispatch_enabled_is_not_a_hub() {
        // 202 未开放调度（新增站点默认值）→ 不能作为换乘站；201→203 只能直达兜底
        StationDO a = station(201, "南门", 106.5765, 29.5325);
        StationDO noDispatch = station(202, "新站点", 106.5900, 29.5250);
        noDispatch.setDispatchEnabled(false);
        StationDO d = station(203, "学堂湾", 106.6050, 29.5150);
        List<RouteStationDO> routes = new ArrayList<>();
        onRoute(routes, 301, 201);
        onRoute(routes, 301, 202);
        onRoute(routes, 302, 202);
        onRoute(routes, 302, 203);
        MultiLegPlanner.PlanResult r = planner.plan(order(201, 203), a, d, List.of(a, noDispatch, d), routes);
        assertNotEquals(202L, r.transferStationId(), "未开放调度的站点不能作为换乘站");
        assertEquals(1, r.legCount());
    }

    @Test
    void candidates_include_explanation_for_admin() {
        StationDO a = station(201, "南门", 106.5765, 29.5325);
        StationDO hub = station(202, "上新街", 106.5900, 29.5250);
        StationDO d = station(203, "学堂湾", 106.6050, 29.5150);
        List<RouteStationDO> routes = new ArrayList<>();
        onRoute(routes, 301, 201);
        onRoute(routes, 301, 202);
        onRoute(routes, 302, 202);
        onRoute(routes, 302, 203);
        MultiLegPlanner.PlanResult r = planner.plan(order(201, 203), a, d, List.of(a, hub, d), routes);
        assertFalse(r.candidates().isEmpty(), "必须给出候选方案供后台解释");
        assertTrue(r.candidates().stream().allMatch(c -> c.reason() != null && !c.reason().isBlank()));
    }

}
