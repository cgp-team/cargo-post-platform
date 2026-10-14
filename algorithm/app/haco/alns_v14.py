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
from .encoding import ObjectiveVector, TaskBlock, TaskType
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


def destroy_route(
    routes: list[RouteGenome],
    tasks_by_id: dict[str, TaskBlock],
    rng: random.Random,
) -> tuple[list[RouteGenome], list[TaskBlock], int | None]:
    """整台车的任务一次全部撤出（route removal / ejection），返回 (路线, 待重放任务, 被关车辆号)。

    为什么必须有这一手：目标按字典序比，`vehicle_count` 排在里程之前
    （`encoding.ObjectiveVector.key`），而其余 destroy 算子全是**任务级**的 ——
    想关掉一台车得先把它逐件搬空，第一件搬走时"里程变差而用车数没降"，
    在字典序下必然被拒，于是搜索永远走不到"这台车清空"的那个状态。
    实测候选车翻倍反而解变差（58.351→76.401 km、用车 3→4 台）就是这道门槛造成的，
    不是插入质量不够。主循环会把这台车从修复候选里剔掉，
    任务只能往剩下的车里合（否则最佳位置往往还是它自己，算子等于白做）。
    """
    loaded = [r for r in routes if r.task_count() > 0]
    if len(loaded) < 2:
        # 只有一台车在用（或全空）：没有"关一台"的余地
        return [r.copy() for r in routes], [], None

    # 优先撤装载最少的车（关它的搬运成本最低），并列时在候选里随机取一台
    loaded.sort(key=lambda r: (r.task_count(), r.vehicle_index))
    lightest = loaded[0].task_count()
    pool = [r for r in loaded if r.task_count() == lightest]
    victim = pool[rng.randrange(len(pool))]

    new_routes = [r.copy() for r in routes]
    removed: list[TaskBlock] = []
    for route in new_routes:
        if route.vehicle_index != victim.vehicle_index:
            continue
        for task_id in list(route.placements):
            task = tasks_by_id.get(task_id)
            if task is not None:
                removed.append(task)
            route.remove_task(task_id)
        break
    return new_routes, removed, victim.vehicle_index


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


def repair_with_ejection(
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
    deadline: SearchDeadline | None = None,
    max_attempts: int = 400,
) -> list[RouteGenome]:
    """贪心修复 + 一层「先挪开再塞回」（ejection chain），用于撤车时的任务搬运。

    `repair_greedy` 只往**已有的空隙**里插，插不进就把任务留在原地没安置，撤车判定随即
    按"插不回"收手。实测 r_village-M-002 的 125k 臂（2 台 / 38.604km）：把 veh0 的 4 件
    搬进 veh1 时 3 件落位、只剩 S:T1（2 件货）找不到空位；而同一台车只要先把 K:K3 挪开、
    放进 S:T1、再把 K:K3 塞回去，就得到**完整且可行**的 1 车解（36.618km，
    key=(0.0, 1, 0.0, 2.587, 36.618, 3640.3)）。车数是业务字典序第二维，
    这一步之差就是"预算越大反而多开一台"（任务 #27，#6 违背的根因）。

    代价被三道闸挡住：只在贪心确实没安置全时启动；总量 `max_attempts` 次挪开-塞回；
    每次都问 deadline 要预算。超限时交回的是**当前已修好的那部分**（= 没撤成），
    不是更差的解 —— 采纳与否仍由调用方的完整性 + 字典序闸门判。
    """
    repaired = repair_greedy(
        routes, destroyed_tasks, tasks_by_id, feasibility_engine,
        station_map, matrix, passenger_capacities, cargo_capacities,
        initial_passenger_loads, initial_cargo_loads, rng,
    )
    placed = {tid for r in repaired for tid in r.placements}
    missing = [t for t in destroyed_tasks if t.task_id not in placed]
    if not missing:
        return repaired

    attempts = 0
    two_leg = (TaskType.PASSENGER, TaskType.SHIPMENT)

    def _score(task, route, pi, di):
        # 一次插入候选 = 一份搜索预算，口径同 local_search_v14（"一次移动评估就是一份搜索预算"）。
        # 不 tick 的话 ejection 的开销不进确定性预算，评估次数这把尺子会漏计这一段。
        if deadline is not None:
            deadline.tick()
            if deadline.expired():
                return float("inf")
        return _insertion_score(
            task, route, pi, di, tasks_by_id, station_map, matrix,
            feasibility_engine, passenger_capacities, cargo_capacities,
            initial_passenger_loads, initial_cargo_loads,
        )

    def _try_one(task):
        """把 task 塞进某台车：必要时先挪开该车上的一件，再把挪开的那件塞回任意位置。"""
        nonlocal attempts
        for ri, route in enumerate(repaired):
            for victim_id in list(route.placements):
                if attempts >= max_attempts:
                    return None
                attempts += 1
                if deadline is not None and deadline.expired():
                    return None
                trial = [r.copy() for r in repaired]
                trial[ri].remove_task(victim_id)
                event_count = len(trial[ri].events)
                spot = None
                for pi in range(1, event_count):
                    if deadline is not None and deadline.expired():
                        return None
                    deliveries = (
                        range(pi + 1, event_count + 1)
                        if task.task_type in two_leg else (None,)
                    )
                    for di in deliveries:
                        if _score(task, trial[ri], pi, di) != float("inf"):
                            spot = (pi, di)
                            break
                    if spot is not None:
                        break
                if spot is None:
                    continue
                try:
                    trial[ri].insert_task(task, spot[0], spot[1])
                except (ValueError, IndexError):
                    continue
                after = repair_greedy(
                    trial, [tasks_by_id[victim_id]], tasks_by_id, feasibility_engine,
                    station_map, matrix, passenger_capacities, cargo_capacities,
                    initial_passenger_loads, initial_cargo_loads, rng,
                )
                got = {tid for r in after for tid in r.placements}
                if task.task_id in got and victim_id in got:
                    return after
        return None

    for task in missing:
        improved = _try_one(task)
        if improved is not None:
            repaired = improved
    return repaired


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


