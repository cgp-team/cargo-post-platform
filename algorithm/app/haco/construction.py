"""HACO-CPS 1.3.0 蚂蚁构建过程：改进的路线构造。

关键改进（相比1.2.0）：
1. 任务选择基于质量（所有候选），不是位置（前N个）
2. 插入评分考虑已有任务的交互
3. 贪婪最近邻作为初始解参考
4. 增量评估减少重复计算
"""

from __future__ import annotations

import random
from dataclasses import dataclass
from typing import TYPE_CHECKING

from .candidate import CandidateInsertion
from .deadline import SearchDeadline
from .encoding import TaskBlock, TaskType
from .feasibility import fast_feasible_insert_state
from .feasibility_engine import FeasibilityEngine
from .heuristic import compute_distance, compute_duration
from .route_genome import EventType, RouteGenome
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
                # 绕行硬约束：偏离运营路线超过上限的插入判不可行（订单留给多段联运）
                if _compute_cargo_detour(task, state, gap.gap_index, station_map, matrix) > config.max_detour_km:
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
            # 绕行硬约束：偏离运营路线超过上限的插入直接判不可行，订单留给多段联运（换乘站接力）
            if c_detour > config.max_detour_km:
                continue
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


# ═══════════════════════════════════════════════════════════════
# 1.4.0 新核心：基于 RouteGenome 的候选搜索
# ═══════════════════════════════════════════════════════════════


@dataclass(frozen=True)
class InsertionCandidate:
    vehicle_index: int
    pickup_index: int
    delivery_index: int | None
    delta_distance: float
    delta_duration: float
    passenger_impact: float
    cargo_detour: float
    heuristic_score: float


