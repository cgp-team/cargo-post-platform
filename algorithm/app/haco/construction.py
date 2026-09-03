"""HACO-CPS 1.3.0 蚂蚁构建过程：改进的路线构造。

关键改进（相比1.2.0）：
1. 任务选择基于质量（所有候选），不是位置（前N个）
2. 插入评分考虑已有任务的交互
3. 贪婪最近邻作为初始解参考
4. 增量评估减少重复计算
"""

from __future__ import annotations

import random
from typing import TYPE_CHECKING

from .candidate import CandidateInsertion
from .encoding import TaskBlock, TaskType
from .feasibility import fast_feasible_insert_state
from .heuristic import compute_distance, compute_duration
from .route_state import RouteState

if TYPE_CHECKING:
    from .config import HacoConfig
    from .pheromone import PheromoneMatrix
    from ..distance import DistanceMatrix
    from ..models import Station

EPSILON = 1e-6


def construct_ant_solution(
    tasks: list[TaskBlock],
    route_states: list[RouteState],
    pheromone: PheromoneMatrix,
    station_map: dict[str, Station],
    matrix: DistanceMatrix | None,
    config: HacoConfig,
    rng: random.Random,
    gap_pheromone=None,
    alpha_gap: float = 0.5,
) -> list[RouteState]:
    """一只蚂蚁构建一个完整解。"""
    working_states = [rs.copy() for rs in route_states]
    unassigned = list(tasks)
    last_task_id = "DEPOT"  # 跟踪上一个插入的任务

    while unassigned:
        # 选择下一个任务（基于质量，不是位置）
        task = _select_next_task(
            unassigned, working_states, pheromone, station_map, matrix, config, rng,
            gap_pheromone, alpha_gap, last_task_id
        )
        if task is None:
            break

        # 生成所有合法候选插入
        candidates = _generate_candidates(
            task, working_states, station_map, matrix, config
        )

        if not candidates:
            unassigned.remove(task)
            continue

        # 选择最佳插入（轮盘赌）
        selected = _select_insertion(candidates, pheromone, config, rng, gap_pheromone, alpha_gap, last_task_id)

        # 执行插入
        target_state = working_states[selected.vehicle_index]
        target_state.insert_task(task, selected.gap_index)
        unassigned.remove(task)
        last_task_id = task.task_id

    return working_states


def construct_greedy_solution(
    tasks: list[TaskBlock],
    route_states: list[RouteState],
    station_map: dict[str, Station],
    matrix: DistanceMatrix | None,
    rng: random.Random,
) -> list[RouteState]:
    """贪婪最近邻构造（用于初始解）。

    关键改进：考虑当前路线末端位置，优先选择地理上邻近的任务。
    这模拟了 OR-Tools 的 PATH_CHEAPEST_ARC 策略。
    """
    working_states = [rs.copy() for rs in route_states]
    unassigned = list(tasks)

    while unassigned:
        # 获取当前路线末端位置
        current_positions = []
        for state in working_states:
            if state.tasks:
                last_task = state.tasks[-1]
                station = station_map.get(last_task.delivery_station)
                if station:
                    current_positions.append(station)
            else:
                depot = station_map.get(state.depot_station)
                if depot:
                    current_positions.append(depot)

        best_task = None
        best_state = None
        best_gap = 0
        best_score = float("inf")

        for task in unassigned:
            for state in working_states:
                for gap in state.gaps:
                    feasible, _ = fast_feasible_insert_state(task, state, gap.gap_index)
                    if not feasible:
                        continue

                    # 计算距离增量
                    score = _greedy_insertion_score(task, state, gap.gap_index, station_map, matrix)

                    # 加上到当前末端位置的邻近度奖励
                    if current_positions:
                        pickup_station = station_map.get(task.pickup_station)
                        if pickup_station:
                            min_dist = min(
                                compute_distance(pos, pickup_station, matrix)
                                for pos in current_positions
                            )
                            # 距离越近，score 越小（越优先）
                            score += min_dist * 0.5

                    if score < best_score:
                        best_score = score
                        best_task = task
                        best_state = state
                        best_gap = gap.gap_index

        if best_task is None:
            break

        best_state.insert_task(best_task, best_gap)
        unassigned.remove(best_task)

    return working_states


