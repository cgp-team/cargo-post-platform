"""DynamicDispatchCoordinator：动态调度唯一主入口（DISPATCH_CORE_V047）。

标准流程：

    NEW_ORDER
      → Service Point Resolve
      → Candidate Build
      → Candidate-specific Reachability
      → TripLock
      → Real Marginal Cost
      → Passenger Constraint
      → SLA / ETA
      → Economic Admission
      → Candidate Compare
      → Current / Next / Other / MultiLeg
      → DispatchPlan
      → Decision Trace

约束：main / solver / candidate_selector 不得各自维护一套动态排序逻辑；
新订单统一走本入口。默认不直接跑 Global HACO，仅在低成本候选全部不可行且
订单高价值 / SLA 高紧急 / 存在全局优化空间时才升级，并记录 trigger reason。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Sequence

from .candidate_builder import (
    CandidateType,
    DispatchCandidate,
    DispatchCandidateBuilder,
    DispatchOrder,
    TripView,
)
from .comparator import compare_trip_candidates
from .decision_trace import DecisionTrace, DecisionTraceRecord
from .economic_policy import (
    EconomicAdmission,
    EconomicPolicy,
    EconomicPolicyInput,
)
from .flexibility import FlexibilityInput, estimate_flexibility
from .marginal_cost import MarginalCostEvaluator
from .reachability import (
    CandidateReachability,
    CandidateReachabilityInput,
    classify_candidate_reachability,
)
from .route_cost_provider import RouteCostProvider
from .trip_lock import TripLockPolicy, is_on_planned_route


@dataclass
class DispatchRequest:
    order: DispatchOrder
    current_trips: Sequence[TripView] = ()
    future_trips: Sequence[TripView] = ()
    other_route_trips: Sequence[TripView] = ()
    nearest_station_trip: TripView | None = None
    max_passenger_impact_s: float | None = None
    sla_urgency: float = 0.0
    global_haco_available: bool = False


@dataclass
class DispatchPlan:
    order_id: str
    status: str  # ASSIGNED / HOLD / MANUAL_REVIEW
    level: str
    chosen: DispatchCandidate | None
    candidates: list[DispatchCandidate]
    reason_code: str
    explanation: str
    trace: DecisionTrace
    global_haco_trigger_reason: str | None = None

    def as_dict(self) -> dict:
        return {
            "orderId": self.order_id,
            "status": self.status,
            "level": self.level,
            "reasonCode": self.reason_code,
            "explanation": self.explanation,
            "chosen": None
            if self.chosen is None
            else {
                "candidateId": self.chosen.candidate_id,
                "type": self.chosen.candidate_type.value,
                "routeId": self.chosen.route_id,
                "shiftId": self.chosen.shift_id,
                "vehicleId": self.chosen.vehicle_id,
                "costIsFormal": self.chosen.cost_is_formal,
                "incrementalDistanceM": self.chosen.detour_distance_m,
                "incrementalDurationS": self.chosen.detour_duration_s,
                "passengerImpactS": self.chosen.passenger_impact_s,
                "incrementalCost": self.chosen.incremental_cost,
                "reasonCode": self.chosen.reason_code,
            },
            "globalHacoTriggerReason": self.global_haco_trigger_reason,
            "trace": self.trace.as_dict(),
        }


class DynamicDispatchCoordinator:
    """动态调度唯一主入口。"""

    def __init__(
        self,
        *,
        route_provider: RouteCostProvider | None = None,
        cost_evaluator: MarginalCostEvaluator | None = None,
        lock_policy: TripLockPolicy | None = None,
        economic_policy: EconomicPolicy | None = None,
        builder: DispatchCandidateBuilder | None = None,
        now: float = 0.0,
        max_passenger_impact_s: float | None = None,
    ):
        self.builder = builder or DispatchCandidateBuilder(
            route_provider=route_provider, now=now
        )
        self.cost_evaluator = cost_evaluator or MarginalCostEvaluator()
        self.lock_policy = lock_policy or TripLockPolicy()
        self.economic_policy = economic_policy or EconomicPolicy()
        self.now = now
        self.max_passenger_impact_s = max_passenger_impact_s
        self.global_haco_trigger_count = 0

    # ── public API ──
    def plan(self, req: DispatchRequest) -> DispatchPlan:
        order = req.order
        trace = DecisionTrace(order.order_id)

        candidates = self._build_candidates(req)
        evaluated: list[tuple[DispatchCandidate, CandidateReachability, EconomicAdmission]] = []

        for cand in candidates:
            reach = self._reachability(cand)
            economic = self._economic(cand, req, reach)
            feasible = self._apply_lock_and_constraints(cand, reach, economic, req)
            cand.feasibility = feasible
            evaluated.append((cand, reach, economic.admission))
            trace.add(self._record(cand, reach, economic, feasible))

        ranked = self._rank(evaluated)
        if ranked:
            chosen = ranked[0]
            trace.mark_selected(chosen.candidate_id, level=self._level_of(chosen))
            return DispatchPlan(
                order_id=order.order_id,
                status="ASSIGNED",
                level=self._level_of(chosen),
                chosen=chosen,
                candidates=[c for c, _, _ in evaluated],
                reason_code=chosen.reason_code,
                explanation=trace.why_selected(),
                trace=trace,
            )

        # 无可行候选：按梯度升级，绝不直接全局重算
        trigger = self._global_haco_trigger(req, candidates)
        if trigger is not None:
            self.global_haco_trigger_count += 1
            return DispatchPlan(
                order_id=order.order_id,
                status="HOLD",
                level="LEVEL_4_GLOBAL_HACO",
                chosen=None,
                candidates=[c for c, _, _ in evaluated],
                reason_code="NO_FEASIBLE_CANDIDATE_ESCALATE",
                explanation="低成本候选全部不可行，升级 Global HACO。",
                trace=trace,
                global_haco_trigger_reason=trigger,
            )

        hold = self.builder.build_hold(order)
        trace.add(
            DecisionTraceRecord(
                order_id=order.order_id,
                candidate_id=hold.candidate_id,
                candidate_type=hold.candidate_type.value,
                feasible=False,
                reason_code="HOLD_NO_FEASIBLE_CANDIDATE",
            )
        )
        return DispatchPlan(
            order_id=order.order_id,
            status="HOLD",
            level="LEVEL_5_HOLD",
            chosen=None,
            candidates=[c for c, _, _ in evaluated],
            reason_code="HOLD_NO_FEASIBLE_CANDIDATE",
            explanation="全部候选不可行，挂起并进入人工/下一轮动态调度。",
            trace=trace,
        )

    def flexibility_of(self, candidates: Sequence[DispatchCandidate]) -> str:
        inp = FlexibilityInput(
            feasible_current_trips=sum(
                1 for c in candidates if c.feasibility and c.candidate_type == CandidateType.CURRENT_TRIP
            ),
            feasible_future_trips=sum(
                1
                for c in candidates
                if c.feasibility and c.candidate_type in (CandidateType.NEXT_TRIP, CandidateType.LATER_TRIP)
            ),
            feasible_other_routes=sum(
                1 for c in candidates if c.feasibility and c.candidate_type == CandidateType.OTHER_ROUTE
            ),
            feasible_multileg=sum(
                1
                for c in candidates
                if c.feasibility and c.candidate_type in (CandidateType.MULTILEG_2, CandidateType.MULTILEG_3)
            ),
        )
        return estimate_flexibility(inp).level.value

    # ── internals ──
    def _build_candidates(self, req: DispatchRequest) -> list[DispatchCandidate]:
        b = self.builder
        order = req.order
        out: list[DispatchCandidate] = []
        out.extend(b.build_current(req.current_trips, order))
        out.extend(b.build_future(req.future_trips, order))
        out.extend(b.build_other(req.other_route_trips, order))
        out.extend(
            b.build_multileg(
                list(req.future_trips) + list(req.other_route_trips),
                list(req.other_route_trips) or list(req.future_trips),
                order,
            )
        )
        out.extend(b.build_nearest_station(req.nearest_station_trip, order))
        return out

    def _reachability(self, cand: DispatchCandidate) -> CandidateReachability:
        view = cand.detail.get("trip_view")
        inp = CandidateReachabilityInput(
            vehicle_id=cand.vehicle_id,
            route_id=cand.route_id,
            shift_id=cand.shift_id,
            execution_state=cand.execution_state,
            service_point=cand.pickup_service_point,
            original_point=cand.pickup_service_point,
            network_known=getattr(view, "network_known", True),
            road_reachable=getattr(view, "road_reachable", True),
            vehicle_access=getattr(view, "vehicle_access", True),
            user_access=getattr(view, "user_access", True),
            already_passed=getattr(view, "already_passed", False),
            location_fresh=getattr(view, "location_fresh", True),
            driver_shift_ok=getattr(view, "driver_shift_ok", True),
            cargo_capacity_ok=cand.remaining_cargo_capacity >= 1,
            has_transfer_option=cand.candidate_type
            in (CandidateType.MULTILEG_2, CandidateType.MULTILEG_3),
            marginal_detour_m=cand.detour_distance_m,
            remaining_detour_m=cand.trip_detour_remaining_m,
            cost_is_formal=cand.cost_is_formal,
        )
        return classify_candidate_reachability(inp)

    def _economic(
        self, cand: DispatchCandidate, req: DispatchRequest, reach: CandidateReachability
    ):
        return self.economic_policy.evaluate(
            EconomicPolicyInput(
                economic_value=cand.economic_value,
                incremental_cost=cand.incremental_cost,
                passenger_impact_s=cand.passenger_impact_s,
                detour_m=cand.detour_distance_m,
                duration_s=cand.detour_duration_s,
                waiting_s=cand.waiting_s,
                handover_count=cand.handover_count,
                sla_slack_s=cand.sla_slack_s,
                high_value=req.order.is_high_value,
                execution_state=cand.execution_state.value,
                capacity_available=cand.remaining_cargo_capacity >= max(1, req.order.quantity),
            )
        )

    def _apply_lock_and_constraints(
        self,
        cand: DispatchCandidate,
        reach: CandidateReachability,
        economic,
        req: DispatchRequest,
    ) -> bool:
        if cand.candidate_type == CandidateType.HOLD or not cand.feasibility:
            return False

        status = reach.status.value
        if status == "UNKNOWN":
            # UNKNOWN must never be silently treated as a normal candidate
            cand.reason_code = "UNKNOWN_PENDING_CONFIRMATION"
            return False
        if status != "REACHABLE" and status != "REACHABLE_WITH_DETOUR":
            cand.reason_code = reach.reason.value
            return False

        limit = (
            req.max_passenger_impact_s
            if req.max_passenger_impact_s is not None
            else self.max_passenger_impact_s
        )
        if limit is not None and cand.passenger_impact_s > limit:
            cand.reason_code = "PASSENGER_IMPACT_EXCEEDED"
            return False
        if cand.sla_slack_s is not None and cand.sla_slack_s < 0:
            cand.reason_code = "ETA_MISSED"
            return False

        view = cand.detail.get("trip_view")
        on_route = req.order.on_planned_route
        if on_route is None:
            remaining = getattr(view, "remaining_planned_stops", ()) or ()
            on_route = is_on_planned_route(
                pickup=cand.pickup_service_point,
                delivery=cand.delivery_service_point,
                remaining_planned_stops=remaining,
            )

        lock = self.lock_policy.decide_candidate(
            cand.execution_state,
            is_high_value=req.order.is_high_value,
            on_planned_route=on_route,
            cost_is_formal=cand.cost_is_formal,
            marginal_distance_m=cand.detour_distance_m,
            marginal_duration_s=cand.detour_duration_s,
            passenger_impact_s=cand.passenger_impact_s,
            detour_budget_ok=(
                cand.per_leg_budget_ok
                if cand.per_leg_budget_ok is not None
                else cand.detour_distance_m <= cand.trip_detour_remaining_m + 1e-6
            ),
            capacity_ok=cand.remaining_cargo_capacity >= max(1, req.order.quantity),
            driver_shift_ok=getattr(cand.detail.get("trip_view"), "driver_shift_ok", True),
            sla_safe=cand.sla_slack_s is None or cand.sla_slack_s >= 0,
            economic_admission=economic.admission.value,
            strict_passenger_limit_s=limit,
        )
        cand.reason_code = lock.reason_code
        return lock.allow_insert

    def _rank(
        self,
        evaluated: list[tuple[DispatchCandidate, CandidateReachability, EconomicAdmission]],
    ) -> list[DispatchCandidate]:
        feasible = [c for c, _, _ in evaluated if c.feasibility]
        if not feasible:
            return []
        ranked = compare_trip_candidates([c.to_trip_candidate() for c in feasible])
        by_id = {c.candidate_id: c for c in feasible}
        out: list[DispatchCandidate] = []
        for tc in ranked:
            cand = tc.raw
            if isinstance(cand, DispatchCandidate) and cand.candidate_id in by_id:
                out.append(cand)
        return out or sorted(
            feasible,
            key=lambda c: (c.passenger_impact_s, c.detour_distance_m, c.incremental_cost),
        )

    def _level_of(self, cand: DispatchCandidate) -> str:
        return {
            CandidateType.CURRENT_TRIP: "LEVEL_0_FAST_INSERT",
            CandidateType.NEXT_TRIP: "LEVEL_1_NEXT_TRIP",
            CandidateType.LATER_TRIP: "LEVEL_1_NEXT_TRIP",
            CandidateType.OTHER_ROUTE: "LEVEL_2_OTHER_ROUTE_OR_MULTILEG",
            CandidateType.MULTILEG_2: "LEVEL_2_OTHER_ROUTE_OR_MULTILEG",
            CandidateType.MULTILEG_3: "LEVEL_2_OTHER_ROUTE_OR_MULTILEG",
            CandidateType.NEAREST_STATION: "LEVEL_2_OTHER_ROUTE_OR_MULTILEG",
        }.get(cand.candidate_type, "LEVEL_5_HOLD")

    @staticmethod
    def _global_haco_trigger(
        req: DispatchRequest, candidates: Sequence[DispatchCandidate]
    ) -> str | None:
        """默认不跑 Global HACO；仅高价值 / SLA 高紧急 / 明显全局空间才升级。"""
        if not req.global_haco_available:
            return None
        if req.order.is_high_value:
            return "HIGH_VALUE_ORDER"
        if req.sla_urgency >= 0.8:
            return "SLA_HIGH_URGENCY"
        if len(candidates) == 0:
            return "CLEAR_GLOBAL_OPTIMIZATION_SPACE"
        return None

    @staticmethod
    def _record(cand, reach, economic, feasible: bool) -> DecisionTraceRecord:
        return DecisionTraceRecord(
            order_id="",
            candidate_id=cand.candidate_id,
            candidate_type=cand.candidate_type.value,
            vehicle_id=cand.vehicle_id,
            route_id=cand.route_id,
            shift_id=cand.shift_id,
            feasible=feasible,
            reason_code=cand.reason_code,
            pickup_eta=cand.estimated_pickup_eta,
            delivery_eta=cand.estimated_delivery_eta,
            sla_slack_s=cand.sla_slack_s,
            delta_distance_m=cand.detour_distance_m,
            delta_duration_s=cand.detour_duration_s,
            passenger_impact_s=cand.passenger_impact_s,
            economic_value=cand.economic_value,
            incremental_cost=cand.incremental_cost,
            efficiency=(
                None
                if cand.incremental_cost <= 0 or cand.economic_value is None
                else cand.economic_value / cand.incremental_cost
            ),
            handover_count=cand.handover_count,
            waiting_s=cand.waiting_s,
            selected=False,
            cost_formal=cand.cost_is_formal,
            detail={
                "reachability": reach.status.value,
                "reachabilityReason": reach.reason.value,
                "economicAdmission": economic.admission.value,
            },
        )


__all__ = [
    "DispatchRequest",
    "DispatchPlan",
    "DynamicDispatchCoordinator",
]