# ─── SA Acceptance ──────────────────────────────────────────


def sa_accept(
    current_obj: ObjectiveVector,
    candidate_obj: ObjectiveVector,
    temperature: float,
    rng: random.Random,
) -> bool:
    """SA 接受准则：更好/相等必接受；更差以 exp(-delta / T) 接受。

    - delta 来自 ObjectiveVector.scalar_delta_worse（搜索内部能量差，不是业务 key）。
    - 硬约束不可行（infeasibility > 0）不得靠 SA 无限接受：直接拒绝。
    - temperature <= 0 或非有限时，只接受非更差解。
    - delta 非有限 / 溢出下溢安全处理，绝不产生 NaN。
    """
    if candidate_obj.infeasibility > 0.0:
        return False

    # lexicographic 业务序：更好或相等
    if candidate_obj.key() <= current_obj.key():
        return True

    if not math.isfinite(temperature) or temperature <= 0.0:
        return False

    delta = candidate_obj.scalar_delta_worse(current_obj)
    if delta <= 0.0:
        return True
    if not math.isfinite(delta):
        return False

    # exp(-delta/T) overflow/underflow 保护
    exponent = -delta / temperature
    if exponent < -700.0:
        return False
    if exponent > 0.0:
        return True

    prob = math.exp(exponent)
    if not math.isfinite(prob) or prob <= 0.0:
        return False
    return rng.random() < prob


