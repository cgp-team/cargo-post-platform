"""算法链集成测试：Gap 绕行预算 + 边际成本 + FeasibilityEngine 硬裁决。

验证 dispatch_opt 真正参与构造/可行性，而不是旁路演示。
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.dispatch_opt.gap_detour import calculate_gap_detour
from app.dispatch_opt.marginal_cost import MarginalCostEvaluator
from app.dispatch_opt.models import HandoverCostBreakdown
from app.haco.encoding import ObjectiveVector, TaskBlock, TaskType
from app.haco.evaluator import evaluate_route_genome
from app.haco.feasibility_engine import FeasibilityEngine
from app.haco.route_genome import RouteGenome


class S:
    def __init__(self, sid, lat=30.0, lon=104.0):
        self.stationId = sid
        self.latitude = lat
        self.longitude = lon


def test_gap_detour_budget_is_hard_constraint():
    coords = {
        "A": (30.0, 104.0),
        "B": (30.01, 104.0),
        "X": (30.005, 104.002),
    }
    ok = calculate_gap_detour(
        from_station="A",
        to_station="B",
        via_pickup="X",
        via_delivery="X",
        rejoin_station="B",
        coords=coords,
        mandatory_stops=["A", "B"],
        planned_stops_after=["A", "X", "B"],
        max_detour_m=5000,
        trip_detour_remaining_m=5000,
    )
    assert ok.ok

    denied = calculate_gap_detour(
        from_station="A",
        to_station="B",
        via_pickup="X",
        via_delivery="X",
        rejoin_station="B",
        coords=coords,
        mandatory_stops=["A", "B"],
        planned_stops_after=["A", "X", "B"],
        trip_detour_remaining_m=10,
    )
    assert not denied.ok
    assert denied.reason_code == "DETOUR_BUDGET_EXCEEDED"


def test_feasibility_engine_trip_detour_budget():
    sm = {"D": S("D"), "A": S("A", 30.0, 104.0), "B": S("B", 30.0, 104.2)}
    r = RouteGenome(0, 1, "D")
    t = TaskBlock("c1", TaskType.SHIPMENT, "A", "B", size=1)
    r.insert_task(t, 1, 2)
    engine = FeasibilityEngine(station_map=sm, matrix=None)
    ok = engine.check(
        r,
        {"c1": t},
        passenger_capacity=5,
        cargo_capacity=5,
        trip_detour_remaining_m=999999,
    )
    assert ok.feasible

    bad = engine.check(
        r,
        {"c1": t},
        passenger_capacity=5,
        cargo_capacity=5,
        trip_detour_remaining_m=0.0,
        max_detour_km=0.0,
    )
    assert not bad.feasible
    assert bad.reason_code in ("DETOUR_BUDGET_EXCEEDED", "TRIP_DETOUR_BUDGET_EXCEEDED")


def test_marginal_cost_traceable_in_search_energy_path():
    ev = MarginalCostEvaluator()
    b = ev.evaluate(
        delta_distance_m=1200,
        delta_duration_s=90,
        delta_passenger_impact_s=30,
        handover_count=1,
        handover_breakdown=HandoverCostBreakdown(operational_cost=1.0),
        delta_waiting_s=300,
    )
    assert set(b.as_dict()) >= {
        "distance_cost",
        "time_cost",
        "passenger_impact_cost",
        "handover_cost",
        "waiting_cost",
        "total_incremental_cost",
    }
    assert b.total_incremental_cost > 0


def test_objective_key_still_business_authority():
    a = ObjectiveVector(vehicle_count=1, passenger_impact=10, total_distance=5)
    b = ObjectiveVector(vehicle_count=2, passenger_impact=0, total_distance=1)
    # 业务序：vehicle_count 优先于 distance
    assert a < b
    # 搜索标量存在且有限
    assert a.objective_to_scalar() > 0
    assert b.scalar_delta_worse(a) >= 0


def test_passenger_impact_uses_initial_load_consistently():
    sm = {
        "D": S("D"),
        "A": S("A", 30.0, 104.0),
        "B": S("B", 30.0, 104.1),
    }
    r = RouteGenome(0, 1, "D")
    p = TaskBlock("p1", TaskType.PASSENGER, "A", "B")
    r.insert_task(p, 1, 2)
    t = TaskBlock("c1", TaskType.SHIPMENT, "A", "B")
    r.insert_task(t, 1, 2)
    m0 = evaluate_route_genome(r, {"p1": p, "c1": t}, sm, None, initial_passenger_load=0)
    m2 = evaluate_route_genome(r, {"p1": p, "c1": t}, sm, None, initial_passenger_load=2)
    assert m2["passenger_impact"] >= m0["passenger_impact"]


def test_economic_value_does_not_break_hard_constraints():
    """性价比再高，绕行预算超限仍必须拒绝。"""
    sm = {"D": S("D"), "A": S("A", 30.0, 104.0), "B": S("B", 30.0, 104.5)}
    r = RouteGenome(0, 1, "D")
    t = TaskBlock("c1", TaskType.SHIPMENT, "A", "B", size=1, economic_value=1e9)
    r.insert_task(t, 1, 2)
    engine = FeasibilityEngine(station_map=sm, matrix=None)
    res = engine.check(
        r,
        {"c1": t},
        passenger_capacity=5,
        cargo_capacity=5,
        max_detour_km=0.01,
    )
    assert not res.feasible