def _select_next_task(
    unassigned: list[TaskBlock],
    route_states: list[RouteState],
    pheromone: PheromoneMatrix,
    station_map: dict[str, Station],
    matrix: DistanceMatrix | None,
    config: HacoConfig,
    rng: random.Random,
    gap_pheromone=None,
    alpha_gap: float = 0.5,
    last_task_id: str = "DEPOT",
) -> TaskBlock | None:
    """选择下一个要插入的任务（基于质量 + 地理邻近度）。

    关键改进：考虑当前路线末端位置，优先选择地理上邻近的任务。
    这解决了 HACO 不会合并同一站点附近任务的问题。
    """
    if not unassigned:
        return None

    # 获取当前路线末端位置（用于计算邻近度）
    current_positions = []
    for state in route_states:
        if state.tasks:
            last_task = state.tasks[-1]
            station = station_map.get(last_task.delivery_station)
            if station:
                current_positions.append(station)
        else:
            depot = station_map.get(state.depot_station)
            if depot:
                current_positions.append(depot)

    task_scores = []
    for task in unassigned:
        best_eta = float("inf")
        for state in route_states:
            for gap in state.gaps:
                feasible, _ = fast_feasible_insert_state(task, state, gap.gap_index)
                if not feasible:
                    continue
                score = _compute_insertion_score(task, state, gap.gap_index, station_map, matrix, config)
                best_eta = min(best_eta, score)

        if best_eta >= float("inf"):
            continue

        # 计算邻近度奖励（距离越近得分越高）
        proximity_bonus = 1.0
        if current_positions:
            pickup_station = station_map.get(task.pickup_station)
            if pickup_station:
                min_dist = min(
                    compute_distance(pos, pickup_station, matrix)
                    for pos in current_positions
                )
                # 距离越近，bonus 越大（最大 3x）
                proximity_bonus = max(1.0, 3.0 - min_dist * 100)

        tau = pheromone.get(last_task_id, task.task_id)
        urgency = _task_urgency(task)
        score = (tau ** config.alpha) * ((1.0 / (best_eta + EPSILON)) ** config.beta) * urgency * proximity_bonus
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


def _task_urgency(task: TaskBlock) -> float:
    """任务紧急度评分。"""
    if task.task_type == TaskType.PASSENGER:
        return 1.5
    elif task.task_type == TaskType.SHIPMENT:
        return 1.3
    elif task.task_type == TaskType.DELIVERY:
        return 1.0
    elif task.task_type == TaskType.PICKUP:
        return 1.0
    return 1.0


def _generate_candidates(
    task: TaskBlock,
    route_states: list[RouteState],
    station_map: dict[str, Station],
    matrix: DistanceMatrix | None,
    config: HacoConfig,
) -> list[CandidateInsertion]:
    """生成任务的所有合法候选插入。"""
    candidates = []

    for state in route_states:
        for gap in state.gaps:
            feasible, reason = fast_feasible_insert_state(task, state, gap.gap_index)
            if not feasible:
                continue

            delta_dist = _compute_delta_distance(task, state, gap.gap_index, station_map, matrix)
            delta_dur = _compute_delta_duration(task, state, gap.gap_index, station_map, matrix)
            p_impact = _compute_passenger_impact(task, state, gap.gap_index, station_map, matrix)
            c_detour = _compute_cargo_detour(task, state, gap.gap_index, station_map, matrix)
            h_score = _compute_insertion_score(task, state, gap.gap_index, station_map, matrix, config)

            candidates.append(CandidateInsertion(
                task_id=task.task_id,
                task=task,
                vehicle_index=state.vehicle_index,
                gap_index=gap.gap_index,
                delta_distance=delta_dist,
                delta_duration=delta_dur,
                passenger_impact=p_impact,
                cargo_detour=c_detour,
                heuristic_score=h_score,
                feasible=True,
            ))

    return candidates


