"""HACO-CPS LNS Destroy-Repair：大邻域搜索的破坏与修复算子。"""

from __future__ import annotations

import random
from typing import TYPE_CHECKING

from .construction import _find_best_insertion
from .encoding import Solution, TaskBlock, TaskInsertion, VehicleRoute
from .evaluator import evaluate_solution

if TYPE_CHECKING:
    from .config import HacoConfig
    from ..distance import DistanceMatrix
    from ..models import PlanRequest, Station


def destroy_repair(
    solution: Solution,
    request: PlanRequest,
    station_map: dict[str, Station],
    matrix: DistanceMatrix | None,
    config: HacoConfig,
    rng: random.Random,
) -> Solution:
    """执行一次 LNS destroy-repair。"""
    # Destroy：移除一部分任务
    destroyed, remaining = _destroy(solution, config, rng)

    if not destroyed:
        return solution

    # Repair：重新插入被移除的任务
    repaired = _repair(remaining, destroyed, station_map, matrix, config, rng)

    return repaired


def _destroy(
    solution: Solution,
    config: HacoConfig,
    rng: random.Random,
) -> tuple[list[TaskBlock], Solution]:
    """破坏阶段：移除一部分任务。"""
    # 收集所有已分配的任务
    all_tasks = []
    for route in solution.routes:
        for ins in route.insertions:
            all_tasks.append((route, ins))

    if not all_tasks:
        return [], solution

    # 确定移除数量
    destroy_count = max(1, int(len(all_tasks) * config.destroy_fraction))
    destroy_count = min(destroy_count, len(all_tasks))

    # 随机选择要移除的任务
    to_remove = rng.sample(all_tasks, destroy_count)

    # 构建新路线
    new_routes = []
    for route in solution.routes:
        new_route = VehicleRoute(
            vehicle_index=route.vehicle_index,
            vehicle_id=route.vehicle_id,
            passenger_capacity=route.passenger_capacity,
            cargo_capacity=route.cargo_capacity,
            initial_passenger_load=route.initial_passenger_load,
            initial_cargo_load=route.initial_cargo_load,
            skeleton=route.skeleton,
            insertions=[],
        )
        for ins in route.insertions:
            if (route, ins) not in to_remove:
                new_route.insertions.append(ins)
        new_routes.append(new_route)

    destroyed_tasks = [ins.task for _, ins in to_remove]
    return destroyed_tasks, Solution(routes=new_routes)


def _repair(
    remaining: Solution,
    destroyed: list[TaskBlock],
    station_map: dict[str, Station],
    matrix: DistanceMatrix | None,
    config: HacoConfig,
    rng: random.Random,
) -> Solution:
    """修复阶段：贪婪插入被移除的任务。"""
    # 按随机顺序插入
    tasks_to_insert = list(destroyed)
    rng.shuffle(tasks_to_insert)

    routes = remaining.routes

    for task in tasks_to_insert:
        best_route, best_pos = _find_best_insertion(
            task, routes, station_map, matrix, config
        )
        if best_route is not None:
            insertion = TaskInsertion(task=task, pickup_position=best_pos, delivery_position=best_pos)
            best_route.insertions.insert(best_pos, insertion)

    return Solution(routes=routes)
