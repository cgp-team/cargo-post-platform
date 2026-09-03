"""HACO-CPS 2.0 全局路线构造：Passenger-first + Cargo insertion。

核心策略：
1. 先构造乘客骨架（稳定的方向性）
2. 再将货运任务插入最佳位置
3. 使用 task-to-task 信息素引导顺序
4. 使用地理邻近度引导选择
"""

from __future__ import annotations

import random
from math import hypot
from typing import TYPE_CHECKING

from .encoding import TaskBlock, TaskType
from .route_genome import GlobalRouteGenome

if TYPE_CHECKING:
    from .config import HacoConfig
    from .pheromone import PheromoneMatrix
    from ..distance import DistanceMatrix
    from ..models import Station

EPSILON = 1e-6


def construct_global_solution(
    tasks: list[TaskBlock],
    genome_template: GlobalRouteGenome,
    pheromone: PheromoneMatrix,
    station_map: dict,
    matrix: DistanceMatrix | None,
    config: HacoConfig,
    rng: random.Random,
    alpha: float | None = None,
    beta: float | None = None,
) -> GlobalRouteGenome:
    """构造一个完整的全局解。

    策略：Passenger-first + Cargo insertion。
    alpha/beta 可由调用方传入（自适应），否则使用 config 默认值。
    """
    _alpha = alpha if alpha is not None else config.alpha
    _beta = beta if beta is not None else config.beta

    genome = genome_template.copy()

    # 分离乘客和货运任务
    passenger_tasks = [t for t in tasks if t.task_type == TaskType.PASSENGER]
    cargo_tasks = [t for t in tasks if t.task_type != TaskType.PASSENGER]

    # Phase 1: 构造乘客骨架
    _construct_passenger_backbone(passenger_tasks, genome, pheromone, station_map, matrix, config, rng, _alpha, _beta)

    # Phase 2: 插入货运任务
    _insert_cargo_tasks(cargo_tasks, genome, pheromone, station_map, matrix, config, rng, _alpha, _beta)

    return genome


def construct_greedy_global(
    tasks: list[TaskBlock],
    genome_template: GlobalRouteGenome,
    station_map: dict,
    matrix: DistanceMatrix | None,
    rng: random.Random,
) -> GlobalRouteGenome:
    """贪婪最近邻全局构造。"""
    genome = genome_template.copy()
    unassigned = list(tasks)

    while unassigned:
        best_task = None
        best_vi = 0
        best_pos = 0
        best_score = float("inf")

        # 获取当前各车辆末端位置
        end_positions = _get_route_end_positions(genome, station_map)

        for task in unassigned:
            for vi in genome.vehicle_routes.keys():
                route = genome.get_route(vi)
                for pos in range(len(route) + 1):
                    score = _greedy_global_score(task, vi, pos, genome, station_map, matrix, end_positions)
                    if score < best_score:
                        best_score = score
                        best_task = task
                        best_vi = vi
                        best_pos = pos

        if best_task is None:
            break

        genome.insert_task(best_vi, best_pos, best_task.task_id)
        unassigned.remove(best_task)

    return genome


def construct_nn_global(
    tasks: list[TaskBlock],
    genome_template: GlobalRouteGenome,
    station_map: dict,
    matrix: DistanceMatrix | None,
) -> GlobalRouteGenome:
    """Nearest Neighbor 全局构造。"""
    genome = genome_template.copy()
    unassigned = list(tasks)
    vi = list(genome.vehicle_routes.keys())[0]  # 使用第一辆车

    while unassigned:
        route = genome.get_route(vi)
        if not route:
            # 第一个任务：选择离 depot 最近的
            depot = station_map.get(genome.depot_station)
            best_task = None
            best_dist = float("inf")
            for task in unassigned:
                pickup = station_map.get(task.pickup_station)
                if pickup and depot:
                    d = _distance(depot, pickup, matrix)
                    if d < best_dist:
                        best_dist = d
                        best_task = task
        else:
            # 后续任务：选择离当前末端最近的
            last_task_id = route[-1]
            last_task = genome.task_blocks.get(last_task_id)
            if not last_task:
                break
            last_station = station_map.get(last_task.delivery_station)
            best_task = None
            best_dist = float("inf")
            for task in unassigned:
                pickup = station_map.get(task.pickup_station)
                if pickup and last_station:
                    d = _distance(last_station, pickup, matrix)
                    if d < best_dist:
                        best_dist = d
                        best_task = task

        if best_task is None:
            break

        genome.insert_task(vi, len(route), best_task.task_id)
        unassigned.remove(best_task)

    return genome


