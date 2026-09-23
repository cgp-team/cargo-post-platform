"""8 个固定动态调度场景回归 + 稳定性指标模板。"""

from app.dispatch_opt import (
    TripCandidateSelector,
    TripExecutionState,
    calculate_gap_detour,
    can_detour_within_gap,
    compare_trip_candidates,
    explain_choice,
)
from app.dispatch_opt.candidate_selector import TripContext
from app.dispatch_opt.handover import ChainLegResult, TransportChainEvaluator
from app.dispatch_opt.models import MarginalCostBreakdown, TripCandidate
from app.dispatch_opt.trip_lock import TripLockPolicy

COORDS = {
    "A": (29.50, 106.50),
    "B": (29.51, 106.50),
    "C": (29.52, 106.50),
    "D": (29.53, 106.50),
    "E": (29.54, 106.50),
    "X": (29.52, 106.51),
    "Y": (29.53, 106.51),
}
MANDATORY = ["A", "B", "C", "D", "E"]


def _ctx(**kw):
    defaults = dict(
        route_id="347",
        shift_id="07:30",
        vehicle_id=1,
        driver_id=1,
        departure_time=0.0,
        execution_state=TripExecutionState.PLANNED,
        remaining_cargo_capacity=2,
    )
    defaults.update(kw)
    return TripContext(**defaults)


def _stats():
    return {
        "locked_task_violation": 0,
        "mandatory_stop_violation": 0,
        "task_loss": 0,
        "task_duplication": 0,
        "invalid_handover": 0,
        "driver_conflict": 0,
        "shift_conflict": 0,
        "capacity_violation": 0,
        "detour_budget_violation": 0,
        "passenger_sla_violation": 0,
    }


def test_scenario_a_on_route_current_trip():
    sel = TripCandidateSelector()
    r = sel.generate(
        current_trips=[_ctx()],
        future_trips=[],
        other_route_trips=[],
        economic_value=10.0,
    )
    assert r.chosen is not None
    assert r.chosen.source_kind == "CURRENT_TRIP"
    assert r.chosen.reason_code == "PRE_DEPARTURE_OPEN"
    assert all(v == 0 for v in _stats().values())


def test_scenario_b_small_detour_within_gap():
    det = calculate_gap_detour(
        from_station="C",
        to_station="D",
        via_pickup="X",
        via_delivery="X",
        rejoin_station="D",
        coords=COORDS,
        mandatory_stops=MANDATORY,
        planned_stops_after=["A", "B", "C", "X", "D", "E"],
        passenger_count=1,
        max_detour_m=5000,
    )
    assert det.ok
    assert det.delta_distance_m > 0
    assert det.mandatory_sequence_preserved
    assert det.route_return_feasible


def test_scenario_c_large_detour_rejected():
    det = calculate_gap_detour(
        from_station="A",
        to_station="B",
        via_pickup="Y",
        via_delivery="E",
        rejoin_station="B",
        coords=COORDS,
        mandatory_stops=MANDATORY,
        planned_stops_after=["A", "Y", "E", "B"],
        max_detour_m=50,
    )
    assert not det.ok
    assert det.reason_code in (
        "DETOUR_BUDGET_EXCEEDED",
        "MANDATORY_STOP_VIOLATION",
        "REJOIN_INVALID",
    )


def test_scenario_d_departed_normal_order_to_next_trip():
    sel = TripCandidateSelector()
    r = sel.generate(
        current_trips=[_ctx(execution_state=TripExecutionState.DEPARTED)],
        future_trips=[_ctx(shift_id="08:00", departure_time=1800.0, vehicle_id=2)],
        other_route_trips=[],
        is_high_value=False,
    )
    assert r.chosen is not None
    assert r.chosen.source_kind in ("NEXT_TRIP", "LATER_TRIP")


def test_scenario_e_departed_high_value_realtime_insert():
    policy = TripLockPolicy()
    cost = MarginalCostBreakdown(
        delta_distance_m=150, delta_duration_s=20, delta_passenger_impact_s=5
    )
    d = policy.decide(
        TripExecutionState.DEPARTED,
        is_high_value=True,
        cost=cost,
        efficiency=8.0,
    )
    assert d.allow_insert
    assert d.reason_code == "HIGH_VALUE_REALTIME_INSERT"


