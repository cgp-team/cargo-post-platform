"""Stop-Level 构造器：逐活动构建路线。

核心区别于 task-level：
- 每次选择一个 activity（不是 task）
- Activity 有 precedence 约束（BOARD before ALIGHT）
- 允许 ride-through（乘客可以在中间站上下车）
"""

from __future__ import annotations

import random
from math import hypot
from typing import TYPE_CHECKING

from .models import (
    Activity,
    ActionType,
    Request,
    StopLevelRoute,
    StopLevelSolution,
)

if TYPE_CHECKING:
    from ..config import HacoConfig
    from ..distance import DistanceMatrix
    from ..models import Station


def construct_greedy_stop_level(
    requests: list[Request],
    route_templates: list[StopLevelRoute],
    station_map: dict,
    matrix: DistanceMatrix | None,
    rng: random.Random,
) -> StopLevelSolution:
    """贪婪最近邻 stop-level 构造。"""
    routes = {vi: template.copy() for vi, template in enumerate(route_templates)}
    request_map = {r.request_id: r for r in requests}

    # 收集所有待插入的 activities
    pending = _get_all_pending_activities(requests)

    while pending:
        best_activity = None
        best_vi = 0
        best_pos = 0
        best_score = float("inf")

        # 获取当前各车辆末端位置
        end_positions = {}
        for vi, route in routes.items():
            if route.activities:
                last = route.activities[-1]
                end_positions[vi] = station_map.get(last.station_id)
            else:
                end_positions[vi] = station_map.get(route.depot_station)

        for activity in pending:
            # 检查 precedence 约束
            if not _check_precedence(activity, routes, request_map):
                continue

            for vi, route in routes.items():
                for pos in range(len(route.activities) + 1):
                    # 位置感知的先后约束：ALIGHT 必须排在对应 BOARD 之后
                    # （历史实现只检查"BOARD 是否已存在于某处"，导致卸载被插到装载前面 → 负乘客数）
                    if not _check_position_precedence(activity, route, pos, request_map):
                        continue
                    # 检查容量约束
                    if not _check_capacity(activity, route, pos, request_map):
                        continue

                    score = _insertion_score(activity, vi, pos, routes, station_map, matrix, end_positions)
                    if score < best_score:
                        best_score = score
                        best_activity = activity
                        best_vi = vi
                        best_pos = pos

        if best_activity is None:
            break

        routes[best_vi].activities.insert(best_pos, best_activity)
        pending.remove(best_activity)

    return StopLevelSolution(
        routes=routes,
        requests=request_map,
        depot_station=route_templates[0].depot_station if route_templates else "S0",
    )


def construct_passenger_first_stop_level(
    requests: list[Request],
    route_templates: list[StopLevelRoute],
    station_map: dict,
    matrix: DistanceMatrix | None,
    rng: random.Random,
) -> StopLevelSolution:
    """Passenger-first stop-level 构造。

    先插入所有乘客活动，再插入货运活动。
    但乘客活动之间允许插入其他活动（ride-through）。
    """
    routes = {vi: template.copy() for vi, template in enumerate(route_templates)}
    request_map = {r.request_id: r for r in requests}

    # Phase 1: 插入乘客活动
    passenger_requests = [r for r in requests if r.request_type == "PASSENGER"]
    cargo_requests = [r for r in requests if r.request_type != "PASSENGER"]

    # 乘客活动按 pickup 站排序
    passenger_requests.sort(key=lambda r: _station_order(r.pickup_station, station_map))

    for req in passenger_requests:
        # 插入 BOARD
        board = req.board_activity
        best_vi, best_pos = _find_best_position(board, routes, station_map, matrix)
        routes[best_vi].activities.insert(best_pos, board)

        # 插入 ALIGHT（允许在其他活动之后）
        alight = req.alight_activity
        if alight:
            best_vi, best_pos = _find_best_position(alight, routes, station_map, matrix)
            routes[best_vi].activities.insert(best_pos, alight)

    # Phase 2: 插入货运活动
    for req in cargo_requests:
        activity = req.board_activity
        best_vi, best_pos = _find_best_position(activity, routes, station_map, matrix)
        routes[best_vi].activities.insert(best_pos, activity)

        if req.alight_activity:
            alight = req.alight_activity
            best_vi, best_pos = _find_best_position(alight, routes, station_map, matrix)
            routes[best_vi].activities.insert(best_pos, alight)

    return StopLevelSolution(
        routes=routes,
        requests=request_map,
        depot_station=route_templates[0].depot_station if route_templates else "S0",
    )


