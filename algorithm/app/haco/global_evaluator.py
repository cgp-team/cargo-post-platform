"""HACO-CPS 2.0 全局路线评估器：基于 GlobalRouteGenome 的评估。

评估指标：
- 距离
- 时间
- 乘客影响（总 + 最大）
- 货物绕行
- 回溯率
- 站点重访
- 方向反转
"""

from __future__ import annotations

from math import hypot
from typing import TYPE_CHECKING

from .encoding import TaskType
from .route_genome import GenomeEvaluation, GlobalRouteGenome

if TYPE_CHECKING:
    from ..distance import DistanceMatrix
    from ..models import Station

EPSILON = 1e-6


def evaluate_genome(
    genome: GlobalRouteGenome,
    station_map: dict[str, Station],
    matrix: DistanceMatrix | None,
) -> GenomeEvaluation:
    """评估 GlobalRouteGenome，返回分层目标向量。"""
    total_distance = 0.0
    total_duration = 0.0
    passenger_total_impact = 0.0
    passenger_max_impact = 0.0
    cargo_detour = 0.0
    used_vehicles = 0
    total_backtracking = 0.0
    total_forward = 0.0
    station_revisits = 0

    for vi, route in genome.vehicle_routes.items():
        if not route:
            continue
        used_vehicles += 1

        caps = genome.vehicle_caps.get(vi, (5, 4, 0, 0))
        p_cap, c_cap, p_init, c_init = caps

        result = _evaluate_route(
            vi, route, genome, station_map, matrix, p_cap, c_cap, p_init, c_init
        )

        if not result["feasible"]:
            return GenomeEvaluation(feasible=False)

        total_distance += result["distance"]
        total_duration += result["duration"]
        passenger_total_impact += result["passenger_impact"]
        passenger_max_impact = max(passenger_max_impact, result["max_passenger_impact"])
        cargo_detour += result["cargo_detour"]
        total_backtracking += result["backtracking"]
        total_forward += result["forward"]
        station_revisits += result["revisits"]

    backtracking_ratio = total_backtracking / max(total_forward + total_backtracking, EPSILON)

    normalized_cost = (
        (0.0 if True else 10000)  # feasible
        + used_vehicles * 1000
        + passenger_total_impact * 0.1
        + backtracking_ratio * 500
        + cargo_detour * 10
        + total_distance
        + total_duration * 0.01
    )

    return GenomeEvaluation(
        feasible=True,
        vehicle_count=used_vehicles,
        total_distance=round(total_distance, 3),
        total_duration=round(total_duration, 1),
        passenger_total_impact=round(passenger_total_impact, 1),
        passenger_max_impact=round(passenger_max_impact, 1),
        cargo_detour=round(cargo_detour, 3),
        backtracking_ratio=round(backtracking_ratio, 4),
        station_revisits=station_revisits,
        normalized_cost=normalized_cost,
    )


