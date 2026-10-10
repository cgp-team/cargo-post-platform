"""GraphHopper 熔断器 / CachedRealRouteProvider LRU / GeometryCache TTL 与副本语义 /
Coordinator 候选评估时间预算。"""

from __future__ import annotations

from app.dispatch_opt import (
    DispatchOrder,
    DispatchRequest,
    DynamicDispatchCoordinator,
    TripView,
)
from app.dispatch_opt.models import TripExecutionState
from app.dispatch_opt.route_cost_provider import (
    CachedRealRouteProvider,
    GapRoutePair,
    RouteCost,
    RouteCostStatus,
    build_gap_waypoints,
)
from app.routing import GeometryCache, GeometryStatus
from app.routing.graphhopper_routing import (
    CircuitBreaker,
    GraphHopperRoutingEngine,
    LocalRoutingConfig,
)
from app.routing.models import GeometrySource, RouteGeometryResult, RouteType


# ── CircuitBreaker 状态机 ──


def test_breaker_opens_after_threshold_and_blocks_requests():
    now = [0.0]
    cb = CircuitBreaker(failure_threshold=3, cooldown_s=30.0, clock=lambda: now[0])
    for _ in range(3):
        assert cb.allow_request()
        cb.record_failure()
    # 冷却窗口内直接拒绝
    assert not cb.allow_request()
    assert cb.is_open
    now[0] += 29.9
    assert not cb.allow_request()


def test_breaker_half_open_probe_and_exponential_backoff():
    now = [0.0]
    cb = CircuitBreaker(
        failure_threshold=2, cooldown_s=30.0, max_cooldown_s=120.0, clock=lambda: now[0]
    )
    for _ in range(2):
        assert cb.allow_request()
        cb.record_failure()
    now[0] += 31.0
    # 窗口结束：半开只放行一个试探
    assert cb.allow_request()
    assert not cb.allow_request()
    # 试探失败：重新打开且冷却翻倍 30s → 60s
    cb.record_failure()
    now[0] += 31.0
    assert not cb.allow_request()
    now[0] += 30.0
    assert cb.allow_request()
    # 试探成功：完全复位（冷却回到基准值）
    cb.record_success()
    assert not cb.is_open
    assert cb.allow_request()
    cb.record_failure()
    assert cb.allow_request()  # 未达阈值不熔断


def test_engine_short_circuits_when_breaker_open():
    cb = CircuitBreaker(failure_threshold=2, cooldown_s=30.0)
    gh = GraphHopperRoutingEngine(
        LocalRoutingConfig(graphhopper_base_url="http://127.0.0.1:1", graphhopper_timeout_ms=50),
        breaker=cb,
    )
    for _ in range(2):
        r = gh.route((29.50, 106.50), (29.52, 106.50))
        assert "LOCAL_ROUTING_UNAVAILABLE" in r.reason_code
        assert "CIRCUIT_OPEN" not in r.reason_code
    # 第 3 次：熔断打开，不发请求直接快速失败
    r = gh.route((29.50, 106.50), (29.52, 106.50))
    assert r.reason_code == "LOCAL_ROUTING_UNAVAILABLE:CIRCUIT_OPEN"
    assert r.available is False


# ── CachedRealRouteProvider LRU ──


class _StubEngine:
    def __init__(self, graph_version: str = "g1"):
        self.graph_version = graph_version


class _StubBaseProvider:
    def __init__(self):
        self.calls = 0
        self.engine = _StubEngine()

    def is_formal(self) -> bool:
        return True

    def route(self, origin, destination, *, waypoints=(), route_type=RouteType.FLEXIBLE_GAP) -> RouteCost:
        self.calls += 1
        return RouteCost(
            status=RouteCostStatus.FORMAL,
            distance_m=100.0,
            duration_s=10.0,
            provider="stub_formal",
        )

    def route_gap(self, gap_from, gap_to, *, pickup=None, delivery=None, extra=()) -> GapRoutePair:
        mids = build_gap_waypoints(pickup, delivery, extra)
        return GapRoutePair(
            baseline=self.route(gap_from, gap_to),
            insert=self.route(gap_from, gap_to, waypoints=mids),
            waypoints=mids,
        )

    def route_insert(self, insert_from, insert_to, insert_points) -> RouteCost:
        return self.route(insert_from, insert_to, waypoints=insert_points)

    def route_candidate(self, waypoints) -> RouteCost:
        return self.route(waypoints[0], waypoints[-1], waypoints=waypoints[1:-1])


A, B, C = (29.50, 106.50), (29.52, 106.50), (29.54, 106.50)


def test_cache_hit_returns_cached_real_and_skips_base():
    base = _StubBaseProvider()
    prov = CachedRealRouteProvider(base)
    r1 = prov.route(A, B)
    r2 = prov.route(A, B)
    assert r1.status == RouteCostStatus.FORMAL
    assert r2.status == RouteCostStatus.CACHED_REAL
    assert r2.cache_hit is True
    assert base.calls == 1


