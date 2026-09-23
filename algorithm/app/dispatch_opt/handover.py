"""交接可行性与运输链评估（**司机对司机同站交接**，禁止远距步行换乘）。（包装 MultiLeg，不重写 MultiLegPlanner）。"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Sequence

from .models import HandoverCostBreakdown, HandoverDecision, HandoverKind


def can_handover(
    *,
    from_arrival_time: float,
    to_departure_time: float,
    handover_dwell_s: float = 20 * 60.0,
    same_station: bool = True,
    station_distance_m: float = 0.0,
    nearby_threshold_m: float = 150.0,
    cargo_present: bool = True,
    next_cargo_capacity: int = 1,
    next_driver_available: bool = True,
    shift_conflict: bool = False,
    order_wait_limit_s: float | None = None,
    handover_travel_speed_m_s: float = 1.2,
    handling_time_s: float = 300.0,
    timetable_buffer_s: float = 120.0,
) -> HandoverDecision:
    """检查两段之间是否可交接。"""
    if not cargo_present:
        return HandoverDecision(False, None, "CARGO_NOT_PRESENT")
    if next_cargo_capacity <= 0:
        return HandoverDecision(False, None, "NEXT_CAPACITY_UNAVAILABLE")
    if not next_driver_available:
        return HandoverDecision(False, None, "DRIVER_UNAVAILABLE")
    if shift_conflict:
        return HandoverDecision(False, None, "SHIFT_CONFLICT")

    if same_station:
        kind = HandoverKind.SAME_STATION
        transfer_d = 0.0
    else:
        if station_distance_m > nearby_threshold_m:
            return HandoverDecision(
                False, HandoverKind.NEARBY_STATION, "HANDOVER_DISTANCE_EXCEEDED"
            )
        kind = HandoverKind.NEARBY_STATION
        transfer_d = station_distance_m

    handling = max(0.0, handling_time_s)
    travel = transfer_d / max(handover_travel_speed_m_s, 0.1)
    total_needed = handover_dwell_s + travel + handling + max(0.0, timetable_buffer_s)
    # P0-5: readiness must include transfer travel + dwell + handling, not just arrival<=departure.
    ready_time = from_arrival_time + total_needed
    waiting = max(0.0, to_departure_time - ready_time)

    if order_wait_limit_s is not None:
        wait_for_order = max(0.0, to_departure_time - from_arrival_time)
        if wait_for_order > order_wait_limit_s:
            return HandoverDecision(False, kind, "WAIT_TIMEOUT")

    if to_departure_time + 1e-6 < from_arrival_time:
        # 下一班已发车
        return HandoverDecision(False, kind, "NEXT_TRIP_DEPARTED")
    if to_departure_time + 1e-6 < ready_time:
        # next leg departs before the cargo is actually ready to be loaded
        return HandoverDecision(False, kind, "HANDOVER_INFEASIBLE")

    br = HandoverCostBreakdown(
        transfer_distance_m=transfer_d,
        handling_time_s=handling,
        dwell_time_s=handover_dwell_s,
        waiting_time_s=waiting,
        operational_cost=2.0 + transfer_d / 1000.0,
        failure_risk=0.0 if same_station else 0.2,
    )
    return HandoverDecision(True, kind, "HANDOVER_OK", br)


@dataclass
class ChainLegResult:
    leg_id: str
    completed: bool
    handover_after: bool = False


@dataclass
class TransportChainResult:
    order_status: str  # IN_TRANSIT / ORDER_COMPLETED / CHAIN_BROKEN
    completed_legs: int
    total_legs: int
    reason_code: str


class TransportChainEvaluator:
    """Leg1 + Transfer + Leg2 ... 整条货运链；仅最终 DELIVERY 完成才算订单完成。"""

    def evaluate(
        self,
        legs: Sequence[ChainLegResult],
        final_delivery_completed: bool,
    ) -> TransportChainResult:
        total = len(legs)
        completed = sum(1 for x in legs if x.completed)
        if total == 0:
            return TransportChainResult("CHAIN_BROKEN", 0, 0, "NO_LEGS")
        if not final_delivery_completed:
            return TransportChainResult(
                "IN_TRANSIT", completed, total, "AWAITING_FINAL_DELIVERY"
            )
        # 最后一段必须完成
        if not legs[-1].completed:
            return TransportChainResult(
                "IN_TRANSIT", completed, total, "FINAL_LEG_NOT_COMPLETED"
            )
        return TransportChainResult("ORDER_COMPLETED", completed, total, "ORDER_COMPLETED")
