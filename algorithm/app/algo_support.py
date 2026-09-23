"""算法侧补全：增量评估 / 恢复成本 / 迭代预算 / 位置访问适配 / 同route 邻域。

覆盖第一阶段遗留与第二阶段接入缺口，保持与 FeasibilityEngine / ObjectiveVector 一致。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Callable, Sequence

from .dispatch_opt.marginal_cost import DefaultCostModel, MarginalCostEvaluator
from .dispatch_opt.models import (
    HandoverCostBreakdown,
    MarginalCostBreakdown,
    ReachabilityReason,
)
from .haco.encoding import ObjectiveVector, TaskBlock
from .haco.evaluator import calculate_passenger_impact, evaluate_route_genome


# ═══════════════════════════════════════════════════════════════
# 1. Incremental Evaluator（debug 可与 full recompute 对照）
# ═══════════════════════════════════════════════════════════════


@dataclass
class IncrementalScore:
    distance: float
    duration: float
    passenger_impact: float
    cargo_detour: float

    def to_metrics(self) -> dict[str, float]:
        return {
            "distance": self.distance,
            "duration": self.duration,
            "passenger_impact": self.passenger_impact,
            "cargo_detour": self.cargo_detour,
        }


class IncrementalEvaluator:
    """对单 route 维护增量分数；DEBUG=True 时与全量重算对照。

    只缓存不会因 route 状态失真的数据：station 坐标 / matrix。
    任何 insert/remove 后必须 invalidate。
    """

    def __init__(self, station_map: dict, matrix=None, *, debug: bool = False):
        self.station_map = station_map
        self.matrix = matrix
        self.debug = debug
        self._cache: dict[str, IncrementalScore] = {}

    def invalidate(self, route_key: str) -> None:
        self._cache.pop(route_key, None)

    def full_score(self, route, tasks_by_id: dict[str, TaskBlock], initial_passenger_load: int = 0) -> IncrementalScore:
        m = calculate_passenger_impact(
            route, tasks_by_id, self.station_map, self.matrix, initial_passenger_load
        )
        return IncrementalScore(
            distance=m["distance"],
            duration=m["duration"],
            passenger_impact=m["passenger_impact"],
            cargo_detour=m["cargo_detour"],
        )

    def score(
        self,
        route_key: str,
        route,
        tasks_by_id: dict[str, TaskBlock],
        initial_passenger_load: int = 0,
    ) -> IncrementalScore:
        """增量路径：命中缓存直接返回；否则全量算并缓存。

        路线事件变化时调用方必须 invalidate，否则返回陈旧值。
        debug 模式下每次与全量重算比对，不一致则抛 AssertionError。
        """
        cached = self._cache.get(route_key)
        if cached is not None:
            if self.debug:
                fresh = self.full_score(route, tasks_by_id, initial_passenger_load)
                if abs(fresh.distance - cached.distance) > 1e-6:
                    raise AssertionError(
                        f"incremental_score != full_recompute_score: "
                        f"inc={cached} full={fresh}"
                    )
            return cached

        fresh = self.full_score(route, tasks_by_id, initial_passenger_load)
        self._cache[route_key] = fresh
        return fresh


# ═══════════════════════════════════════════════════════════════
# 2. RecoveryCostEvaluator（不可达恢复专用成本）
# ═══════════════════════════════════════════════════════════════


@dataclass
class RecoveryCostBreakdown:
    reroute_distance_m: float = 0.0
    reroute_duration_s: float = 0.0
    additional_passenger_impact_s: float = 0.0
    extra_driver_time_s: float = 0.0
    extra_vehicle_usage_s: float = 0.0
    transfer_cost: float = 0.0
    waiting_cost: float = 0.0
    customer_action_cost: float = 0.0
    risk_cost: float = 0.0

    @property
    def total(self) -> float:
        return (
            self.reroute_distance_m / 1000.0
            + self.reroute_duration_s / 60.0 * 0.5
            + self.additional_passenger_impact_s / 60.0 * 0.8
            + self.extra_driver_time_s / 60.0 * 0.5
            + self.extra_vehicle_usage_s / 60.0 * 0.5
            + self.transfer_cost
            + self.waiting_cost
            + self.customer_action_cost
            + self.risk_cost
        )


class RecoveryCostEvaluator:
    def evaluate(
        self,
        *,
        reroute_distance_m: float = 0.0,
        reroute_duration_s: float = 0.0,
        additional_passenger_impact_s: float = 0.0,
        extra_driver_time_s: float = 0.0,
        extra_vehicle_usage_s: float = 0.0,
        transfer_count: int = 0,
        waiting_s: float = 0.0,
        needs_customer_action: bool = False,
        failure_risk: float = 0.0,
    ) -> RecoveryCostBreakdown:
        return RecoveryCostBreakdown(
            reroute_distance_m=max(0.0, reroute_distance_m),
            reroute_duration_s=max(0.0, reroute_duration_s),
            additional_passenger_impact_s=max(0.0, additional_passenger_impact_s),
            extra_driver_time_s=max(0.0, extra_driver_time_s),
            extra_vehicle_usage_s=max(0.0, extra_vehicle_usage_s),
            transfer_cost=max(0, transfer_count) * 2.0,
            waiting_cost=max(0.0, waiting_s) / 60.0 * 0.15,
            customer_action_cost=5.0 if needs_customer_action else 0.0,
            risk_cost=max(0.0, failure_risk) * 3.0,
        )


# ═══════════════════════════════════════════════════════════════
# 3. SearchBudget：迭代/评估次数预算（替代纯 wall-clock）
# ═══════════════════════════════════════════════════════════════


@dataclass
class SearchBudget:
    """确定性搜索预算。

    max_evaluations / max_iterations 优先于 time_limit_s，
    消除批量测试下因 wall-clock 截断导致的抖动。
    """

    max_iterations: int = 50
    max_evaluations: int = 10_000
    time_limit_s: float | None = None
    iterations: int = 0
    evaluations: int = 0
    _t0: float | None = None

    def start(self, now: float) -> None:
        self._t0 = now
        self.iterations = 0
        self.evaluations = 0

    def tick_iteration(self) -> bool:
        self.iterations += 1
        return self.iterations <= self.max_iterations

    def tick_evaluation(self) -> bool:
        self.evaluations += 1
        return self.evaluations <= self.max_evaluations

    def expired(self, now: float | None = None) -> bool:
        if self.iterations > self.max_iterations:
            return True
        if self.evaluations > self.max_evaluations:
            return True
        if self.time_limit_s is not None and now is not None and self._t0 is not None:
            if now - self._t0 > self.time_limit_s:
                return True
        return False


# ═══════════════════════════════════════════════════════════════
# 4. Location / Access 适配（StationAccessUtil + VehicleLocation 语义）
# ═══════════════════════════════════════════════════════════════


@dataclass
class AccessSnapshot:
    """映射 StationAccessUtil / ServiceMode 结论到算法 AccessFlags。"""

    user_access: bool = True
    vehicle_access: bool = True
    dispatch_enabled: bool = True
    road_reachable: bool = True
    network_known: bool = True
    service_mode: str | None = None  # DOOR_PICKUP / SAFE_ROADSIDE / NEAREST_STATION

    def to_flags(self, *, already_passed: bool = False, eta_feasible: bool = True,
                 detour_feasible: bool = True, cargo_capacity_ok: bool = True,
                 driver_shift_ok: bool = True):
        from .dispatch_opt.reachability import AccessFlags

        return AccessFlags(
            vehicle_access=self.vehicle_access and self.dispatch_enabled,
            user_access=self.user_access,
            road_reachable=self.road_reachable,
            network_known=self.network_known,
            already_passed=already_passed,
            eta_feasible=eta_feasible,
            detour_feasible=detour_feasible,
            driver_shift_ok=driver_shift_ok,
            cargo_capacity_ok=cargo_capacity_ok,
        )


@dataclass
class LocationFix:
    """VehicleLocationProvider → LocationSnapshot。"""

    vehicle_id: int
    lat: float
    lon: float
    timestamp: float
    now: float
    fresh_threshold_s: float = 60.0

    def to_snapshot(self):
        from .dispatch_opt.reachability import LocationSnapshot

        return LocationSnapshot(
            vehicle_id=self.vehicle_id,
            lat=self.lat,
            lon=self.lon,
            timestamp=self.timestamp,
            now=self.now,
            fresh_threshold_s=self.fresh_threshold_s,
        )


# ═══════════════════════════════════════════════════════════════
# 5. Trip Detour Budget 滚动累计
# ═══════════════════════════════════════════════════════════════


@dataclass
class TripDetourBudget:
    limit_m: float
    consumed_m: float = 0.0

    @property
    def remaining_m(self) -> float:
        return max(0.0, self.limit_m - self.consumed_m)

    def can_accept(self, delta_m: float) -> bool:
        return self.consumed_m + max(0.0, delta_m) <= self.limit_m + 1e-6

    def consume(self, delta_m: float) -> bool:
        if not self.can_accept(delta_m):
            return False
        self.consumed_m += max(0.0, delta_m)
        return True


# ═══════════════════════════════════════════════════════════════
# 6. 同 route SWAP / OR_OPT / PAIR_RELOCATE 邻域生成
# ═══════════════════════════════════════════════════════════════


class MoveKind(str, Enum):
    RELOCATE = "RELOCATE"
    SWAP = "SWAP"
    SAME_ROUTE_SWAP = "SAME_ROUTE_SWAP"
    OR_OPT = "OR_OPT"
    PAIR_RELOCATE = "PAIR_RELOCATE"


@dataclass(frozen=True)
class MoveSpec:
    kind: MoveKind
    vehicle_index: int
    other_vehicle_index: int | None
    task_ids: tuple[str, ...]
    pickup_positions: tuple[int, ...]
    delivery_positions: tuple[int | None, ...]


def enumerate_same_route_swap_positions(route, task_a: str, task_b: str) -> list[tuple[tuple[int, int | None], tuple[int, int | None]]]:
    """同 route 双任务换位的合法插入位（与 apply 相同描述）。"""
    pa = route.placements.get(task_a)
    pb = route.placements.get(task_b)
    if pa is None or pb is None:
        return []
    n = len(route.events)
    out = []
    # 有限候选：对方原位置附近 ± 全量 gap 端点，避免 O(n^2) 爆炸
    anchors = sorted({pa.pickup_index, pb.pickup_index, max(1, pa.pickup_index - 1), min(n - 1, pb.pickup_index + 1)})
    for p1 in anchors:
        for d1 in (None, min(n, p1 + 2)):
            if d1 is not None and d1 <= p1:
                continue
            for p2 in anchors:
                for d2 in (None, min(n, p2 + 2)):
                    if d2 is not None and d2 <= p2:
                        continue
                    out.append(((p1, d1), (p2, d2)))
                    if len(out) >= 16:
                        return out
    return out


def enumerate_or_opt_segments(route, task_ids: Sequence[str], max_seg: int = 3) -> list[tuple[str, ...]]:
    """OR_OPT：连续 2..max_seg 任务段整体反序/重插（预留接口）。"""
    ids = [t for t in task_ids if t in route.placements]
    segs: list[tuple[str, ...]] = []
    for L in range(2, max_seg + 1):
        for i in range(0, len(ids) - L + 1):
            segs.append(tuple(ids[i : i + L]))
    return segs


def enumerate_pair_relocate(
    route,
    pickup_task_id: str,
    delivery_task_id: str,
) -> list[tuple[int, int | None, int, int | None]]:
    """PAIR_RELOCATE：SHIPMENT 的 pickup+delivery 成对移动（预留接口）。"""
    pa = route.placements.get(pickup_task_id)
    pd = route.placements.get(delivery_task_id)
    if pa is None or pd is None:
        return []
    n = len(route.events)
    out = []
    for p in range(1, n):
        for d in range(p + 1, n + 1):
            out.append((p, d, p, d))
            if len(out) >= 8:
                return out
    return out


# ═══════════════════════════════════════════════════════════════
# 7. Cross-gap 配对校验
# ═══════════════════════════════════════════════════════════════


def allows_cross_gap_pair(pickup_gap: int, delivery_gap: int) -> bool:
    """pickup_gap <= delivery_gap 才合法（skeleton 顺序不破坏）。"""
    return pickup_gap <= delivery_gap


# ═══════════════════════════════════════════════════════════════
# 8. Exact Oracle 模型差异清单（用于报告与测试断言）
# ═══════════════════════════════════════════════════════════════

EXACT_ORACLE_SIMPLIFICATIONS = (
    "no_skeleton_gap",
    "no_terminal_return_invariant",
    "simplified_capacity_without_cargo_out_in_preloaded",
    "no_time_window_with_service_time",
    "distance_only_objective",
    "no_passenger_impact",
)


# ═══════════════════════════════════════════════════════════════
# 9. Segment Completion 状态映射（DispatchPlanItem.status）
# ═══════════════════════════════════════════════════════════════


class PlanItemStatus(str, Enum):
    PLANNED = "PLANNED"
    READY = "READY"
    DEPARTED = "DEPARTED"
    IN_PROGRESS = "IN_PROGRESS"
    ARRIVED = "ARRIVED"
    COMPLETED = "COMPLETED"
    FAILED = "FAILED"
    LOCKED_ACTIVE_TRIP = "LOCKED_ACTIVE_TRIP"


def is_locked_after_departure(status: PlanItemStatus | str) -> bool:
    s = status.value if isinstance(status, PlanItemStatus) else str(status)
    return s in ("DEPARTED", "IN_PROGRESS", "ARRIVED", "LOCKED_ACTIVE_TRIP")


def is_terminal(status: PlanItemStatus | str) -> bool:
    s = status.value if isinstance(status, PlanItemStatus) else str(status)
    return s in ("COMPLETED", "FAILED")


# ═══════════════════════════════════════════════════════════════
# 10. 动态 Benchmark 统计器
# ═══════════════════════════════════════════════════════════════


@dataclass
class DynamicBenchmarkStats:
    feasible_count: int = 0
    vehicle_count_sum: int = 0
    passenger_impact_sum: float = 0.0
    cargo_detour_sum: float = 0.0
    distance_sum: float = 0.0
    duration_sum: float = 0.0
    runtime_sum: float = 0.0
    exception_count: int = 0
    invariant_failure_count: int = 0
    current_trip_acceptance: int = 0
    future_trip_reassignment: int = 0
    realtime_high_value_insertion: int = 0
    waiting_sum_s: float = 0.0
    handover_count_sum: int = 0
    handover_success: int = 0
    incremental_cost_sum: float = 0.0
    economic_efficiency_sum: float = 0.0
    economic_efficiency_n: int = 0
    passenger_sla_violation: int = 0
    locked_plan_violation: int = 0
    replan_count: int = 0
    local_replan_count: int = 0
    global_replan_count: int = 0
    orders_saved_by_next_trip: int = 0
    orders_saved_by_multi_leg: int = 0
    reachability_failure_count: int = 0
    temporarily_unreachable_count: int = 0
    same_trip_recovery_count: int = 0
    future_trip_recovery_count: int = 0
    other_route_recovery_count: int = 0
    multi_leg_recovery_count: int = 0
    nearest_station_recovery_count: int = 0
    manual_review_count: int = 0
    unserviceable_count: int = 0
    recovery_cost_sum: float = 0.0
    recovery_delay_sum_s: float = 0.0
    # 稳定性硬指标
    locked_task_violation: int = 0
    mandatory_stop_violation: int = 0
    task_loss: int = 0
    task_duplication: int = 0
    invalid_handover: int = 0
    driver_conflict: int = 0
    shift_conflict: int = 0
    capacity_violation: int = 0
    detour_budget_violation: int = 0
    seeds: list[dict] = field(default_factory=list)

    def record_seed(self, row: dict) -> None:
        self.seeds.append(row)

    def summary(self) -> dict[str, Any]:
        n = max(1, len(self.seeds))
        return {
            "feasible_rate": self.feasible_count / n,
            "mean_vehicles": self.vehicle_count_sum / n,
            "mean_passenger_impact": self.passenger_impact_sum / n,
            "mean_cargo_detour": self.cargo_detour_sum / n,
            "mean_distance": self.distance_sum / n,
            "mean_duration": self.duration_sum / n,
            "mean_runtime": self.runtime_sum / n,
            "exception_count": self.exception_count,
            "invariant_failure_count": self.invariant_failure_count,
            "current_trip_acceptance_rate": self.current_trip_acceptance / n,
            "future_trip_reassignment_rate": self.future_trip_reassignment / n,
            "realtime_high_value_insertion_rate": self.realtime_high_value_insertion / n,
            "average_waiting_time_s": self.waiting_sum_s / n,
            "handover_count": self.handover_count_sum,
            "handover_success_rate": (
                self.handover_success / self.handover_count_sum
                if self.handover_count_sum
                else 1.0
            ),
            "average_incremental_cost": self.incremental_cost_sum / n,
            "average_economic_efficiency": (
                self.economic_efficiency_sum / self.economic_efficiency_n
                if self.economic_efficiency_n
                else None
            ),
            "passenger_sla_violation_rate": self.passenger_sla_violation / n,
            "locked_plan_violation_count": self.locked_plan_violation,
            "replan_count": self.replan_count,
            "local_replan_count": self.local_replan_count,
            "global_replan_count": self.global_replan_count,
            "orders_saved_by_next_trip": self.orders_saved_by_next_trip,
            "orders_saved_by_multi_leg": self.orders_saved_by_multi_leg,
            "recovery_success_rate": (
                (
                    self.same_trip_recovery_count
                    + self.future_trip_recovery_count
                    + self.other_route_recovery_count
                    + self.multi_leg_recovery_count
                    + self.nearest_station_recovery_count
                )
                / max(1, self.reachability_failure_count)
            ),
            "recovery_breakdown": {
                "same_trip": self.same_trip_recovery_count,
                "future_trip": self.future_trip_recovery_count,
                "other_route": self.other_route_recovery_count,
                "multi_leg": self.multi_leg_recovery_count,
                "nearest_station": self.nearest_station_recovery_count,
                "manual": self.manual_review_count,
                "unserviceable": self.unserviceable_count,
            },
            "stability": {
                "locked_task_violation": self.locked_task_violation,
                "mandatory_stop_violation": self.mandatory_stop_violation,
                "task_loss": self.task_loss,
                "task_duplication": self.task_duplication,
                "invalid_handover": self.invalid_handover,
                "driver_conflict": self.driver_conflict,
                "shift_conflict": self.shift_conflict,
                "capacity_violation": self.capacity_violation,
                "detour_budget_violation": self.detour_budget_violation,
            },
        }

    def assert_stable(self) -> None:
        s = self.summary()["stability"]
        bad = {k: v for k, v in s.items() if v}
        if bad:
            raise AssertionError(f"stability violations: {bad}")