def _select_insertion(
    candidates: list[CandidateInsertion],
    pheromone: PheromoneMatrix,
    config: HacoConfig,
    rng: random.Random,
    gap_pheromone=None,
    alpha_gap: float = 0.5,
    last_task_id: str = "DEPOT",
) -> CandidateInsertion:
    """基于信息素和启发式选择最佳插入（轮盘赌）。"""
    if len(candidates) == 1:
        return candidates[0]

    probs = []
    for cand in candidates:
        # 使用实际的 task-to-task 信息素
        tau_task = pheromone.get(last_task_id, cand.task_id)
        eta = cand.heuristic_score
        if gap_pheromone is not None:
            tau_gap = gap_pheromone.get(cand.task_id, cand.gap_index)
            prob = (tau_task ** config.alpha) * (tau_gap ** alpha_gap) * ((1.0 / (eta + EPSILON)) ** config.beta)
        else:
            prob = (tau_task ** config.alpha) * ((1.0 / (eta + EPSILON)) ** config.beta)
        probs.append(prob)

    total = sum(probs)
    if total <= 0:
        return rng.choice(candidates)

    probs = [p / total for p in probs]
    r = rng.random()
    cumulative = 0.0
    for i, prob in enumerate(probs):
        cumulative += prob
        if r <= cumulative:
            return candidates[i]

    return candidates[-1]


def _greedy_insertion_score(task, state, gap_index, station_map, matrix) -> float:
    """贪婪插入评分（纯距离）。"""
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


def _compute_delta_duration(task, state, gap_index, station_map, matrix) -> float:
    """计算插入任务后的时间增量（秒）。"""
    gaps = state.gaps
    if gap_index >= len(gaps):
        return 3600.0
    gap = gaps[gap_index]

    from_station = station_map.get(gap.from_station)
    to_station = station_map.get(gap.to_station)
    pickup = station_map.get(task.pickup_station)
    delivery = station_map.get(task.delivery_station)

    if not all([from_station, to_station, pickup, delivery]):
        return 3600.0

    orig = compute_duration(from_station, to_station, matrix)
    new = (compute_duration(from_station, pickup, matrix)
           + compute_duration(pickup, delivery, matrix)
           + compute_duration(delivery, to_station, matrix))

    return max(0.0, new - orig)


def _compute_passenger_impact(task, state, gap_index, station_map, matrix) -> float:
    """计算插入任务对车上乘客的影响（秒）。"""
    if task.task_type == TaskType.PASSENGER:
        return 0.0

    gap_tasks = state.get_tasks_in_gap(gap_index)
    passenger_count = state.initial_passenger_load
    for t in gap_tasks:
        if t.task_type == TaskType.PASSENGER:
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


def _compute_insertion_score(task, state, gap_index, station_map, matrix, config) -> float:
    """计算插入的综合启发式得分（越小越好）。"""
    delta_dist = _compute_delta_distance(task, state, gap_index, station_map, matrix)
    p_impact = _compute_passenger_impact(task, state, gap_index, station_map, matrix)
    c_detour = _compute_cargo_detour(task, state, gap_index, station_map, matrix)

    norm_dist = delta_dist / 10.0
    norm_passenger = p_impact / 300.0
    norm_detour = c_detour / 5.0

    skeleton_set = set(state.skeleton)
    skel_penalty = 0.0
    if task.pickup_station not in skeleton_set:
        skel_penalty += 0.5
    if task.delivery_station not in skeleton_set:
        skel_penalty += 0.5

    cost = (
        config.w_distance * norm_dist
        + config.w_passenger_impact * norm_passenger
        + config.w_detour * norm_detour
        + config.w_skeleton_penalty * skel_penalty
    )

    return max(cost, EPSILON)
