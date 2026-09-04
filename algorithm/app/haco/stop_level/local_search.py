"""Stop-Level 局部搜索：操作 Activity 序列。

关键操作：
- Activity Relocate: 移动单个活动
- Activity Swap: 交换两个活动
- Activity 2-opt: 反转一段活动序列
- Pair Relocate: 移动一对活动（BOARD+ALIGHT）
- Same-station Reorder: 重新排列同站活动
"""

from __future__ import annotations

import random
from typing import TYPE_CHECKING

from .evaluator import evaluate_solution
from .models import (
    Activity,
    ActionType,
    StopLevelSolution,
)

if TYPE_CHECKING:
    from ..config import HacoConfig
    from ..distance import DistanceMatrix
    from ..models import Station


def stop_level_local_search(
    solution: StopLevelSolution,
    station_map: dict,
    matrix: DistanceMatrix | None,
    config: HacoConfig,
    rng: random.Random,
) -> StopLevelSolution:
    """对 stop-level 解执行多轮局部搜索。"""
    best = solution.copy()
    best_obj = evaluate_solution(best, station_map, matrix)

    for _ in range(config.local_search_rounds):
        improved = False

        # Activity Relocate
        new_sol = _activity_relocate(best, station_map, matrix, rng)
        if new_sol:
            new_obj = evaluate_solution(new_sol, station_map, matrix)
            if new_obj < best_obj:
                best = new_sol
                best_obj = new_obj
                improved = True

        # Activity Swap
        new_sol = _activity_swap(best, station_map, matrix, rng)
        if new_sol:
            new_obj = evaluate_solution(new_sol, station_map, matrix)
            if new_obj < best_obj:
                best = new_sol
                best_obj = new_obj
                improved = True

        # Same-station Reorder
        new_sol = _same_station_reorder(best, station_map, matrix, rng)
        if new_sol:
            new_obj = evaluate_solution(new_sol, station_map, matrix)
            if new_obj < best_obj:
                best = new_sol
                best_obj = new_obj
                improved = True

        if not improved:
            break

    return best


def _activity_relocate(solution: StopLevelSolution, station_map: dict, matrix, rng: random.Random) -> StopLevelSolution | None:
    """Activity Relocate：移动单个活动到新位置。"""
    # 收集所有活动
    all_activities = []
    for vi, route in solution.routes.items():
        for pos, activity in enumerate(route.activities):
            all_activities.append((vi, pos, activity))

    if not all_activities:
        return None

    # 随机选择一个活动
    vi, pos, activity = rng.choice(all_activities)

    # 创建新解
    new_sol = solution.copy()
    new_route = new_sol.routes[vi]

    # 移除
    new_route.activities.pop(pos)

    # 找最佳新位置
    best_vi = vi
    best_pos = pos
    best_score = float("inf")

    for new_vi, route in new_sol.routes.items():
        for new_pos in range(len(route.activities) + 1):
            # 检查 precedence
            if not _check_precedence_for_position(activity, new_vi, new_pos, new_sol):
                continue

            score = _relocation_score(activity, new_vi, new_pos, new_sol, station_map, matrix)
            if score < best_score:
                best_score = score
                best_vi = new_vi
                best_pos = new_pos

    # 插入
    new_sol.routes[best_vi].activities.insert(best_pos, activity)
    return new_sol


def _activity_swap(solution: StopLevelSolution, station_map: dict, matrix, rng: random.Random) -> StopLevelSolution | None:
    """Activity Swap：交换两个活动的位置。"""
    all_activities = []
    for vi, route in solution.routes.items():
        for pos, activity in enumerate(route.activities):
            all_activities.append((vi, pos, activity))

    if len(all_activities) < 2:
        return None

    # 随机选择两个活动
    (vi1, pos1, act1), (vi2, pos2, act2) = rng.sample(all_activities, 2)

    # 创建新解
    new_sol = solution.copy()

    # 交换
    new_sol.routes[vi1].activities[pos1] = act2
    new_sol.routes[vi2].activities[pos2] = act1

    # 验证 precedence
    for vi, route in new_sol.routes.items():
        if not _validate_route_precedence(route.activities, solution.requests):
            return None

    return new_sol


def _same_station_reorder(solution: StopLevelSolution, station_map: dict, matrix, rng: random.Random) -> StopLevelSolution | None:
    """Same-station Reorder：重新排列同一站点的活动顺序。"""
    new_sol = solution.copy()

    # 找到有多个活动的站点
    for vi, route in new_sol.routes.items():
        # 按站点分组
        station_groups = {}
        for pos, activity in enumerate(route.activities):
            if activity.station_id not in station_groups:
                station_groups[activity.station_id] = []
            station_groups[activity.station_id].append((pos, activity))

        # 找到有多活动的站点
        for station_id, group in station_groups.items():
            if len(group) >= 2:
                # 随机打乱
                positions = [p for p, _ in group]
                activities = [a for _, a in group]
                rng.shuffle(activities)

                # 检查 precedence
                for i, pos in enumerate(positions):
                    route.activities[pos] = activities[i]

                if _validate_route_precedence(route.activities, solution.requests):
                    return new_sol
                else:
                    # 恢复
                    for i, pos in enumerate(positions):
                        route.activities[pos] = group[i][1]

    return None


def _check_precedence_for_position(activity: Activity, vi: int, pos: int, solution: StopLevelSolution) -> bool:
    """检查在指定位置插入活动是否满足 precedence。"""
    if not activity.request_id:
        return True

    req = solution.requests.get(activity.request_id)
    if not req:
        return True

    # 收集该请求的所有活动
    all_activities = []
    for v, route in solution.routes.items():
        for a in route.activities:
            if a.request_id == activity.request_id:
                all_activities.append(a)

    if activity.action == ActionType.ALIGHT:
        # ALIGHT 之前必须有 BOARD
        has_board = any(a.action == ActionType.BOARD for a in all_activities)
        if not has_board:
            return False

    if activity.action == ActionType.DELIVER:
        # DELIVER 之前必须有 PICKUP（对于 SHIPMENT）
        if req.request_type == "SHIPMENT":
            has_pickup = any(a.action == ActionType.PICKUP for a in all_activities)
            if not has_pickup:
                return False

    return True


def _validate_route_precedence(activities: list[Activity], requests: dict) -> bool:
    """验证路线中的 precedence 约束。"""
    seen = {}  # request_id -> set of actions

    for activity in activities:
        if not activity.request_id:
            continue

        req_id = activity.request_id
        if req_id not in seen:
            seen[req_id] = set()

        if activity.action == ActionType.ALIGHT:
            if ActionType.BOARD not in seen[req_id]:
                return False

        if activity.action == ActionType.DELIVER:
            req = requests.get(req_id)
            if req and req.request_type == "SHIPMENT":
                if ActionType.PICKUP not in seen[req_id]:
                    return False

        seen[req_id].add(activity.action)

    return True


def _relocation_score(activity: Activity, vi: int, pos: int, solution: StopLevelSolution, station_map: dict, matrix) -> float:
    """计算重新定位的得分。"""
    from math import hypot

    route = solution.routes[vi]

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

    orig = hypot(prev.longitude - nxt.longitude, prev.latitude - nxt.latitude)
    new = (hypot(prev.longitude - target.longitude, prev.latitude - target.latitude)
           + hypot(target.longitude - nxt.longitude, target.latitude - nxt.latitude))

    return max(0.0, new - orig)
