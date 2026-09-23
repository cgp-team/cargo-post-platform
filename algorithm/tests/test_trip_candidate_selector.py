"""TripCandidateSelector 测试。"""

from app.dispatch_opt.candidate_selector import TripCandidateSelector, TripContext
from app.dispatch_opt.models import TripCandidate, TripExecutionState


def _ctx(route="347", shift="07:30", dep=0.0, state=TripExecutionState.PLANNED, veh=1):
    return TripContext(
        route_id=route,
        shift_id=shift,
        vehicle_id=veh,
        driver_id=veh,
        departure_time=dep,
        execution_state=state,
        remaining_cargo_capacity=2,
    )


def test_current_trip_wins_when_planned():
    sel = TripCandidateSelector()
    r = sel.generate(
        current_trips=[_ctx()],
        future_trips=[_ctx(shift="08:00", dep=1800, veh=2)],
        other_route_trips=[_ctx(route="303", shift="07:50", dep=1200, veh=3)],
        economic_value=10.0,
    )
    assert r.chosen is not None
    assert r.chosen.source_kind == "CURRENT_TRIP"


def test_next_trip_when_departed_normal():
    sel = TripCandidateSelector()
    r = sel.generate(
        current_trips=[_ctx(state=TripExecutionState.DEPARTED)],
        future_trips=[_ctx(shift="08:00", dep=1800, veh=2)],
        other_route_trips=[],
        economic_value=10.0,
        is_high_value=False,
    )
    assert r.chosen is not None
    assert r.chosen.source_kind in ("NEXT_TRIP", "LATER_TRIP")


def test_other_route_candidate():
    sel = TripCandidateSelector()
    r = sel.generate(
        current_trips=[_ctx(state=TripExecutionState.DEPARTED)],
        future_trips=[],
        other_route_trips=[_ctx(route="303", dep=300, veh=3)],
        economic_value=8.0,
        is_high_value=False,
    )
    assert r.chosen is not None
    assert r.chosen.source_kind == "OTHER_ROUTE"


def test_multileg_candidate_via_builder():
    def ml_builder(future, other, econ):
        return [
            TripCandidate(
                source_kind="MULTI_LEG",
                route_id="347->303",
                shift_id="ML1",
                vehicle_id=9,
                handover_count=1,
                handover_cost=2.0,
                incremental_cost=3.0,
                economic_value=econ,
                feasibility=True,
                legs=("347", "303"),
            ).with_efficiency()
        ]

    sel = TripCandidateSelector(build_multileg=ml_builder)
    r = sel.generate(
        current_trips=[_ctx(state=TripExecutionState.COMPLETED)],
        future_trips=[],
        other_route_trips=[],
        economic_value=12.0,
    )
    assert r.chosen is not None
    assert r.chosen.source_kind == "MULTI_LEG"


def test_no_feasible_does_not_hard_fail():
    sel = TripCandidateSelector()
    r = sel.generate(
        current_trips=[_ctx(state=TripExecutionState.DEPARTED)],
        future_trips=[],
        other_route_trips=[],
    )
    assert r.chosen is None
    assert "FUTURE" in r.reason_code or "HOLD" in r.level.value
