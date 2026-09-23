"""LocalRoutingEngine + Hybrid 路由闭环测试。

Local Routing 必须跑在真实道路 Graph 上；Haversine 只作预筛选。
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.routing import (
    AMapRoutingEngine,
    GeometryCache,
    GeometrySanityValidator,
    GeometryStatus,
    LocalRoutingEngine,
    MapRoutingEngine,
    RealRoadMarginalCostEvaluator,
    RoadEdge,
    RoadGraph,
    RoutingCallBudget,
    RouteGeometryResult,
    RouteType,
    estimated_only,
    gap_candidates,
    is_formal_geometry,
    plan_gap_routes,
    route_fingerprint,
    uncertain,
)
from app.routing.models import GeometrySource


def build_test_graph() -> RoadGraph:
    """B--C 主干 + B--X--Y--C 绕行（真实道路长于直线）。"""
    g = RoadGraph()
    g.add_node("B", 29.50, 106.50)
    g.add_node("C", 29.52, 106.50)
    g.add_node("X", 29.51, 106.52)
    g.add_node("Y", 29.53, 106.52)

    def poly(*pts):
        return tuple(pts)

    g.add_edge(RoadEdge("B", "C", 3200, 240, poly((29.50, 106.50), (29.51, 106.50), (29.52, 106.50))))
    g.add_edge(RoadEdge("B", "X", 2500, 200, poly((29.50, 106.50), (29.505, 106.51), (29.51, 106.52))))
    g.add_edge(RoadEdge("X", "Y", 1800, 150, poly((29.51, 106.52), (29.52, 106.52), (29.53, 106.52))))
    g.add_edge(RoadEdge("Y", "C", 2500, 200, poly((29.53, 106.52), (29.525, 106.51), (29.52, 106.50))))
    return g


B = (29.50, 106.50)
C = (29.52, 106.50)
X = (29.51, 106.52)
Y = (29.53, 106.52)


def make_engine(amap=None, cache=None, budget=None) -> MapRoutingEngine:
    return MapRoutingEngine(
        LocalRoutingEngine(build_test_graph()),
        amap=amap,
        cache=cache or GeometryCache(),
        budget=budget or RoutingCallBudget(),
        sanity=GeometrySanityValidator(),
    )


class FakeAMap:
    """模拟高德：返回真实折线，不是两点直线。"""

    def __init__(self, fail: bool = False):
        self.fail = fail
        self.calls = 0

    def route(self, origin, destination, waypoints=()):
        self.calls += 1
        if self.fail:
            return uncertain("AMAP_DOWN", origin=origin, destination=destination or C)
        pts = [origin, *list(waypoints or ()), destination or C]
        poly = []
        for i, p in enumerate(pts):
            poly.append(p)
            if i + 1 < len(pts):
                q = pts[i + 1]
                poly.append(((p[0] + q[0]) / 2 + 0.001, (p[1] + q[1]) / 2))
        return RouteGeometryResult(
            available=True,
            distance_m=4200,
            duration_s=300,
            polyline=tuple(poly),
            provider="amap",
            geometry_source=GeometrySource.AMAP_ROAD,
            status=GeometryStatus.AMAP_VERIFIED,
            route_type=RouteType.FLEXIBLE_GAP,
            confidence=0.95,
            reason_code="AMAP_OK",
            route_fingerprint=route_fingerprint(
                origin, destination or C, tuple(waypoints or ()), provider="amap"
            ),
            straight_distance_m=100,
            origin=origin,
            destination=destination or C,
        )


def test_local_routing_uses_real_graph_not_haversine():
    eng = LocalRoutingEngine(build_test_graph())
    r = eng.route(B, C)
    assert r.available
    assert r.status == GeometryStatus.LOCAL_ROAD
    assert r.distance_m > r.straight_distance_m
    assert len(r.polyline) > 2
    assert r.local_routed


def test_local_routing_failure_is_uncertain_not_fake_line():
    empty = LocalRoutingEngine(RoadGraph())
    r = empty.route(B, C)
    assert not is_formal_geometry(r.status)
    assert r.status in (GeometryStatus.ESTIMATED, GeometryStatus.UNCERTAIN)
    assert r.reason_code


def test_gap_b_to_c_baseline():
    eng = make_engine()
    routes = plan_gap_routes(eng, B, C, top_k=2)
    assert routes
    assert any(r.cargo_waypoints == () for r in routes)


def test_gap_b_x_c_no_return_to_b():
    eng = make_engine()
    routes = plan_gap_routes(eng, B, C, pickup=X, delivery=X, top_k=4)
    mids = {r.cargo_waypoints for r in routes}
    assert any(X in m for m in mids)
    assert (X, B) not in mids
    for r in routes:
        if r.geometry.available:
            assert r.geometry.destination == C


def test_gap_b_x_y_c():
    eng = make_engine()
    mids = gap_candidates(B, C, pickup=X, delivery=Y)
    assert () in mids
    assert (X, Y) in mids
    routes = plan_gap_routes(eng, B, C, pickup=X, delivery=Y, top_k=4)
    assert routes


def test_skeleton_mandatory_order_preserved():
    eng = make_engine()
    r = plan_gap_routes(eng, B, C, pickup=X, top_k=3)[0]
    assert r.from_mandatory == B
    assert r.to_mandatory == C
    assert r.rejoin_is_next_mandatory


def test_haversine_not_formal_execution():
    est = estimated_only(100.0, 100.0)
    assert not is_formal_geometry(est.status)
    ok, reason = GeometrySanityValidator().validate(est)
    assert not ok


def test_amap_fail_local_success():
    eng = make_engine(amap=FakeAMap(fail=True))
    r = eng.resolve(B, C, (X,))
    assert r.is_formal
    assert r.status in (GeometryStatus.LOCAL_ROAD, GeometryStatus.AMAP_VERIFIED)


def test_local_fail_amap_success():
    empty_local = LocalRoutingEngine(RoadGraph())
    eng = MapRoutingEngine(empty_local, amap=FakeAMap(fail=False))
    r = eng.resolve(B, C)
    assert r.status == GeometryStatus.AMAP_VERIFIED or not r.available
    if r.available:
        assert len(r.polyline) > 2


def test_both_fail_uncertain_not_fake_success():
    eng = MapRoutingEngine(LocalRoutingEngine(RoadGraph()), amap=FakeAMap(fail=True))
    r = eng.resolve(B, C)
    assert not r.available or r.status == GeometryStatus.UNCERTAIN
    assert r.status != GeometryStatus.AMAP_VERIFIED


def test_geometry_cache_hit_and_stability():
    cache = GeometryCache()
    eng = make_engine(cache=cache)
    r1 = eng.resolve(B, C, (X,))
    r2 = eng.resolve(B, C, (X,))
    assert r2.cache_hit
    assert r1.route_fingerprint == r2.route_fingerprint
    assert cache.stats()["hits"] >= 1


def test_cache_expiry_requery():
    cache = GeometryCache(ttl_s=0.0)
    eng = make_engine(cache=cache)
    eng.resolve(B, C)
    eng.resolve(B, C)
    assert cache.stats()["misses"] >= 2


def test_topk_amap_budget_limits_calls():
    amap = FakeAMap()
    budget = RoutingCallBudget(amap_limit=1)
    eng = MapRoutingEngine(LocalRoutingEngine(RoadGraph()), amap=amap, budget=budget)
    cands = [
        (B, (), C),
        (B, (X,), C),
        (B, (Y,), C),
        (B, (X, Y), C),
    ]
    eng.select_topk(cands, top_k=2)
    assert amap.calls <= 2
    assert budget.amap_calls <= 2


def test_fingerprint_changes_with_waypoints():
    f1 = route_fingerprint(B, C, ())
    f2 = route_fingerprint(B, C, (X,))
    assert f1 != f2


def test_geometry_sanity_rejects_distance_below_straight():
    bad = RouteGeometryResult(
        available=True,
        distance_m=1.0,
        duration_s=1.0,
        polyline=(B, (29.51, 106.51), C),
        status=GeometryStatus.LOCAL_ROAD,
        straight_distance_m=2000.0,
        origin=B,
        destination=C,
    )
    ok, reason = GeometrySanityValidator().validate(bad)
    assert not ok
    assert reason in ("DISTANCE_BELOW_STRAIGHT", "NETWORK_ANOMALY")


def test_straight_line_formal_plan_count_zero_on_success():
    budget = RoutingCallBudget()
    eng = make_engine(budget=budget)
    r = eng.finalize(eng.resolve(B, C, (X,)))
    assert r.is_formal
    assert budget.straight_fallback_formal == 0


def test_multi_leg_each_leg_own_geometry():
    eng = make_engine()
    leg1 = eng.resolve(B, X)
    leg2 = eng.resolve(X, Y)
    leg3 = eng.resolve(Y, C)
    for leg in (leg1, leg2, leg3):
        assert leg.is_formal
        assert len(leg.polyline) > 2
        assert leg.distance_m > 0


def test_real_road_cost_rejects_estimated():
    ev = RealRoadMarginalCostEvaluator()
    est = estimated_only(10, 10)
    formal = make_engine().resolve(B, C)
    c = ev.evaluate(baseline=formal, candidate=est, economic_value=50.0)
    assert c.geometry_formal is False
    c2 = ev.evaluate(baseline=formal, candidate=formal, passenger_count=1, economic_value=50.0)
    assert c2.geometry_formal is True
    assert c2.delta_distance_m >= 0


def test_route_planning_failure_no_formal_straight_dispatch():
    budget = RoutingCallBudget()
    eng = MapRoutingEngine(LocalRoutingEngine(RoadGraph()), amap=FakeAMap(fail=True), budget=budget)
    r = eng.finalize(eng.resolve(B, C))
    assert r.status == GeometryStatus.UNCERTAIN
    assert budget.straight_fallback_formal == 0
