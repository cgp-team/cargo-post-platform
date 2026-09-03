"""HACO-CPS 1.2.0 LNS Destroy-Repair：自适应算子选择。

Destroy: random / worst / shaw / segment
Repair: greedy / regret-2 / regret-3 / gap-best
"""

from __future__ import annotations

import random
from dataclasses import dataclass
from typing import TYPE_CHECKING

from .encoding import TaskType
from .feasibility import fast_feasible_insert_state
from .heuristic import compute_distance, compute_insertion_score
from .route_state import RouteState

if TYPE_CHECKING:
    from .config import HacoConfig
    from ..distance import DistanceMatrix
    from ..models import Station


# ── Destroy 算子 ────────────────────────────────────────────

def destroy_random(states, destroy_ratio, rng) -> tuple[list, list[RouteState]]:
    """随机破坏。"""
    all_tasks = _collect_all_tasks(states)
    if not all_tasks:
        return [], [s.copy() for s in states]

    count = max(1, int(len(all_tasks) * destroy_ratio))
    to_remove = rng.sample(all_tasks, min(count, len(all_tasks)))
    return to_remove, _remove_tasks(states, to_remove)


def destroy_worst(states, station_map, matrix, destroy_ratio, rng) -> tuple[list, list[RouteState]]:
    """最差破坏：移除增量成本最大的任务。"""
    all_tasks = _collect_all_tasks(states)
    if not all_tasks:
        return [], [s.copy() for s in states]

    costs = []
    for task in all_tasks:
        cost = _estimate_task_cost(task, states, station_map, matrix)
        costs.append((task, cost))

    costs.sort(key=lambda x: x[1], reverse=True)
    count = max(1, int(len(all_tasks) * destroy_ratio))
    to_remove = [t for t, _ in costs[:count]]
    return to_remove, _remove_tasks(states, to_remove)


def destroy_shaw(states, station_map, matrix, destroy_ratio, rng) -> tuple[list, list[RouteState]]:
    """Shaw 破坏：移除相关性高的任务。"""
    all_tasks = _collect_all_tasks(states)
    if not all_tasks:
        return [], [s.copy() for s in states]

    seed = rng.choice(all_tasks)
    seed_station = station_map.get(seed.pickup_station)
    if not seed_station:
        return destroy_random(states, destroy_ratio, rng)

    # 计算相关性
    related = []
    for task in all_tasks:
        if task.task_id == seed.task_id:
            continue
        rel = _task_relatedness(seed, task, station_map, matrix)
        related.append((task, rel))

    related.sort(key=lambda x: x[1], reverse=True)  # 高相关性排前面
    count = max(1, int(len(all_tasks) * destroy_ratio))
    to_remove = [seed] + [t for t, _ in related[:count - 1]]
    return to_remove, _remove_tasks(states, to_remove)


def destroy_segment(states, station_map, matrix, destroy_ratio, rng) -> tuple[list, list[RouteState]]:
    """Segment 破坏：删除某个骨架间隙中的任务。"""
    active_states = [s for s in states if s.tasks]
    if not active_states:
        return [], [s.copy() for s in states]

    state = rng.choice(active_states)
    gaps_with_tasks = [g for g in state.gaps if state.get_tasks_in_gap(g.gap_index)]
    if not gaps_with_tasks:
        return destroy_random(states, destroy_ratio, rng)

    gap = rng.choice(gaps_with_tasks)
    gap_tasks = state.get_tasks_in_gap(gap.gap_index)

    # 删除该 gap 中一定比例的任务
    count = max(1, int(len(gap_tasks) * destroy_ratio))
    to_remove = rng.sample(gap_tasks, min(count, len(gap_tasks)))
    return to_remove, _remove_tasks(states, to_remove)


# ── Repair 算子 ────────────────────────────────────────────

def repair_greedy(states, tasks, station_map, matrix, config, rng) -> list[RouteState]:
    """贪婪修复。"""
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
                score = compute_insertion_score(task, state, gap.gap_index, station_map, matrix, config)
                if score < best_score:
                    best_score = score
                    best_state = state
                    best_gap = gap.gap_index

        if best_state is not None:
            best_state.insert_task(task, best_gap)

    return states


def repair_regret2(states, tasks, station_map, matrix, config, rng) -> list[RouteState]:
    """Regret-2 修复。"""
    remaining = list(tasks)

    while remaining:
        best_task = None
        best_state = None
        best_gap = 0
        best_regret = float("-inf")

        for task in remaining:
            scores = []
            for state in states:
                for gap in state.gaps:
                    feasible, _ = fast_feasible_insert_state(task, state, gap.gap_index)
                    if not feasible:
                        continue
                    score = compute_insertion_score(task, state, gap.gap_index, station_map, matrix, config)
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


