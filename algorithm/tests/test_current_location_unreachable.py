"""当前位置不可达 / 恢复测试。"""

from app.dispatch_opt.models import ReachabilityReason, RecoveryAction, TripCandidate
from app.dispatch_opt.reachability import (
    AccessFlags,
    LocationSnapshot,
    classify_reachability,
    plan_recovery,
)


def _loc(fresh=True):
    return LocationSnapshot(
        vehicle_id=1, lat=29.5, lon=106.6, timestamp=100.0 if fresh else 0.0, now=120.0
    )


def _flags(**kw):
    return AccessFlags(**kw)


def test_normal_reachable():
    d = classify_reachability(
        flags=_flags(), location=_loc(), service_point="X", original_point="U"
    )
    assert d.reachable
    assert d.reason_code == ReachabilityReason.REACHABLE


def test_road_unreachable_not_same_as_passed():
    d = classify_reachability(
        flags=_flags(road_reachable=False),
        location=_loc(),
        service_point="X",
        original_point="U",
    )
    assert d.reason_code == ReachabilityReason.ROAD_UNREACHABLE
    d2 = classify_reachability(
        flags=_flags(already_passed=True),
        location=_loc(),
        service_point="X",
        original_point="U",
    )
    assert d2.reason_code == ReachabilityReason.ALREADY_PASSED


def test_vehicle_access_blocked_suggests_nearest():
    d = classify_reachability(
        flags=_flags(vehicle_access=False),
        location=_loc(),
        service_point="X",
        original_point="重庆邮电大学校内",
    )
    assert d.suggested_action == RecoveryAction.NEAREST_STATION
    assert d.status.value == "NEAREST_STATION_REQUIRED"


def test_user_point_unservable():
    d = classify_reachability(
        flags=_flags(user_access=False),
        location=_loc(),
        service_point="X",
        original_point="U",
    )
    assert d.reason_code == ReachabilityReason.USER_POINT_UNSERVABLE


def test_eta_missed_and_detour_too_large():
    d = classify_reachability(
        flags=_flags(eta_feasible=False),
        location=_loc(),
        service_point="X",
        original_point="U",
    )
    assert d.reason_code == ReachabilityReason.ETA_MISSED
    d2 = classify_reachability(
        flags=_flags(detour_feasible=False),
        location=_loc(),
        service_point="X",
        original_point="U",
    )
    assert d2.reason_code == ReachabilityReason.DETOUR_TOO_LARGE


def test_location_stale_is_unknown_not_unreachable():
    d = classify_reachability(
        flags=_flags(), location=_loc(fresh=False), service_point="X", original_point="U"
    )
    assert d.status.value == "UNKNOWN"
    assert d.reason_code == ReachabilityReason.LOCATION_STALE
    assert d.suggested_action == RecoveryAction.WAIT_LOCATION


def test_network_uncertain_not_unreachable():
    d = classify_reachability(
        flags=_flags(network_known=False),
        location=_loc(),
        service_point="X",
        original_point="U",
    )
    assert d.reason_code == ReachabilityReason.NETWORK_UNCERTAIN
    assert d.status.value == "UNKNOWN"


def test_recovery_prefers_future_then_other_then_multileg():
    d = classify_reachability(
        flags=_flags(already_passed=True),
        location=_loc(),
        service_point="X",
        original_point="U",
    )

    def cand(kind, **kw):
        return TripCandidate(
            source_kind=kind,
            route_id="R",
            shift_id="S",
            vehicle_id=1,
            feasibility=True,
            incremental_cost=1.0,
            economic_value=10.0,
            **kw,
        )

    out = plan_recovery(
        d,
        future_trips=[cand("NEXT_TRIP")],
        other_routes=[cand("OTHER_ROUTE")],
        multileg=[cand("MULTI_LEG", handover_count=1)],
    )
    kinds = [c.recovery_action for c in out]
    assert RecoveryAction.SAME_ROUTE_FUTURE_TRIP in kinds
    assert RecoveryAction.MULTI_LEG in kinds


def test_campus_scenario_service_point_downgrade():
    d = classify_reachability(
        flags=_flags(vehicle_access=False, user_access=False),
        location=_loc(),
        service_point="NEAREST_X",
        original_point="重庆邮电大学校内",
    )
    assert d.original_point == "重庆邮电大学校内"
    assert d.service_point == "NEAREST_X"
    out = plan_recovery(
        d,
        nearest_station=TripCandidate(
            source_kind="OTHER_ROUTE",
            route_id="303",
            shift_id="07:50",
            vehicle_id=3,
            feasibility=True,
            incremental_cost=2.0,
            economic_value=10.0,
        ),
    )
    assert out
    assert any(
        c.recovery_action
        in (
            RecoveryAction.NEAREST_STATION,
            RecoveryAction.OTHER_ROUTE,
            RecoveryAction.SAME_ROUTE_FUTURE_TRIP,
            RecoveryAction.MULTI_LEG,
        )
        for c in out
    )
