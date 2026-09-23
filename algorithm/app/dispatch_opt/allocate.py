"""新订单动态分配入口：Reachability → Lock → Candidate → Cost → 解释。

供 solver / 调度执行层调用；不创建业务表。

DISPATCH_CORE_V047：
- 不再注入 500m / 60s / 10s / 2.0 / 3.0 占位成本；
- 真实增量成本通过 `cost_for_current` 或 `DynamicDispatchCoordinator` 注入；
- 提供 `route_provider` 时，恢复候选也走真实道路成本。
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Sequence

from .candidate_selector import TripCandidateSelector, TripContext
from .comparator import compare_trip_candidates
from .coordinator import DispatchPlan, DispatchRequest, DynamicDispatchCoordinator
from .explanation import explain_choice, explain_unreachable_recovery
from .marginal_cost import MarginalCostEvaluator
from .models import (
    MarginalCostBreakdown,
    TripCandidate,
)
from .reachability import (
    AccessFlags,
    LocationSnapshot,
    classify_reachability,
    plan_recovery,
)
from .route_cost_provider import RouteCostProvider
from .trip_lock import TripLockPolicy


@dataclass
class AllocationRequest:
    order_id: str
    pickup_station: str
    delivery_station: str
    economic_value: float | None = None
    is_high_value: bool = False
    pickup_service_point: str | None = None
    delivery_service_point: str | None = None
    original_pickup: str | None = None
    original_delivery: str | None = None


@dataclass
class AllocationResult:
    chosen: TripCandidate | None
    level: str
    reason_code: str
    explanation: str
    reachability_pickup: Any = None
    reachability_delivery: Any = None
    recovery_candidates: list[TripCandidate] | None = None
    cost_breakdown: MarginalCostBreakdown | None = None
    dispatch_plan: DispatchPlan | None = None


def _recovery_candidate(
    *,
    source_kind: str,
    ctx: TripContext,
    economic_value: float | None,
    reason_code: str,
) -> TripCandidate:
    """恢复候选：成本未知时显式标记，绝不填入占位价格。"""
    return TripCandidate(
        source_kind=source_kind,
        route_id=ctx.route_id,
        shift_id=ctx.shift_id,
        vehicle_id=ctx.vehicle_id,
        waiting_time_s=max(0.0, ctx.departure_time),
        feasibility=ctx.remaining_cargo_capacity > 0,
        reason_code=reason_code,
        economic_value=economic_value,
    ).with_efficiency()


def allocate_new_order(
    req: AllocationRequest,
    *,
    current_trips: Sequence[TripContext],
    future_trips: Sequence[TripContext],
    other_route_trips: Sequence[TripContext],
    location: LocationSnapshot | None = None,
    pickup_access: AccessFlags | None = None,
    delivery_access: AccessFlags | None = None,
    multileg_builder=None,
    nearest_station: TripCandidate | None = None,
    cost_evaluator: MarginalCostEvaluator | None = None,
    lock_policy: TripLockPolicy | None = None,
    cost_for_current: dict[int, MarginalCostBreakdown] | None = None,
    route_provider: RouteCostProvider | None = None,
    dispatch_request: DispatchRequest | None = None,
) -> AllocationResult:
    """Pickup 与 Delivery 都必须过可达性；任一侧不可达走 Recovery，而不是拒单。

    传入 `dispatch_request` 时整体委托给 `DynamicDispatchCoordinator`
    （真实增量成本 + 候选级可达性 + TripLock + 经济准入 + Decision Trace）。
    """
    if dispatch_request is not None:
        coordinator = DynamicDispatchCoordinator(
            route_provider=route_provider,
            cost_evaluator=cost_evaluator,
            lock_policy=lock_policy,
        )
        plan = coordinator.plan(dispatch_request)
        chosen = plan.chosen.to_trip_candidate() if plan.chosen is not None else None
        return AllocationResult(
            chosen=chosen,
            level=plan.level,
            reason_code=plan.reason_code,
            explanation=plan.explanation,
            cost_breakdown=None if chosen is None else chosen.cost_breakdown,
            dispatch_plan=plan,
        )

    pickup_access = pickup_access or AccessFlags()
    delivery_access = delivery_access or AccessFlags()
    cost_evaluator = cost_evaluator or MarginalCostEvaluator()
    lock_policy = lock_policy or TripLockPolicy()
    cost_for_current = cost_for_current or {}

    d_pu = classify_reachability(
        flags=pickup_access,
        location=location,
        service_point=req.pickup_service_point or req.pickup_station,
        original_point=req.original_pickup or req.pickup_station,
    )
    d_de = classify_reachability(
        flags=delivery_access,
        location=location,
        service_point=req.delivery_service_point or req.delivery_station,
        original_point=req.original_delivery or req.delivery_station,
    )

    def _recover(decision, label: str) -> AllocationResult:
        rec = plan_recovery(
            decision,
            future_trips=[
                _recovery_candidate(
                    source_kind="NEXT_TRIP",
                    ctx=t,
                    economic_value=req.economic_value,
                    reason_code="RECOVERY_SAME_ROUTE_FUTURE_TRIP",
                )
                for t in future_trips
            ],
            other_routes=[
                _recovery_candidate(
                    source_kind="OTHER_ROUTE",
                    ctx=t,
                    economic_value=req.economic_value,
                    reason_code="RECOVERY_OTHER_ROUTE",
                )
                for t in other_route_trips
            ],
            multileg=[],
            nearest_station=nearest_station,
        )
        ranked = compare_trip_candidates([c for c in rec if c.feasibility]) or rec
        chosen = ranked[0] if ranked else None
        return AllocationResult(
            chosen=chosen,
            level="RECOVERY",
            reason_code=f"{label}_UNREACHABLE:{decision.reason_code.value}",
            explanation=explain_unreachable_recovery(
                decision.reason_code.value,
                chosen,
                original_point=decision.original_point,
                service_point=decision.service_point,
            ),
            reachability_pickup=d_pu,
            reachability_delivery=d_de,
            recovery_candidates=rec,
        )

    # Delivery 不可达也必须响应（不能只查 pickup）
    if not d_pu.reachable and d_pu.status.value != "UNKNOWN":
        return _recover(d_pu, "PICKUP")
    if not d_de.reachable and d_de.status.value != "UNKNOWN":
        return _recover(d_de, "DELIVERY")

    # 可达：走 TripCandidateSelector（成本只接受真实注入值）
    sel = TripCandidateSelector(
        lock_policy=lock_policy,
        cost_evaluator=cost_evaluator,
        build_multileg=multileg_builder,
    )
    result = sel.generate(
        current_trips=current_trips,
        future_trips=future_trips,
        other_route_trips=other_route_trips,
        economic_value=req.economic_value,
        is_high_value=req.is_high_value,
        cost_for_current=cost_for_current,
        efficiency_for_current={
            vehicle_id: cost_evaluator.efficiency(
                req.economic_value, breakdown.total_incremental_cost
            )
            for vehicle_id, breakdown in cost_for_current.items()
        },
    )
    exp = (
        explain_choice(result.chosen, result.candidates)
        if result.chosen
        else result.explanation
    )
    return AllocationResult(
        chosen=result.chosen,
        level=result.level.value,
        reason_code=result.reason_code,
        explanation=exp,
        reachability_pickup=d_pu,
        reachability_delivery=d_de,
        cost_breakdown=(
            cost_for_current.get(result.chosen.vehicle_id)
            if result.chosen is not None
            else None
        ),
    )


__all__ = ["AllocationRequest", "AllocationResult", "allocate_new_order"]
