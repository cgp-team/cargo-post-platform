"""真实道路边际成本：正式成本只用 routingDistance，不用 Haversine。"""

from __future__ import annotations

from dataclasses import dataclass

from ..dispatch_opt.marginal_cost import DefaultCostModel, MarginalCostEvaluator
from ..dispatch_opt.models import MarginalCostBreakdown
from .models import RouteGeometryResult, is_formal_geometry


@dataclass
class RoadMarginalCost:
    delta_distance_m: float
    delta_duration_s: float
    delta_passenger_impact_s: float
    breakdown: MarginalCostBreakdown
    geometry_formal: bool
    net_benefit: float | None
    economic_efficiency: float | None


class RealRoadMarginalCostEvaluator:
    """用真实道路 baseline/candidate 计算 Δ；非 formal geometry 不参与正式比较。"""

    def __init__(self, model: DefaultCostModel | None = None):
        self._ev = MarginalCostEvaluator(model)

    def evaluate(
        self,
        *,
        baseline: RouteGeometryResult,
        candidate: RouteGeometryResult,
        passenger_count: int = 0,
        handover_count: int = 0,
        waiting_s: float = 0.0,
        economic_value: float | None = None,
    ) -> RoadMarginalCost:
        if not is_formal_geometry(candidate.status):
            return RoadMarginalCost(
                delta_distance_m=0.0,
                delta_duration_s=0.0,
                delta_passenger_impact_s=0.0,
                breakdown=MarginalCostBreakdown(),
                geometry_formal=False,
                net_benefit=None,
                economic_efficiency=None,
            )
        delta_d = max(0.0, candidate.distance_m - baseline.distance_m)
        delta_t = max(0.0, candidate.duration_s - baseline.duration_s)
        pax = delta_t * max(0, passenger_count)
        breakdown = self._ev.evaluate(
            delta_distance_m=delta_d,
            delta_duration_s=delta_t,
            delta_passenger_impact_s=pax,
            handover_count=handover_count,
            delta_waiting_s=waiting_s,
        )
        eff = self._ev.efficiency(economic_value, breakdown.total_incremental_cost)
        net = (
            economic_value - breakdown.total_incremental_cost
            if economic_value is not None
            else None
        )
        return RoadMarginalCost(
            delta_distance_m=delta_d,
            delta_duration_s=delta_t,
            delta_passenger_impact_s=pax,
            breakdown=breakdown,
            geometry_formal=True,
            net_benefit=net,
            economic_efficiency=eff,
        )
