"""Handover 可行性测试。"""

from app.dispatch_opt.handover import (
    ChainLegResult,
    TransportChainEvaluator,
    can_handover,
)
from app.dispatch_opt.models import HandoverKind


def test_same_station_handover():
    d = can_handover(
        from_arrival_time=0.0,
        to_departure_time=3600.0,
        same_station=True,
    )
    assert d.feasible
    assert d.kind == HandoverKind.SAME_STATION
    assert d.breakdown.transfer_distance_m == 0


def test_nearby_station_ok_and_too_far():
    ok = can_handover(
        from_arrival_time=0.0,
        to_departure_time=7200.0,
        same_station=False,
        station_distance_m=80.0,
        nearby_threshold_m=150.0,
    )
    assert ok.feasible
    assert ok.kind == HandoverKind.NEARBY_STATION
    far = can_handover(
        from_arrival_time=0.0,
        to_departure_time=7200.0,
        same_station=False,
        station_distance_m=400.0,
        nearby_threshold_m=150.0,
    )
    assert not far.feasible
    assert far.reason_code == "HANDOVER_DISTANCE_EXCEEDED"


def test_transfer_waiting_time():
    d = can_handover(
        from_arrival_time=0.0,
        to_departure_time=5000.0,
        same_station=True,
        handover_dwell_s=600.0,
    )
    assert d.feasible
    assert d.breakdown.waiting_time_s > 0


def test_insufficient_next_capacity():
    d = can_handover(
        from_arrival_time=0.0,
        to_departure_time=3600.0,
        next_cargo_capacity=0,
    )
    assert not d.feasible
    assert d.reason_code == "NEXT_CAPACITY_UNAVAILABLE"


def test_driver_unavailable_and_shift_conflict():
    assert not can_handover(
        from_arrival_time=0.0, to_departure_time=3600.0, next_driver_available=False
    ).feasible
    assert not can_handover(
        from_arrival_time=0.0, to_departure_time=3600.0, shift_conflict=True
    ).feasible


def test_wait_timeout():
    d = can_handover(
        from_arrival_time=0.0,
        to_departure_time=90000.0,
        order_wait_limit_s=3600.0,
    )
    assert not d.feasible
    assert d.reason_code == "WAIT_TIMEOUT"


def test_chain_order_complete_only_at_final_delivery():
    ev = TransportChainEvaluator()
    legs = [
        ChainLegResult("L1", True),
        ChainLegResult("L2", True),
        ChainLegResult("L3", False),
    ]
    r = ev.evaluate(legs, final_delivery_completed=False)
    assert r.order_status == "IN_TRANSIT"
    legs[-1] = ChainLegResult("L3", True)
    r = ev.evaluate(legs, final_delivery_completed=True)
    assert r.order_status == "ORDER_COMPLETED"
