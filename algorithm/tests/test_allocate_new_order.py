"""allocate_new_order 测试：Pickup/Delivery 双侧可达 + 恢复。"""

from app.dispatch_opt.allocate import AllocationRequest, allocate_new_order
from app.dispatch_opt.candidate_selector import TripContext
from app.dispatch_opt.models import TripExecutionState
from app.dispatch_opt.reachability import AccessFlags, LocationSnapshot


def _ctx(state=TripExecutionState.PLANNED, route="347", shift="07:30", dep=0.0, veh=1):
    return TripContext(
        route_id=route, shift_id=shift, vehicle_id=veh, driver_id=veh,
        departure_time=dep, execution_state=state, remaining_cargo_capacity=2,
    )


def _loc():
    return LocationSnapshot(1, 30.0, 104.0, 100.0, 110.0)


def test_both_reachable_uses_current_trip():
    r = allocate_new_order(
        AllocationRequest("o1", "A", "B", economic_value=10.0),
        current_trips=[_ctx()],
        future_trips=[_ctx(shift="08:00", dep=1800, veh=2)],
        other_route_trips=[],
        location=_loc(),
    )
    assert r.chosen is not None
    assert r.chosen.source_kind == "CURRENT_TRIP"
    assert r.reachability_pickup.reachable
    assert r.reachability_delivery.reachable


def test_pickup_unreachable_goes_recovery_not_fail():
    r = allocate_new_order(
        AllocationRequest("o2", "A", "B"),
        current_trips=[_ctx()],
        future_trips=[_ctx(shift="08:00", dep=1800, veh=2)],
        other_route_trips=[],
        location=_loc(),
        pickup_access=AccessFlags(road_reachable=False),
    )
    assert r.level == "RECOVERY"
    assert "PICKUP_UNREACHABLE" in r.reason_code
    assert r.recovery_candidates


def test_delivery_unreachable_still_recovers():
    r = allocate_new_order(
        AllocationRequest("o3", "A", "UNREACH_D", original_delivery="重庆邮电大学校内"),
        current_trips=[_ctx()],
        future_trips=[_ctx(shift="08:00", dep=1800, veh=2)],
        other_route_trips=[_ctx(route="303", dep=600, veh=3)],
        location=_loc(),
        delivery_access=AccessFlags(vehicle_access=False, user_access=False),
    )
    assert r.level == "RECOVERY"
    assert "DELIVERY_UNREACHABLE" in r.reason_code
    assert r.recovery_candidates


def test_departed_normal_order_future_not_current():
    r = allocate_new_order(
        AllocationRequest("o4", "A", "B"),
        current_trips=[_ctx(state=TripExecutionState.DEPARTED)],
        future_trips=[_ctx(shift="08:00", dep=1800, veh=2)],
        other_route_trips=[],
        location=_loc(),
    )
    assert r.chosen is not None
    assert r.chosen.source_kind in ("NEXT_TRIP", "LATER_TRIP", "OTHER_ROUTE")
