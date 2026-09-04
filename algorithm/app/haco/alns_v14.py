"""HACO-CPS 1.4.0 ALNS：Adaptive Large Neighborhood Search。

包含：
- Destroy: random, worst, shaw, segment
- Repair: greedy, regret-2, regret-3
- Adaptive Operator Selector
"""

from __future__ import annotations

import math
import random
from dataclasses import dataclass, field
from typing import TYPE_CHECKING

from .deadline import SearchDeadline
from .encoding import TaskBlock, TaskType
from .evaluator import evaluate_route_genome, evaluate_route_states
from .feasibility_engine import FeasibilityEngine
from .route_genome import EventType, RouteGenome

if TYPE_CHECKING:
    from ..distance import DistanceMatrix
    from ..models import Station


# ─── Adaptive Operator Selector ──────────────────────────────


@dataclass
class OperatorStats:
    name: str
    usage: int = 0
    success: int = 0
    score: float = 0.0
    weight: float = 1.0


class AdaptiveOperatorSelector:
    """自适应算子选择器。"""

    def __init__(self, destroy_names: list[str], repair_names: list[str]):
        self.destroy_ops = {name: OperatorStats(name) for name in destroy_names}
        self.repair_ops = {name: OperatorStats(name) for name in repair_names}
        self.rho = 0.1  # 学习率

    def select_destroy(self, rng: random.Random) -> str:
        """轮盘赌选择 destroy 算子。"""
        names = list(self.destroy_ops.keys())
        weights = [self.destroy_ops[n].weight for n in names]
        total = sum(weights)
        if total <= 0:
            return rng.choice(names)

        probs = [w / total for w in weights]
        r = rng.random()
        cumulative = 0.0
        for i, prob in enumerate(probs):
            cumulative += prob
            if r <= cumulative:
                return names[i]
        return names[-1]

    def select_repair(self, rng: random.Random) -> str:
        """轮盘赌选择 repair 算子。"""
        names = list(self.repair_ops.keys())
        weights = [self.repair_ops[n].weight for n in names]
        total = sum(weights)
        if total <= 0:
            return rng.choice(names)

        probs = [w / total for w in weights]
        r = rng.random()
        cumulative = 0.0
        for i, prob in enumerate(probs):
            cumulative += prob
            if r <= cumulative:
                return names[i]
        return names[-1]

    def reward(
        self,
        destroy_name: str,
        repair_name: str,
        reward_type: str,
    ):
        """奖励算子。"""
        rewards = {
            "global_best": 8,
            "iteration_best": 5,
            "accepted_better": 3,
            "accepted_feasible": 1,
        }
        reward = rewards.get(reward_type, 0)

        if destroy_name in self.destroy_ops:
            op = self.destroy_ops[destroy_name]
            op.success += 1
            op.score += reward

        if repair_name in self.repair_ops:
            op = self.repair_ops[repair_name]
            op.success += 1
            op.score += reward

    def update_weights(self):
        """更新算子权重。"""
        for op in self.destroy_ops.values():
            if op.usage > 0:
                op.weight = op.weight * (1 - self.rho) + self.rho * (op.score / op.usage)
            op.usage = 0
            op.score = 0

        for op in self.repair_ops.values():
            if op.usage > 0:
                op.weight = op.weight * (1 - self.rho) + self.rho * (op.score / op.usage)
            op.usage = 0
            op.score = 0


# ─── Destroy Operators ───────────────────────────────────────


def destroy_random(
    routes: list[RouteGenome],
    tasks_by_id: dict[str, TaskBlock],
    destroy_fraction: float,
    rng: random.Random,
) -> tuple[list[RouteGenome], list[TaskBlock]]:
    """随机删除任务。"""
    all_tasks = []
    for route in routes:
        for task_id in route.placements:
            task = tasks_by_id.get(task_id)
            if task:
                all_tasks.append(task)

    destroy_count = max(1, int(len(all_tasks) * destroy_fraction))
    destroyed = rng.sample(all_tasks, min(destroy_count, len(all_tasks)))

    new_routes = [r.copy() for r in routes]
    for task in destroyed:
        for route in new_routes:
            if task.task_id in route.placements:
                route.remove_task(task.task_id)
                break

    return new_routes, destroyed


