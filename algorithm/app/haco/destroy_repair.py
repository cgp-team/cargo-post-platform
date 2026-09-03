"""HACO-CPS LNS Destroy-Repair：基于 RouteState 的大邻域搜索。

Destroy：从路线中移除一部分任务
Repair：重新插入被移除的任务
"""

from __future__ import annotations

import random
from typing import TYPE_CHECKING

from .candidate import CandidateInsertion
from .construction import _compute_insertion_score, _generate_candidates
from .encoding import TaskType
from .feasibility import fast_feasible_insert_state
from .route_state import RouteState

if TYPE_CHECKING:
    from .config import HacoConfig
    from ..distance import DistanceMatrix
    from ..models import Station


def destroy_random(
    states: list[RouteState],
    destroy_ratio: float,
    rng: random.Random,
) -> tuple[list, list[RouteState]]:
    """随机破坏：随机移除一部分任务。"""
    all_tasks = []
    for state in states:
        all_tasks.extend(state.tasks)

    if not all_tasks:
        return [], [s.copy() for s in states]

    count = max(1, int(len(all_tasks) * destroy_ratio))
    count = min(count, len(all_tasks))

    to_remove = rng.sample(all_tasks, count)
    removed_ids = {t.task_id for t in to_remove}

    new_states = [s.copy() for s in states]
    for state in new_states:
        for task in list(state.tasks):
            if task.task_id in removed_ids:
                state.remove_task(task.task_id)

    return to_remove, new_states


def destroy_worst(
    states: list[RouteState],
    station_map: dict[str, Station],
    matrix: DistanceMatrix | None,
    destroy_ratio: float,
    rng: random.Random,
) -> tuple[list, list[RouteState]]:
    """最差破坏：移除增量成本最大的任务。"""
    all_tasks_with_cost = []
    for state in states:
        for task in state.tasks:
            # 估算移除该任务的成本节省
            cost = _estimate_task_cost(task, state, station_map, matrix)
            all_tasks_with_cost.append((task, cost))

    if not all_tasks_with_cost:
        return [], [s.copy() for s in states]

    # 按成本降序排序
    all_tasks_with_cost.sort(key=lambda x: x[1], reverse=True)

    count = max(1, int(len(all_tasks_with_cost) * destroy_ratio))
    count = min(count, len(all_tasks_with_cost))

    to_remove = [t for t, _ in all_tasks_with_cost[:count]]
    removed_ids = {t.task_id for t in to_remove}

    new_states = [s.copy() for s in states]
    for state in new_states:
        for task in list(state.tasks):
            if task.task_id in removed_ids:
                state.remove_task(task.task_id)

    return to_remove, new_states


def destroy_related(
    states: list[RouteState],
    station_map: dict[str, Station],
    matrix: DistanceMatrix | None,
    destroy_ratio: float,
    rng: random.Random,
) -> tuple[list, list[RouteState]]:
    """相关破坏：移除与随机种子任务相关的任务。"""
    all_tasks = []
    for state in states:
        all_tasks.extend(state.tasks)

    if not all_tasks:
        return [], [s.copy() for s in states]

    # 选择种子任务
    seed = rng.choice(all_tasks)
    seed_station = station_map.get(seed.pickup_station)

    if not seed_station:
        return destroy_random(states, destroy_ratio, rng)

    # 按距离排序
    from .heuristic import compute_distance
    tasks_with_dist = []
    for task in all_tasks:
        if task.task_id == seed.task_id:
            continue
        task_station = station_map.get(task.pickup_station)
        if task_station:
            dist = compute_distance(seed_station, task_station, matrix)
            tasks_with_dist.append((task, dist))

    tasks_with_dist.sort(key=lambda x: x[1])

    count = max(1, int(len(all_tasks) * destroy_ratio))
    count = min(count, len(all_tasks))

    to_remove = [seed]
    for task, _ in tasks_with_dist[:count - 1]:
        to_remove.append(task)

    removed_ids = {t.task_id for t in to_remove}

    new_states = [s.copy() for s in states]
    for state in new_states:
        for task in list(state.tasks):
            if task.task_id in removed_ids:
                state.remove_task(task.task_id)

    return to_remove, new_states


def repair_greedy(
    states: list[RouteState],
    tasks: list,
    station_map: dict[str, Station],
    matrix: DistanceMatrix | None,
    config: HacoConfig,
    rng: random.Random,
) -> list[RouteState]:
    """贪婪修复：按成本从低到高插入任务。"""
    remaining = list(tasks)
    rng.shuffle(remaining)

    for task in remaining:
        best_state = None
        best_gap = 0
        best_score = float("inf")

        for state in states:
            for gap in state.gaps:
                feasible, _ = fast_feasible_insert_state(task, state, gap.gap_index)
                if not feasible:
                    continue
                score = _compute_insertion_score(
                    task, state, gap.gap_index, station_map, matrix, config
                )
                if score < best_score:
                    best_score = score
                    best_state = state
                    best_gap = gap.gap_index

        if best_state is not None:
            best_state.insert_task(task, best_gap)

    return states


def repair_regret2(
    states: list[RouteState],
    tasks: list,
    station_map: dict[str, Station],
    matrix: DistanceMatrix | None,
    config: HacoConfig,
    rng: random.Random,
) -> list[RouteState]:
    """Regret-2 修复：优先插入"后悔值"最大的任务。

    后悔值 = 第二最佳插入成本 - 最佳插入成本
    后悔值大的任务意味着"如果现在不插入，以后会更贵"。
    """
    remaining = list(tasks)

    while remaining:
        best_task = None
        best_state = None
        best_gap = 0
        best_regret = float("-inf")

        for task in remaining:
            # 找最佳和次佳插入
            scores = []
            for state in states:
                for gap in state.gaps:
                    feasible, _ = fast_feasible_insert_state(task, state, gap.gap_index)
                    if not feasible:
                        continue
                    score = _compute_insertion_score(
                        task, state, gap.gap_index, station_map, matrix, config
                    )
                    scores.append((score, state, gap.gap_index))

            if not scores:
                continue

            scores.sort(key=lambda x: x[0])

            best_score = scores[0][0]
            second_score = scores[1][0] if len(scores) > 1 else best_score * 2

            regret = second_score - best_score

            if regret > best_regret:
                best_regret = regret
                best_task = task
                best_state = scores[0][1]
                best_gap = scores[0][2]

        if best_task is None:
            break

        best_state.insert_task(best_task, best_gap)
        remaining.remove(best_task)

    return states


def _estimate_task_cost(
    task,
    state: RouteState,
    station_map: dict[str, Station],
    matrix: DistanceMatrix | None,
) -> float:
    """估算任务在路线中的成本（用于最差破坏）。"""
    from .construction import _compute_delta_distance
    gap = state.task_gap_map.get(task.task_id)
    if gap is None:
        return 0.0
    return _compute_delta_distance(task, state, gap, station_map, matrix)
