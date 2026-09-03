"""HACO-CPS 蚂蚁构建过程：每只蚂蚁根据信息素和启发式信息构建完整解。"""

from __future__ import annotations

import random
from typing import TYPE_CHECKING

from .encoding import Solution, TaskBlock, TaskInsertion, VehicleRoute
from .feasibility import fast_feasible_insert
from .heuristic import heuristic_score

if TYPE_CHECKING:
    from .config import HacoConfig
    from .pheromone import PheromoneMatrix
    from ..distance import DistanceMatrix
    from ..models import Station

EPSILON = 1e-6


def construct_ant_solution(
    tasks: list[TaskBlock],
    routes: list[VehicleRoute],
    pheromone: PheromoneMatrix,
    station_map: dict[str, Station],
    matrix: DistanceMatrix | None,
    config: HacoConfig,
    rng: random.Random,
) -> Solution:
    """一只蚂蚁构建一个完整解。

    每步：
    1. 从未分配任务中选择
    2. 计算转移概率（信息素 × 启发式）
    3. 轮盘赌选择
    4. 找最佳插入位置
    5. 插入任务
    """
    # 复制路线（不修改原始模板）
    working_routes = [_copy_route(r) for r in routes]
    unassigned = list(tasks)

    while unassigned:
        # 选择下一个任务
        task = _select_next_task(
            unassigned, working_routes, pheromone, station_map, matrix, config, rng
        )
        if task is None:
            break

        # 找最佳插入位置
        best_route, best_pos = _find_best_insertion(
            task, working_routes, station_map, matrix, config
        )

        if best_route is not None:
            # 插入
            insertion = TaskInsertion(task=task, pickup_position=best_pos, delivery_position=best_pos)
            best_route.insertions.insert(best_pos, insertion)
            unassigned.remove(task)
        else:
            # 无法插入，跳过
            unassigned.remove(task)

    return Solution(routes=working_routes)


def _select_next_task(
    unassigned: list[TaskBlock],
    routes: list[VehicleRoute],
    pheromone: PheromoneMatrix,
    station_map: dict[str, Station],
    matrix: DistanceMatrix | None,
    config: HacoConfig,
    rng: random.Random,
) -> TaskBlock | None:
    """选择下一个要插入的任务（轮盘赌）。"""
    if not unassigned:
        return None

    # 限制候选集大小
    candidate_size = min(config.candidate_size, len(unassigned))
    candidates = unassigned[:candidate_size]

    # 计算每个候选的概率
    probabilities = []
    for task in candidates:
        # 信息素：使用最近插入的任务的 pheromone
        tau = _get_task_pheromone(task, routes, pheromone)
        # 启发式：取所有可行插入位置的最佳得分
        eta = _get_best_eta(task, routes, station_map, matrix, config)
        # 转移概率
        prob = (tau ** config.alpha) * ((1.0 / (eta + EPSILON)) ** config.beta)
        probabilities.append(prob)

    # 归一化
    total = sum(probabilities)
    if total <= 0:
        return rng.choice(candidates)

    probabilities = [p / total for p in probabilities]

    # 轮盘赌
    r = rng.random()
    cumulative = 0.0
    for i, prob in enumerate(probabilities):
        cumulative += prob
        if r <= cumulative:
            return candidates[i]

    return candidates[-1]


def _get_task_pheromone(
    task: TaskBlock,
    routes: list[VehicleRoute],
    pheromone: PheromoneMatrix,
) -> float:
    """获取任务的信息素水平（基于最近插入的任务）。"""
    # 找到最后插入的任务
    last_task_id = "DEPOT"
    for route in routes:
        if route.insertions:
            last_task_id = route.insertions[-1].task.task_id
    return pheromone.get(last_task_id, task.task_id)


def _get_best_eta(
    task: TaskBlock,
    routes: list[VehicleRoute],
    station_map: dict[str, Station],
    matrix: DistanceMatrix | None,
    config: HacoConfig,
) -> float:
    """获取任务在所有可行位置中的最佳启发式得分。"""
    best_eta = float("inf")
    for route in routes:
        for pos in range(len(route.insertions) + 1):
            feasible, _ = fast_feasible_insert(task, route, pos)
            if not feasible:
                continue
            score = heuristic_score(
                task, route, pos, station_map, matrix, config,
                route.initial_passenger_load, route.initial_cargo_load
            )
            best_eta = min(best_eta, score)
    return best_eta if best_eta < float("inf") else 100.0


def _find_best_insertion(
    task: TaskBlock,
    routes: list[VehicleRoute],
    station_map: dict[str, Station],
    matrix: DistanceMatrix | None,
    config: HacoConfig,
) -> tuple[VehicleRoute | None, int]:
    """找到任务的最佳插入位置（最低成本）。"""
    best_route = None
    best_pos = 0
    best_cost = float("inf")

    for route in routes:
        for pos in range(len(route.insertions) + 1):
            feasible, _ = fast_feasible_insert(task, route, pos)
            if not feasible:
                continue
            cost = heuristic_score(
                task, route, pos, station_map, matrix, config,
                route.initial_passenger_load, route.initial_cargo_load
            )
            if cost < best_cost:
                best_cost = cost
                best_route = route
                best_pos = pos

    return best_route, best_pos


def _copy_route(route: VehicleRoute) -> VehicleRoute:
    """复制路线（浅复制 insertions 列表）。"""
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