def destroy_worst(
    routes: list[RouteGenome],
    tasks_by_id: dict[str, TaskBlock],
    destroy_fraction: float,
    rng: random.Random,
    station_map: dict,
    matrix,
) -> tuple[list[RouteGenome], list[TaskBlock]]:
    """删除最差任务（按边际成本排序）。"""
    task_costs = []

    for route in routes:
        for task_id in route.placements:
            task = tasks_by_id.get(task_id)
            if not task:
                continue

            # 计算边际成本
            test_route = route.copy()
            test_route.remove_task(task_id)

            route_tasks = {
                eid.task_id: tasks_by_id[eid.task_id]
                for eid in test_route.events
                if eid.task_id and eid.task_id in tasks_by_id
            }

            if route_tasks:
                metrics = evaluate_route_genome(
                    test_route, route_tasks, station_map, matrix
                )
                cost = (
                    metrics["distance"]
                    + metrics["passenger_impact"] * 0.01
                    + metrics["cargo_detour"] * 10.0
                )
            else:
                cost = 0.0

            task_costs.append((task, cost))

    # 按成本排序（成本高的先删除）
    task_costs.sort(key=lambda x: x[1], reverse=True)

    destroy_count = max(1, int(len(task_costs) * destroy_fraction))
    destroyed = [t for t, _ in task_costs[:destroy_count]]

    new_routes = [r.copy() for r in routes]
    for task in destroyed:
        for route in new_routes:
            if task.task_id in route.placements:
                route.remove_task(task.task_id)
                break

    return new_routes, destroyed


def destroy_shaw(
    routes: list[RouteGenome],
    tasks_by_id: dict[str, TaskBlock],
    destroy_fraction: float,
    rng: random.Random,
    station_map: dict,
    matrix,
) -> tuple[list[RouteGenome], list[TaskBlock]]:
    """Shaw 相关性删除。"""
    all_tasks = []
    for route in routes:
        for task_id in route.placements:
            task = tasks_by_id.get(task_id)
            if task:
                all_tasks.append((route, task))

    if not all_tasks:
        return [r.copy() for r in routes], []

    # 选择种子任务
    seed_route, seed_task = rng.choice(all_tasks)

    # 计算相关性
    relatedness = []
    for route, task in all_tasks:
        if task.task_id == seed_task.task_id:
            continue

        # pickup 距离
        pickup_dist = _station_distance(
            seed_task.pickup_station, task.pickup_station,
            station_map, matrix
        )

        # delivery 距离
        delivery_dist = _station_distance(
            seed_task.delivery_station, task.delivery_station,
            station_map, matrix
        )

        # 类型相似性
        type_sim = 1.0 if seed_task.task_type == task.task_type else 0.0

        # 车辆相似性
        vehicle_sim = 1.0 if seed_route.vehicle_index == route.vehicle_index else 0.0

        score = (
            0.35 * pickup_dist
            + 0.35 * delivery_dist
            + 0.15 * type_sim
            + 0.15 * vehicle_sim
        )

        relatedness.append((route, task, score))

    # 按相关性排序
    relatedness.sort(key=lambda x: x[2])

    destroy_count = max(1, int(len(all_tasks) * destroy_fraction))
    destroyed = [t for _, t, _ in relatedness[:destroy_count]]

    new_routes = [r.copy() for r in routes]
    for task in destroyed:
        for route in new_routes:
            if task.task_id in route.placements:
                route.remove_task(task.task_id)
                break

    return new_routes, destroyed