def repair_regret3(states, tasks, station_map, matrix, config, rng) -> list[RouteState]:
    """Regret-3 修复。"""
    remaining = list(tasks)

    while remaining:
        best_task = None
        best_state = None
        best_gap = 0
        best_regret = float("-inf")

        for task in remaining:
            scores = []
            for state in states:
                for gap in state.gaps:
                    feasible, _ = fast_feasible_insert_state(task, state, gap.gap_index)
                    if not feasible:
                        continue
                    score = compute_insertion_score(task, state, gap.gap_index, station_map, matrix, config)
                    scores.append((score, state, gap.gap_index))

            if not scores:
                continue

            scores.sort(key=lambda x: x[0])
            best_score = scores[0][0]
            second_score = scores[1][0] if len(scores) > 1 else best_score * 2
            third_score = scores[2][0] if len(scores) > 2 else best_score * 3
            regret = (third_score - best_score) + (second_score - best_score)

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


def repair_gap_best(states, tasks, station_map, matrix, config, rng) -> list[RouteState]:
    """Gap-aware 修复：优先选择最佳 gap。"""
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
                score = compute_insertion_score(task, state, gap.gap_index, station_map, matrix, config)
                if score < best_score:
                    best_score = score
                    best_state = state
                    best_gap = gap.gap_index

        if best_state is not None:
            best_state.insert_task(task, best_gap)

    return states


# ── 自适应算子选择 ──────────────────────────────────────────

@dataclass
class OperatorStats:
    """算子统计。"""
    name: str
    weight: float = 1.0
    usage: int = 0
    success: int = 0
    score: float = 0.0


class AdaptiveOperatorSelector:
    """自适应算子选择器。"""

    def __init__(self, destroy_names: list[str], repair_names: list[str]):
        self.destroy_ops = {name: OperatorStats(name=name) for name in destroy_names}
        self.repair_ops = {name: OperatorStats(name=name) for name in repair_names}

    def select_destroy(self, rng: random.Random) -> str:
        """轮盘赌选择破坏算子。"""
        ops = list(self.destroy_ops.values())
        weights = [max(0.01, op.weight) for op in ops]
        total = sum(weights)
        probs = [w / total for w in weights]

        r = rng.random()
        cumulative = 0.0
        for i, prob in enumerate(probs):
            cumulative += prob
            if r <= cumulative:
                return ops[i].name
        return ops[-1].name

    def select_repair(self, rng: random.Random) -> str:
        """轮盘赌选择修复算子。"""
        ops = list(self.repair_ops.values())
        weights = [max(0.01, op.weight) for op in ops]
        total = sum(weights)
        probs = [w / total for w in weights]

        r = rng.random()
        cumulative = 0.0
        for i, prob in enumerate(probs):
            cumulative += prob
            if r <= cumulative:
                return ops[i].name
        return ops[-1].name

    def reward(self, destroy_name: str, repair_name: str, reward_type: str) -> None:
        """奖励算子。"""
        reward_values = {
            "global_best": 8,
            "iteration_best": 5,
            "accepted_better": 3,
            "accepted_feasible": 1,
        }
        value = reward_values.get(reward_type, 0)

        if destroy_name in self.destroy_ops:
            op = self.destroy_ops[destroy_name]
            op.usage += 1
            op.success += 1
            op.score += value
            op.weight = op.weight * 0.8 + (op.score / max(1, op.usage)) * 0.2

        if repair_name in self.repair_ops:
            op = self.repair_ops[repair_name]
            op.usage += 1
            op.success += 1
            op.score += value
            op.weight = op.weight * 0.8 + (op.score / max(1, op.usage)) * 0.2

    def record_usage(self, destroy_name: str, repair_name: str) -> None:
        """记录使用。"""
        if destroy_name in self.destroy_ops:
            self.destroy_ops[destroy_name].usage += 1
        if repair_name in self.repair_ops:
            self.repair_ops[repair_name].usage += 1


# ── 辅助函数 ──────────────────────────────────────────────


def _collect_all_tasks(states) -> list:
    """收集所有任务。"""
    all_tasks = []
    for state in states:
        all_tasks.extend(state.tasks)
    return all_tasks


def _remove_tasks(states, tasks) -> list[RouteState]:
    """从路线中移除任务。"""
    removed_ids = {t.task_id for t in tasks}
    new_states = [s.copy() for s in states]
    for state in new_states:
        for task in list(state.tasks):
            if task.task_id in removed_ids:
                state.remove_task(task.task_id)
    return new_states


def _estimate_task_cost(task, states, station_map, matrix) -> float:
    """估算任务在路线中的成本。"""
    for state in states:
        gap = state.task_gap_map.get(task.task_id)
        if gap is not None:
            return compute_insertion_score(task, state, gap, station_map, matrix, type('C', (), {
                'w_distance': 0.3, 'w_passenger_impact': 0.25, 'w_detour': 0.2,
                'w_time_risk': 0.15, 'w_skeleton_penalty': 0.1, 'w_capacity_risk': 0.0,
            })())
    return 0.0


def _task_relatedness(t1, t2, station_map, matrix) -> float:
    """计算两个任务的相关性（0~1，越高越相关）。"""
    s1 = station_map.get(t1.pickup_station)
    s2 = station_map.get(t2.pickup_station)
    if not s1 or not s2:
        return 0.0

    dist = compute_distance(s1, s2, matrix)
    # 距离越近相关性越高
    geo_sim = max(0, 1.0 - dist / 20.0)

    # 同类型任务相关性更高
    type_sim = 1.0 if t1.task_type == t2.task_type else 0.5

    return 0.7 * geo_sim + 0.3 * type_sim
