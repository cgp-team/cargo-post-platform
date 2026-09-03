"""HACO-CPS 1.2.0 局部搜索：Relocate / Swap / 2-opt / Or-opt / SWAP*。

所有操作必须 block-aware：
- Passenger: BOARD+ALIGHT 整体移动
- Shipment: PICKUP+DELIVERY 整体移动
- DELIVERY/PICKUP: 单站移动

执行顺序：Relocate → Or-opt → SWAP* → 2-opt
"""

from __future__ import annotations

import random
from typing import TYPE_CHECKING

from .encoding import ObjectiveVector, TaskType
from .evaluator import evaluate_route_states
from .feasibility import fast_feasible_insert_state
from .heuristic import compute_insertion_score
from .route_state import RouteState

if TYPE_CHECKING:
    from .config import HacoConfig
    from ..distance import DistanceMatrix
    from ..models import Station


def local_search(
    states: list[RouteState],
    station_map: dict[str, Station],
    matrix: DistanceMatrix | None,
    config: HacoConfig,
    rng: random.Random,
) -> list[RouteState]:
    """对解执行多轮局部搜索（best improvement）。"""
    best = [s.copy() for s in states]
    best_obj = evaluate_route_states(best, station_map, matrix)

    for _ in range(config.local_search_rounds):
        improved = False

        # Relocate
        new_states = _relocate(best, station_map, matrix, config, rng)
        if new_states:
            new_obj = evaluate_route_states(new_states, station_map, matrix)
            if new_obj < best_obj:
                best = new_states
                best_obj = new_obj
                improved = True

        # Or-opt
        for block_size in [1, 2]:
            new_states = _or_opt(best, block_size, station_map, matrix, config, rng)
            if new_states:
                new_obj = evaluate_route_states(new_states, station_map, matrix)
                if new_obj < best_obj:
                    best = new_states
                    best_obj = new_obj
                    improved = True

        # SWAP*
        new_states = _swap_star(best, station_map, matrix, config, rng)
        if new_states:
            new_obj = evaluate_route_states(new_states, station_map, matrix)
            if new_obj < best_obj:
                best = new_states
                best_obj = new_obj
                improved = True

        # 2-opt (within gaps)
        new_states = _two_opt(best, station_map, matrix, config, rng)
        if new_states:
            new_obj = evaluate_route_states(new_states, station_map, matrix)
            if new_obj < best_obj:
                best = new_states
                best_obj = new_obj
                improved = True

        if not improved:
            break

    return best


def _relocate(states, station_map, matrix, config, rng) -> list[RouteState] | None:
    """Relocate：将一个任务从一条路线移到另一条。"""
    active_states = [s for s in states if s.tasks]
    if not active_states:
        return None

    src = rng.choice(active_states)
    if not src.tasks:
        return None

    task = rng.choice(src.tasks)

    for dst in states:
        if dst is src:
            continue
        for gap in dst.gaps:
            feasible, _ = fast_feasible_insert_state(task, dst, gap.gap_index)
            if feasible:
                new_states = [s.copy() for s in states]
                new_src = new_states[states.index(src)]
                new_dst = new_states[states.index(dst)]
                new_src.remove_task(task.task_id)
                new_dst.insert_task(task, gap.gap_index)
                return new_states

    return None


def _or_opt(states, block_size, station_map, matrix, config, rng) -> list[RouteState] | None:
    """Or-opt：移动连续 block_size 个任务到新位置。"""
    active_states = [s for s in states if len(s.tasks) >= block_size]
    if not active_states:
        return None

    src = rng.choice(active_states)
    if len(src.tasks) < block_size:
        return None

    # 选择连续 block
    start_idx = rng.randrange(len(src.tasks) - block_size + 1)
    block = src.tasks[start_idx:start_idx + block_size]

    # 尝试插入到其他位置
    for dst in states:
        for gap in dst.gaps:
            # 检查 block 中所有任务是否可行
            all_feasible = True
            for task in block:
                ok, _ = fast_feasible_insert_state(task, dst, gap.gap_index)
                if not ok:
                    all_feasible = False
                    break
            if all_feasible:
                new_states = [s.copy() for s in states]
                new_src = new_states[states.index(src)]
                new_dst = new_states[states.index(dst)]
                for task in block:
                    new_src.remove_task(task.task_id)
                    new_dst.insert_task(task, gap.gap_index)
                return new_states

    return None