def _construct_passenger_backbone(
    passenger_tasks: list[TaskBlock],
    genome: GlobalRouteGenome,
    pheromone: PheromoneMatrix,
    station_map: dict,
    matrix: DistanceMatrix | None,
    config: HacoConfig,
    rng: random.Random,
    alpha: float = 1.0,
    beta: float = 3.0,
) -> None:
    """构造乘客骨架。"""
    if not passenger_tasks:
        return

    unassigned = list(passenger_tasks)
    vi = list(genome.vehicle_routes.keys())[0]

    while unassigned:
        # 选择下一个乘客任务
        task = _select_next_passenger(unassigned, genome, vi, pheromone, station_map, matrix, config, rng, alpha, beta)
        if task is None:
            break

        # 找最佳插入位置
        best_pos = _find_best_position(task, vi, genome, station_map, matrix)
        genome.insert_task(vi, best_pos, task.task_id)
        unassigned.remove(task)


def _insert_cargo_tasks(
    cargo_tasks: list[TaskBlock],
    genome: GlobalRouteGenome,
    pheromone: PheromoneMatrix,
    station_map: dict,
    matrix: DistanceMatrix | None,
    config: HacoConfig,
    rng: random.Random,
    alpha: float = 1.0,
    beta: float = 3.0,
) -> None:
    """将货运任务插入到已有路线的最佳位置。"""
    if not cargo_tasks:
        return

    unassigned = list(cargo_tasks)

    while unassigned:
        best_task = None
        best_vi = 0
        best_pos = 0
        best_score = float("inf")

        for task in unassigned:
            for vi in genome.vehicle_routes.keys():
                route = genome.get_route(vi)
                for pos in range(len(route) + 1):
                    score = _cargo_insertion_score(task, vi, pos, genome, station_map, matrix, config, alpha, beta)
                    if score < best_score:
                        best_score = score
                        best_task = task
                        best_vi = vi
                        best_pos = pos

        if best_task is None:
            break

        genome.insert_task(best_vi, best_pos, best_task.task_id)
        unassigned.remove(best_task)


def _select_next_passenger(
    unassigned: list[TaskBlock],
    genome: GlobalRouteGenome,
    vi: int,
    pheromone: PheromoneMatrix,
    station_map: dict,
    matrix: DistanceMatrix | None,
    config: HacoConfig,
    rng: random.Random,
    alpha: float = 1.0,
    beta: float = 3.0,
) -> TaskBlock | None:
    """选择下一个乘客任务。"""
    if not unassigned:
        return None

    route = genome.get_route(vi)
    last_task_id = route[-1] if route else "DEPOT"

    task_scores = []
    for task in unassigned:
        # 信息素
        tau = pheromone.get(last_task_id, task.task_id)

        # 启发式：距离
        pickup = station_map.get(task.pickup_station)
        if not pickup:
            continue

        if route:
            last_task = genome.task_blocks.get(route[-1])
            if last_task:
                last_station = station_map.get(last_task.delivery_station)
                if last_station:
                    dist = _distance(last_station, pickup, matrix)
                else:
                    dist = 10.0
            else:
                dist = 10.0
        else:
            depot = station_map.get(genome.depot_station)
            dist = _distance(depot, pickup, matrix) if depot else 10.0

        eta = 1.0 / (dist + EPSILON)
        score = (tau ** alpha) * (eta ** beta)
        task_scores.append((task, score))

    if not task_scores:
        return None

    total = sum(s for _, s in task_scores)
    if total <= 0:
        return rng.choice([t for t, _ in task_scores])

    probs = [s / total for _, s in task_scores]
    r = rng.random()
    cumulative = 0.0
    for i, prob in enumerate(probs):
        cumulative += prob
        if r <= cumulative:
            return task_scores[i][0]

    return task_scores[-1][0]


def _find_best_position(
    task: TaskBlock,
    vi: int,
    genome: GlobalRouteGenome,
    station_map: dict,
    matrix: DistanceMatrix | None,
) -> int:
    """找任务在路线中的最佳插入位置。"""
    route = genome.get_route(vi)
    best_pos = len(route)
    best_score = float("inf")

    for pos in range(len(route) + 1):
        score = _position_score(task, vi, pos, genome, station_map, matrix)
        if score < best_score:
            best_score = score
            best_pos = pos

    return best_pos


def _position_score(
    task: TaskBlock,
    vi: int,
    pos: int,
    genome: GlobalRouteGenome,
    station_map: dict,
    matrix: DistanceMatrix | None,
) -> float:
    """计算插入位置的得分。"""
    route = genome.get_route(vi)

    # 前一站
    if pos == 0:
        prev = station_map.get(genome.depot_station)
    else:
        prev_task = genome.task_blocks.get(route[pos - 1])
        prev = station_map.get(prev_task.delivery_station) if prev_task else None

    # 后一站
    if pos >= len(route):
        nxt = station_map.get(genome.depot_station)
    else:
        nxt_task = genome.task_blocks.get(route[pos])
        nxt = station_map.get(nxt_task.pickup_station) if nxt_task else None

    pickup = station_map.get(task.pickup_station)
    delivery = station_map.get(task.delivery_station)

    if not all([prev, nxt, pickup, delivery]):
        return 100.0

    # 原距离
    orig = _distance(prev, nxt, matrix)

    # 新距离
    new = (_distance(prev, pickup, matrix)
           + _distance(pickup, delivery, matrix)
           + _distance(delivery, nxt, matrix))

    return max(0.0, new - orig)


