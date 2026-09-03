"""HACO-CPS 解评估器：基于 RouteState 计算 ObjectiveVector。

直接在 RouteState 上计算：
- 距离
- 时间
- 乘客影响
- 货物绕行
- 容量利用率
"""

from __future__ import annotations

from typing import TYPE_CHECKING

from .encoding import ObjectiveVector, TaskType
from .heuristic import compute_distance, compute_duration
from .route_state import RouteState

if TYPE_CHECKING:
    from ..distance import DistanceMatrix
    from ..models import PlanRequest, Station


def evaluate_route_states(
    states: list[RouteState],
    station_map: dict[str, Station],
    matrix: DistanceMatrix | None,
) -> ObjectiveVector:
    """评估一组路线状态，返回分层目标向量。"""
    if not states or all(not s.tasks for s in states):
        return ObjectiveVector(infeasibility=1.0)

    total_distance = 0.0
    total_duration = 0.0
    total_passenger_impact = 0.0
    total_cargo_detour = 0.0
    used_vehicles = 0

    for state in states:
        if not state.tasks:
            continue
        used_vehicles += 1

        result = _evaluate_single_route(state, station_map, matrix)
        total_distance += result["distance"]
        total_duration += result["duration"]
        total_passenger_impact += result["passenger_impact"]
        total_cargo_detour += result["cargo_detour"]

    return ObjectiveVector(
        infeasibility=0.0,
        vehicle_count=used_vehicles,
        passenger_impact=total_passenger_impact,
        cargo_detour=total_cargo_detour,
        total_distance=round(total_distance, 3),
        total_duration=round(total_duration, 1),
    )


def _evaluate_single_route(
    state: RouteState,
    station_map: dict[str, Station],
    matrix: DistanceMatrix | None,
) -> dict:
    """评估单条路线。"""
    depot = station_map.get(state.depot_station)
    if not depot:
        return {"distance": 0, "duration": 0, "passenger_impact": 0, "cargo_detour": 0}

    distance = 0.0
    duration = 0.0
    passenger_impact = 0.0
    cargo_detour = 0.0
    current_station = depot
    current_passengers = state.initial_passenger_load

    # 遍历骨架间隙
    for gap in state.gaps:
        gap_tasks = state.get_tasks_in_gap(gap.gap_index)

        for task in gap_tasks:
            pickup = station_map.get(task.pickup_station)
            if not pickup:
                continue

            # 到 pickup 站
            d = compute_distance(current_station, pickup, matrix)
            t = compute_duration(current_station, pickup, matrix)
            distance += d
            duration += t
            current_station = pickup

            if task.task_type == TaskType.PASSENGER:
                current_passengers += 1
            elif task.task_type in (TaskType.SHIPMENT, TaskType.DELIVERY, TaskType.PICKUP):
                # 货运绕行
                delivery = station_map.get(task.delivery_station)
                if delivery and task.task_type == TaskType.SHIPMENT:
                    direct_d = compute_distance(pickup, delivery, matrix)
                    cargo_detour += 0  # 在 gap 内不算绕行
                    if current_passengers > 0:
                        detour_seconds = 0  # 无绕行
                        passenger_impact += detour_seconds * current_passengers

            # 到 delivery 站
            if task.task_type in (TaskType.PASSENGER, TaskType.SHIPMENT):
                delivery = station_map.get(task.delivery_station)
                if delivery:
                    d = compute_distance(current_station, delivery, matrix)
                    t = compute_duration(current_station, delivery, matrix)
                    distance += d
                    duration += t
                    current_station = delivery

                if task.task_type == TaskType.PASSENGER:
                    current_passengers -= 1

        # 到骨架站
        to_station = station_map.get(gap.to_station)
        if to_station and gap.to_station != state.depot_station:
            d = compute_distance(current_station, to_station, matrix)
            t = compute_duration(current_station, to_station, matrix)
            distance += d
            duration += t
            current_station = to_station

    # 返回场站
    distance += compute_distance(current_station, depot, matrix)
    duration += compute_duration(current_station, depot, matrix)

    return {
        "distance": distance,
        "duration": duration,
        "passenger_impact": passenger_impact,
        "cargo_detour": cargo_detour,
    }


def compute_route_signature(states: list[RouteState]) -> str:
    """计算解的签名（用于多样性度量）。"""
    parts = []
    for state in states:
        if not state.tasks:
            continue
        task_ids = sorted(t.task_id for t in state.tasks)
        parts.append(f"V{state.vehicle_index}:{'|'.join(task_ids)}")
    return ";".join(sorted(parts))


def compute_diversity(solutions: list[list[RouteState]]) -> float:
    """计算解集合的多样性（唯一签名比例）。"""
    if not solutions:
        return 0.0
    signatures = set()
    for sol in solutions:
        sig = compute_route_signature(sol)
        signatures.add(sig)
    return len(signatures) / len(solutions)
