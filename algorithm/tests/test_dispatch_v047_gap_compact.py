"""DISPATCH_CORE_V047：Gap 真实道路 / gap-level 绕行 / _compact_solution 安全替换 / 最终出口硬约束。"""

from __future__ import annotations

from app.dispatch_opt import calculate_gap_detour
from app.dispatch_opt.route_cost_provider import (
    CachedRealRouteProvider,
    GapRoutePair,
    HaversineLowerBoundProvider,
    MapEngineRouteCostProvider,
    RouteCost,
    RouteCostStatus,
    RouteCostProvider,
    build_gap_waypoints,
)
from app.haco.encoding import ObjectiveVector, TaskType
from app.haco.feasibility_engine import FeasibilityContext, FeasibilityEngine

COORDS = {
    "A": (29.50, 106.50),
    "B": (29.52, 106.50),
    "C": (29.54, 106.50),
    "X": (29.52, 106.53),
    "Y": (29.53, 106.54),
}
MANDATORY = ["A", "B", "C"]


class FormalStubProvider:
    def __init__(self, distance_m: float = 1000.0, duration_s: float = 120.0):
        self.distance_m = distance_m
        self.duration_s = duration_s

    def is_formal(self) -> bool:
        return True

    def route(self, origin, destination, *, waypoints=(), route_type=None) -> RouteCost:
        n = 1 + len(tuple(waypoints))
        return RouteCost(
            status=RouteCostStatus.FORMAL,
            distance_m=self.distance_m * n,
            duration_s=self.duration_s * n,
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


# ═══════════ RouteCostProvider 正式性语义 ═══════════


def test_haversine_provider_is_never_formal():
    prov = HaversineLowerBoundProvider()
    assert prov.is_formal() is False
    cost = prov.route((29.5, 106.5), (29.6, 106.6))
    assert cost.is_formal is False
    assert cost.status is RouteCostStatus.FALLBACK
    assert cost.is_lower_bound is True


def test_map_engine_provider_unknown_when_no_formal_geometry():
    """空路网 → ESTIMATED → 不得冒充 formal，必须 UNKNOWN/FALLBACK。"""
    prov = MapEngineRouteCostProvider()
    cost = prov.route((29.5, 106.5), (29.6, 106.6))
    assert cost.is_formal is False
    assert cost.status in (RouteCostStatus.UNKNOWN, RouteCostStatus.FALLBACK)


def test_cached_provider_returns_cached_real_on_second_call():
    cached = CachedRealRouteProvider(FormalStubProvider())
    first = cached.route((29.5, 106.5), (29.6, 106.6))
    assert first.status is RouteCostStatus.FORMAL
    second = cached.route((29.5, 106.5), (29.6, 106.6))
    assert second.status is RouteCostStatus.CACHED_REAL
    assert second.cache_hit is True
    assert second.is_formal is True


# ═══════════ Gap 真实道路 ═══════════


def test_gap_detour_uses_formal_road_when_provider_given():
    res = calculate_gap_detour(
        from_station="B",
        to_station="C",
        via_pickup="X",
        via_delivery="Y",
        rejoin_station="C",
        coords=COORDS,
        mandatory_stops=MANDATORY,
        planned_stops_after=["A", "B", "X", "Y", "C"],
        passenger_count=2,
        route_provider=FormalStubProvider(distance_m=1000.0, duration_s=120.0),
        max_detour_m=10_000.0,
        max_passenger_impact_s=100_000.0,
    )
    assert res.formal is True
    assert res.status == "FORMAL"
    # baseline = 1 段；insert = 3 段
    assert res.original_distance_m == 1000.0
    assert res.detour_distance_m == 3000.0
    assert res.delta_distance_m == 2000.0
    assert res.delta_duration_s == 240.0
    assert res.passenger_impact_s == 240.0 * 2
    assert res.mandatory_sequence_preserved


def test_gap_detour_returns_unknown_when_route_not_formal():
    res = calculate_gap_detour(
        from_station="B",
        to_station="C",
        via_pickup="X",
        via_delivery="Y",
        rejoin_station="C",
        coords=COORDS,
        mandatory_stops=MANDATORY,
        planned_stops_after=["A", "B", "X", "Y", "C"],
        route_provider=MapEngineRouteCostProvider(),  # 有 provider，但拿不到 formal
    )
    assert res.ok is False
    assert res.reason_code == "ROUTE_UNKNOWN"
    assert res.status == "UNKNOWN"
    assert res.delta_distance_m == 0.0  # 绝不拿直线冒充正式 delta


def test_gap_detour_legacy_fallback_is_marked_non_formal():
    res = calculate_gap_detour(
        from_station="B",
        to_station="C",
        via_pickup="X",
        via_delivery="Y",
        rejoin_station="C",
        coords=COORDS,
        mandatory_stops=MANDATORY,
        planned_stops_after=["A", "B", "X", "Y", "C"],
    )
    assert res.formal is False
    assert res.status == "FALLBACK"
    assert res.source == "haversine_lower_bound"


# ═══════════ gap-level 绕行（不重复累计） ═══════════


def test_gap_waypoints_single_and_two_cargo_points():
    assert build_gap_waypoints(pickup=(1.0, 1.0)) == ((1.0, 1.0),)
    assert build_gap_waypoints(pickup=(1.0, 1.0), delivery=(2.0, 2.0)) == (
        (1.0, 1.0),
        (2.0, 2.0),
    )
    # pickup 与 delivery 同点：只出现一次
    assert build_gap_waypoints(pickup=(1.0, 1.0), delivery=(1.0, 1.0)) == ((1.0, 1.0),)


def test_gap_level_delta_is_per_gap_not_whole_route():
    """每个 gap 的 baseline 只覆盖本 gap 端点，跨多个 passenger gap 不重复累计。"""
    prov = FormalStubProvider(distance_m=500.0, duration_s=60.0)
    gap1 = calculate_gap_detour(
        from_station="A",
        to_station="B",
        via_pickup="X",
        via_delivery="X",
        rejoin_station="B",
        coords=COORDS,
        mandatory_stops=MANDATORY,
        planned_stops_after=["A", "X", "B", "C"],
        route_provider=prov,
    )
    gap2 = calculate_gap_detour(
        from_station="B",
        to_station="C",
        via_pickup="Y",
        via_delivery="Y",
        rejoin_station="C",
        coords=COORDS,
        mandatory_stops=MANDATORY,
        planned_stops_after=["A", "B", "Y", "C"],
        route_provider=prov,
    )
    # 每个 gap baseline 仅 1 段（500），而不是整条线路
    assert gap1.original_distance_m == 500.0
    assert gap2.original_distance_m == 500.0
    assert gap1.delta_distance_m == 500.0
    assert gap2.delta_distance_m == 500.0
    # 两个 gap 的 delta 相加 = 各自独立计算之和（无 double count）
    assert gap1.delta_distance_m + gap2.delta_distance_m == 1000.0


def test_gap_service_time_split_does_not_double_count_duration():
    """pickup+delivery 在同一 gap：一次计算 gap 级 delta，不按事件重复累计。"""
    prov = FormalStubProvider(distance_m=1000.0, duration_s=100.0)
    both = calculate_gap_detour(
        from_station="B",
        to_station="C",
        via_pickup="X",
        via_delivery="Y",
        rejoin_station="C",
        coords=COORDS,
        mandatory_stops=MANDATORY,
        planned_stops_after=["A", "B", "X", "Y", "C"],
        route_provider=prov,
    )
    # baseline 100s，insert 300s → delta 恰好 200s（不是 400s）
    assert both.delta_duration_s == 200.0


# ═══════════ FeasibilityEngine 最终出口硬约束 ═══════════


def _minimal_route():
    """构造最小 RouteGenome：DEPOT -> PICKUP -> DELIVER -> RETURN。"""
    from app.haco.encoding import TaskBlock
    from app.haco.route_genome import RouteGenome

    task = TaskBlock(
        task_id="T1",
        task_type=TaskType.SHIPMENT,
        size=1,
        order_ids=["O1"],
        pickup_station="A",
        delivery_station="C",
    )
    route = RouteGenome(vehicle_index=0, vehicle_id=1, depot_station="D")
    route.insert_task(task, 1, 2)
    return route, {"T1": task}


def test_validate_solution_forwards_detour_hard_constraint():
    route, tasks_by_id = _minimal_route()
    from app.models import Station

    station_map = {
        "D": Station(stationId="D", longitude=106.50, latitude=29.50),
        "A": Station(stationId="A", longitude=106.53, latitude=29.52),
        "C": Station(stationId="C", longitude=106.50, latitude=29.54),
    }
    engine = FeasibilityEngine(station_map=station_map, matrix=None)

    ok = engine.validate_solution(
        [route], tasks_by_id,
        passenger_capacities={0: 5}, cargo_capacities={0: 5},
        initial_passenger_loads={0: 0}, initial_cargo_loads={0: 0},
        station_map=station_map, matrix=None,
    )
    assert ok.feasible

    # 通过 context 注入极小 trip detour 预算 → 最终出口必须拒绝
    ctx = FeasibilityContext(station_map=station_map, matrix=None, trip_detour_remaining_m=1.0)
    bad = engine.validate_solution(
        [route], tasks_by_id,
        passenger_capacities={0: 5}, cargo_capacities={0: 5},
        initial_passenger_loads={0: 0}, initial_cargo_loads={0: 0},
        context=ctx,
    )
    assert bad.feasible is False
    assert bad.reason_code in ("TRIP_DETOUR_BUDGET_EXCEEDED", "DETOUR_BUDGET_EXCEEDED")


def test_check_forwards_max_detour_km_from_context():
    route, tasks_by_id = _minimal_route()
    from app.models import Station

    station_map = {
        "D": Station(stationId="D", longitude=106.50, latitude=29.50),
        "A": Station(stationId="A", longitude=106.53, latitude=29.52),
        "C": Station(stationId="C", longitude=106.50, latitude=29.54),
    }
    engine = FeasibilityEngine(
        station_map=station_map, matrix=None, context=FeasibilityContext(max_detour_km=0.0001)
    )
    res = engine.check(route, tasks_by_id, passenger_capacity=5, cargo_capacity=5)
    assert res.feasible is False
    assert res.reason_code in ("DETOUR_BUDGET_EXCEEDED", "TRIP_DETOUR_BUDGET_EXCEEDED")


# ═══════════ _compact_solution 安全替换 ═══════════


class _StubRoute:
    def __init__(self, n_tasks: int = 1, tag: str = "r"):
        self._n = n_tasks
        self.tag = tag

    def task_count(self) -> int:
        return self._n

    def copy(self):
        return self


class _StubTask:
    def __init__(self, task_id: str, task_type=TaskType.SHIPMENT, size: int = 1):
        self.task_id = task_id
        self.task_type = task_type
        self.size = size


def _run_compact(monkeypatch, candidate_obj: ObjectiveVector, current_obj: ObjectiveVector):
    from app.haco import v14_solver

    original_routes = [_StubRoute(2, "orig1"), _StubRoute(1, "orig2")]
    candidate_routes = [_StubRoute(3, "cand1")]
    tasks = [_StubTask("T1"), _StubTask("T2"), _StubTask("T3")]

    def fake_eval(routes, tasks_by_id, station_map, matrix, *a, **kw):
        return candidate_obj if routes is candidate_routes else current_obj

    monkeypatch.setattr(v14_solver, "evaluate_route_states", fake_eval)
    monkeypatch.setattr(
        v14_solver,
        "_cheapest_insertion",
        lambda *a, **kw: v14_solver.GreedySeedResult(
            routes=candidate_routes, complete=True, placed_count=3, total_tasks=3
        ),
    )
    monkeypatch.setattr(v14_solver, "_is_complete", lambda routes, tasks: True)
    monkeypatch.setattr(v14_solver, "_routes_feasible", lambda *a, **kw: True)

    return v14_solver._compact_solution(
        original_routes,
        tasks,
        templates=[object(), object()],
        tasks_by_id={t.task_id: t for t in tasks},
        engine=None,
        passenger_capacities={0: 5, 1: 5},
        cargo_capacities={0: 5, 1: 5},
        initial_passenger_loads={0: 0, 1: 0},
        initial_cargo_loads={0: 0, 1: 0},
        station_map=None,
        matrix=None,
        config=None,
        rng=None,
        deadline=None,
    ), original_routes, candidate_routes


def test_compact_solution_keeps_original_when_objective_worse(monkeypatch):
    current = ObjectiveVector(vehicle_count=1, passenger_impact=10.0)
    worse = ObjectiveVector(vehicle_count=1, passenger_impact=90.0)
    result, original, _ = _run_compact(monkeypatch, worse, current)
    assert result is original  # 同车辆数但 objective 更差 → 保留原 HACO 解


def test_compact_solution_accepts_better_objective(monkeypatch):
    current = ObjectiveVector(vehicle_count=2, passenger_impact=10.0)
    better = ObjectiveVector(vehicle_count=1, passenger_impact=10.0)
    result, original, candidate = _run_compact(monkeypatch, better, current)
    assert result is candidate
