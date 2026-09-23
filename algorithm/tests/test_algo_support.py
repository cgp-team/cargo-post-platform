"""algo_support 集成测试：增量评估 / 恢复成本 / 迭代预算 / 邻域 / 预算累计。"""

from __future__ import annotations

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.algo_support import (
    EXACT_ORACLE_SIMPLIFICATIONS,
    AccessSnapshot,
    DynamicBenchmarkStats,
    IncrementalEvaluator,
    LocationFix,
    MoveKind,
    PlanItemStatus,
    RecoveryCostEvaluator,
    SearchBudget,
    TripDetourBudget,
    allows_cross_gap_pair,
    enumerate_or_opt_segments,
    enumerate_pair_relocate,
    enumerate_same_route_swap_positions,
    is_locked_after_departure,
    is_terminal,
)
from app.haco.encoding import TaskBlock, TaskType
from app.haco.route_genome import RouteGenome


class S:
    def __init__(self, sid, lat=30.0, lon=104.0):
        self.stationId = sid
        self.latitude = lat
        self.longitude = lon


def _route():
    r = RouteGenome(0, 1, "D")
    t1 = TaskBlock("a", TaskType.SHIPMENT, "A", "B")
    t2 = TaskBlock("b", TaskType.SHIPMENT, "B", "A")
    r.insert_task(t1, 1, 2)
    r.insert_task(t2, 1, 3)
    return r, {"a": t1, "b": t2}


def test_incremental_matches_full_recompute():
    sm = {"D": S("D"), "A": S("A", 30.0, 104.0), "B": S("B", 30.0, 104.1)}
    r, tasks = _route()
    ev = IncrementalEvaluator(sm, None, debug=True)
    s1 = ev.score("r0", r, tasks)
    s2 = ev.score("r0", r, tasks)  # cache hit + debug verify
    assert s1.distance == s2.distance
    ev.invalidate("r0")
    s3 = ev.score("r0", r, tasks)
    assert abs(s3.distance - s1.distance) < 1e-9


def test_incremental_debug_detects_stale_cache():
    sm = {"D": S("D"), "A": S("A", 30.0, 104.0), "B": S("B", 30.0, 104.1)}
    r, tasks = _route()
    ev = IncrementalEvaluator(sm, None, debug=True)
    ev.score("r0", r, tasks)
    # 篡改缓存后 debug 必须失败
    ev._cache["r0"].distance += 999
    with pytest.raises(AssertionError):
        ev.score("r0", r, tasks)


def test_recovery_cost_breakdown():
    ev = RecoveryCostEvaluator()
    b = ev.evaluate(
        reroute_distance_m=1200,
        reroute_duration_s=180,
        transfer_count=1,
        waiting_s=600,
        needs_customer_action=True,
        failure_risk=0.2,
    )
    assert b.total > 0
    assert b.transfer_cost > 0
    assert b.customer_action_cost > 0


def test_search_budget_iteration_dominates():
    b = SearchBudget(max_iterations=3, max_evaluations=100, time_limit_s=999)
    b.start(0.0)
    assert b.tick_iteration()
    assert b.tick_iteration()
    assert b.tick_iteration()
    assert not b.tick_iteration()
    assert b.expired(now=0.1)


def test_search_budget_evaluations():
    b = SearchBudget(max_iterations=100, max_evaluations=2, time_limit_s=None)
    b.start(0.0)
    assert b.tick_evaluation()
    assert b.tick_evaluation()
    assert not b.tick_evaluation()
    assert b.expired()


def test_trip_detour_budget_rolling():
    t = TripDetourBudget(limit_m=1000)
    assert t.can_accept(400)
    assert t.consume(400)
    assert t.remaining_m == pytest.approx(600)
    assert not t.can_accept(700)
    assert t.consumed_m == pytest.approx(400)


def test_route_genome_trip_detour_fields():
    r, _ = _route()
    r.trip_detour_budget_m = 500
    assert r.can_consume_detour(200)
    assert r.consume_detour(200)
    assert r.trip_detour_remaining_m == pytest.approx(300)
    c = r.copy()
    assert c.trip_detour_consumed_m == r.trip_detour_consumed_m
    assert c.trip_detour_budget_m == 500


def test_access_and_location_adapters():
    from app.dispatch_opt.models import ReachabilityReason
    from app.dispatch_opt.reachability import classify_reachability

    snap = AccessSnapshot(vehicle_access=False, user_access=False)
    flags = snap.to_flags()
    loc = LocationFix(1, 30.0, 104.0, timestamp=100.0, now=110.0).to_snapshot()
    d = classify_reachability(
        flags=flags,
        location=loc,
        service_point="X",
        original_point="重庆邮电大学校内",
    )
    assert d.reason_code == ReachabilityReason.VEHICLE_ACCESS_BLOCKED
    assert d.original_point == "重庆邮电大学校内"


def test_same_route_swap_and_or_opt_and_pair():
    r, tasks = _route()
    opts = enumerate_same_route_swap_positions(r, "a", "b")
    assert opts
    assert MoveKind.SAME_ROUTE_SWAP.value == "SAME_ROUTE_SWAP"
    segs = enumerate_or_opt_segments(r, ["a", "b"], max_seg=2)
    assert segs
    pairs = enumerate_pair_relocate(r, "a", "b")
    assert pairs


def test_cross_gap_pair_rule():
    assert allows_cross_gap_pair(0, 1)
    assert allows_cross_gap_pair(1, 1)
    assert not allows_cross_gap_pair(2, 1)


def test_plan_item_status_lock():
    assert is_locked_after_departure(PlanItemStatus.DEPARTED)
    assert is_locked_after_departure("IN_PROGRESS")
    assert not is_locked_after_departure(PlanItemStatus.PLANNED)
    assert is_terminal("COMPLETED")


def test_dynamic_benchmark_stats_and_stability():
    st = DynamicBenchmarkStats()
    st.feasible_count = 1
    st.current_trip_acceptance = 1
    st.reachability_failure_count = 2
    st.future_trip_recovery_count = 1
    st.multi_leg_recovery_count = 1
    st.incremental_cost_sum = 3.0
    st.economic_efficiency_sum = 2.0
    st.economic_efficiency_n = 1
    st.seeds.append({"seed": 0})
    s = st.summary()
    assert s["recovery_success_rate"] == pytest.approx(1.0)
    assert s["average_economic_efficiency"] == pytest.approx(2.0)
    st.assert_stable()
    st.task_loss = 1
    with pytest.raises(AssertionError):
        st.assert_stable()


def test_exact_oracle_simplifications_documented():
    assert "no_skeleton_gap" in EXACT_ORACLE_SIMPLIFICATIONS
    assert "distance_only_objective" in EXACT_ORACLE_SIMPLIFICATIONS