def construct_interleaved_stop_level(
    requests: list[Request],
    route_templates: list[StopLevelRoute],
    station_map: dict,
    matrix: DistanceMatrix | None,
    rng: random.Random,
) -> StopLevelSolution:
    """交错构造：允许乘客和货运活动交错排列。

    关键：BOARD P0, BOARD P1, ALIGHT P0, ALIGHT P1
    而不是：BOARD P0, ALIGHT P0, BOARD P1, ALIGHT P1
    """
    routes = {vi: template.copy() for vi, template in enumerate(route_templates)}
    request_map = {r.request_id: r for r in requests}

    # 先按 station 排序 requests
    sorted_requests = sorted(requests, key=lambda r: _station_order(r.pickup_station, station_map))

    # 先插入所有 BOARD/PICKUP 活动
    for req in sorted_requests:
        board = req.board_activity
        best_vi, best_pos = _find_best_position(board, routes, station_map, matrix)
        routes[best_vi].activities.insert(best_pos, board)

    # 再插入所有 ALIGHT/DELIVER 活动（必须在对应的 BOARD 之后）
    for req in sorted_requests:
        alight = req.alight_activity
        if alight:
            # 找到对应 BOARD 的位置
            board_pos = None
            for vi, route in routes.items():
                for i, a in enumerate(route.activities):
                    if a.request_id == req.request_id and a.action == ActionType.BOARD:
                        board_pos = (vi, i)
                        break
                if board_pos:
                    break

            if board_pos:
                # 在 BOARD 之后找最佳位置
                best_vi, best_pos = _find_best_position_after_board(alight, routes, board_pos, station_map, matrix)
                routes[best_vi].activities.insert(best_pos, alight)

    return StopLevelSolution(
        routes=routes,
        requests=request_map,
        depot_station=route_templates[0].depot_station if route_templates else "S0",
    )


def _find_best_position_after_board(
    activity: Activity,
    routes: dict,
    board_pos: tuple[int, int],
    station_map: dict,
    matrix: DistanceMatrix | None,
) -> tuple[int, int]:
    """在 BOARD 之后找最佳插入位置。"""
    board_vi, board_idx = board_pos
    best_vi = board_vi
    best_pos = board_idx + 1
    best_score = float("inf")

    # 只在 BOARD 所在的车辆中寻找
    route = routes[board_vi]
    for pos in range(board_idx + 1, len(route.activities) + 1):
        score = _position_score(activity, board_vi, pos, routes, station_map, matrix)
        if score < best_score:
            best_score = score
            best_pos = pos

    return best_vi, best_pos


def _get_all_pending_activities(requests: list[Request]) -> list[Activity]:
    """获取所有待插入的 activities。"""
    activities = []
    for req in requests:
        activities.append(req.board_activity)
        if req.alight_activity:
            activities.append(req.alight_activity)
    return activities


def _check_precedence(activity: Activity, routes: dict, request_map: dict) -> bool:
    """检查 precedence 约束。"""
    if not activity.request_id:
        return True

    req = request_map.get(activity.request_id)
    if not req:
        return True

    # 检查该请求的所有 activities 是否已经插入
    all_activities = [a for route in routes.values() for a in route.activities]

    if activity.action == ActionType.ALIGHT:
        # ALIGHT 之前必须有 BOARD
        for a in all_activities:
            if a.request_id == activity.request_id and a.action == ActionType.BOARD:
                return True
        return False

    if activity.action == ActionType.DELIVER:
        # DELIVER 之前必须有 PICKUP（对于 SHIPMENT）
        if req.request_type == "SHIPMENT":
            for a in all_activities:
                if a.request_id == activity.request_id and a.action == ActionType.PICKUP:
                    return True
            return False

    return True


def _check_position_precedence(activity: Activity, route: StopLevelRoute, pos: int,
                               request_map: dict) -> bool:
    """位置感知的先后约束。

    "活动存在"不等于"顺序正确"：ALIGHT/DELIVER 只有插在对应 BOARD/PICKUP **之后** 才合法，
    否则解在 evaluator 里会被判为（负乘客/负货量）不可行。
    """
    if not activity.request_id:
        return True
    if activity.action == ActionType.ALIGHT:
        return any(
            i < pos and a.request_id == activity.request_id and a.action == ActionType.BOARD
            for i, a in enumerate(route.activities)
        )
    if activity.action == ActionType.DELIVER:
        # 只有 SHIPMENT（取送配对）的 DELIVER 需要前置 PICKUP；
        # DELIVERY（预装派送）只有单个 DELIVER 活动，不该被前置条件挡住。
        req = request_map.get(activity.request_id)
        if req is None or req.request_type != "SHIPMENT":
            return True
        return any(
            i < pos and a.request_id == activity.request_id and a.action == ActionType.PICKUP
            for i, a in enumerate(route.activities)
        )
    return True