def destroy_segment(
    routes: list[RouteGenome],
    tasks_by_id: dict[str, TaskBlock],
    destroy_fraction: float,
    rng: random.Random,
) -> tuple[list[RouteGenome], list[TaskBlock]]:
    """从路线中删除连续段。"""
    new_routes = [r.copy() for r in routes]
    destroyed = []

    for route in new_routes:
        if route.task_count() < 2:
            continue

        # 选择连续段
        task_ids = [e.task_id for e in route.events if e.task_id]
        if len(task_ids) < 2:
            continue

        segment_len = max(1, int(len(task_ids) * destroy_fraction))
        start = rng.randrange(len(task_ids) - segment_len + 1)
        segment = task_ids[start:start + segment_len]

        for task_id in segment:
            task = tasks_by_id.get(task_id)
            if task:
                destroyed.append(task)
                route.remove_task(task_id)

    return new_routes, destroyed


# ─── Repair Operators ────────────────────────────────────────


def repair_greedy(
    routes: list[RouteGenome],
    destroyed_tasks: list[TaskBlock],
    tasks_by_id: dict[str, TaskBlock],
    feasibility_engine: FeasibilityEngine,
    station_map: dict,
    matrix,
    passenger_capacities: dict[int, int],
    cargo_capacities: dict[int, int],
    initial_passenger_loads: dict[int, int],
    initial_cargo_loads: dict[int, int],
    rng: random.Random,
) -> list[RouteGenome]:
    """贪心修复：逐个插入最佳位置。"""
    new_routes = [r.copy() for r in routes]
    remaining = list(destroyed_tasks)

    while remaining:
        best_task = None
        best_route_idx = None
        best_pickup = None
        best_delivery = None
        best_score = float("inf")

        for task in remaining:
            for route_idx, route in enumerate(new_routes):
                event_count = len(route.events)

                if task.task_type in (TaskType.PASSENGER, TaskType.SHIPMENT):
                    for pickup in range(1, event_count):
                        for delivery in range(pickup + 1, event_count + 1):
                            score = _insertion_score(
                                task, route, pickup, delivery,
                                tasks_by_id, station_map, matrix,
                                feasibility_engine,
                                passenger_capacities, cargo_capacities,
                                initial_passenger_loads, initial_cargo_loads,
                            )
                            if score < best_score:
                                best_score = score
                                best_task = task
                                best_route_idx = route_idx
                                best_pickup = pickup
                                best_delivery = delivery
                else:
                    for pickup in range(1, event_count):
                        score = _insertion_score(
                            task, route, pickup, None,
                            tasks_by_id, station_map, matrix,
                            feasibility_engine,
                            passenger_capacities, cargo_capacities,
                            initial_passenger_loads, initial_cargo_loads,
                        )
                        if score < best_score:
                            best_score = score
                            best_task = task
                            best_route_idx = route_idx
                            best_pickup = pickup
                            best_delivery = None

        if best_task is None:
            break

        new_routes[best_route_idx].insert_task(
            best_task, best_pickup, best_delivery
        )
        remaining.remove(best_task)

    return new_routes