def _task_set_preserved(
    before_routes: list[RouteGenome],
    after_routes: list[RouteGenome],
) -> bool:
    """destroy/repair 后任务集合必须完全恢复，不允许丢失或复制。"""
    before: set[str] = set()
    after: set[str] = set()
    for r in before_routes:
        before.update(r.placements.keys())
    for r in after_routes:
        for tid in r.placements.keys():
            if tid in after:
                return False  # duplicate
            after.add(tid)
    return before == after


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
    destroy_names = ["random", "worst", "shaw", "segment", "route"]
    repair_names = ["greedy", "regret-2", "regret-3"]

    selector = AdaptiveOperatorSelector(destroy_names, repair_names)

    best_routes = [r.copy() for r in routes]
    best_obj = evaluate_route_states(
        best_routes, tasks_by_id, station_map, matrix,
        initial_passenger_loads=initial_passenger_loads,
    )

    current_routes = [r.copy() for r in routes]
    current_obj = best_obj

    temperature = config.initial_temperature if hasattr(config, 'initial_temperature') else 10.0
    cooling_rate = config.cooling_rate if hasattr(config, 'cooling_rate') else 0.995

    for iteration in range(max_iterations):
        # 一次 destroy+repair 是一份完整评估；计数预算下这是 ALNS 的确定性停止点
        if deadline is not None:
            deadline.tick()
            if deadline.expired():
                break
        # 选择算子
        destroy_name = selector.select_destroy(rng)
        repair_name = selector.select_repair(rng)

        selector.destroy_ops[destroy_name].usage += 1
        selector.repair_ops[repair_name].usage += 1

        # Destroy
        destroy_fraction = config.destroy_fraction if hasattr(config, 'destroy_fraction') else 0.25

        victim_index: int | None = None
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
        elif destroy_name == "route":
            destroyed_routes, removed, victim_index = destroy_route(
                current_routes, tasks_by_id, rng
            )
        else:  # segment
            destroyed_routes, removed = destroy_segment(
                current_routes, tasks_by_id, destroy_fraction, rng
            )

        # 撤整台车时，修复阶段看不到那台车：任务只能往剩下的车里合。
        # 不剔掉它的话，第一件搬走的车内任务最佳位置常常还是它自己，算子等于白做。
        repair_input = (
            [r for r in destroyed_routes if r.vehicle_index != victim_index]
            if victim_index is not None else destroyed_routes
        )

        # Repair
        if repair_name == "greedy":
            repaired = repair_greedy(
                repair_input, removed, tasks_by_id,
                feasibility_engine, station_map, matrix,
                passenger_capacities, cargo_capacities,
                initial_passenger_loads, initial_cargo_loads, rng
            )
        elif repair_name == "regret-2":
            repaired = repair_regret_k(
                repair_input, removed, tasks_by_id,
                feasibility_engine, station_map, matrix,
                passenger_capacities, cargo_capacities,
                initial_passenger_loads, initial_cargo_loads, rng, k=2
            )
        else:  # regret-3
            repaired = repair_regret_k(
                repair_input, removed, tasks_by_id,
                feasibility_engine, station_map, matrix,
                passenger_capacities, cargo_capacities,
                initial_passenger_loads, initial_cargo_loads, rng, k=3
            )

        if victim_index is not None:
            # 被撤空的那台车放回原位（车辆数由 placements 决定，空车不计），
            # 保持"一条路线对应一台候选车"的结构不变
            closed = [r for r in destroyed_routes if r.vehicle_index == victim_index]
            repaired = sorted([*repaired, *closed], key=lambda r: r.vehicle_index)

        # 评估
        repaired_obj = evaluate_route_states(
            repaired, tasks_by_id, station_map, matrix,
            initial_passenger_loads=initial_passenger_loads,
        )

        # 接受准则：业务比较用 key()，SA 用真实能量差
        accept = False
        reward_type = None

        # destroy/repair 不得静默丢任务：不完整解按不可行处理
        if not _task_set_preserved(current_routes, repaired):
            accept = False
        elif repaired_obj.key() < current_obj.key():
            accept = True
            reward_type = "accepted_better"

            if repaired_obj < best_obj:
                best_routes = [r.copy() for r in repaired]
                best_obj = repaired_obj
                reward_type = "global_best"
        elif repaired_obj.key() == current_obj.key():
            accept = True
            reward_type = "accepted_feasible"
        elif sa_accept(current_obj, repaired_obj, temperature, rng):
            accept = True
            reward_type = "accepted_feasible"

        if accept:
            current_routes = repaired
            current_obj = repaired_obj

            if reward_type:
                selector.reward(destroy_name, repair_name, reward_type)

        # 冷却（不低于 temperature_min）
        temperature = max(
            temperature * cooling_rate,
            getattr(config, "temperature_min", 0.01),
        )

        # 更新权重
        if iteration % 10 == 0:
            selector.update_weights()

    return best_routes
