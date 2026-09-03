"""HACO-CPS 局部搜索：Relocate / Swap，基于 RouteState。

所有操作必须 block-aware：
- Passenger: BOARD+ALIGHT 整体移动
- Shipment: PICKUP+DELIVERY 整体移动
- DELIVERY/PICKUP: 单站移动
"""

from __future__ import annotations

import random
from typing import TYPE_CHECKING

from .encoding import ObjectiveVector, TaskType
from .evaluator import evaluate_route_states
from .feasibility import fast_feasible_insert_state
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
    """对解执行多轮局部搜索。"""
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

        # Swap
        new_states = _swap(best, station_map, matrix, config, rng)
        if new_states:
            new_obj = evaluate_route_states(new_states, station_map, matrix)
            if new_obj < best_obj:
                best = new_states
                best_obj = new_obj
                improved = True

        if not improved:
            break

    return best


def _relocate(
    states: list[RouteState],
    station_map: dict[str, Station],
    matrix: DistanceMatrix | None,
    config: HacoConfig,
    rng: random.Random,
) -> list[RouteState] | None:
    """Relocate：将一个任务从一条路线移到另一条。"""
    # 选择有任务的路线
    active_states = [s for s in states if s.tasks]
    if not active_states:
        return None

    # 随机选择源路线和任务
    src = rng.choice(active_states)
    if not src.tasks:
        return None

    task = rng.choice(src.tasks)

    # 尝试插入到其他路线的各个 gap
    for dst in states:
        if dst is src:
            continue
        for gap in dst.gaps:
            feasible, _ = fast_feasible_insert_state(task, dst, gap.gap_index)
            if feasible:
                # 执行移动
                new_states = [s.copy() for s in states]
                new_src = new_states[states.index(src)]
                new_dst = new_states[states.index(dst)]

                # 移除
                new_src.remove_task(task.task_id)
                # 插入
                new_dst.insert_task(task, gap.gap_index)
                return new_states

    return None


def _swap(
    states: list[RouteState],
    station_map: dict[str, Station],
    matrix: DistanceMatrix | None,
    config: HacoConfig,
    rng: random.Random,
) -> list[RouteState] | None:
    """Swap：交换两个任务的位置。"""
    active_states = [s for s in states if s.tasks]
    if len(active_states) < 1:
        return None

    # 选择两个任务
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

    # 找到各自当前的 gap
    g1 = s1.task_gap_map.get(t1.task_id)
    g2 = s2.task_gap_map.get(t2.task_id)
    if g1 is None or g2 is None:
        return None

    # 检查交换后是否可行
    # t1 到 s2 的 g2 位置
    ok1, _ = fast_feasible_insert_state(t1, s2, g2)
    # t2 到 s1 的 g1 位置
    ok2, _ = fast_feasible_insert_state(t2, s1, g1)

    if ok1 and ok2:
        new_states = [s.copy() for s in states]
        ns1 = new_states[states.index(s1)]
        ns2 = new_states[states.index(s2)]

        # 移除
        ns1.remove_task(t1.task_id)
        ns2.remove_task(t2.task_id)

        # 交换插入
        ns1.insert_task(t2, g1)
        ns2.insert_task(t1, g2)
        return new_states

    return None
