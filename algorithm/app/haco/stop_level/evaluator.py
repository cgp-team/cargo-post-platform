"""Stop-Level 评估器：按 activity 序列扫描计算 objective。

关键区别于 task-level：
- 乘客可以 ride-through 中间站
- 同站可以有多活动
- 活动顺序影响载荷变化
"""

from __future__ import annotations

from math import hypot
from typing import TYPE_CHECKING

from .models import (
    Activity,
    ActionType,
    StopLevelEvaluation,
    StopLevelRoute,
    StopLevelSolution,
)

if TYPE_CHECKING:
    from ..distance import DistanceMatrix
    from ..models import Station

EPSILON = 1e-6


def evaluate_solution(
    solution: StopLevelSolution,
    station_map: dict[str, Station],
    matrix: DistanceMatrix | None = None,
) -> StopLevelEvaluation:
    """评估 stop-level 解。"""
    total_distance = 0.0
    total_duration = 0.0
    passenger_total_impact = 0.0
    passenger_max_impact = 0.0
    cargo_detour = 0.0
    used_vehicles = 0
    total_backtracking = 0.0
    total_forward = 0.0
    station_revisits = 0
    violations = []

    for vi, route in solution.routes.items():
        if not route.activities:
            continue
        used_vehicles += 1

        result = _evaluate_route(route, station_map, matrix, solution.depot_station)

        if not result["feasible"]:
            violations.extend(result.get("violations", []))
            return StopLevelEvaluation(
                feasible=False,
                violations=violations,
            )

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
        used_vehicles * 1000
        + passenger_total_impact * 0.1
        + backtracking_ratio * 500
        + cargo_detour * 10
        + total_distance
    )

    return StopLevelEvaluation(
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
    route: StopLevelRoute,
    station_map: dict[str, Station],
    matrix: DistanceMatrix | None,
    depot_station: str,
) -> dict:
    """评估单车路线。"""
    depot = station_map.get(depot_station)
    if not depot:
        return {"feasible": False, "violations": ["depot not found"]}

    distance = 0.0
    duration = 0.0
    passenger_impact = 0.0
    max_passenger_impact = 0.0
    cargo_detour = 0.0
    backtracking = 0.0
    forward = 0.0
    revisits = 0
    violations = []

    current_station = depot
    current_passengers = route.initial_passenger_load
    current_cargo = route.initial_cargo_load
    visited_stations = {depot_station}
    prev_direction = 0.0

    # 跟踪请求状态
    request_state = {}  # request_id -> {"boarded": bool, "alighted": bool, ...}

    for activity in route.activities:
        # 获取目标站点
        target = station_map.get(activity.station_id)
        if not target:
            violations.append(f"station {activity.station_id} not found")
            return {"feasible": False, "violations": violations}

        # 计算距离
        d = _distance(current_station, target, matrix)
        t = _duration(current_station, target, matrix)
        distance += d
        duration += t

        # 方向和重访检测
        if activity.station_id in visited_stations:
            revisits += 1
        visited_stations.add(activity.station_id)

        direction = _compute_direction(current_station, target)
        if prev_direction != 0 and direction != 0:
            if direction * prev_direction < 0:
                backtracking += d
            else:
                forward += d
        else:
            forward += d
        prev_direction = direction

        current_station = target

        # 执行动作
        if activity.action == ActionType.BOARD:
            current_passengers += 1
            if current_passengers > route.passenger_capacity:
                violations.append(f"passenger overflow at {activity.station_id}: {current_passengers} > {route.passenger_capacity}")
                return {"feasible": False, "violations": violations}
            req_id = activity.request_id
            if req_id:
                if req_id not in request_state:
                    request_state[req_id] = {"boarded": False, "alighted": False}
                if request_state[req_id]["boarded"]:
                    violations.append(f"double board for {req_id}")
                    return {"feasible": False, "violations": violations}
                request_state[req_id]["boarded"] = True

        elif activity.action == ActionType.ALIGHT:
            current_passengers -= 1
            if current_passengers < 0:
                violations.append(f"negative passenger at {activity.station_id}")
                return {"feasible": False, "violations": violations}
            req_id = activity.request_id
            if req_id:
                if req_id not in request_state or not request_state[req_id]["boarded"]:
                    violations.append(f"alight before board for {req_id}")
                    return {"feasible": False, "violations": violations}
                if request_state[req_id]["alighted"]:
                    violations.append(f"double alight for {req_id}")
                    return {"feasible": False, "violations": violations}
                request_state[req_id]["alighted"] = True

        elif activity.action == ActionType.PICKUP:
            req_id = activity.request_id
            req = solution_requests.get(req_id) if req_id else None
            if req and req.request_type == "SHIPMENT":
                # SHIPMENT 的 PICKUP 增加货物
                current_cargo += req.size
            elif req and req.request_type == "PICKUP":
                # PICKUP 请求增加货物
                current_cargo += req.size
            else:
                current_cargo += 1
            if current_cargo > route.cargo_capacity:
                violations.append(f"cargo overflow at {activity.station_id}: {current_cargo} > {route.cargo_capacity}")
                return {"feasible": False, "violations": violations}
            if req_id:
                if req_id not in request_state:
                    request_state[req_id] = {"picked_up": False, "delivered": False}
                request_state[req_id]["picked_up"] = True

        elif activity.action == ActionType.DELIVER:
            req_id = activity.request_id
            req = solution_requests.get(req_id) if req_id else None
            if req and req.request_type == "SHIPMENT":
                # SHIPMENT 的 DELIVER 减少货物
                current_cargo -= req.size
            elif req and req.request_type == "DELIVERY":
                # DELIVERY 是 PRELOADED，不减少当前货物（从 initialCargoLoad 消耗）
                pass
            else:
                current_cargo -= 1
            if current_cargo < 0:
                violations.append(f"negative cargo at {activity.station_id}")
                return {"feasible": False, "violations": violations}
            if req_id:
                if req_id not in request_state:
                    request_state[req_id] = {}
                request_state[req_id]["delivered"] = True
                request_state[req_id]["served"] = True

        # 乘客影响（货运活动对车上乘客的影响）
        if activity.action in (ActionType.PICKUP, ActionType.DELIVER) and current_passengers > 0:
            impact = t * current_passengers
            passenger_impact += impact
            max_passenger_impact = max(max_passenger_impact, t)

    # 返回 depot
    d = _distance(current_station, depot, matrix)
    t = _duration(current_station, depot, matrix)
    distance += d
    duration += t

    # 验证所有请求都被服务
    # 首先检查 request_state 中的请求
    for req_id, state in request_state.items():
        served = (state.get("boarded") or state.get("picked_up") or state.get("delivered") or state.get("served"))
        if not served:
            violations.append(f"request {req_id} not served")
            return {"feasible": False, "violations": violations}

    # 然后检查 solution_requests 中未出现在 request_state 中的请求
    for req_id in solution_requests:
        if req_id not in request_state:
            violations.append(f"request {req_id} not served (not in route)")
            return {"feasible": False, "violations": violations}

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
        "violations": violations,
    }


# 全局请求映射（用于评估器访问请求信息）
solution_requests = {}


def _distance(a, b, matrix=None) -> float:
    if matrix is not None:
        key = (a.stationId, b.stationId)
        if key in matrix:
            return matrix[key][0]
    return hypot(a.longitude - b.longitude, a.latitude - b.latitude)


def _duration(a, b, matrix=None) -> float:
    if matrix is not None:
        key = (a.stationId, b.stationId)
        if key in matrix:
            return matrix[key][1] or 0.0
    dist = hypot(a.longitude - b.longitude, a.latitude - b.latitude)
    return dist / 25.0 * 3600


def _compute_direction(a, b) -> float:
    diff = b.longitude - a.longitude
    if abs(diff) < 1e-8:
        return 0.0
    return 1.0 if diff > 0 else -1.0