def test_cache_key_includes_route_type():
    base = _StubBaseProvider()
    prov = CachedRealRouteProvider(base)
    prov.route(A, B)  # FLEXIBLE_GAP
    prov.route_insert(A, B, [])  # CARGO_DETOUR
    prov.route_candidate([A, B])  # POSITION_TO_SERVICE
    assert base.calls == 3
    assert len(prov._cache) == 3


def test_cache_key_includes_graph_version():
    base = _StubBaseProvider()
    prov = CachedRealRouteProvider(base)
    prov.route(A, B)
    base.engine.graph_version = "g2"
    r = prov.route(A, B)
    assert base.calls == 2
    assert r.status == RouteCostStatus.FORMAL


def test_cache_lru_evicts_oldest_beyond_max_entries():
    base = _StubBaseProvider()
    prov = CachedRealRouteProvider(base, max_entries=2)
    prov.route(A, B)
    prov.route(A, C)
    prov.route(B, C)  # 超出上限：淘汰最久未用的 A→B
    assert len(prov._cache) == 2
    r = prov.route(A, C)  # 仍在缓存
    assert r.status == RouteCostStatus.CACHED_REAL
    r = prov.route(A, B)  # 已被淘汰，重新查底层
    assert r.status == RouteCostStatus.FORMAL
    assert base.calls == 4


def test_cache_lru_hit_refreshes_recency():
    base = _StubBaseProvider()
    prov = CachedRealRouteProvider(base, max_entries=2)
    prov.route(A, B)
    prov.route(A, C)
    prov.route(A, B)  # 命中并把 A→B 提到最新
    prov.route(B, C)  # 淘汰 A→C 而非 A→B
    assert prov.route(A, B).status == RouteCostStatus.CACHED_REAL
    assert prov.route(A, C).status == RouteCostStatus.FORMAL


# ── GeometryCache TTL / 副本语义 ──


def _result(status: GeometryStatus = GeometryStatus.LOCAL_ROAD) -> RouteGeometryResult:
    return RouteGeometryResult(
        available=status == GeometryStatus.LOCAL_ROAD,
        status=status,
        geometry_source=GeometrySource.LOCAL_GRAPH,
    )


def test_geometry_cache_default_formal_ttl_is_3600():
    cache = GeometryCache()
    assert cache.ttl_s == 3600.0
    cache.put("k", _result(), now=0.0)
    assert cache.get("k", now=3599.0) is not None
    assert cache.get("k", now=3601.0) is None


def test_geometry_cache_failure_ttl_still_30():
    cache = GeometryCache()
    assert cache.failure_ttl_s == 30.0
    cache.put("k", _result(GeometryStatus.UNCERTAIN), now=0.0)
    assert cache.get("k", now=29.0) is not None
    assert cache.get("k", now=31.0) is None


def test_geometry_cache_get_returns_copy_not_shared_mutation():
    cache = GeometryCache()
    cache.put("k", _result(), now=0.0)
    hit = cache.get("k", now=1.0)
    assert hit is not None and hit.cache_hit is True
    # 缓存内对象不被命中标记污染；再次命中仍是独立副本
    again = cache.get("k", now=2.0)
    assert again is not hit
    assert cache._store["k"].result.cache_hit is False


# ── Coordinator 候选评估时间预算 ──

_COORDS = {"PU": (30.06, 104.0), "DE": (30.0, 104.06)}


def _order() -> DispatchOrder:
    return DispatchOrder(
        order_id="O1",
        pickup_service_point="PU",
        delivery_service_point="DE",
        quantity=1,
        economic_value=50.0,
        delivery_deadline=100_000.0,
    )


def _trip() -> TripView:
    return TripView(
        route_id="347",
        shift_id="07:30",
        vehicle_id=1,
        departure_time=0.0,
        execution_state=TripExecutionState.PLANNED,
        current_location=(30.0, 104.0),
        remaining_cargo_capacity=3,
        trip_detour_remaining_m=50_000.0,
        passenger_impact_budget_s=1_000_000.0,
        station_coords=_COORDS,
    )


def test_plan_budget_exhausted_stops_evaluating_and_degrades():
    co = DynamicDispatchCoordinator(route_provider=_StubBaseProvider(), now=0.0)
    req = DispatchRequest(order=_order(), current_trips=[_trip()])
    plan = co.plan(req, time_budget_s=1e-9)  # 预算立即耗尽
    codes = [r.reason_code for r in plan.trace.records]
    assert "EVALUATION_BUDGET_EXHAUSTED" in codes
    # 无任何候选完成评估 → 降级 HOLD（绝不假装有可行解）
    assert plan.status == "HOLD"
    assert plan.chosen is None


def test_plan_with_sufficient_budget_assigns_normally():
    co = DynamicDispatchCoordinator(route_provider=_StubBaseProvider(), now=0.0)
    req = DispatchRequest(order=_order(), current_trips=[_trip()])
    plan = co.plan(req, time_budget_s=60.0)
    assert plan.status == "ASSIGNED"
    codes = [r.reason_code for r in plan.trace.records]
    assert "EVALUATION_BUDGET_EXHAUSTED" not in codes