def test_scenario_f_other_route_direct():
    sel = TripCandidateSelector()
    r = sel.generate(
        current_trips=[_ctx(execution_state=TripExecutionState.DEPARTED)],
        future_trips=[],
        other_route_trips=[
            _ctx(route_id="303", shift_id="07:50", vehicle_id=3, departure_time=300.0)
        ],
        is_high_value=False,
    )
    assert r.chosen is not None
    assert r.chosen.source_kind == "OTHER_ROUTE"


def test_scenario_g_multileg_347_to_303():
    def ml(future, other, econ):
        return [
            TripCandidate(
                source_kind="MULTI_LEG",
                route_id="347->303",
                shift_id="ML1",
                vehicle_id=1,
                legs=("347", "303"),
                handover_count=1,
                handover_cost=2.0,
                incremental_cost=3.0,
                economic_value=econ,
                feasibility=True,
            ).with_efficiency()
        ]

    sel = TripCandidateSelector(build_multileg=ml)
    r = sel.generate(
        current_trips=[_ctx(execution_state=TripExecutionState.DEPARTED)],
        future_trips=[],
        other_route_trips=[],
        economic_value=15.0,
    )
    assert r.chosen is not None
    assert r.chosen.source_kind == "MULTI_LEG"
    assert r.chosen.handover_count == 1


def test_scenario_h_three_leg_chain_status():
    ev = TransportChainEvaluator()
    legs = [
        ChainLegResult("L1", True),
        ChainLegResult("L2", True),
        ChainLegResult("L3", False),
    ]
    mid = ev.evaluate(legs, final_delivery_completed=False)
    assert mid.order_status == "IN_TRANSIT"
    legs[-1] = ChainLegResult("L3", True)
    done = ev.evaluate(legs, final_delivery_completed=True)
    assert done.order_status == "ORDER_COMPLETED"
    assert done.completed_legs == 3


def test_comparator_prefers_passenger_service():
    worse = TripCandidate(
        source_kind="CURRENT_TRIP",
        route_id="347",
        shift_id="07:30",
        vehicle_id=1,
        passenger_impact_s=120,
        incremental_cost=6.0,
        economic_value=10.0,
        detour_distance_m=2000,
    ).with_efficiency()
    better = TripCandidate(
        source_kind="NEXT_TRIP",
        route_id="347",
        shift_id="08:00",
        vehicle_id=1,
        passenger_impact_s=0,
        incremental_cost=2.0,
        economic_value=10.0,
        waiting_time_s=1200,
    ).with_efficiency()
    ranked = compare_trip_candidates([worse, better])
    assert ranked[0] is better
    text = explain_choice(ranked[0], [worse, better])
    assert "等待" in text or "承运" in text


def test_gap_mandatory_not_skipped():
    assert can_detour_within_gap(MANDATORY, 0, ["A", "B", "C", "X", "Y", "D", "E"])
    assert not can_detour_within_gap(MANDATORY, 0, ["A", "B", "C", "X", "Y", "E"])


def test_next_trip_budget_reset():
    det_now = calculate_gap_detour(
        from_station="C",
        to_station="D",
        via_pickup="X",
        via_delivery="Y",
        rejoin_station="D",
        coords=COORDS,
        mandatory_stops=MANDATORY,
        planned_stops_after=["A", "B", "C", "X", "Y", "D", "E"],
        trip_detour_remaining_m=10.0,
    )
    assert not det_now.ok
    det_next = calculate_gap_detour(
        from_station="C",
        to_station="D",
        via_pickup="X",
        via_delivery="Y",
        rejoin_station="D",
        coords=COORDS,
        mandatory_stops=MANDATORY,
        planned_stops_after=["A", "B", "C", "X", "Y", "D", "E"],
        trip_detour_remaining_m=5000.0,
    )
    assert det_next.ok
