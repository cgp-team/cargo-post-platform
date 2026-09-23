"""TripCandidateSelector / RemainingDispatchAllocator。

新订单候选顺序：
Current Planned/Active → Same Route Future → Other Route → MultiLeg → Hold。
发车后当前司机进入严格模式，不默认永远优先当前车辆。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Callable, Sequence

from .marginal_cost import MarginalCostEvaluator
from .models import (
    DispatchLevel,
    RecoveryAction,
    TripCandidate,
    TripExecutionState,
)
from .trip_lock import LockDecision, TripLockPolicy


@dataclass
class TripContext:
    route_id: str
    shift_id: str
    vehicle_id: int
    driver_id: int | None
    departure_time: float
    execution_state: TripExecutionState
    remaining_cargo_capacity: int = 1
    trip_detour_remaining_m: float = 5000.0
    passenger_impact_budget_s: float = 300.0
    gap_index: int = 0
    raw: Any = None


@dataclass
class SelectionResult:
    chosen: TripCandidate | None
    candidates: list[TripCandidate]
    level: DispatchLevel
    reason_code: str
    explanation: str


class TripCandidateSelector:
    """按优先级生成并比较候选（不修改 MultiLeg.Candidate）。"""

    def __init__(
        self,
        lock_policy: TripLockPolicy | None = None,
        cost_evaluator: MarginalCostEvaluator | None = None,
        build_current: Callable[..., list[TripCandidate]] | None = None,
        build_future: Callable[..., list[TripCandidate]] | None = None,
        build_other: Callable[..., list[TripCandidate]] | None = None,
        build_multileg: Callable[..., list[TripCandidate]] | None = None,
    ):
        self.lock_policy = lock_policy or TripLockPolicy()
        self.cost_evaluator = cost_evaluator or MarginalCostEvaluator()
        self.build_current = build_current
        self.build_future = build_future
        self.build_other = build_other
        self.build_multileg = build_multileg

    def generate(
        self,
        *,
        current_trips: Sequence[TripContext],
        future_trips: Sequence[TripContext],
        other_route_trips: Sequence[TripContext],
        economic_value: float | None = None,
        is_high_value: bool = False,
        cost_for_current: dict[int, Any] | None = None,
        efficiency_for_current: dict[int, float | None] | None = None,
    ) -> SelectionResult:
        candidates: list[TripCandidate] = []
        cost_for_current = cost_for_current or {}
        efficiency_for_current = efficiency_for_current or {}

        # 1) current trip
        for ctx in current_trips:
            lock: LockDecision = self.lock_policy.decide(
                ctx.execution_state,
                is_high_value=is_high_value,
                cost=cost_for_current.get(ctx.vehicle_id),
                efficiency=efficiency_for_current.get(ctx.vehicle_id),
            )
            if not lock.allow_insert:
                candidates.append(
                    TripCandidate(
                        source_kind="CURRENT_TRIP",
                        route_id=ctx.route_id,
                        shift_id=ctx.shift_id,
                        vehicle_id=ctx.vehicle_id,
                        driver_id=ctx.driver_id,
                        departure_time=ctx.departure_time,
                        feasibility=False,
                        reason_code=lock.reason_code,
                        explanation=lock.explanation,
                        economic_value=economic_value,
                    )
                )
                continue
            if self.build_current is not None:
                candidates.extend(self.build_current(ctx, economic_value))
            else:
                c = TripCandidate(
                    source_kind="CURRENT_TRIP",
                    route_id=ctx.route_id,
                    shift_id=ctx.shift_id,
                    vehicle_id=ctx.vehicle_id,
                    driver_id=ctx.driver_id,
                    departure_time=ctx.departure_time,
                    feasibility=True,
                    reason_code=lock.reason_code,
                    explanation=lock.explanation,
                    economic_value=economic_value,
                    gap_index=ctx.gap_index,
                ).with_efficiency()
                candidates.append(c)

        # 2) same route future
        if self.build_future is not None:
            candidates.extend(self.build_future(future_trips, economic_value))
        else:
            for ctx in future_trips:
                wait = max(0.0, ctx.departure_time)
                c = TripCandidate(
                    source_kind="NEXT_TRIP" if wait <= 3600 else "LATER_TRIP",
                    route_id=ctx.route_id,
                    shift_id=ctx.shift_id,
                    vehicle_id=ctx.vehicle_id,
                    driver_id=ctx.driver_id,
                    departure_time=ctx.departure_time,
                    waiting_time_s=wait,
                    feasibility=ctx.remaining_cargo_capacity > 0,
                    reason_code="FUTURE_TRIP" if ctx.remaining_cargo_capacity > 0 else "NO_CAPACITY",
                    economic_value=economic_value,
                ).with_efficiency()
                candidates.append(c)

        # 3) other route
        if self.build_other is not None:
            candidates.extend(self.build_other(other_route_trips, economic_value))
        else:
            for ctx in other_route_trips:
                wait = max(0.0, ctx.departure_time)
                c = TripCandidate(
                    source_kind="OTHER_ROUTE",
                    route_id=ctx.route_id,
                    shift_id=ctx.shift_id,
                    vehicle_id=ctx.vehicle_id,
                    driver_id=ctx.driver_id,
                    departure_time=ctx.departure_time,
                    waiting_time_s=wait,
                    feasibility=ctx.remaining_cargo_capacity > 0,
                    reason_code="OTHER_ROUTE" if ctx.remaining_cargo_capacity > 0 else "NO_CAPACITY",
                    economic_value=economic_value,
                ).with_efficiency()
                candidates.append(c)

        # 4) multi-leg
        if self.build_multileg is not None:
            candidates.extend(self.build_multileg(future_trips, other_route_trips, economic_value))

        return self._select(candidates)

    def _select(self, candidates: list[TripCandidate]) -> SelectionResult:
        feasible = [c for c in candidates if c.feasibility]
        if not feasible:
            # 不把失败变成“无法完成”：继续 SEARCH_FUTURE / MULTI_LEG 语义
            return SelectionResult(
                chosen=None,
                candidates=candidates,
                level=DispatchLevel.LEVEL_5_HOLD,
                reason_code="NO_FEASIBLE_CANDIDATE_SEARCH_FUTURE",
                explanation="当前无可行承运方案，保留候选并继续搜索未来班次/联运，而非直接失败。",
            )

        # 结构化比较：硬可行 → 乘客影响 → 等待/稳定性 → 增量成本 → 性价比
        def key(c: TripCandidate):
            eff = c.efficiency if c.efficiency is not None else 0.0
            return (
                c.passenger_impact_s,
                c.detour_distance_m,
                c.waiting_time_s,
                c.incremental_cost,
                -eff,
                c.handover_count,
            )

        ordered = sorted(feasible, key=key)
        chosen = ordered[0]
        if chosen.source_kind == "CURRENT_TRIP":
            level = DispatchLevel.LEVEL_0_FAST_INSERT
        elif chosen.source_kind in ("NEXT_TRIP", "LATER_TRIP"):
            level = DispatchLevel.LEVEL_1_NEXT_TRIP
        elif chosen.source_kind == "OTHER_ROUTE":
            level = DispatchLevel.LEVEL_2_OTHER_OR_MULTILEG
        else:
            level = DispatchLevel.LEVEL_2_OTHER_OR_MULTILEG

        return SelectionResult(
            chosen=chosen,
            candidates=candidates,
            level=level,
            reason_code=chosen.reason_code,
            explanation=chosen.explanation or f"选择 {chosen.source_kind} {chosen.route_id}/{chosen.shift_id}",
        )


class RemainingDispatchAllocator:
    """发车后新订单去向：高价值插入 → 未来班次 → 其他线路 → 联运 → 暂存。"""

    def __init__(self, selector: TripCandidateSelector):
        self.selector = selector

    def allocate(self, **kwargs) -> SelectionResult:
        return self.selector.generate(**kwargs)
