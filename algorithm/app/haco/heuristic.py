"""HACO-CPS 1.2.0 业务感知启发式函数。

综合：
- 距离增量
- 乘客影响（总 + 最大）
- 绕行距离
- 时间窗风险
- 骨架偏离惩罚
- 容量风险
- 乘客敏感度
"""

from __future__ import annotations

import math
from math import hypot
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from .config import HacoConfig
    from .encoding import TaskBlock
    from .route_state import RouteState
    from ..distance import DistanceMatrix
    from ..models import Station

EPSILON = 1e-6


def _haversine_km(a, b) -> float:
    """两点间 Haversine 距离（km）。"""
    R = 6371.0
    lat1, lon1 = math.radians(a.latitude), math.radians(a.longitude)
    lat2, lon2 = math.radians(b.latitude), math.radians(b.longitude)
    dlat = lat2 - lat1
    dlon = lon2 - lon1
    h = math.sin(dlat / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin(dlon / 2) ** 2
    return R * 2 * math.asin(math.sqrt(h))


def compute_distance(a, b, matrix=None) -> float:
    """计算两站距离（km 或 度，取决于 matrix）。
    无矩阵时使用欧氏度（与 baseline 口径一致）。"""
    if matrix is not None:
        key = (a.stationId, b.stationId)
        if key in matrix:
            return matrix[key][0]
    # 使用欧氏度（与 baseline 口径一致）
    return hypot(a.longitude - b.longitude, a.latitude - b.latitude)


def compute_duration(a, b, matrix=None) -> float:
    """计算两站行驶时间（秒）。
    无矩阵时使用欧氏度估算（与 baseline 口径一致）。"""
    if matrix is not None:
        key = (a.stationId, b.stationId)
        if key in matrix:
            return matrix[key][1] or 0.0
    # 使用欧氏度估算（与 baseline 口径一致）
    dist = hypot(a.longitude - b.longitude, a.latitude - b.latitude)
    return dist / 25.0 * 3600


def compute_insertion_score(
    task: TaskBlock,
    state: RouteState,
    gap_index: int,
    station_map: dict,
    matrix,
    config: HacoConfig,
) -> float:
    """计算插入的综合启发式得分（越小越好）。"""
    delta_dist = _compute_delta_distance(task, state, gap_index, station_map, matrix)
    p_impact = _compute_passenger_impact(task, state, gap_index, station_map, matrix)
    c_detour = _compute_cargo_detour(task, state, gap_index, station_map, matrix)
    time_risk = _compute_time_risk(task, state, gap_index, station_map, matrix)
    cap_risk = _compute_capacity_risk(task, state)
    skel_penalty = _skeleton_penalty(task, state)

    # 归一化
    norm_dist = delta_dist / 10.0
    norm_passenger = p_impact / 300.0
    norm_detour = c_detour / 5.0

    cost = (
        config.w_distance * norm_dist
        + config.w_passenger_impact * norm_passenger
        + config.w_detour * norm_detour
        + config.w_time_risk * time_risk
        + config.w_skeleton_penalty * skel_penalty
        + config.w_capacity_risk * cap_risk
    )

    return max(cost, EPSILON)


def _compute_delta_distance(task, state, gap_index, station_map, matrix) -> float:
    """计算插入任务后的距离增量。"""
    gaps = state.gaps
    if gap_index >= len(gaps):
        return 100.0
    gap = gaps[gap_index]

    from_station = station_map.get(gap.from_station)
    to_station = station_map.get(gap.to_station)
    pickup = station_map.get(task.pickup_station)
    delivery = station_map.get(task.delivery_station)

    if not all([from_station, to_station, pickup, delivery]):
        return 100.0

    orig = compute_distance(from_station, to_station, matrix)
    new = (compute_distance(from_station, pickup, matrix)
           + compute_distance(pickup, delivery, matrix)
           + compute_distance(delivery, to_station, matrix))

    return max(0.0, new - orig)


def _compute_passenger_impact(task, state, gap_index, station_map, matrix) -> float:
    """计算插入任务对车上乘客的影响（秒）。"""
    if task.task_type.value == "PASSENGER":
        return 0.0

    gap_tasks = state.get_tasks_in_gap(gap_index)
    passenger_count = state.initial_passenger_load
    for t in gap_tasks:
        if t.task_type.value == "PASSENGER":
            passenger_count += 1

    if passenger_count <= 0:
        return 0.0

    detour_km = _compute_cargo_detour(task, state, gap_index, station_map, matrix)
    detour_seconds = detour_km / 25.0 * 3600
    return detour_seconds * passenger_count


def _compute_cargo_detour(task, state, gap_index, station_map, matrix) -> float:
    """计算货物绕行距离（km）。"""
    gaps = state.gaps
    if gap_index >= len(gaps):
        return 0.0
    gap = gaps[gap_index]

    from_station = station_map.get(gap.from_station)
    to_station = station_map.get(gap.to_station)
    pickup = station_map.get(task.pickup_station)
    delivery = station_map.get(task.delivery_station)

    if not all([from_station, to_station, pickup, delivery]):
        return 0.0

    direct = compute_distance(from_station, to_station, matrix)
    via = (compute_distance(from_station, pickup, matrix)
           + compute_distance(pickup, delivery, matrix)
           + compute_distance(delivery, to_station, matrix))

    return max(0.0, via - direct)


def _compute_time_risk(task, state, gap_index, station_map, matrix) -> float:
    """计算时间窗风险（0~1）。"""
    detour = _compute_cargo_detour(task, state, gap_index, station_map, matrix)
    # 绕行越大风险越高
    return min(1.0, detour / 20.0)


def _compute_capacity_risk(task, state) -> float:
    """计算容量风险（0~1）。
    插入任务后剩余 slack 越小风险越高。"""
    if task.task_type.value == "PASSENGER":
        p_load = state.initial_passenger_load
        for t in state.tasks:
            if t.task_type.value == "PASSENGER":
                p_load += 1
        remaining = state.passenger_capacity - p_load - 1
        if remaining < 0:
            return 1.0
        return 1.0 - (remaining / max(1, state.passenger_capacity))
    else:
        c_load = state.initial_cargo_load
        for t in state.tasks:
            if t.task_type.value in ("SHIPMENT", "DELIVERY", "PICKUP"):
                c_load += t.size
        remaining = state.cargo_capacity - c_load - task.size
        if remaining < 0:
            return 1.0
        return 1.0 - (remaining / max(1, state.cargo_capacity))


def _skeleton_penalty(task, state) -> float:
    """骨架偏离惩罚（0~1）。"""
    if not state.skeleton:
        return 0.0
    skeleton_set = set(state.skeleton)
    penalty = 0.0
    if task.pickup_station not in skeleton_set:
        penalty += 0.5
    if task.delivery_station not in skeleton_set:
        penalty += 0.5
    return penalty
