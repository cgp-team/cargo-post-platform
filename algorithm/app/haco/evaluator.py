"""HACO-CPS 解评估器：计算 ObjectiveVector 和完整指标。"""

from __future__ import annotations

import math
from typing import TYPE_CHECKING

from .encoding import ObjectiveVector, Solution, TaskInsertion, VehicleRoute
from .heuristic import compute_distance, compute_duration

if TYPE_CHECKING:
    from ..distance import DistanceMatrix
    from ..models import PlanRequest, Station, Vehicle


def evaluate_solution(
    solution: Solution,
    request: PlanRequest,
    station_map: dict[str, Station],
    matrix: DistanceMatrix | None,
) -> ObjectiveVector:
    """评估一个解，返回分层目标向量。"""
    if not solution.routes or all(r.task_count == 0 for r in solution.routes):
        return ObjectiveVector(infeasibility=1.0)

    total_distance = 0.0
    total_duration = 0.0
    total_passenger_impact = 0.0
    total_cargo_detour = 0.0
    used_vehicles = 0

    for route in solution.routes:
        if route.task_count == 0:
            continue
        used_vehicles += 1

        route_result = _evaluate_route(route, station_map, matrix, request.depot.stationId)
        total_distance += route_result["distance"]
        total_duration += route_result["duration"]
        total_passenger_impact += route_result["passenger_impact"]
        total_cargo_detour += route_result["cargo_detour"]

    return ObjectiveVector(
        infeasibility=0.0,
        vehicle_count=used_vehicles,
        passenger_impact=total_passenger_impact,
        cargo_detour=total_cargo_detour,
        total_distance=round(total_distance, 3),
        total_duration=round(total_duration, 1),
    )


def _evaluate_route(
    route: VehicleRoute,
    station_map: dict[str, Station],
    matrix: DistanceMatrix | None,
    depot_station_id: str,
) -> dict:
    """评估单条路线。"""
    depot = station_map.get(depot_station_id)
    if not depot:
        return {"distance": 0, "duration": 0, "passenger_impact": 0, "cargo_detour": 0}

    distance = 0.0
    duration = 0.0
    passenger_impact = 0.0
    cargo_detour = 0.0

    current_station = depot
    current_passengers = route.initial_passenger_load

    for insertion in route.insertions:
        task = insertion.task

        # 到 pickup 站
        pickup = station_map.get(task.pickup_station)
        if pickup:
            d = compute_distance(current_station, pickup, matrix)
            t = compute_duration(current_station, pickup, matrix)
            distance += d
            duration += t
            current_station = pickup

        if task.task_type == "PASSENGER":
            current_passengers += 1
        elif task.task_type in ("SHIPMENT", "DELIVERY", "PICKUP"):
            # 货运绕行
            if task.task_type == "SHIPMENT":
                delivery = station_map.get(task.delivery_station)
                if delivery:
                    d_direct = compute_distance(current_station, delivery, matrix)
                    d_via = compute_distance(current_station, pickup, matrix) + compute_distance(pickup, delivery, matrix) if pickup else 0
                    detour = max(0, d_via - d_direct)
                    cargo_detour += detour
                    if current_passengers > 0:
                        passenger_impact += detour / 25.0 * 3600 * current_passengers

        # 到 delivery 站（如果需要）
        if task.task_type in ("PASSENGER", "SHIPMENT"):
            delivery = station_map.get(task.delivery_station)
            if delivery:
                d = compute_distance(current_station, delivery, matrix)
                t = compute_duration(current_station, delivery, matrix)
                distance += d
                duration += t
                current_station = delivery

            if task.task_type == "PASSENGER":
                current_passengers -= 1

    # 返回场站
    distance += compute_distance(current_station, depot, matrix)
    duration += compute_duration(current_station, depot, matrix)

    return {
        "distance": distance,
        "duration": duration,
        "passenger_impact": passenger_impact,
        "cargo_detour": cargo_detour,
    }
