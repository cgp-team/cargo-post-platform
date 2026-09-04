"""统一业务目标比较：HACO / Baseline / HYBRID 共用同一套排序键。

六维严格 lexicographic（不使用权重混合）：
    1. infeasibility
    2. vehicle_count
    3. passenger_impact
    4. cargo_detour
    5. total_distance
    6. total_duration

normalized_cost 只允许用于内部启发式（pheromone / SA / heuristic），
绝不允许替代最终业务排序。
"""

from __future__ import annotations

from .haco.encoding import ObjectiveVector
from .models import VehiclePlan, StopAction


def solution_key(outcome) -> tuple:
    """从 SolveOutcome 提取业务排序键。

    - feasible 解排在 infeasible 前面
    - 同为 feasible 时按 6 维 ObjectiveVector 排序
    """
    if outcome.status != "feasible":
        return (1, float("inf"), float("inf"), float("inf"), float("inf"), float("inf"))

    obj = evaluate_solution_objective(outcome.vehicle_plans)
    return (0, *obj.key())


def evaluate_solution_objective(
    vehicle_plans: list[VehiclePlan],
) -> ObjectiveVector:
    """从 VehiclePlan 列表计算统一 6 维 ObjectiveVector。

    用于 HYBRID 比较：不管来源是 HACO 还是 Baseline，都用同一把尺子。
    """
    used_vehicles = sum(1 for p in vehicle_plans if len(p.stops) > 1)
    total_distance = 0.0
    total_duration = 0.0
    passenger_impact = 0.0
    cargo_detour = 0.0

    for plan in vehicle_plans:
        total_distance += plan.totalDistance
        stops = plan.stops

        for i, stop in enumerate(stops):
            if stop.segmentDuration is not None:
                total_duration += stop.segmentDuration

            # passenger impact from detour
            if stop.passengerImpact is not None:
                passenger_impact += stop.passengerImpact

            # cargo detour
            if stop.detourDistance is not None and stop.detourDistance > 0:
                cargo_detour += stop.detourDistance

    return ObjectiveVector(
        infeasibility=0.0,
        vehicle_count=used_vehicles,
        passenger_impact=round(passenger_impact, 3),
        cargo_detour=round(cargo_detour, 3),
        total_distance=round(total_distance, 3),
        total_duration=round(total_duration, 1),
    )