def repair_regret_k(
    routes: list[RouteGenome],
    destroyed_tasks: list[TaskBlock],
    tasks_by_id: dict[str, TaskBlock],
    feasibility_engine: FeasibilityEngine,
    station_map: dict,
    matrix,
    passenger_capacities: dict[int, int],
    cargo_capacities: dict[int, int],
    initial_passenger_loads: dict[int, int],
    initial_cargo_loads: dict[int, int],
    rng: random.Random,
    k: int = 2,
) -> list[RouteGenome]:
    """Regret-k 修复。"""
    new_routes = [r.copy() for r in routes]
    remaining = list(destroyed_tasks)

    while remaining:
        best_task = None
        best_route_idx = None
        best_pickup = None
        best_delivery = None
        best_regret = float("-inf")

        for task in remaining:
            # 收集所有可行插入的成本
            insertions = []

            for route_idx, route in enumerate(new_routes):
                event_count = len(route.events)

                if task.task_type in (TaskType.PASSENGER, TaskType.SHIPMENT):
                    for pickup in range(1, event_count):
                        for delivery in range(pickup + 1, event_count + 1):
                            score = _insertion_score(
                                task, route, pickup, delivery,
                                tasks_by_id, station_map, matrix,
                                feasibility_engine,
                                passenger_capacities, cargo_capacities,
                                initial_passenger_loads, initial_cargo_loads,
                            )
                            if score < float("inf"):
                                insertions.append((score, route_idx, pickup, delivery))
                else:
                    for pickup in range(1, event_count):
                        score = _insertion_score(
                            task, route, pickup, None,
                            tasks_by_id, station_map, matrix,
                            feasibility_engine,
                            passenger_capacities, cargo_capacities,
                            initial_passenger_loads, initial_cargo_loads,
                        )
                        if score < float("inf"):
                            insertions.append((score, route_idx, pickup, None))

            if not insertions:
                continue

            # 排序
            insertions.sort(key=lambda x: x[0])

            # 计算 regret
            if len(insertions) >= k:
                regret = insertions[k - 1][0] - insertions[0][0]
            else:
                regret = insertions[-1][0] - insertions[0][0]

            if regret > best_regret:
                best_regret = regret
                best_task = task
                best_route_idx = insertions[0][1]
                best_pickup = insertions[0][2]
                best_delivery = insertions[0][3]

        if best_task is None:
            break

        new_routes[best_route_idx].insert_task(
            best_task, best_pickup, best_delivery
        )
        remaining.remove(best_task)

    return new_routes


# ─── Helper Functions ────────────────────────────────────────


def _station_distance(
    station1: str, station2: str,
    station_map: dict, matrix,
) -> float:
    """计算两站距离。"""
    s1 = station_map.get(station1)
    s2 = station_map.get(station2)
    if not s1 or not s2:
        return 10.0

    if matrix:
        key = (s1.stationId, s2.stationId)
        if key in matrix:
            return matrix[key][0]

    from math import hypot
    return hypot(s1.longitude - s2.longitude, s1.latitude - s2.latitude)


def _insertion_score(
    task: TaskBlock,
    route: RouteGenome,
    pickup_pos: int,
    delivery_pos: int | None,
    tasks_by_id: dict[str, TaskBlock],
    station_map: dict,
    matrix,
    feasibility_engine: FeasibilityEngine,
    passenger_capacities: dict[int, int],
    cargo_capacities: dict[int, int],
    initial_passenger_loads: dict[int, int],
    initial_cargo_loads: dict[int, int],
) -> float:
    """计算插入得分。"""
    test_route = route.copy()

    try:
        test_route.insert_task(task, pickup_pos, delivery_pos)
    except (ValueError, IndexError):
        return float("inf")

    # 检查可行性
    route_tasks = {
        eid.task_id: tasks_by_id[eid.task_id]
        for eid in test_route.events
        if eid.task_id and eid.task_id in tasks_by_id
    }

    result = feasibility_engine.check(
        test_route,
        route_tasks,
        passenger_capacities[route.vehicle_index],
        cargo_capacities[route.vehicle_index],
        initial_passenger_loads[route.vehicle_index],
        initial_cargo_loads[route.vehicle_index],
        station_map=station_map,
        matrix=matrix,
    )

    if not result.feasible:
        return float("inf")

    # 计算成本
    metrics = evaluate_route_genome(
        test_route, route_tasks, station_map, matrix
    )

    return (
        metrics["distance"]
        + metrics["passenger_impact"] * 0.01
        + metrics["cargo_detour"] * 10.0
    )


# ─── ALNS Main Loop ─────────────────────────────────────────


