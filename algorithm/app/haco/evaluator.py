"""HACO-CPS 1.4.0 解评估器：基于 RouteGenome.events 计算真实指标。

核心改进：
- 直接遍历事件序列，不再依赖 gap 模型
- cargo_detour 基于实际 detour（via - direct），不再恒为 0
- passenger_impact 基于实际 detour 秒数 × 当前乘客数
"""

from __future__ import annotations

from typing import TYPE_CHECKING

from .encoding import ObjectiveVector, TaskType
from .heuristic import compute_distance, compute_duration
from .route_genome import EventType, RouteGenome

if TYPE_CHECKING:
    from ..distance import DistanceMatrix
    from ..models import Station
    from .encoding import TaskBlock


def evaluate_route_genome(
    route: RouteGenome,
    tasks_by_id: dict[str, TaskBlock],
    station_map: dict,
    matrix,
) -> dict:
    """基于 RouteGenome.events 计算单车真实指标。

    Returns:
        dict with keys: distance, duration, passenger_impact, cargo_detour
    """
    total_distance = 0.0
    total_duration = 0.0
    passenger_impact = 0.0
    cargo_detour = 0.0

    current_passengers = 0

    events = route.events

    for i in range(1, len(events)):

        previous = events[i - 1]
        current = events[i]

        from_station = station_map[previous.station_id]
        to_station = station_map[current.station_id]

        segment_distance = compute_distance(
            from_station,
            to_station,
            matrix,
        )

        segment_duration = compute_duration(
            from_station,
            to_station,
            matrix,
        )

        total_distance += segment_distance
        total_duration += segment_duration

        if current.event_type == EventType.BOARD:
            current_passengers += 1
            continue

        if current.event_type == EventType.ALIGHT:
            current_passengers = max(
                0,
                current_passengers - 1,
            )
            continue

        if (
            current.event_type
            not in (
                EventType.PICKUP,
                EventType.DELIVER,
            )
        ):
            continue

        if not current.task_id:
            continue

        task = tasks_by_id.get(current.task_id)
        if task is None:
            continue

        if i + 1 >= len(events):
            continue

        next_station = station_map[
            events[i + 1].station_id
        ]

        direct = compute_distance(
            from_station,
            next_station,
            matrix,
        )

        via = (
            compute_distance(
                from_station,
                to_station,
                matrix,
            )
            +
            compute_distance(
                to_station,
                next_station,
                matrix,
            )
        )

        detour = max(
            0.0,
            via - direct,
        )

        if task.task_type.value in (
            "DELIVERY",
            "PICKUP",
            "SHIPMENT",
        ):
            cargo_detour += detour

            if current_passengers > 0:
                passenger_impact += (
                    detour
                    / 25.0
                    * 3600.0
                    * current_passengers
                )

    return {
        "distance": total_distance,
        "duration": total_duration,
        "passenger_impact": passenger_impact,
        "cargo_detour": cargo_detour,
    }


def evaluate_route_states(
    routes: list[RouteGenome],
    tasks_by_id: dict[str, TaskBlock],
    station_map: dict,
    matrix,
) -> ObjectiveVector:
    """评估一组 RouteGenome 路线，返回 ObjectiveVector。"""
    used_vehicles = 0

    total = {
        "distance": 0.0,
        "duration": 0.0,
        "passenger_impact": 0.0,
        "cargo_detour": 0.0,
    }

    for route in routes:

        if route.task_count() == 0:
            continue

        used_vehicles += 1

        metrics = evaluate_route_genome(
            route,
            tasks_by_id,
            station_map,
            matrix,
        )

        for key in total:
            total[key] += metrics[key]

    return ObjectiveVector(
        infeasibility=0.0,
        vehicle_count=used_vehicles,
        passenger_impact=total[
            "passenger_impact"
        ],
        cargo_detour=total[
            "cargo_detour"
        ],
        total_distance=round(
            total["distance"],
            3,
        ),
        total_duration=round(
            total["duration"],
            1,
        ),
    )


# ─── 旧版 RouteState 评估（保留兼容） ────────────────────────


def _evaluate_single_route_legacy(
    state,
    station_map: dict,
    matrix,
) -> dict:
    """旧版单条路线评估（基于 RouteState.gaps）。"""
    from .route_state import RouteState

    depot = station_map.get(state.depot_station)
    if not depot:
        return {"distance": 0, "duration": 0, "passenger_impact": 0, "cargo_detour": 0}

    distance = 0.0
    duration = 0.0
    passenger_impact = 0.0
    cargo_detour = 0.0
    current_station = depot
    current_passengers = state.initial_passenger_load

    for gap in state.gaps:
        gap_tasks = state.get_tasks_in_gap(gap.gap_index)

        for task in gap_tasks:
            pickup = station_map.get(task.pickup_station)
            if not pickup:
                continue

            d = compute_distance(current_station, pickup, matrix)
            t = compute_duration(current_station, pickup, matrix)
            distance += d
            duration += t
            current_station = pickup

            if task.task_type == TaskType.PASSENGER:
                current_passengers += 1
            elif task.task_type in (TaskType.SHIPMENT, TaskType.DELIVERY, TaskType.PICKUP):
                delivery = station_map.get(task.delivery_station)
                if delivery and task.task_type == TaskType.SHIPMENT:
                    direct_d = compute_distance(pickup, delivery, matrix)
                    detour = max(0.0, compute_distance(current_station, delivery, matrix) - direct_d)
                    cargo_detour += detour
                    if current_passengers > 0:
                        detour_seconds = detour / 25.0 * 3600.0
                        passenger_impact += detour_seconds * current_passengers

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

        to_station = station_map.get(gap.to_station)
        if to_station and gap.to_station != state.depot_station:
            d = compute_distance(current_station, to_station, matrix)
            t = compute_duration(current_station, to_station, matrix)
            distance += d
            duration += t
            current_station = to_station

    distance += compute_distance(current_station, depot, matrix)
    duration += compute_duration(current_station, depot, matrix)

    return {
        "distance": distance,
        "duration": duration,
        "passenger_impact": passenger_impact,
        "cargo_detour": cargo_detour,
    }


def compute_route_signature(routes: list[RouteGenome]) -> str:
    """计算解的签名（用于多样性度量）。"""
    parts = []
    for route in routes:
        if route.task_count() == 0:
            continue
        task_ids = sorted(
            e.task_id for e in route.events if e.task_id
        )
        parts.append(
            f"V{route.vehicle_index}:{'|'.join(task_ids)}"
        )
    return ";".join(sorted(parts))


def compute_diversity(solutions: list[list[RouteGenome]]) -> float:
    """计算解集合的多样性（唯一签名比例）。"""
    if not solutions:
        return 0.0
    signatures = set()
    for sol in solutions:
        sig = compute_route_signature(sol)
        signatures.add(sig)
    return len(signatures) / len(solutions)
