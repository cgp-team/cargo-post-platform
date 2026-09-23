"""TripLockPolicy 测试。"""

from app.dispatch_opt.models import MarginalCostBreakdown, TripExecutionState
from app.dispatch_opt.trip_lock import HighValueRealtimeInsertPolicy, TripLockPolicy


def test_pre_departure_allows_normal_insert():
    p = TripLockPolicy()
    d = p.decide(TripExecutionState.PLANNED)
    assert d.allow_insert and d.level == "NORMAL"
    d = p.decide(TripExecutionState.READY)
    assert d.allow_insert


def test_departed_rejects_normal_order():
    p = TripLockPolicy()
    d = p.decide(TripExecutionState.DEPARTED, is_high_value=False)
    assert not d.allow_insert
    assert d.level == "FUTURE_DISPATCH"
    assert d.reason_code == "LOCKED_ACTIVE_TRIP"


def test_departed_high_value_low_impact_allowed():
    p = TripLockPolicy()
    cost = MarginalCostBreakdown(
        delta_distance_m=200, delta_duration_s=30, delta_passenger_impact_s=10
    )
    d = p.decide(
        TripExecutionState.IN_PROGRESS,
        is_high_value=True,
        cost=cost,
        efficiency=3.0,
    )
    assert d.allow_insert
    assert d.reason_code == "HIGH_VALUE_REALTIME_INSERT"


def test_departed_mandatory_risk_rejected():
    p = TripLockPolicy()
    d = p.decide(
        TripExecutionState.DEPARTED,
        is_high_value=True,
        mandatory_stop_risk=True,
    )
    assert not d.allow_insert
    assert d.reason_code == "MANDATORY_STOP_RISK"


def test_departed_detour_budget_rejected():
    p = TripLockPolicy()
    cost = MarginalCostBreakdown(delta_distance_m=5000)
    d = p.decide(
        TripExecutionState.DEPARTED,
        is_high_value=True,
        cost=cost,
        efficiency=9.0,
    )
    assert not d.allow_insert
    assert d.reason_code in ("REALTIME_DETOUR_TOO_LARGE", "DETOUR_BUDGET_EXCEEDED")


def test_high_value_alias():
    p = HighValueRealtimeInsertPolicy()
    d = p.decide(TripExecutionState.PLANNED)
    assert d.allow_insert