def _evaluate_route(
    vi: int,
    route: list[str],
    genome: GlobalRouteGenome,
    station_map: dict,
    matrix,
    p_cap: int,
    c_cap: int,
    p_init: int,
    c_init: int,
) -> dict:
    """评估单车路线。"""
    depot = station_map.get(genome.depot_station)
    if not depot:
        return {"feasible": False}

    distance = 0.0
    duration = 0.0
    passenger_impact = 0.0
    max_passenger_impact = 0.0
    cargo_detour = 0.0
    backtracking = 0.0
    forward = 0.0
    revisits = 0

    current_station = depot
    current_passengers = p_init
    current_cargo = c_init
    visited_stations = {genome.depot_station}
    prev_direction = 0.0  # 0 = unknown, 1 = forward, -1 = backward

    for task_id in route:
        task = genome.task_blocks.get(task_id)
        if not task:
            return {"feasible": False}

        # 到 pickup 站
        pickup = station_map.get(task.pickup_station)
        if not pickup:
            return {"feasible": False}

        d = _distance(current_station, pickup, matrix)
        t = _duration(current_station, pickup, matrix)
        distance += d
        duration += t

        # 方向和重访检测
        if task.pickup_station in visited_stations:
            revisits += 1
        visited_stations.add(task.pickup_station)

        # 回溯检测
        direction = _compute_direction(current_station, pickup)
        if prev_direction != 0 and direction != 0:
            if direction * prev_direction < 0:
                backtracking += d
            else:
                forward += d
        else:
            forward += d
        prev_direction = direction

        current_station = pickup

        if task.task_type == TaskType.PASSENGER:
            current_passengers += 1
            if current_passengers > p_cap:
                return {"feasible": False}

            # 到 delivery 站
            delivery = station_map.get(task.delivery_station)
            if not delivery:
                return {"feasible": False}

            d = _distance(current_station, delivery, matrix)
            t = _duration(current_station, delivery, matrix)
            distance += d
            duration += t

            if task.delivery_station in visited_stations:
                revisits += 1
            visited_stations.add(task.delivery_station)

            direction = _compute_direction(current_station, delivery)
            if prev_direction != 0 and direction != 0:
                if direction * prev_direction < 0:
                    backtracking += d
                else:
                    forward += d
            else:
                forward += d
            prev_direction = direction

            current_station = delivery
            current_passengers -= 1

        elif task.task_type == TaskType.SHIPMENT:
            current_cargo += task.size
            if current_cargo > c_cap:
                return {"feasible": False}

            # 到 delivery 站
            delivery = station_map.get(task.delivery_station)
            if not delivery:
                return {"feasible": False}

            d = _distance(current_station, delivery, matrix)
            t = _duration(current_station, delivery, matrix)
            distance += d
            duration += t

            # 绕行计算
            direct = _distance(pickup, delivery, matrix)
            detour = max(0.0, d - direct)
            cargo_detour += detour

            if current_passengers > 0:
                impact = detour / 25.0 * 3600 * current_passengers
                passenger_impact += impact
                max_passenger_impact = max(max_passenger_impact, impact / max(current_passengers, 1))

            if task.delivery_station in visited_stations:
                revisits += 1
            visited_stations.add(task.delivery_station)

            direction = _compute_direction(current_station, delivery)
            if prev_direction != 0 and direction != 0:
                if direction * prev_direction < 0:
                    backtracking += d
                else:
                    forward += d
            else:
                forward += d
            prev_direction = direction

            current_station = delivery
            current_cargo -= task.size

        elif task.task_type == TaskType.DELIVERY:
            current_cargo += task.size
            if current_cargo > c_cap:
                return {"feasible": False}

        elif task.task_type == TaskType.PICKUP:
            current_cargo += task.size
            if current_cargo > c_cap:
                return {"feasible": False}

    # 返回 depot
    d = _distance(current_station, depot, matrix)
    t = _duration(current_station, depot, matrix)
    distance += d
    duration += t

    return {
        "feasible": True,
        "distance": distance,
        "duration": duration,
        "passenger_impact": passenger_impact,
        "max_passenger_impact": max_passenger_impact,
        "cargo_detour": cargo_detour,
        "backtracking": backtracking,
        "forward": forward,
        "revisits": revisits,
    }


def _distance(a, b, matrix=None) -> float:
    """计算距离（欧氏度）。"""
    if matrix is not None:
        key = (a.stationId, b.stationId)
        if key in matrix:
            return matrix[key][0]
    return hypot(a.longitude - b.longitude, a.latitude - b.latitude)


def _duration(a, b, matrix=None) -> float:
    """计算行驶时间（秒）。"""
    if matrix is not None:
        key = (a.stationId, b.stationId)
        if key in matrix:
            return matrix[key][1] or 0.0
    dist = hypot(a.longitude - b.longitude, a.latitude - b.latitude)
    return dist / 25.0 * 3600


def _compute_direction(a, b) -> float:
    """计算方向（简单用经度差表示东西方向）。"""
    diff = b.longitude - a.longitude
    if abs(diff) < 1e-8:
        return 0.0
    return 1.0 if diff > 0 else -1.0


def compute_genome_signature(genome: GlobalRouteGenome) -> str:
    """计算基因组签名（用于多样性度量）。"""
    parts = []
    for vi in sorted(genome.vehicle_routes.keys()):
        route = genome.vehicle_routes[vi]
        if route:
            parts.append(f"V{vi}:{'|'.join(route)}")
    return ";".join(parts)


def compute_genome_diversity(genomes: list[GlobalRouteGenome]) -> float:
    """计算基因组集合的多样性。"""
    if len(genomes) <= 1:
        return 0.0
    signatures = set()
    for g in genomes:
        signatures.add(compute_genome_signature(g))
    return len(signatures) / len(genomes)
