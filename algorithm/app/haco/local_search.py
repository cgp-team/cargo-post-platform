"""HACO-CPS 局部搜索：Relocate / Swap / 2-opt / Or-opt。"""

from __future__ import annotations

import random
from typing import TYPE_CHECKING

from .encoding import Solution, TaskInsertion, VehicleRoute
from .evaluator import evaluate_solution
from .feasibility import fast_feasible_insert

if TYPE_CHECKING:
    from .config import HacoConfig
    from ..distance import DistanceMatrix
    from ..models import PlanRequest, Station


def local_search(
    solution: Solution,
    request: PlanRequest,
    station_map: dict[str, Station],
    matrix: DistanceMatrix | None,
    config: HacoConfig,
    rng: random.Random,
) -> Solution:
    """对解执行多轮局部搜索。"""
    best = solution
    best_obj = evaluate_solution(solution, request, station_map, matrix)

    for _ in range(config.local_search_rounds):
        improved = False

        # Relocate
        new_sol = _relocate(best, station_map, matrix, config, rng)
        if new_sol:
            new_obj = evaluate_solution(new_sol, request, station_map, matrix)
            if new_obj < best_obj:
                best = new_sol
                best_obj = new_obj
                improved = True

        # Swap
        new_sol = _swap(best, station_map, matrix, config, rng)
        if new_sol:
            new_obj = evaluate_solution(new_sol, request, station_map, matrix)
            if new_obj < best_obj:
                best = new_sol
                best_obj = new_obj
                improved = True

        if not improved:
            break

    return best


def _relocate(
    solution: Solution,
    station_map: dict[str, Station],
    matrix: DistanceMatrix | None,
    config: HacoConfig,
    rng: random.Random,
) -> Solution | None:
    """Relocate：将一个任务从一条路线移到另一条。"""
    routes = solution.routes
    used_routes = [r for r in routes if r.task_count > 0]
    if len(used_routes) < 1:
        return None

    # 随机选择源路线和任务
    src_route = rng.choice(used_routes)
    if not src_route.insertions:
        return None

    src_pos = rng.randrange(len(src_route.insertions))
    task = src_route.insertions[src_pos].task

    # 尝试插入到其他路线
    for dst_route in routes:
        if dst_route is src_route:
            continue
        for dst_pos in range(len(dst_route.insertions) + 1):
            feasible, _ = fast_feasible_insert(task, dst_route, dst_pos)
            if feasible:
                # 执行移动
                new_routes = [_copy_route(r) for r in routes]
                new_src = new_routes[routes.index(src_route)]
                new_dst = new_routes[routes.index(dst_route)]

                # 移除
                removed = new_src.insertions.pop(src_pos)
                # 插入
                new_dst.insertions.insert(dst_pos, TaskInsertion(
                    task=removed.task, pickup_position=dst_pos, delivery_position=dst_pos
                ))
                return Solution(routes=new_routes)

    return None


def _swap(
    solution: Solution,
    station_map: dict[str, Station],
    matrix: DistanceMatrix | None,
    config: HacoConfig,
    rng: random.Random,
) -> Solution | None:
    """Swap：交换两个任务的位置。"""
    routes = solution.routes
    used_routes = [r for r in routes if r.task_count > 0]
    if len(used_routes) < 1:
        return None

    # 选择两个任务
    r1 = rng.choice(used_routes)
    if not r1.insertions:
        return None
    p1 = rng.randrange(len(r1.insertions))

    r2 = rng.choice(used_routes)
    if not r2.insertions:
        return None
    p2 = rng.randrange(len(r2.insertions))

    if r1 is r2 and p1 == p2:
        return None

    task1 = r1.insertions[p1].task
    task2 = r2.insertions[p2].task

    # 检查交换后是否可行
    # 简化：先尝试交换再验证
    new_routes = [_copy_route(r) for r in routes]
    nr1 = new_routes[routes.index(r1)]
    nr2 = new_routes[routes.index(r2)]

    # 交换
    nr1.insertions[p1] = TaskInsertion(task=task2, pickup_position=p1, delivery_position=p1)
    nr2.insertions[p2] = TaskInsertion(task=task1, pickup_position=p2, delivery_position=p2)

    # 验证可行性
    for route in new_routes:
        for ins in route.insertions:
            feasible, _ = fast_feasible_insert(ins.task, route, route.insertions.index(ins))
            if not feasible:
                return None

    return Solution(routes=new_routes)


def _copy_route(route: VehicleRoute) -> VehicleRoute:
    """复制路线。"""
    return VehicleRoute(
        vehicle_index=route.vehicle_index,
        vehicle_id=route.vehicle_id,
        passenger_capacity=route.passenger_capacity,
        cargo_capacity=route.cargo_capacity,
        initial_passenger_load=route.initial_passenger_load,
        initial_cargo_load=route.initial_cargo_load,
        skeleton=route.skeleton,
        insertions=list(route.insertions),
    )