def generate_insertion_candidates(
    task: TaskBlock,
    routes: list[RouteGenome],
    tasks_by_id: dict[str, TaskBlock],
    feasibility_engine: FeasibilityEngine,
    station_map: dict,
    matrix,
    passenger_capacities: dict[int, int],
    cargo_capacities: dict[int, int],
    initial_passenger_loads: dict[int, int],
    initial_cargo_loads: dict[int, int],
    candidate_size: int,
    deadline: SearchDeadline | None = None,
    max_detour_km: float | None = None,
) -> list[InsertionCandidate]:
    """为 task 生成可行插入候选（两阶段筛选）。

    Stage A — cheap pre-screen：
        对每个 (route, pickup_index, delivery_index) 只计算廉价指标
        (delta_distance, delta_duration, skeleton_penalty, capacity_risk)，
        不调用完整 FeasibilityEngine / ObjectiveVector。
        按 cheap_score 排序，保留 top pool_size 个。

    Stage B — full evaluation：
        只对 top pool 执行 FeasibilityEngine.check + evaluate_route_genome，
        最终截断到 candidate_size。
    """
    from .evaluator import evaluate_route_genome

    # ─── Stage A: cheap pre-screen ──────────────────────────────
    @dataclass(frozen=True)
    class _CheapSlot:
        vehicle_index: int
        pickup_index: int
        delivery_index: int | None
        cheap_score: float
        delta_distance: float
        delta_duration: float
        skeleton_penalty: float
        capacity_risk: float

    cheap_slots: list[_CheapSlot] = []
    # For paired tasks: group by (vehicle, pickup_index) to ensure diversity
    # Key: (vehicle_index, pickup_index), Value: list of _CheapSlot
    grouped_by_pickup: dict[tuple[int, int], list[_CheapSlot]] = {}
    _check_count = 0

    pickup_station = station_map.get(task.pickup_station)
    delivery_station = station_map.get(task.delivery_station)

    for route in routes:
        event_count = len(route.events)
        skeleton_set = set(route.skeleton) if route.skeleton else set()

        # skeleton penalty for this task on this route
        skel_penalty = 0.0
        if task.pickup_station not in skeleton_set:
            skel_penalty += 0.5
        if (
            task.task_type in (TaskType.PASSENGER, TaskType.SHIPMENT)
            and task.delivery_station not in skeleton_set
        ):
            skel_penalty += 0.5

        # capacity risk: how close is this route to capacity?
        pax_used = sum(
            1 for e in route.events
            if e.event_type == EventType.BOARD
        ) - sum(
            1 for e in route.events
            if e.event_type == EventType.ALIGHT
        )
        pax_cap = passenger_capacities.get(route.vehicle_index, 999)
        cargo_cap = cargo_capacities.get(route.vehicle_index, 999)
        pax_risk = max(0.0, (pax_used + (1 if task.task_type == TaskType.PASSENGER else 0)) / max(pax_cap, 1) - 0.8)
        cargo_risk = max(0.0, task.size / max(cargo_cap, 1) - 0.5) if task.task_type != TaskType.PASSENGER else 0.0
        cap_risk = pax_risk + cargo_risk

        if task.task_type in (TaskType.PASSENGER, TaskType.SHIPMENT):
            for pickup_index in range(1, event_count):
                # cheap distance for the pickup insertion point
                from_station = station_map.get(route.events[pickup_index - 1].station_id)
                after_pickup = station_map.get(route.events[min(pickup_index, event_count - 1)].station_id)

                for delivery_index in range(pickup_index + 1, event_count + 1):
                    _check_count += 1
                    if _check_count % 32 == 0 and deadline is not None and deadline.expired():
                        break

                    # cheap distance estimate: incremental detour for inserting
                    # pickup at pickup_index and delivery at delivery_index.
                    # pickup cost: from_station → pickup_station → after_pickup
                    # delivery cost: before_delivery → delivery_station → after_delivery
                    dd = 0.0
                    dur = 0.0
                    if from_station and pickup_station and delivery_station and after_pickup:
                        # pickup detour
                        d_orig_pickup = compute_distance(from_station, after_pickup, matrix)
                        d_new_pickup = (
                            compute_distance(from_station, pickup_station, matrix)
                            + compute_distance(pickup_station, after_pickup, matrix)
                        )
                        dd_pickup = max(0.0, d_new_pickup - d_orig_pickup)

                        # delivery detour
                        if delivery_index <= event_count:
                            before_del = station_map.get(route.events[delivery_index - 1].station_id)
                            after_del = station_map.get(route.events[min(delivery_index, event_count - 1)].station_id)
                        else:
                            before_del = after_pickup
                            after_del = station_map.get(route.depot_station)

                        dd_delivery = 0.0
                        if before_del and after_del:
                            d_orig_del = compute_distance(before_del, after_del, matrix)
                            d_new_del = (
                                compute_distance(before_del, delivery_station, matrix)
                                + compute_distance(delivery_station, after_del, matrix)
                            )
                            dd_delivery = max(0.0, d_new_del - d_orig_del)

                        dd = dd_pickup + dd_delivery
                        # 绕行硬约束只约束货运任务（DELIVERY/PICKUP/SHIPMENT）：
                        # 偏离运营路线超过上限的插入直接跳过（订单留给多段联运）。
                        # 乘客任务沿公交线路上下车，无"偏离运营路线取送"语义，不受此约束。
                        if (
                            max_detour_km is not None
                            and dd > max_detour_km
                            and task.task_type in (TaskType.DELIVERY, TaskType.PICKUP, TaskType.SHIPMENT)
                        ):
                            continue

                        # duration estimate (same structure)
                        t_orig_pickup = compute_duration(from_station, after_pickup, matrix)
                        t_new_pickup = (
                            compute_duration(from_station, pickup_station, matrix)
                            + compute_duration(pickup_station, after_pickup, matrix)
                        )
                        dur_pickup = max(0.0, t_new_pickup - t_orig_pickup)

                        dur_delivery = 0.0
                        if before_del and after_del:
                            t_orig_del = compute_duration(before_del, after_del, matrix)
                            t_new_del = (
                                compute_duration(before_del, delivery_station, matrix)
                                + compute_duration(delivery_station, after_del, matrix)
                            )
                            dur_delivery = max(0.0, t_new_del - t_orig_del)

                        dur = dur_pickup + dur_delivery

                    cheap_score = dd + skel_penalty * 10.0 + cap_risk * 50.0
                    slot = _CheapSlot(
                        vehicle_index=route.vehicle_index,
                        pickup_index=pickup_index,
                        delivery_index=delivery_index,
                        cheap_score=cheap_score,
                        delta_distance=dd,
                        delta_duration=dur,
                        skeleton_penalty=skel_penalty,
                        capacity_risk=cap_risk,
                    )
                    cheap_slots.append(slot)
                    grouped_by_pickup.setdefault(
                        (route.vehicle_index, pickup_index), []
                    ).append(slot)
                if _check_count % 32 == 0 and deadline is not None and deadline.expired():
                    break
        else:
            for pickup_index in range(1, event_count):
                _check_count += 1
                if _check_count % 32 == 0 and deadline is not None and deadline.expired():
                    break

                from_station = station_map.get(route.events[pickup_index - 1].station_id)
                to_station = station_map.get(route.events[min(pickup_index, event_count - 1)].station_id)
                dd = 0.0
                dur = 0.0
                if from_station and pickup_station and to_station:
                    d_orig = compute_distance(from_station, to_station, matrix)
                    d_new = (
                        compute_distance(from_station, pickup_station, matrix)
                        + compute_distance(pickup_station, to_station, matrix)
                    )
                    dd = max(0.0, d_new - d_orig)
                    # 绕行硬约束：偏离运营路线超过上限的插入直接跳过（订单留给多段联运）
                    if max_detour_km is not None and dd > max_detour_km:
                        continue
                    t_orig = compute_duration(from_station, to_station, matrix)
                    t_new = (
                        compute_duration(from_station, pickup_station, matrix)
                        + compute_duration(pickup_station, to_station, matrix)
                    )
                    dur = max(0.0, t_new - t_orig)

                cheap_score = dd + skel_penalty * 10.0 + cap_risk * 50.0
                cheap_slots.append(_CheapSlot(
                    vehicle_index=route.vehicle_index,
                    pickup_index=pickup_index,
                    delivery_index=None,
                    cheap_score=cheap_score,
                    delta_distance=dd,
                    delta_duration=dur,
                    skeleton_penalty=skel_penalty,
                    capacity_risk=cap_risk,
                ))

    # ─── Stage A truncation: per-pickup diversity + global pool ───
    # Pool size is capped to bound Stage B FeasibilityEngine evaluation cost.
    # For normal HACO search (candidate_size=8): pool=32, cost=~32 evals/task.
    # For _cheapest_insertion (candidate_size=10000): pool=64, cost=~64 evals/task.
    # With 50 tasks: 50*64=3200 evals, ~1-2s total — fits in 4s budget.
    pool_size = min(max(candidate_size * 4, 16), 64)

    if grouped_by_pickup:
        # Paired task: vehicle diversity + global cheap_score ranking.
        # Strategy: collect the best slot per vehicle (guaranteed diversity),
        # then fill remaining pool with globally cheapest slots.
        vehicles_with_slots: dict[int, list[_CheapSlot]] = {}
        for s in cheap_slots:
            vehicles_with_slots.setdefault(s.vehicle_index, []).append(s)

        # Step 1: guarantee at least 1 slot per vehicle in the pool
        protected: list[_CheapSlot] = []
        protected_set: set[tuple[int, int, int | None]] = set()
        for vi, vi_slots in vehicles_with_slots.items():
            vi_slots.sort(key=lambda s: s.cheap_score)
            best = vi_slots[0]
            protected.append(best)
            protected_set.add((best.vehicle_index, best.pickup_index, best.delivery_index))

        # Step 2: add more slots per vehicle-pickup group for diversity,
        # but cap total protected to leave room in pool_size
        per_vp_quota = max(1, min(candidate_size // 2, 4))
        for pickup_key, slots in grouped_by_pickup.items():
            slots.sort(key=lambda s: s.cheap_score)
            for s in slots[:per_vp_quota]:
                key = (s.vehicle_index, s.pickup_index, s.delivery_index)
                if key not in protected_set:
                    protected.append(s)
                    protected_set.add(key)

        # Step 3: sort remaining by cheap_score and fill pool
        remaining = [s for s in cheap_slots if (s.vehicle_index, s.pickup_index, s.delivery_index) not in protected_set]
        remaining.sort(key=lambda s: s.cheap_score)

        # Build pool: protected (diversity-first) + remaining (cheapest-first)
        # Sort protected by cheap_score so best protected slots come first
        protected.sort(key=lambda s: s.cheap_score)
        top_pool = (protected + remaining)[:pool_size]

        # Step 4: post-truncation vehicle diversity guarantee
        # If any vehicle was completely cut from top_pool, add its best slot
        pool_vehicles = {s.vehicle_index for s in top_pool}
        for vi, vi_slots in vehicles_with_slots.items():
            if vi not in pool_vehicles and vi_slots:
                best = min(vi_slots, key=lambda s: s.cheap_score)
                top_pool.append(best)
    else:
        # Single-event task: simple sort and truncate
        cheap_slots.sort(key=lambda s: s.cheap_score)
        top_pool = cheap_slots[:pool_size]

    # ─── Stage B: full FeasibilityEngine + ObjectiveVector ──────
    candidates: list[InsertionCandidate] = []

    for slot in top_pool:
        if deadline is not None and deadline.expired():
            break

        route = routes[slot.vehicle_index]
        candidate_route = route.copy()

        try:
            candidate_route.insert_task(
                task, slot.pickup_index, slot.delivery_index,
            )
        except (ValueError, IndexError):
            continue

        result = feasibility_engine.check(
            candidate_route,
            tasks_by_id | {task.task_id: task},
            passenger_capacities[route.vehicle_index],
            cargo_capacities[route.vehicle_index],
            initial_passenger_loads[route.vehicle_index],
            initial_cargo_loads[route.vehicle_index],
            station_map=station_map,
            matrix=matrix,
        )
        if not result.feasible:
            continue

        metrics = evaluate_route_genome(
            candidate_route,
            tasks_by_id | {task.task_id: task},
            station_map,
            matrix,
        )

        candidates.append(InsertionCandidate(
            vehicle_index=route.vehicle_index,
            pickup_index=slot.pickup_index,
            delivery_index=slot.delivery_index,
            delta_distance=metrics["distance"],
            delta_duration=metrics["duration"],
            passenger_impact=metrics["passenger_impact"],
            cargo_detour=metrics["cargo_detour"],
            heuristic_score=(
                metrics["distance"]
                + metrics["passenger_impact"] * 0.01
                + metrics["cargo_detour"] * 10.0
            ),
        ))

    candidates.sort(key=lambda x: x.heuristic_score)
    return candidates[:max(1, candidate_size)]


def construct_ant_solution_v14(
    tasks: list[TaskBlock],
    routes: list[RouteGenome],
    tasks_by_id: dict[str, TaskBlock],
    pheromone: PheromoneMatrix,
    feasibility_engine: FeasibilityEngine,
    station_map: dict,
    matrix,
    passenger_capacities: dict[int, int],
    cargo_capacities: dict[int, int],
    initial_passenger_loads: dict[int, int],
    initial_cargo_loads: dict[int, int],
    config: HacoConfig,
    rng: random.Random,
    alpha: float = 1.0,
    beta: float = 3.0,
    candidate_size: int = 8,
    deadline: SearchDeadline | None = None,
) -> list[RouteGenome]:
    """1.4.0 蚂蚁构造：基于 RouteGenome + FeasibilityEngine + alpha/beta。"""
    working_routes = [r.copy() for r in routes]
    unassigned = list(tasks)
    last_task_id = "DEPOT"

    while unassigned:
        # NOTE: 不在此处按墙钟打断蚂蚁构造。一次蚂蚁的 RNG 消耗必须确定，
        # 否则主循环同 seed 下复现性被破坏（确定性回归）。超时只在外层迭代边界判定，
        # 单蚂蚁的微小超时由外层 deadline 兜底（本地 S4 实测 <1 只蚂蚁构造时长）。
        # 选择下一个任务
        task_scores = []
        for task in unassigned:
            tau = pheromone.get(last_task_id, task.task_id)
            urgency = _task_urgency(task)
            score = (tau ** alpha) * urgency
            task_scores.append((task, score))

        if not task_scores:
            break

        total_score = sum(s for _, s in task_scores)
        if total_score <= 0:
            selected_task = rng.choice(unassigned)
        else:
            probs = [s / total_score for _, s in task_scores]
            r = rng.random()
            cumulative = 0.0
            selected_task = task_scores[-1][0]
            for i, prob in enumerate(probs):
                cumulative += prob
                if r <= cumulative:
                    selected_task = task_scores[i][0]
                    break

        # 生成候选插入
        candidates = generate_insertion_candidates(
            selected_task,
            working_routes,
            tasks_by_id,
            feasibility_engine,
            station_map,
            matrix,
            passenger_capacities,
            cargo_capacities,
            initial_passenger_loads,
            initial_cargo_loads,
            candidate_size,
            deadline=deadline,
            max_detour_km=config.max_detour_km,
        )

        if not candidates:
            unassigned.remove(selected_task)
            continue

        # 轮盘赌选择
        if len(candidates) == 1:
            selected = candidates[0]
        else:
            probs = []
            for cand in candidates:
                tau_task = pheromone.get(last_task_id, selected_task.task_id)
                eta = cand.heuristic_score
                prob = (tau_task ** alpha) * ((1.0 / (eta + EPSILON)) ** beta)
                probs.append(prob)

            total = sum(probs)
            if total <= 0:
                selected = rng.choice(candidates)
            else:
                probs = [p / total for p in probs]
                r = rng.random()
                cumulative = 0.0
                selected = candidates[-1]
                for i, prob in enumerate(probs):
                    cumulative += prob
                    if r <= cumulative:
                        selected = candidates[i]
                        break

        # 执行插入
        target_route = working_routes[selected.vehicle_index]
        target_route.insert_task(
            selected_task,
            selected.pickup_index,
            selected.delivery_index,
        )
        unassigned.remove(selected_task)
        last_task_id = selected_task.task_id

    return working_routes