def _cargo_insertion_score(
    task: TaskBlock,
    vi: int,
    pos: int,
    genome: GlobalRouteGenome,
    station_map: dict,
    matrix: DistanceMatrix | None,
    config: HacoConfig,
    alpha: float = 1.0,
    beta: float = 3.0,
) -> float:
    """计算货运任务插入得分。"""
    delta_dist = _position_score(task, vi, pos, genome, station_map, matrix)

    # 乘客影响
    p_impact = _estimate_passenger_impact(task, vi, pos, genome, station_map, matrix)

    # 回溯惩罚
    backtracking = _estimate_backtracking(task, vi, pos, genome, station_map, matrix)

    # 归一化
    norm_dist = delta_dist / 10.0
    norm_passenger = p_impact / 300.0
    norm_backtracking = backtracking / 10.0

    cost = (
        config.w_distance * norm_dist
        + config.w_passenger_impact * norm_passenger
        + 0.2 * norm_backtracking  # 回溯惩罚权重
    )

    return max(cost, EPSILON)


def _estimate_passenger_impact(
    task: TaskBlock,
    vi: int,
    pos: int,
    genome: GlobalRouteGenome,
    station_map: dict,
    matrix: DistanceMatrix | None,
) -> float:
    """估算插入任务对乘客的影响。"""
    if task.task_type == TaskType.PASSENGER:
        return 0.0

    route = genome.get_route(vi)

    # 统计 pos 之前的乘客数
    p_count = 0
    for i in range(pos):
        t = genome.task_blocks.get(route[i])
        if t and t.task_type == TaskType.PASSENGER:
            p_count += 1

    if p_count <= 0:
        return 0.0

    # 绕行距离
    delta = _position_score(task, vi, pos, genome, station_map, matrix)
    detour_seconds = delta / 25.0 * 3600
    return detour_seconds * p_count


def _estimate_backtracking(
    task: TaskBlock,
    vi: int,
    pos: int,
    genome: GlobalRouteGenome,
    station_map: dict,
    matrix: DistanceMatrix | None,
) -> float:
    """估算插入任务造成的回溯距离。"""
    route = genome.get_route(vi)

    # 前一站
    if pos == 0:
        prev = station_map.get(genome.depot_station)
    else:
        prev_task = genome.task_blocks.get(route[pos - 1])
        prev = station_map.get(prev_task.delivery_station) if prev_task else None

    pickup = station_map.get(task.pickup_station)

    if not prev or not pickup:
        return 0.0

    # 简单用经度差判断方向
    direction_diff = pickup.longitude - prev.longitude

    # 后一站
    if pos < len(route):
        next_task = genome.task_blocks.get(route[pos])
        if next_task:
            next_station = station_map.get(next_task.pickup_station)
            if next_station:
                next_direction = next_station.longitude - pickup.longitude
                # 方向反转
                if direction_diff * next_direction < 0:
                    return _distance(pickup, next_station, matrix)

    return 0.0


def _greedy_global_score(
    task: TaskBlock,
    vi: int,
    pos: int,
    genome: GlobalRouteGenome,
    station_map: dict,
    matrix: DistanceMatrix | None,
    end_positions: dict,
) -> float:
    """贪婪全局得分。"""
    delta = _position_score(task, vi, pos, genome, station_map, matrix)

    # 邻近度
    pickup = station_map.get(task.pickup_station)
    end_pos = end_positions.get(vi)
    proximity = 0.0
    if pickup and end_pos:
        proximity = _distance(end_pos, pickup, matrix)

    return delta + proximity * 0.5


def _get_route_end_positions(genome: GlobalRouteGenome, station_map: dict) -> dict:
    """获取各车辆路线末端位置。"""
    positions = {}
    for vi, route in genome.vehicle_routes.items():
        if route:
            last_task = genome.task_blocks.get(route[-1])
            if last_task:
                station = station_map.get(last_task.delivery_station)
                if station:
                    positions[vi] = station
        else:
            depot = station_map.get(genome.depot_station)
            if depot:
                positions[vi] = depot
    return positions


def _distance(a, b, matrix=None) -> float:
    if matrix is not None:
        key = (a.stationId, b.stationId)
        if key in matrix:
            return matrix[key][0]
    return hypot(a.longitude - b.longitude, a.latitude - b.latitude)
