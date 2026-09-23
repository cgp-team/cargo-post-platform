"""MarginalCostEvaluator：新增订单的增量成本分解。

不把所有维度压成一个神秘权重后直接当业务最优。
业务排序用结构化 Comparator；scalar 仅供搜索内部。
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol, TYPE_CHECKING

from .models import HandoverCostBreakdown, MarginalCostBreakdown

if TYPE_CHECKING:  # avoid circular import (route_cost_provider does not import this module)
    from .route_cost_provider import RouteCost

# Placeholder constants are FORBIDDEN in runtime cost paths (unit test stubs only).
FORBIDDEN_PLACEHOLDER_COSTS = (500.0, 60.0, 10.0, 2.0, 3.0)

# No real passenger timetable exists yet: passenger impact is a PROXY only.
PASSENGER_IMPACT_KIND_PROXY = "PROXY"


@dataclass
class RouteMarginalCost:
    """Real-road baseline -> candidate incremental cost conclusion."""

    formal: bool
    status: str
    reason_code: str
    delta_distance_m: float = 0.0
    delta_duration_s: float = 0.0
    delta_passenger_impact_s: float = 0.0
    delta_waiting_s: float = 0.0
    delta_handover_count: int = 0
    delta_trip_deviation_m: float = 0.0
    delta_delay_risk: float = 0.0
    passenger_impact_kind: str = PASSENGER_IMPACT_KIND_PROXY
    breakdown: MarginalCostBreakdown | None = None

    @property
    def incremental_cost(self) -> float:
        return self.breakdown.total_incremental_cost if self.breakdown else 0.0


class CostModel(Protocol):
    """可替换成本模型接口（RuralBus / ElectricBus / DieselBus...）。"""

    def distance_cost(self, delta_distance_m: float) -> float: ...
    def time_cost(self, delta_duration_s: float) -> float: ...
    def passenger_impact_cost(self, delta_passenger_impact_s: float) -> float: ...
    def driver_cost(self, delta_driver_time_s: float) -> float: ...
    def vehicle_cost(self, delta_vehicle_time_s: float) -> float: ...
    def handover_cost(self, handover_count: int, breakdown: HandoverCostBreakdown | None) -> float: ...
    def waiting_cost(self, delta_waiting_s: float) -> float: ...
    def risk_cost(self, delta_delay_risk: float) -> float: ...


@dataclass
class DefaultCostModel:
    """默认可解释成本模型（单位归一到“分钟当量”）。

    单价常数集中在一处，禁止各模块私自加权。不是业务报价。
    """
    # 元当量 / km（燃油+磨损代理，可被真实 CostModel 替换）
    yuan_per_km: float = 1.0
    # 元当量 / 分钟（司机+车辆时间）
    yuan_per_minute: float = 0.5
    # 乘客影响额外惩罚（元当量 / 分钟·人）
    yuan_per_passenger_minute: float = 0.8
    # 每次交接固定操作成本
    yuan_per_handover: float = 2.0
    # 等待时间折扣（低于在途时间）
    waiting_discount: float = 0.3
    # 风险系数
    risk_weight: float = 3.0

    def distance_cost(self, delta_distance_m: float) -> float:
        return max(0.0, delta_distance_m) / 1000.0 * self.yuan_per_km

    def time_cost(self, delta_duration_s: float) -> float:
        return max(0.0, delta_duration_s) / 60.0 * self.yuan_per_minute

    def passenger_impact_cost(self, delta_passenger_impact_s: float) -> float:
        return max(0.0, delta_passenger_impact_s) / 60.0 * self.yuan_per_passenger_minute

    def driver_cost(self, delta_driver_time_s: float) -> float:
        return max(0.0, delta_driver_time_s) / 60.0 * self.yuan_per_minute

    def vehicle_cost(self, delta_vehicle_time_s: float) -> float:
        return max(0.0, delta_vehicle_time_s) / 60.0 * self.yuan_per_minute

    def handover_cost(self, handover_count: int, breakdown: HandoverCostBreakdown | None) -> float:
        base = max(0, handover_count) * self.yuan_per_handover
        if breakdown is not None:
            return base + breakdown.operational_cost + breakdown.total * 0.0
        return base

    def waiting_cost(self, delta_waiting_s: float) -> float:
        return max(0.0, delta_waiting_s) / 60.0 * self.yuan_per_minute * self.waiting_discount

    def risk_cost(self, delta_delay_risk: float) -> float:
        return max(0.0, delta_delay_risk) * self.risk_weight


class MarginalCostEvaluator:
    """计算“新增一个订单后系统多付出多少成本”。"""

    def __init__(self, model: CostModel | None = None):
        self.model = model or DefaultCostModel()

    def evaluate(
        self,
        *,
        delta_distance_m: float = 0.0,
        delta_duration_s: float = 0.0,
        delta_passenger_impact_s: float = 0.0,
        delta_cargo_usage: int = 0,
        delta_driver_time_s: float = 0.0,
        delta_vehicle_time_s: float = 0.0,
        handover_count: int = 0,
        handover_breakdown: HandoverCostBreakdown | None = None,
        delta_waiting_s: float = 0.0,
        delta_trip_deviation_m: float = 0.0,
        delta_delay_risk: float = 0.0,
    ) -> MarginalCostBreakdown:
        m = self.model
        # trip deviation 并入 distance（已是 ΔDistance 语义时避免双计）
        dist_for_cost = delta_distance_m
        if delta_trip_deviation_m > delta_distance_m:
            dist_for_cost = delta_trip_deviation_m

        return MarginalCostBreakdown(
            distance_cost=m.distance_cost(dist_for_cost),
            time_cost=m.time_cost(delta_duration_s),
            passenger_impact_cost=m.passenger_impact_cost(delta_passenger_impact_s),
            driver_cost=m.driver_cost(delta_driver_time_s if delta_driver_time_s else delta_duration_s),
            vehicle_cost=m.vehicle_cost(delta_vehicle_time_s if delta_vehicle_time_s else delta_duration_s),
            handover_cost=m.handover_cost(handover_count, handover_breakdown),
            waiting_cost=m.waiting_cost(delta_waiting_s),
            risk_cost=m.risk_cost(delta_delay_risk),
            delta_distance_m=delta_distance_m,
            delta_duration_s=delta_duration_s,
            delta_passenger_impact_s=delta_passenger_impact_s,
            delta_cargo_usage=delta_cargo_usage,
            delta_driver_time_s=delta_driver_time_s or delta_duration_s,
            delta_vehicle_time_s=delta_vehicle_time_s or delta_duration_s,
            delta_handover_count=handover_count,
            delta_waiting_s=delta_waiting_s,
            delta_trip_deviation_m=delta_trip_deviation_m,
            delta_delay_risk=delta_delay_risk,
        )

    @staticmethod
    def efficiency(economic_value: float | None, incremental_cost: float) -> float | None:
        """性价比 = economicValue / incrementalCost。缺失经济价值时返回 None，不造假价格。"""
        if economic_value is None or incremental_cost <= 0:
            return None
        return economic_value / incremental_cost

    # ------------------------------------------------------------------
    # Formal entry: real-road baseline vs candidate (DISPATCH_CORE_V047)
    # ------------------------------------------------------------------
    def evaluate_from_route_costs(
        self,
        *,
        baseline: "RouteCost",
        candidate: "RouteCost",
        passenger_count: int = 0,
        handover_count: int = 0,
        handover_breakdown: HandoverCostBreakdown | None = None,
        waiting_s: float = 0.0,
        trip_deviation_m: float = 0.0,
        delay_risk: float = 0.0,
        driver_time_s: float | None = None,
        vehicle_time_s: float | None = None,
    ) -> RouteMarginalCost:
        """BOTH sides must be formal; otherwise UNKNOWN (never fabricate cost)."""
        if not (baseline.is_formal and candidate.is_formal):
            reason = (
                "BASELINE_NOT_FORMAL"
                if not baseline.is_formal
                else "CANDIDATE_NOT_FORMAL"
            )
            return RouteMarginalCost(
                formal=False,
                status="UNKNOWN",
                reason_code=reason,
                passenger_impact_kind=PASSENGER_IMPACT_KIND_PROXY,
            )
        return self.evaluate_deltas(
            delta_distance_m=max(0.0, candidate.distance_m - baseline.distance_m),
            delta_duration_s=max(0.0, candidate.duration_s - baseline.duration_s),
            passenger_count=passenger_count,
            handover_count=handover_count,
            handover_breakdown=handover_breakdown,
            waiting_s=waiting_s,
            trip_deviation_m=trip_deviation_m,
            delay_risk=delay_risk,
            driver_time_s=driver_time_s,
            vehicle_time_s=vehicle_time_s,
        )

    def evaluate_deltas(
        self,
        *,
        delta_distance_m: float,
        delta_duration_s: float,
        passenger_count: int = 0,
        handover_count: int = 0,
        handover_breakdown: HandoverCostBreakdown | None = None,
        waiting_s: float = 0.0,
        trip_deviation_m: float = 0.0,
        delay_risk: float = 0.0,
        driver_time_s: float | None = None,
        vehicle_time_s: float | None = None,
    ) -> RouteMarginalCost:
        """Assemble a formal cost from real deltas (passenger impact is PROXY)."""
        pax_impact = max(0.0, delta_duration_s) * max(0, passenger_count)
        breakdown = self.evaluate(
            delta_distance_m=delta_distance_m,
            delta_duration_s=delta_duration_s,
            delta_passenger_impact_s=pax_impact,
            handover_count=handover_count,
            handover_breakdown=handover_breakdown,
            delta_waiting_s=waiting_s,
            delta_trip_deviation_m=trip_deviation_m,
            delta_delay_risk=delay_risk,
            delta_driver_time_s=driver_time_s or delta_duration_s,
            delta_vehicle_time_s=vehicle_time_s or delta_duration_s,
        )
        return RouteMarginalCost(
            formal=True,
            status="FORMAL",
            reason_code="FORMAL_OK",
            delta_distance_m=delta_distance_m,
            delta_duration_s=delta_duration_s,
            delta_passenger_impact_s=pax_impact,
            delta_waiting_s=waiting_s,
            delta_handover_count=handover_count,
            delta_trip_deviation_m=trip_deviation_m,
            delta_delay_risk=delay_risk,
            passenger_impact_kind=PASSENGER_IMPACT_KIND_PROXY,
            breakdown=breakdown,
        )