def alns_search(
    routes: list[RouteGenome],
    tasks_by_id: dict[str, TaskBlock],
    feasibility_engine: FeasibilityEngine,
    station_map: dict,
    matrix,
    passenger_capacities: dict[int, int],
    cargo_capacities: dict[int, int],
    initial_passenger_loads: dict[int, int],
    initial_cargo_loads: dict[int, int],
    config,
    rng: random.Random,
    max_iterations: int = 100,
    deadline: SearchDeadline | None = None,
) -> list[RouteGenome]:
    """ALNS 主搜索循环。"""
    destroy_names = ["random", "worst", "shaw", "segment"]
    repair_names = ["greedy", "regret-2", "regret-3"]

    selector = AdaptiveOperatorSelector(destroy_names, repair_names)

    best_routes = [r.copy() for r in routes]
    best_obj = evaluate_route_states(best_routes, tasks_by_id, station_map, matrix)

    current_routes = [r.copy() for r in routes]
    current_obj = best_obj

    temperature = config.initial_temperature if hasattr(config, 'initial_temperature') else 10.0
    cooling_rate = config.cooling_rate if hasattr(config, 'cooling_rate') else 0.995

    for iteration in range(max_iterations):
        if deadline is not None and deadline.expired():
            break
        # 选择算子
        destroy_name = selector.select_destroy(rng)
        repair_name = selector.select_repair(rng)

        selector.destroy_ops[destroy_name].usage += 1
        selector.repair_ops[repair_name].usage += 1

        # Destroy
        destroy_fraction = config.destroy_fraction if hasattr(config, 'destroy_fraction') else 0.25

        if destroy_name == "random":
            destroyed_routes, removed = destroy_random(
                current_routes, tasks_by_id, destroy_fraction, rng
            )
        elif destroy_name == "worst":
            destroyed_routes, removed = destroy_worst(
                current_routes, tasks_by_id, destroy_fraction, rng,
                station_map, matrix
            )
        elif destroy_name == "shaw":
            destroyed_routes, removed = destroy_shaw(
                current_routes, tasks_by_id, destroy_fraction, rng,
                station_map, matrix
            )
        else:  # segment
            destroyed_routes, removed = destroy_segment(
                current_routes, tasks_by_id, destroy_fraction, rng
            )

        # Repair
        if repair_name == "greedy":
            repaired = repair_greedy(
                destroyed_routes, removed, tasks_by_id,
                feasibility_engine, station_map, matrix,
                passenger_capacities, cargo_capacities,
                initial_passenger_loads, initial_cargo_loads, rng
            )
        elif repair_name == "regret-2":
            repaired = repair_regret_k(
                destroyed_routes, removed, tasks_by_id,
                feasibility_engine, station_map, matrix,
                passenger_capacities, cargo_capacities,
                initial_passenger_loads, initial_cargo_loads, rng, k=2
            )
        else:  # regret-3
            repaired = repair_regret_k(
                destroyed_routes, removed, tasks_by_id,
                feasibility_engine, station_map, matrix,
                passenger_capacities, cargo_capacities,
                initial_passenger_loads, initial_cargo_loads, rng, k=3
            )

        # 评估
        repaired_obj = evaluate_route_states(
            repaired, tasks_by_id, station_map, matrix
        )

        # 接受准则
        accept = False
        reward_type = None

        if repaired_obj < current_obj:
            accept = True
            reward_type = "accepted_better"

            if repaired_obj < best_obj:
                best_routes = [r.copy() for r in repaired]
                best_obj = repaired_obj
                reward_type = "global_best"
        else:
            # SA 接受
            delta = repaired_obj.key() > current_obj.key()
            if temperature > 0.01:
                prob = math.exp(-1.0 / temperature)
                if rng.random() < prob:
                    accept = True
                    reward_type = "accepted_feasible"

        if accept:
            current_routes = repaired
            current_obj = repaired_obj

            if reward_type:
                selector.reward(destroy_name, repair_name, reward_type)

        # 冷却
        temperature *= cooling_rate

        # 更新权重
        if iteration % 10 == 0:
            selector.update_weights()

    return best_routes
