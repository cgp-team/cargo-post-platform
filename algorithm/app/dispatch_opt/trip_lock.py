"""TripLockPolicy：发车前可优化，发车后默认冻结。

发车后两条并列例外：
1. 顺路插入——订单就在本车调度路线上（`on_planned_route`）；
2. 高价值实时插入——性价比高（`is_high_value`）。
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Sequence

from .models import MarginalCostBreakdown, TripExecutionState


def is_on_planned_route(
    *,
    pickup: str,
    delivery: str,
    remaining_planned_stops: Sequence[str],
) -> bool:
    """订单取送点都在本车剩余调度路线上 → 发车后可顺路插入。"""
    if not remaining_planned_stops:
        return False
    stops = set(remaining_planned_stops)
    return pickup in stops and delivery in stops


@dataclass
class LockDecision:
    allow_insert: bool
    reason_code: str
    level: str  # NORMAL / HIGH_VALUE_REALTIME / FUTURE_DISPATCH
    explanation: str = ""


class TripLockPolicy:
    """发车锁定策略（复用 Driver execution / Shift 状态，不新建发车系统）。

    发车后默认冻结，仅两条并列例外通道：
    1. **顺路插入**（`on_planned_route`）：订单就在本车调度路线上，扰动小即可插入；
    2. **高价值插入**（`is_high_value`）：性价比高，走更严的实时阈值。
    """

    def __init__(
        self,
        *,
        max_realtime_detour_m: float = 800.0,
        max_realtime_duration_s: float = 180.0,
        max_realtime_passenger_impact_s: float = 60.0,
        min_realtime_efficiency: float = 1.5,
        # 顺路插入阈值（已在调度路线上，允许比高价值通道更宽的扰动）
        max_on_route_detour_m: float = 1500.0,
        max_on_route_duration_s: float = 360.0,
        max_on_route_passenger_impact_s: float = 120.0,
    ):
        self.max_realtime_detour_m = max_realtime_detour_m
        self.max_realtime_duration_s = max_realtime_duration_s
        self.max_realtime_passenger_impact_s = max_realtime_passenger_impact_s
        self.min_realtime_efficiency = min_realtime_efficiency
        self.max_on_route_detour_m = max_on_route_detour_m
        self.max_on_route_duration_s = max_on_route_duration_s
        self.max_on_route_passenger_impact_s = max_on_route_passenger_impact_s

    def allows_normal_insert(self, state: TripExecutionState) -> bool:
        return state in (TripExecutionState.PLANNED, TripExecutionState.READY)

    def decide(
        self,
        state: TripExecutionState,
        *,
        is_high_value: bool = False,
        on_planned_route: bool = False,
        cost: MarginalCostBreakdown | None = None,
        efficiency: float | None = None,
        mandatory_stop_risk: bool = False,
        detour_budget_ok: bool = True,
        conflicts_existing_cargo: bool = False,
        driver_shift_ok: bool = True,
    ) -> LockDecision:
        if state in (TripExecutionState.COMPLETED, TripExecutionState.FAILED):
            return LockDecision(
                False, "TRIP_TERMINAL", "FUTURE_DISPATCH",
                "班次已结束，新订单进入未来调度。",
            )

        if self.allows_normal_insert(state):
            return LockDecision(
                True, "PRE_DEPARTURE_OPEN", "NORMAL",
                "发车前允许正常货运插入优化。",
            )

        # DEPARTED / IN_PROGRESS：默认冻结
        if mandatory_stop_risk:
            return LockDecision(
                False, "MANDATORY_STOP_RISK", "FUTURE_DISPATCH",
                "已发车，插入会影响后续 Mandatory Passenger Stop，转未来调度。",
            )
        if not detour_budget_ok:
            return LockDecision(
                False, "DETOUR_BUDGET_EXCEEDED", "FUTURE_DISPATCH",
                "已发车，且将超过当前班次绕行预算，转未来调度。",
            )
        if conflicts_existing_cargo:
            return LockDecision(
                False, "EXISTING_CARGO_CONFLICT", "FUTURE_DISPATCH",
                "已发车，且影响既有货运订单，转未来调度。",
            )
        if not driver_shift_ok:
            return LockDecision(
                False, "DRIVER_SHIFT_CONFLICT", "FUTURE_DISPATCH",
                "司机/班次冲突，转未来调度。",
            )

        # 例外一：订单就在本车调度路线上 → 顺路插入（不依赖高价值）
        if on_planned_route and not is_high_value:
            if cost is not None:
                if cost.delta_distance_m > self.max_on_route_detour_m:
                    return LockDecision(
                        False, "ON_ROUTE_DETOUR_TOO_LARGE", "FUTURE_DISPATCH",
                        "虽在调度路线上，但扰动过大，不顺路插入。",
                    )
                if cost.delta_duration_s > self.max_on_route_duration_s:
                    return LockDecision(
                        False, "ON_ROUTE_DURATION_TOO_LARGE", "FUTURE_DISPATCH",
                        "虽在调度路线上，但时间增量过大，不顺路插入。",
                    )
                if cost.delta_passenger_impact_s > self.max_on_route_passenger_impact_s:
                    return LockDecision(
                        False, "ON_ROUTE_PAX_IMPACT_TOO_LARGE", "FUTURE_DISPATCH",
                        "虽在调度路线上，但乘客影响超阈值，不顺路插入。",
                    )
            return LockDecision(
                True, "ON_ROUTE_REALTIME_INSERT", "ON_ROUTE_REALTIME",
                "订单在本车调度路线上，发车后允许顺路插入。",
            )

        if not is_high_value and not on_planned_route:
            return LockDecision(
                False, "LOCKED_ACTIVE_TRIP", "FUTURE_DISPATCH",
                "当前司机班次已发车锁定，普通新订单不直接追加，进入 GO_TO_FUTURE_DISPATCH。",
            )

        # 例外二：高价值实时插入（性价比高）
        if cost is not None:
            if cost.delta_distance_m > self.max_realtime_detour_m:
                return LockDecision(
                    False, "REALTIME_DETOUR_TOO_LARGE", "FUTURE_DISPATCH",
                    "高价值但绕行过大，不实时插入。",
                )
            if cost.delta_duration_s > self.max_realtime_duration_s:
                return LockDecision(
                    False, "REALTIME_DURATION_TOO_LARGE", "FUTURE_DISPATCH",
                    "高价值但时间增量过大，不实时插入。",
                )
            if cost.delta_passenger_impact_s > self.max_realtime_passenger_impact_s:
                return LockDecision(
                    False, "REALTIME_PAX_IMPACT_TOO_LARGE", "FUTURE_DISPATCH",
                    "高价值但乘客影响超严格阈值，不实时插入。",
                )
        if efficiency is not None and efficiency < self.min_realtime_efficiency:
            return LockDecision(
                False, "REALTIME_EFFICIENCY_LOW", "FUTURE_DISPATCH",
                "高价值判定不足以覆盖实时插入门槛。",
            )

        return LockDecision(
            True, "HIGH_VALUE_REALTIME_INSERT", "HIGH_VALUE_REALTIME",
            "高价值、低扰动，允许 Fast Feasible Insert 更新剩余计划。",
        )


    def decide_candidate(
        self,
        state: TripExecutionState,
        *,
        is_high_value: bool = False,
        on_planned_route: bool = False,
        cost_is_formal: bool = False,
        marginal_distance_m: float | None = None,
        marginal_duration_s: float | None = None,
        passenger_impact_s: float | None = None,
        detour_budget_ok: bool = True,
        capacity_ok: bool = True,
        driver_shift_ok: bool = True,
        sla_safe: bool = True,
        economic_admission: str | None = None,
        strict_passenger_limit_s: float | None = None,
        realtime_distance_limit_m: float | None = None,
        realtime_duration_limit_s: float | None = None,
    ) -> LockDecision:
        """Unified TripLock decision for a single candidate (P0-5 / section 10).

        DEPARTED / IN_PROGRESS normal orders are not inserted. Two parallel exceptions:

        1. **on_planned_route**（顺路插入）：订单就在本车调度路线上 → 允许插入，
           使用较宽的 on-route 阈值，不要求高价值。
        2. **is_high_value**（高价值插入）：性价比高 → 使用更严的实时阈值。

        Every rejection carries a reason_code.
        """
        if state in (TripExecutionState.COMPLETED, TripExecutionState.FAILED):
            return LockDecision(False, "TRIP_TERMINAL", "FUTURE_DISPATCH")

        if state in (TripExecutionState.PLANNED, TripExecutionState.READY):
            if not capacity_ok:
                return LockDecision(False, "VEHICLE_CAPACITY_UNAVAILABLE", "FUTURE_DISPATCH")
            if not detour_budget_ok:
                return LockDecision(False, "DETOUR_BUDGET_EXCEEDED", "FUTURE_DISPATCH")
            if not driver_shift_ok:
                return LockDecision(False, "DRIVER_SHIFT_CONFLICT", "FUTURE_DISPATCH")
            if not sla_safe:
                return LockDecision(False, "SLA_MISSED", "FUTURE_DISPATCH")
            if cost_is_formal and not self._passenger_ok(passenger_impact_s, strict_passenger_limit_s):
                return LockDecision(False, "PASSENGER_IMPACT_EXCEEDED", "FUTURE_DISPATCH")
            return LockDecision(True, "PRE_DEPARTURE_OPEN", "NORMAL")

        # DEPARTED / IN_PROGRESS: frozen by default
        if not is_high_value and not on_planned_route:
            return LockDecision(False, "LOCKED_ACTIVE_TRIP", "FUTURE_DISPATCH")
        if not cost_is_formal:
            return LockDecision(False, "COST_NOT_FORMAL", "FUTURE_DISPATCH")
        if not capacity_ok:
            return LockDecision(False, "VEHICLE_CAPACITY_UNAVAILABLE", "FUTURE_DISPATCH")
        if not detour_budget_ok:
            return LockDecision(False, "DETOUR_BUDGET_EXCEEDED", "FUTURE_DISPATCH")
        if not driver_shift_ok:
            return LockDecision(False, "DRIVER_SHIFT_CONFLICT", "FUTURE_DISPATCH")
        if not sla_safe:
            return LockDecision(False, "SLA_MISSED", "FUTURE_DISPATCH")

        # 顺路插入：订单在调度路线上，用较宽阈值；同时兼容高价值（取更宽通道）
        if on_planned_route and not is_high_value:
            dist_limit = (
                realtime_distance_limit_m
                if realtime_distance_limit_m is not None
                else self.max_on_route_detour_m
            )
            dur_limit = (
                realtime_duration_limit_s
                if realtime_duration_limit_s is not None
                else self.max_on_route_duration_s
            )
            pax_limit = (
                strict_passenger_limit_s
                if strict_passenger_limit_s is not None
                else self.max_on_route_passenger_impact_s
            )
            if marginal_distance_m is None or marginal_distance_m > dist_limit:
                return LockDecision(False, "ON_ROUTE_DETOUR_TOO_LARGE", "FUTURE_DISPATCH")
            if marginal_duration_s is None or marginal_duration_s > dur_limit:
                return LockDecision(False, "ON_ROUTE_DURATION_TOO_LARGE", "FUTURE_DISPATCH")
            if not self._passenger_ok(passenger_impact_s, pax_limit):
                return LockDecision(False, "ON_ROUTE_PAX_IMPACT_TOO_LARGE", "FUTURE_DISPATCH")
            return LockDecision(True, "ON_ROUTE_REALTIME_INSERT", "ON_ROUTE_REALTIME")

        # 高价值实时插入（更严阈值）
        dist_limit = (
            realtime_distance_limit_m
            if realtime_distance_limit_m is not None
            else self.max_realtime_detour_m
        )
        dur_limit = (
            realtime_duration_limit_s
            if realtime_duration_limit_s is not None
            else self.max_realtime_duration_s
        )
        if marginal_distance_m is None or marginal_distance_m > dist_limit:
            return LockDecision(False, "REALTIME_DETOUR_TOO_LARGE", "FUTURE_DISPATCH")
        if marginal_duration_s is None or marginal_duration_s > dur_limit:
            return LockDecision(False, "REALTIME_DURATION_TOO_LARGE", "FUTURE_DISPATCH")
        if not self._passenger_ok(passenger_impact_s, strict_passenger_limit_s):
            return LockDecision(False, "REALTIME_PAX_IMPACT_TOO_LARGE", "FUTURE_DISPATCH")
        if economic_admission is not None and economic_admission != "ACCEPT":
            return LockDecision(
                False, f"ECONOMIC_ADMISSION_{economic_admission}", "FUTURE_DISPATCH"
            )
        return LockDecision(True, "HIGH_VALUE_REALTIME_INSERT", "HIGH_VALUE_REALTIME")

    def _passenger_ok(
        self, passenger_impact_s: float | None, strict_limit_s: float | None
    ) -> bool:
        limit = (
            strict_limit_s
            if strict_limit_s is not None
            else self.max_realtime_passenger_impact_s
        )
        if passenger_impact_s is None:
            return False
        return passenger_impact_s <= limit


class HighValueRealtimeInsertPolicy(TripLockPolicy):
    """别名：强调 DEPARTED 后的例外通道。"""
    pass