def _check_capacity(activity: Activity, route: StopLevelRoute, pos: int, request_map: dict) -> bool:
    """检查容量约束。"""
    # 模拟插入后的载荷
    p_load = route.initial_passenger_load
    c_load = route.initial_cargo_load

    for i, a in enumerate(route.activities):
        if i == pos:
            # 插入位置
            # 注意：这里要应用的是**待插入的新活动** activity；历史实现误写成 a（已存在活动）
            # 并且紧接着又把 a 应用了一次 → 货量/乘客数被算重，容量校验会把后续活动全部拒掉。
            p_load, c_load = _apply_activity(activity, p_load, c_load, request_map, route)

        p_load, c_load = _apply_activity(a, p_load, c_load, request_map, route)

    # 插入位置在末尾
    if pos >= len(route.activities):
        p_load, c_load = _apply_activity(activity, p_load, c_load, request_map, route)

    # 检查容量
    if p_load > route.passenger_capacity or c_load > route.cargo_capacity:
        return False
    if p_load < 0 or c_load < 0:
        return False

    return True


def _apply_activity(activity: Activity, p_load: int, c_load: int, request_map: dict, route: StopLevelRoute) -> tuple[int, int]:
    """应用一个活动到载荷。"""
    if activity.action == ActionType.BOARD:
        p_load += 1
    elif activity.action == ActionType.ALIGHT:
        p_load -= 1
    elif activity.action == ActionType.PICKUP:
        req = request_map.get(activity.request_id)
        c_load += req.size if req else 1
    elif activity.action == ActionType.DELIVER:
        req = request_map.get(activity.request_id)
        # 口径与 evaluator 保持一致：DELIVERY 是「预装（PRELOADED）」单——货本来就在车上
        # （从 initialCargoLoad 消耗），DELIVER 不减当前货量；只有 SHIPMENT 的 DELIVER 才减货。
        # 历史实现在这里统一减货，导致纯派送单把货量算成负数、后续活动全部插不进去。
        if req is not None and req.request_type == "DELIVERY":
            return p_load, c_load
        c_load -= req.size if req else 1
    return p_load, c_load


def _find_best_position(
    activity: Activity,
    routes: dict,
    station_map: dict,
    matrix: DistanceMatrix | None,
) -> tuple[int, int]:
    """找活动在路线中的最佳插入位置。"""
    best_vi = 0
    best_pos = 0
    best_score = float("inf")

    for vi, route in routes.items():
        for pos in range(len(route.activities) + 1):
            # 检查 precedence：ALIGHT 必须在 BOARD 之后
            if activity.action == ActionType.ALIGHT:
                # 检查该请求的 BOARD 是否已经在路线中
                has_board = any(
                    a.request_id == activity.request_id and a.action == ActionType.BOARD
                    for a in route.activities
                )
                if not has_board:
                    continue  # 跳过没有 BOARD 的位置

            # 检查 DELIVER 必须在 PICKUP 之后（对于 SHIPMENT）
            if activity.action == ActionType.DELIVER:
                has_pickup = any(
                    a.request_id == activity.request_id and a.action == ActionType.PICKUP
                    for a in route.activities
                )
                if not has_pickup:
                    continue

            score = _position_score(activity, vi, pos, routes, station_map, matrix)
            if score < best_score:
                best_score = score
                best_vi = vi
                best_pos = pos

    return best_vi, best_pos


def _insertion_score(activity: Activity, vi: int, pos: int, routes: dict, station_map: dict, matrix, end_positions: dict) -> float:
    """计算插入得分。"""
    delta = _position_score(activity, vi, pos, routes, station_map, matrix)

    # 邻近度
    target = station_map.get(activity.station_id)
    end_pos = end_positions.get(vi)
    proximity = 0.0
    if target and end_pos:
        proximity = _distance(end_pos, target, matrix)

    return delta + proximity * 0.5


def _position_score(activity: Activity, vi: int, pos: int, routes: dict, station_map: dict, matrix) -> float:
    """计算插入位置的距离增量。"""
    route = routes[vi]

    # 前一站
    if pos == 0:
        prev = station_map.get(route.depot_station)
    else:
        prev_act = route.activities[pos - 1]
        prev = station_map.get(prev_act.station_id)

    # 后一站
    if pos >= len(route.activities):
        nxt = station_map.get(route.depot_station)
    else:
        nxt_act = route.activities[pos]
        nxt = station_map.get(nxt_act.station_id)

    target = station_map.get(activity.station_id)

    if not all([prev, nxt, target]):
        return 100.0

    orig = _distance(prev, nxt, matrix)
    new = _distance(prev, target, matrix) + _distance(target, nxt, matrix)

    return max(0.0, new - orig)


def _station_order(station_id: str, station_map: dict) -> float:
    """获取站点的排序值（用经度近似）。"""
    s = station_map.get(station_id)
    if not s:
        return 0.0
    return s.longitude


def _distance(a, b, matrix=None) -> float:
    if matrix is not None:
        key = (a.stationId, b.stationId)
        if key in matrix:
            return matrix[key][0]
    return hypot(a.longitude - b.longitude, a.latitude - b.latitude)
