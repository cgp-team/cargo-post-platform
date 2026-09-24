"""DispatchCandidateBuilder（DISPATCH_CORE_V047）。

为**每个真实候选**生成完整的车辆/班次/时间/路线上下文，而不是只造一个
`route_id + vehicle_id + feasibility=True` 的空壳。

候选类型：
`CURRENT_TRIP / NEXT_TRIP / LATER_TRIP / OTHER_ROUTE / MULTILEG_2 / MULTILEG_3 /
NEAREST_STATION / HOLD`
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Sequence

from .handover import can_handover
from .marginal_cost import MarginalCostEvaluator
from .models import TripCandidate, TripExecutionState
from .route_cost_provider import (
    HaversineLowerBoundProvider,
    Point,
    RouteCostProvider,
)


class CandidateType(str, Enum):
    CURRENT_TRIP = "CURRENT_TRIP"
    NEXT_TRIP = "NEXT_TRIP"
    LATER_TRIP = "LATER_TRIP"
    OTHER_ROUTE = "OTHER_ROUTE"
    MULTILEG_2 = "MULTILEG_2"
    MULTILEG_3 = "MULTILEG_3"
    NEAREST_STATION = "NEAREST_STATION"
    HOLD = "HOLD"


@dataclass
class DispatchOrder:
    order_id: str
    pickup_service_point: str
    delivery_service_point: str
    original_pickup: str | None = None
    original_delivery: str | None = None
    economic_value: float | None = None
    is_high_value: bool = False
    ready_time: float = 0.0
    pickup_deadline: float | None = None
    delivery_deadline: float | None = None
    priority: int = 0
    passenger_count: int = 0
    quantity: int = 1
    weight_kg: float | None = None
    volume_m3: float | None = None
    service_duration_s: float = 300.0
    # None=按 trip 剩余调度站点自动判定；True/False=调用方显式覆盖
    on_planned_route: bool | None = None


@dataclass
class TripView:
    """单个真实班次的完整视图（candidate 级输入，非全局一次判定）。"""

    route_id: str
    shift_id: str
    vehicle_id: int
    driver_id: int | None = None
    departure_time: float = 0.0
    execution_state: TripExecutionState = TripExecutionState.PLANNED
    current_location: Point | None = None
    remaining_cargo_capacity: int = 1
    remaining_passenger_capacity: int = 1
    trip_detour_remaining_m: float = 0.0
    passenger_impact_budget_s: float = 0.0
    gap_index: int = 0
    shift_end_time: float | None = None
    station_coords: dict[str, Point] = field(default_factory=dict)
    already_passed: bool = False
    location_fresh: bool = True
    network_known: bool = True
    road_reachable: bool = True
    vehicle_access: bool = True
    user_access: bool = True
    driver_shift_ok: bool = True
    # 剩余调度路线站点（mandatory 未到站 + 已排服务点）；用于顺路插入判定
    remaining_planned_stops: tuple[str, ...] = ()
    raw: object | None = None


@dataclass
class DispatchCandidate:
    """section 5 要求的完整字段集合。"""

    candidate_type: CandidateType
    vehicle_id: int
    driver_id: int | None
    route_id: str
    shift_id: str
    departure_time: float
    execution_state: TripExecutionState
    current_location: Point | None
    pickup_service_point: str
    delivery_service_point: str
    remaining_cargo_capacity: int
    remaining_passenger_capacity: int
    trip_detour_remaining_m: float
    passenger_impact_budget_s: float
    gap_index: int
    estimated_pickup_eta: float | None
    estimated_delivery_eta: float | None
    sla_slack_s: float | None
    handover_count: int = 0
    reason_code: str = "OK"
    legs: tuple[str, ...] = ()
    waiting_s: float = 0.0
    feasibility: bool = True
    cost_is_formal: bool = False
    detour_distance_m: float = 0.0
    detour_duration_s: float = 0.0
    passenger_impact_s: float = 0.0
    incremental_cost: float = 0.0
    economic_value: float | None = None
    source_kind: str = ""
    detail: dict = field(default_factory=dict)
    # For MULTILEG chains the detour budget is per-leg (each leg has its own vehicle).
    per_leg_budget_ok: bool | None = None

    @property
    def candidate_id(self) -> str:
        return f"{self.candidate_type.value}:{self.route_id}:{self.vehicle_id}:{self.shift_id}"

    def to_trip_candidate(self) -> TripCandidate:
        return TripCandidate(
            source_kind=self._trip_source_kind(),
            route_id=self.route_id,
            shift_id=self.shift_id,
            vehicle_id=self.vehicle_id,
            driver_id=self.driver_id,
            departure_time=self.departure_time,
            estimated_pickup_time=self.estimated_pickup_eta or 0.0,
            estimated_delivery_time=self.estimated_delivery_eta or 0.0,
            waiting_time_s=self.waiting_s,
            detour_distance_m=self.detour_distance_m,
            detour_duration_s=self.detour_duration_s,
            passenger_impact_s=self.passenger_impact_s,
            handover_count=self.handover_count,
            incremental_cost=self.incremental_cost,
            economic_value=self.economic_value,
            feasibility=self.feasibility,
            reason_code=self.reason_code,
            legs=self.legs,
            gap_index=self.gap_index,
            raw=self,
        ).with_efficiency()

    def _trip_source_kind(self) -> str:
        if self.candidate_type in (CandidateType.MULTILEG_2, CandidateType.MULTILEG_3):
            return "MULTI_LEG"
        if self.candidate_type == CandidateType.NEAREST_STATION:
            return "NEAREST_STATION"
        return self.candidate_type.value


class DispatchCandidateBuilder:
    """生成真实候选；ETA/绕行来自 routing provider，非 formal 时显式标记。"""

    def __init__(
        self,
        *,
        route_provider: RouteCostProvider | None = None,
        cost_evaluator: MarginalCostEvaluator | None = None,
        now: float = 0.0,
        next_trip_horizon_s: float = 3600.0,
        max_multileg_legs: int = 3,
    ):
        self.route_provider = route_provider
        self.lower_bound = HaversineLowerBoundProvider()
        self.cost_evaluator = cost_evaluator or MarginalCostEvaluator()
        self.now = now
        self.next_trip_horizon_s = next_trip_horizon_s
        self.max_multileg_legs = max_multileg_legs

    # ── routing helpers ──
    def _leg(self, a: Point | None, b: Point | None) -> tuple[float, float, bool]:
        """返回 (distance_m, duration_s, is_formal)。"""
        if a is None or b is None:
            return 0.0, 0.0, False
        if self.route_provider is not None:
            cost = self.route_provider.route(a, b)
            if cost.is_formal:
                return cost.distance_m, cost.duration_s, True
        lb = self.lower_bound.route(a, b)
        return lb.distance_m, lb.duration_s, False

    def _eta_to_point(self, from_pt: Point | None, to_pt: Point | None) -> tuple[float, bool]:
        dist, dur, formal = self._leg(from_pt, to_pt)
        return dur, formal

    # ── candidate construction ──
    def _base_fields(self, trip: TripView) -> dict:
        return dict(
            vehicle_id=trip.vehicle_id,
            driver_id=trip.driver_id,
            route_id=trip.route_id,
            shift_id=trip.shift_id,
            departure_time=trip.departure_time,
            execution_state=trip.execution_state,
            current_location=trip.current_location,
            remaining_cargo_capacity=trip.remaining_cargo_capacity,
            remaining_passenger_capacity=trip.remaining_passenger_capacity,
            trip_detour_remaining_m=trip.trip_detour_remaining_m,
            passenger_impact_budget_s=trip.passenger_impact_budget_s,
            gap_index=trip.gap_index,
        )

    def _estimate_trip_candidate(
        self,
        trip: TripView,
        order: DispatchOrder,
        *,
        candidate_type: CandidateType,
        service: str = "BOTH",
    ) -> DispatchCandidate:
        pu = trip.station_coords.get(order.pickup_service_point)
        de = trip.station_coords.get(order.delivery_service_point)
        origin = trip.current_location

        to_pu_dur, to_pu_formal = self._eta_to_point(origin, pu)
        pu_to_de_dur, pd_formal = self._eta_to_point(pu, de)
        o_pu_dist, _, _ = self._leg(origin, pu)
        pu_de_dist, _, _ = self._leg(pu, de)
        o_de_dist, _, _ = self._leg(origin, de)

        formal = to_pu_formal and pd_formal
        start = max(self.now, trip.departure_time, order.ready_time)

        # Per-leg semantics: a MULTILEG_* chain only carries its OWN portion, so its
        # marginal detour is the insert of that portion alone (not the full both-leg detour).
        if service == "PICKUP":
            detour_dist = max(0.0, o_pu_dist - o_de_dist)
            detour_dur = to_pu_dur
            pickup_eta = start + to_pu_dur
            delivery_eta = pickup_eta  # arrival at the handover point
        elif service == "DELIVERY":
            detour_dist = max(0.0, pu_de_dist - o_de_dist)
            detour_dur = pu_to_de_dur
            pickup_eta = start
            delivery_eta = start + order.service_duration_s + pu_to_de_dur
        else:
            # detour = (origin -> pickup -> delivery) - (origin -> delivery)
            detour_dist = max(0.0, (o_pu_dist + pu_de_dist) - o_de_dist)
            detour_dur = to_pu_dur + pu_to_de_dur
            pickup_eta = start + to_pu_dur
            delivery_eta = pickup_eta + order.service_duration_s + pu_to_de_dur

        sla_slack = None
        if order.delivery_deadline is not None:
            sla_slack = order.delivery_deadline - delivery_eta
        if order.pickup_deadline is not None:
            pu_slack = order.pickup_deadline - pickup_eta
            sla_slack = pu_slack if sla_slack is None else min(sla_slack, pu_slack)

        reason = "OK"
        feasible = True
        if trip.remaining_cargo_capacity < max(1, order.quantity):
            feasible, reason = False, "VEHICLE_CAPACITY_UNAVAILABLE"
        elif order.passenger_count > trip.remaining_passenger_capacity:
            feasible, reason = False, "PASSENGER_CAPACITY_UNAVAILABLE"
        elif sla_slack is not None and sla_slack < 0:
            feasible, reason = False, "ETA_MISSED"
        elif detour_dist > trip.trip_detour_remaining_m + 1e-6:
            feasible, reason = False, "DETOUR_TOO_LARGE"

        pax_impact = max(0.0, detour_dur) * max(0, order.passenger_count)
        if pax_impact > trip.passenger_impact_budget_s + 1e-6:
            feasible, reason = False, "PASSENGER_IMPACT_EXCEEDED"

        breakdown = self.cost_evaluator.evaluate(
            delta_distance_m=detour_dist,
            delta_duration_s=detour_dur,
            delta_passenger_impact_s=pax_impact,
        )
        cand = DispatchCandidate(
            candidate_type=candidate_type,
            pickup_service_point=order.pickup_service_point,
            delivery_service_point=order.delivery_service_point,
            estimated_pickup_eta=pickup_eta,
            estimated_delivery_eta=delivery_eta,
            sla_slack_s=sla_slack,
            waiting_s=max(0.0, start - self.now),
            feasibility=feasible,
            reason_code=reason,
            cost_is_formal=formal,
            detour_distance_m=detour_dist,
            detour_duration_s=detour_dur,
            passenger_impact_s=pax_impact,
            incremental_cost=breakdown.total_incremental_cost,
            economic_value=order.economic_value,
            source_kind="",
            detail={"trip_view": trip},
            **self._base_fields(trip),
        )
        return cand

    def build_current(
        self, trips: Sequence[TripView], order: DispatchOrder
    ) -> list[DispatchCandidate]:
        return [
            self._estimate_trip_candidate(t, order, candidate_type=CandidateType.CURRENT_TRIP)
            for t in trips
        ]

    def build_future(
        self, trips: Sequence[TripView], order: DispatchOrder
    ) -> list[DispatchCandidate]:
        out: list[DispatchCandidate] = []
        for t in trips:
            ctype = (
                CandidateType.NEXT_TRIP
                if max(0.0, t.departure_time) <= self.next_trip_horizon_s
                else CandidateType.LATER_TRIP
            )
            out.append(self._estimate_trip_candidate(t, order, candidate_type=ctype))
        return out

    def build_other(
        self, trips: Sequence[TripView], order: DispatchOrder
    ) -> list[DispatchCandidate]:
        return [
            self._estimate_trip_candidate(t, order, candidate_type=CandidateType.OTHER_ROUTE)
            for t in trips
        ]

    def build_nearest_station(
        self, trip: TripView | None, order: DispatchOrder
    ) -> list[DispatchCandidate]:
        if trip is None:
            return []
        cand = self._estimate_trip_candidate(
            trip, order, candidate_type=CandidateType.NEAREST_STATION
        )
        cand.detail["nearest_station"] = trip.route_id
        cand.reason_code = "NEAREST_LEGAL_SERVICE_POINT"
        return [cand]

    def build_multileg(
        self,
        leg1_trips: Sequence[TripView],
        leg2_trips: Sequence[TripView],
        order: DispatchOrder,
    ) -> list[DispatchCandidate]:
        """真实 2-leg / 3-leg 链：每条腿独立 ETA，交接做完整时间可行性校验。"""
        out: list[DispatchCandidate] = []
        if not leg1_trips or not leg2_trips:
            return out

        for a in leg1_trips:
            for b in leg2_trips:
                if a.vehicle_id == b.vehicle_id and a.shift_id == b.shift_id:
                    continue
                first = self._estimate_trip_candidate(
                    a, order, candidate_type=CandidateType.MULTILEG_2, service="PICKUP"
                )
                second = self._estimate_trip_candidate(
                    b, order, candidate_type=CandidateType.MULTILEG_2, service="DELIVERY"
                )
                arrival = first.estimated_delivery_eta or 0.0
                departure = second.departure_time
                ho = can_handover(
                    from_arrival_time=arrival,
                    to_departure_time=departure,
                    same_station=True,
                    cargo_present=True,
                    next_cargo_capacity=max(1, order.quantity),
                )
                feasible = first.feasibility and second.feasibility and ho.feasible
                reason = "MULTILEG_OK" if feasible else (
                    "HANDOVER_INFEASIBLE" if not ho.feasible else (first.reason_code if not first.feasibility else second.reason_code)
                )
                chain = DispatchCandidate(
                    candidate_type=CandidateType.MULTILEG_2,
                    vehicle_id=a.vehicle_id,
                    driver_id=a.driver_id,
                    route_id=f"{a.route_id}->{b.route_id}",
                    shift_id=f"{a.shift_id}->{b.shift_id}",
                    departure_time=a.departure_time,
                    execution_state=a.execution_state,
                    current_location=a.current_location,
                    pickup_service_point=order.pickup_service_point,
                    delivery_service_point=order.delivery_service_point,
                    remaining_cargo_capacity=min(
                        a.remaining_cargo_capacity, b.remaining_cargo_capacity
                    ),
                    remaining_passenger_capacity=min(
                        a.remaining_passenger_capacity, b.remaining_passenger_capacity
                    ),
                    # chain detour budget = sum of the participating legs' budgets
                    trip_detour_remaining_m=(
                        a.trip_detour_remaining_m + b.trip_detour_remaining_m
                    ),
                    passenger_impact_budget_s=a.passenger_impact_budget_s,
                    gap_index=a.gap_index,
                    estimated_pickup_eta=first.estimated_pickup_eta,
                    estimated_delivery_eta=second.estimated_delivery_eta,
                    sla_slack_s=(
                        None
                        if order.delivery_deadline is None
                        else order.delivery_deadline - (second.estimated_delivery_eta or 0.0)
                    ),
                    handover_count=1,
                    reason_code=reason,
                    legs=(a.route_id, b.route_id),
                    waiting_s=second.waiting_s,
                    feasibility=feasible,
                    cost_is_formal=first.cost_is_formal and second.cost_is_formal,
                    detour_distance_m=first.detour_distance_m + second.detour_distance_m,
                    detour_duration_s=first.detour_duration_s + second.detour_duration_s,
                    passenger_impact_s=first.passenger_impact_s + second.passenger_impact_s,
                    incremental_cost=first.incremental_cost + second.incremental_cost,
                    economic_value=order.economic_value,
                    per_leg_budget_ok=(
                        first.detour_distance_m <= a.trip_detour_remaining_m + 1e-6
                        and second.detour_distance_m <= b.trip_detour_remaining_m + 1e-6
                    ),
                    detail={
                        "handover": ho.reason_code,
                        "legs": [a.route_id, b.route_id],
                        "leg1": {"vehicle": a.vehicle_id, "detour_m": first.detour_distance_m},
                        "leg2": {"vehicle": b.vehicle_id, "detour_m": second.detour_distance_m},
                    },
                )
                out.append(chain)

        if self.max_multileg_legs >= 3 and len(leg1_trips) >= 2 and leg2_trips:
            for i, a in enumerate(leg1_trips):
                for b in leg1_trips[i + 1:]:
                    c = leg2_trips[0]
                    if len({a.vehicle_id, b.vehicle_id, c.vehicle_id}) < 2:
                        continue
                    chain = self._build_three_leg(a, b, c, order)
                    if chain is not None:
                        out.append(chain)
        return out

    def _build_three_leg(
        self, a: TripView, b: TripView, c: TripView, order: DispatchOrder
    ) -> DispatchCandidate | None:
        first = self._estimate_trip_candidate(
            a, order, candidate_type=CandidateType.MULTILEG_3, service="PICKUP"
        )
        second = self._estimate_trip_candidate(
            b, order, candidate_type=CandidateType.MULTILEG_3, service="DELIVERY"
        )
        third = self._estimate_trip_candidate(
            c, order, candidate_type=CandidateType.MULTILEG_3, service="DELIVERY"
        )
        ho1 = can_handover(
            from_arrival_time=first.estimated_delivery_eta or 0.0,
            to_departure_time=second.departure_time,
            same_station=True,
        )
        ho2 = can_handover(
            from_arrival_time=second.estimated_delivery_eta or 0.0,
            to_departure_time=third.departure_time,
            same_station=True,
        )
        feasible = first.feasibility and second.feasibility and third.feasibility and ho1.feasible and ho2.feasible
        if not feasible and not (ho1.feasible and ho2.feasible):
            return None
        return DispatchCandidate(
            candidate_type=CandidateType.MULTILEG_3,
            vehicle_id=a.vehicle_id,
            driver_id=a.driver_id,
            route_id=f"{a.route_id}->{b.route_id}->{c.route_id}",
            shift_id=f"{a.shift_id}->{b.shift_id}->{c.shift_id}",
            departure_time=a.departure_time,
            execution_state=a.execution_state,
            current_location=a.current_location,
            pickup_service_point=order.pickup_service_point,
            delivery_service_point=order.delivery_service_point,
            remaining_cargo_capacity=min(
                a.remaining_cargo_capacity, b.remaining_cargo_capacity, c.remaining_cargo_capacity
            ),
            remaining_passenger_capacity=min(
                a.remaining_passenger_capacity,
                b.remaining_passenger_capacity,
                c.remaining_passenger_capacity,
            ),
            trip_detour_remaining_m=(
                a.trip_detour_remaining_m
                + b.trip_detour_remaining_m
                + c.trip_detour_remaining_m
            ),
            passenger_impact_budget_s=a.passenger_impact_budget_s,
            gap_index=a.gap_index,
            estimated_pickup_eta=first.estimated_pickup_eta,
            estimated_delivery_eta=third.estimated_delivery_eta,
            sla_slack_s=(
                None
                if order.delivery_deadline is None
                else order.delivery_deadline - (third.estimated_delivery_eta or 0.0)
            ),
            handover_count=2,
            reason_code="MULTILEG_OK" if feasible else "HANDOVER_INFEASIBLE",
            legs=(a.route_id, b.route_id, c.route_id),
            waiting_s=third.waiting_s,
            feasibility=feasible,
            cost_is_formal=first.cost_is_formal and second.cost_is_formal and third.cost_is_formal,
            detour_distance_m=first.detour_distance_m + second.detour_distance_m + third.detour_distance_m,
            passenger_impact_s=first.passenger_impact_s + second.passenger_impact_s + third.passenger_impact_s,
            incremental_cost=first.incremental_cost + second.incremental_cost + third.incremental_cost,
            economic_value=order.economic_value,
            per_leg_budget_ok=(
                first.detour_distance_m <= a.trip_detour_remaining_m + 1e-6
                and second.detour_distance_m <= b.trip_detour_remaining_m + 1e-6
                and third.detour_distance_m <= c.trip_detour_remaining_m + 1e-6
            ),
            detail={"handover": [ho1.reason_code, ho2.reason_code]},
        )

    def build_hold(self, order: DispatchOrder, *, reason_code: str = "HOLD_NO_CANDIDATE") -> DispatchCandidate:
        return DispatchCandidate(
            candidate_type=CandidateType.HOLD,
            vehicle_id=-1,
            driver_id=None,
            route_id="",
            shift_id="",
            departure_time=float("inf"),
            execution_state=TripExecutionState.PLANNED,
            current_location=None,
            pickup_service_point=order.pickup_service_point,
            delivery_service_point=order.delivery_service_point,
            remaining_cargo_capacity=0,
            remaining_passenger_capacity=0,
            trip_detour_remaining_m=0.0,
            passenger_impact_budget_s=0.0,
            gap_index=-1,
            estimated_pickup_eta=None,
            estimated_delivery_eta=None,
            sla_slack_s=None,
            reason_code=reason_code,
            feasibility=False,
            economic_value=order.economic_value,
        )


__all__ = [
    "CandidateType",
    "DispatchOrder",
    "TripView",
    "DispatchCandidate",
    "DispatchCandidateBuilder",
]