def _swap_star(states, station_map, matrix, config, rng) -> list[RouteState] | None:
    """SWAP*：删除两个任务后分别寻找最佳插入位置。"""
    active_states = [s for s in states if s.tasks]
    if len(active_states) < 1:
        return None

    s1 = rng.choice(active_states)
    if not s1.tasks:
        return None
    t1 = rng.choice(s1.tasks)

    s2 = rng.choice(active_states)
    if not s2.tasks:
        return None
    t2 = rng.choice(s2.tasks)

    if t1.task_id == t2.task_id:
        return None

    # t1 到 s2 的最佳位置
    best_gap_1 = None
    best_score_1 = float("inf")
    for gap in s2.gaps:
        ok, _ = fast_feasible_insert_state(t1, s2, gap.gap_index)
        if ok:
            score = compute_insertion_score(t1, s2, gap.gap_index, station_map, matrix, config)
            if score < best_score_1:
                best_score_1 = score
                best_gap_1 = gap.gap_index

    # t2 到 s1 的最佳位置
    best_gap_2 = None
    best_score_2 = float("inf")
    for gap in s1.gaps:
        ok, _ = fast_feasible_insert_state(t2, s1, gap.gap_index)
        if ok:
            score = compute_insertion_score(t2, s1, gap.gap_index, station_map, matrix, config)
            if score < best_score_2:
                best_score_2 = score
                best_gap_2 = gap.gap_index

    if best_gap_1 is not None and best_gap_2 is not None:
        new_states = [s.copy() for s in states]
        ns1 = new_states[states.index(s1)]
        ns2 = new_states[states.index(s2)]
        ns1.remove_task(t1.task_id)
        ns2.remove_task(t2.task_id)
        ns1.insert_task(t2, best_gap_2)
        ns2.insert_task(t1, best_gap_1)
        return new_states

    return None


def _two_opt(states, station_map, matrix, config, rng) -> list[RouteState] | None:
    """2-opt：在骨架间隙内重新排序任务块。"""
    active_states = [s for s in states if len(s.tasks) >= 2]
    if not active_states:
        return None

    state = rng.choice(active_states)
    if len(state.tasks) < 2:
        return None

    # 找到同一 gap 中的两个任务
    for gap in state.gaps:
        gap_tasks = state.get_tasks_in_gap(gap.gap_index)
        if len(gap_tasks) >= 2:
            # 尝试反转
            i = rng.randrange(len(gap_tasks))
            j = rng.randrange(len(gap_tasks))
            if i == j:
                continue

            # 创建新状态（任务顺序改变通过 gap 内的插入顺序体现）
            new_states = [s.copy() for s in states]
            new_state = new_states[states.index(state)]
            # 2-opt 在 gap 内：移除再重新插入
            task_i = gap_tasks[i]
            task_j = gap_tasks[j]

            # 简化：交换两个任务的 gap 位置
            # 实际上在同一 gap 中，顺序由 insertion list 决定
            # 这里通过移除再插入来改变顺序
            new_state.remove_task(task_i.task_id)
            new_state.remove_task(task_j.task_id)

            # 重新插入（交换顺序）
            ok1, _ = fast_feasible_insert_state(task_j, new_state, gap.gap_index)
            ok2, _ = fast_feasible_insert_state(task_i, new_state, gap.gap_index)
            if ok1 and ok2:
                new_state.insert_task(task_j, gap.gap_index)
                new_state.insert_task(task_i, gap.gap_index)
                return new_states

    return None
