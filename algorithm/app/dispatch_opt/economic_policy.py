"""经济准入策略（DISPATCH_CORE_V047）。

只在 candidate **已经 feasible** 之后使用；不参与可行性判定，不用巨大 magic weight。
没有 `economicValue` 时必须正常工作（按可行性放行，标记 `FEASIBILITY_ONLY`）。

输出：`ACCEPT / DEFER / TRANSFER / HOLD / REJECT`
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum


class EconomicAdmission(str, Enum):
    ACCEPT = "ACCEPT"
    DEFER = "DEFER"
    TRANSFER = "TRANSFER"
    HOLD = "HOLD"
    REJECT = "REJECT"


@dataclass
class EconomicPolicyInput:
    economic_value: float | None = None
    incremental_cost: float = 0.0
    passenger_impact_s: float = 0.0
    detour_m: float = 0.0
    duration_s: float = 0.0
    waiting_s: float = 0.0
    handover_count: int = 0
    risk: float = 0.0
    sla_slack_s: float | None = None
    high_value: bool = False
    execution_state: str | None = None
    capacity_available: bool = True


@dataclass
class EconomicDecision:
    admission: EconomicAdmission
    net_value: float | None
    value_to_cost_ratio: float | None
    reason_code: str
    explanation: str = ""

    @property
    def accepted(self) -> bool:
        return self.admission == EconomicAdmission.ACCEPT


class EconomicPolicy:
    """可解释的经济准入；无经济价值时退化为可行性放行。"""

    def __init__(
        self,
        *,
        accept_ratio: float = 1.0,
        high_value_accept_ratio: float = 1.0,
        defer_ratio: float = 0.5,
        hold_waiting_s: float = 1800.0,
    ):
        self.accept_ratio = accept_ratio
        self.high_value_accept_ratio = high_value_accept_ratio
        self.defer_ratio = defer_ratio
        self.hold_waiting_s = hold_waiting_s

    def evaluate(self, inp: EconomicPolicyInput) -> EconomicDecision:
        # 无经济价值：不伪造价格，也不因此拒绝（兼容旧行为）
        if inp.economic_value is None:
            return EconomicDecision(
                admission=EconomicAdmission.ACCEPT,
                net_value=None,
                value_to_cost_ratio=None,
                reason_code="FEASIBILITY_ONLY_NO_ECONOMIC_VALUE",
                explanation="未提供 economicValue，仅按可行性放行。",
            )

        if not inp.capacity_available:
            return EconomicDecision(
                EconomicAdmission.HOLD,
                inp.economic_value,
                None,
                "CAPACITY_UNAVAILABLE",
                "运力不足，挂起等待下一班/其他线路。",
            )

        cost = max(0.0, inp.incremental_cost)
        if cost <= 0:
            return EconomicDecision(
                EconomicAdmission.ACCEPT,
                inp.economic_value,
                None,
                "ZERO_MARGINAL_COST",
                "增量成本为 0，直接接受。",
            )

        ratio = inp.economic_value / cost
        net = inp.economic_value - cost
        threshold = self.high_value_accept_ratio if inp.high_value else self.accept_ratio

        if ratio >= threshold:
            return EconomicDecision(
                EconomicAdmission.ACCEPT, net, ratio, "VALUE_COST_ACCEPT",
                f"性价比 {ratio:.2f} ≥ 阈值 {threshold:.2f}。",
            )
        if ratio >= self.defer_ratio:
            return EconomicDecision(
                EconomicAdmission.DEFER, net, ratio, "VALUE_COST_DEFER",
                f"性价比 {ratio:.2f} 偏低，延后到更优班次。",
            )
        if inp.handover_count > 0:
            return EconomicDecision(
                EconomicAdmission.TRANSFER, net, ratio, "VALUE_COST_TRANSFER",
                "直接成本不合算，转联运/其他线路。",
            )
        if inp.waiting_s >= self.hold_waiting_s:
            return EconomicDecision(
                EconomicAdmission.HOLD, net, ratio, "VALUE_COST_HOLD",
                "性价比不足且等待较久，挂起观察。",
            )
        return EconomicDecision(
            EconomicAdmission.REJECT, net, ratio, "VALUE_COST_REJECT",
            f"性价比 {ratio:.2f} 低于 {self.defer_ratio:.2f}，拒绝。",
        )


__all__ = [
    "EconomicAdmission",
    "EconomicPolicyInput",
    "EconomicDecision",
    "EconomicPolicy",
]
