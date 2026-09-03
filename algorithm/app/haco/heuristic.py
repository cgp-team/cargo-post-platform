"""HACO-CPS 业务感知启发式函数。

不使用简单的 eta = 1/distance，而是综合：
- 距离增量
- 乘客影响
- 绕行距离
- 时间窗风险
- 骨架偏离惩罚
- 容量风险
"""

from __future__ import annotations

import math
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from .config import HacoConfig
    from .encoding import TaskBlock, VehicleRoute
    from ..distance import DistanceMatrix
    from ..models import Station

EPSILON = 1e-6


def _haversine_km(a: Station, b: Station) -> float:
    """两点间 Haversine 距离（km）。"""
    R = 6371.0
    lat1, lon1 = math.radians(a.latitude), math.radians(a.longitude)
    lat2, lon2 = math.radians(b.latitude), math.radians(b.longitude)
    dlat = lat2 - lat1
    dlon = lon2 - lon1
    h = math.sin(dlat / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin(dlon / 2) ** 2
    return R * 2 * math.asin(math.sqrt(h))


def compute_distance(a: Station, b: Station, matrix: DistanceMatrix | None) -> float:
    """计算两站距离（km）。"""
    if matrix is not None:
        key = (a.stationId, b.stationId)
        if key in matrix:
            return matrix[key][0]
    return _haversine_km(a, b)


def compute_duration(a: Station, b: Station, matrix: DistanceMatrix | None) -> float:
    """计算两站行驶时间（秒）。"""
    if matrix is not None:
        key = (a.stationId, b.stationId)
        if key in matrix:
            return matrix[key][1] or 0.0
    km = _haversine_km(a, b)
    return km / 25.0 * 3600  # EUCLIDEAN_AVG_SPEED_KMH = 25


def heuristic_score(
    task: TaskBlock,
    route: VehicleRoute,
    insert_pos: int,
    station_map: dict[str, Station],
    matrix: DistanceMatrix | None,
    config: HacoConfig,
    current_load_passenger: int,
    current_load_cargo: int,
) -> float:
    """计算将 task 插入 route 在 insert_pos 位置的启发式得分。

    得分越高越好（eta = 1 / (epsilon + cost)）。

    返回归一化成本（越小越好）。"""
    # 获取插入位置前后的站点
    depot_station = station_map.get("DEPOT") or list(station_map.values())[0]

    # 计算距离增量
    distance_increment = _estimate_distance_increment(
        task, route, insert_pos, station_map, matrix, depot_station
    )

    # 归一化距离（参考值：10km）
    norm_distance = distance_increment / 10.0

    # 乘客影响估算
    passenger_impact = _estimate_passenger_impact(
        task, route, insert_pos, station_map, matrix, depot_station, current_load_passenger
    )
    norm_passenger = passenger_impact / 300.0  # 参考值：5分钟

    # 绕行距离
    detour = _estimate_detour(task, route, insert_pos, station_map, matrix, depot_station)
    norm_detour = detour / 5.0  # 参考值：5km

    # 时间窗风险
    time_risk = _estimate_time_risk(task, route, insert_pos, station_map, matrix, depot_station)
    norm_time = time_risk

    # 骨架偏离惩罚
    skeleton_penalty = _skeleton_penalty(task, route, station_map)
    norm_skeleton = skeleton_penalty

    # 加权成本
    cost = (
        config.w_distance * norm_distance
        + config.w_passenger_impact * norm_passenger
        + config.w_detour * norm_detour
        + config.w_time_risk * norm_time
        + config.w_skeleton_penalty * norm_skeleton
    )

    return max(cost, EPSILON)


def _estimate_distance_increment(
    task: TaskBlock,
    route: VehicleRoute,
    insert_pos: int,
    station_map: dict[str, Station],
    matrix: DistanceMatrix | None,
    depot_station: Station,
) -> float:
    """估算将 task 插入 insert_pos 的距离增量。"""
    insertions = route.insertions

    # 获取前一站和后一站
    if insert_pos == 0:
        prev_station = depot_station
    else:
        prev_insertion = insertions[insert_pos - 1]
        prev_station = station_map.get(prev_insertion.task.delivery_station) or depot_station

    if insert_pos >= len(insertions):
        next_station = depot_station
    else:
        next_insertion = insertions[insert_pos]
        next_station = station_map.get(next_insertion.task.pickup_station) or depot_station

    pickup_station = station_map.get(task.pickup_station)
    delivery_station = station_map.get(task.delivery_station)
    if not pickup_station or not delivery_station:
        return 100.0  # 惩罚

    # 原距离
    orig_dist = compute_distance(prev_station, next_station, matrix)

    # 新距离：prev -> pickup -> delivery -> next
    new_dist = (
        compute_distance(prev_station, pickup_station, matrix)
        + compute_distance(pickup_station, delivery_station, matrix)
        + compute_distance(delivery_station, next_station, matrix)
    )

    return max(0.0, new_dist - orig_dist)


def _estimate_passenger_impact(
    task: TaskBlock,
    route: VehicleRoute,
    insert_pos: int,
    station_map: dict[str, Station],
    matrix: DistanceMatrix | None,
    depot_station: Station,
    current_passengers: int,
) -> float:
    """估算插入任务对车上乘客的影响（秒）。"""
    if current_passengers <= 0:
        return 0.0
    if task.task_type == "PASSENGER":
        return 0.0  # 乘客任务本身不影响其他乘客

    # 货运任务的绕行时间
    detour_km = _estimate_detour(task, route, insert_pos, station_map, matrix, depot_station)
    detour_seconds = detour_km / 25.0 * 3600  # 估算
    return detour_seconds * current_passengers


def _estimate_detour(
    task: TaskBlock,
    route: VehicleRoute,
    insert_pos: int,
    station_map: dict[str, Station],
    matrix: DistanceMatrix | None,
    depot_station: Station,
) -> float:
    """估算绕行距离（km）。"""
    insertions = route.insertions

    if insert_pos == 0:
        prev_station = depot_station
    else:
        prev_insertion = insertions[insert_pos - 1]
        prev_station = station_map.get(prev_insertion.task.delivery_station) or depot_station

    if insert_pos >= len(insertions):
        next_station = depot_station
    else:
        next_insertion = insertions[insert_pos]
        next_station = station_map.get(next_insertion.task.pickup_station) or depot_station

    pickup_station = station_map.get(task.pickup_station)
    delivery_station = station_map.get(task.delivery_station)
    if not pickup_station or not delivery_station:
        return 100.0

    direct = compute_distance(prev_station, next_station, matrix)
    via = (
        compute_distance(prev_station, pickup_station, matrix)
        + compute_distance(pickup_station, delivery_station, matrix)
        + compute_distance(delivery_station, next_station, matrix)
    )
    return max(0.0, via - direct)


def _estimate_time_risk(
    task: TaskBlock,
    route: VehicleRoute,
    insert_pos: int,
    station_map: dict[str, Station],
    matrix: DistanceMatrix | None,
    depot_station: Station,
) -> float:
    """估算时间窗风险（0~1 归一化）。"""
    # 简化：绕行越大风险越高
    detour = _estimate_detour(task, route, insert_pos, station_map, matrix, depot_station)
    return min(1.0, detour / 20.0)


def _skeleton_penalty(
    task: TaskBlock,
    route: VehicleRoute,
    station_map: dict[str, Station],
) -> float:
    """骨架偏离惩罚（0~1）。"""
    if not route.skeleton:
        return 0.0
    skeleton_set = set(route.skeleton)
    penalty = 0.0
    if task.pickup_station not in skeleton_set:
        penalty += 0.5
    if task.delivery_station not in skeleton_set:
        penalty += 0.5
    return penalty
