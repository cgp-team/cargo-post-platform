"""订单灵活性估计（DISPATCH_CORE_V047）。

用于 candidate ordering / ALNS repair priority：优先处理「低灵活性 + 高 SLA 紧急」的订单，
避免搜索后期留下没人能接的订单。不用复杂机器学习。
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum


class FlexibilityLevel(str, Enum):
    VERY_LOW = "VERY_LOW"
    LOW = "LOW"
    MEDIUM = "MEDIUM"
    HIGH = "HIGH"


@dataclass
class FlexibilityInput:
    feasible_current_trips: int = 0
    feasible_future_trips: int = 0
    feasible_other_routes: int = 0
    feasible_multileg: int = 0
    feasible_service_points: int = 1


@dataclass
class OrderFlexibility:
    level: FlexibilityLevel
    score: float
    options: int

    def as_dict(self) -> dict:
        return {"level": self.level.value, "score": self.score, "options": self.options}


_WEIGHTS = {
    "current": 1.0,
    "future": 0.9,
    "other": 1.1,
    "multileg": 0.8,
    "service_point": 0.6,
}


def estimate_flexibility(inp: FlexibilityInput) -> OrderFlexibility:
    score = (
        inp.feasible_current_trips * _WEIGHTS["current"]
        + inp.feasible_future_trips * _WEIGHTS["future"]
        + inp.feasible_other_routes * _WEIGHTS["other"]
        + inp.feasible_multileg * _WEIGHTS["multileg"]
        + max(0, inp.feasible_service_points - 1) * _WEIGHTS["service_point"]
    )
    options = (
        inp.feasible_current_trips
        + inp.feasible_future_trips
        + inp.feasible_other_routes
        + inp.feasible_multileg
    )
    if options == 0:
        level = FlexibilityLevel.VERY_LOW
    elif score < 1.5:
        level = FlexibilityLevel.LOW
    elif score < 3.5:
        level = FlexibilityLevel.MEDIUM
    else:
        level = FlexibilityLevel.HIGH
    return OrderFlexibility(level=level, score=score, options=options)


def dispatch_priority(
    flex: OrderFlexibility,
    *,
    sla_urgency: float = 0.0,
    economic_value: float | None = None,
) -> float:
    """越大越应先处理。低灵活性 + 高 SLA 紧急优先。"""
    flexibility_penalty = {
        FlexibilityLevel.VERY_LOW: 100.0,
        FlexibilityLevel.LOW: 30.0,
        FlexibilityLevel.MEDIUM: 10.0,
        FlexibilityLevel.HIGH: 0.0,
    }[flex.level]
    value_term = 0.0
    if economic_value is not None:
        value_term = min(20.0, economic_value / 50.0)
    return flexibility_penalty + max(0.0, sla_urgency) * 50.0 + value_term


def should_protect_from_destroy(
    flex: OrderFlexibility,
    *,
    high_value: bool,
) -> bool:
    """ALNS destroy 保护：高价值且低灵活性 → 强保护；高价值但可转移 → 不必强保护。"""
    if not high_value:
        return False
    return flex.level in (FlexibilityLevel.VERY_LOW, FlexibilityLevel.LOW)


__all__ = [
    "FlexibilityLevel",
    "FlexibilityInput",
    "OrderFlexibility",
    "estimate_flexibility",
    "dispatch_priority",
    "should_protect_from_destroy",
]
