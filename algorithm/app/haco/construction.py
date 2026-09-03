"""HACO-CPS 蚂蚁构建过程：每只蚂蚁真正自己构造路线。

核心流程：
1. 初始化所有车辆的 RouteState（含骨架间隙）
2. 未分配任务集合
3. 选择 task（信息素 + 启发式引导）
4. 生成全部合法 CandidateInsertion
5. 计算 tau^alpha * eta^beta
6. 轮盘赌选择
7. 插入 task block 到 RouteState
8. 重复直到所有任务完成
9. 得到完整多车辆路线

重要：构造过程中不调用 OR-Tools。
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
    """一只蚂蚁构建一个完整解。

    返回修改后的 route_states 列表（每辆车的路线状态）。
    """
    # 复制路线状态（不修改原始模板）
    working_states = [rs.copy() for rs in route_states]
    unassigned = list(tasks)

    while unassigned:
        # 选择下一个任务
        task = _select_next_task(
            unassigned, working_states, pheromone, station_map, matrix, config, rng
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

        # 基于信息素和启发式选择最佳插入
        selected = _select_insertion(candidates, pheromone, config, rng, gap_pheromone, alpha_gap)

        # 执行插入
        target_state = working_states[selected.vehicle_index]
        target_state.insert_task(task, selected.gap_index)
        unassigned.remove(task)

    return working_states


def _select_next_task(
    unassigned: list[TaskBlock],
    route_states: list[RouteState],
    pheromone: PheromoneMatrix,
    station_map: dict[str, Station],
    matrix: DistanceMatrix | None,
    config: HacoConfig,
    rng: random.Random,
) -> TaskBlock | None:
    """选择下一个要插入的任务。

    策略：综合信息素、紧急度、距离、时间窗风险。
    """
    if not unassigned:
        return None

    # 限制候选集大小
    candidate_size = min(config.candidate_size, len(unassigned))
    candidates = unassigned[:candidate_size]

    # 计算每个候选的得分
    scores = []
    for task in candidates:
        # 信息素：从 DEPOT 到该任务
        tau = pheromone.get("DEPOT", task.task_id)

        # 启发式：取所有可行插入位置的最佳得分
        eta = _get_best_eta(task, route_states, station_map, matrix, config)

        # 紧急度：SHIPPMENT/PASSENGER 比 DELIVERY/PICKUP 更紧急
        urgency = _task_urgency(task)

        # 综合得分
        score = (tau ** config.alpha) * ((1.0 / (eta + EPSILON)) ** config.beta) * urgency
        scores.append(score)

    # 归一化
    total = sum(scores)
    if total <= 0:
        return rng.choice(candidates)

    probs = [s / total for s in scores]

    # 轮盘赌
    r = rng.random()
    cumulative = 0.0
    for i, prob in enumerate(probs):
        cumulative += prob
        if r <= cumulative:
            return candidates[i]

    return candidates[-1]


def _task_urgency(task: TaskBlock) -> float:
    """任务紧急度评分。"""
    if task.task_type == TaskType.PASSENGER:
        return 1.5  # 乘客最紧急
    elif task.task_type == TaskType.SHIPMENT:
        return 1.3  # 配对货运次之
    elif task.task_type == TaskType.DELIVERY:
        return 1.0
    elif task.task_type == TaskType.PICKUP:
        return 1.0
    return 1.0


def _get_best_eta(
    task: TaskBlock,
    route_states: list[RouteState],
    station_map: dict[str, Station],
    matrix: DistanceMatrix | None,
    config: HacoConfig,
) -> float:
    """获取任务在所有可行位置中的最佳启发式得分。"""
    best_eta = float("inf")
    for state in route_states:
        for gap in state.gaps:
            feasible, _ = fast_feasible_insert_state(task, state, gap.gap_index)
            if not feasible:
                continue
            score = _compute_insertion_score(task, state, gap.gap_index, station_map, matrix, config)
            best_eta = min(best_eta, score)
    return best_eta if best_eta < float("inf") else 100.0


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
            # 快速可行性检查
            feasible, reason = fast_feasible_insert_state(task, state, gap.gap_index)
            if not feasible:
                continue

            # 计算增量成本
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
) -> CandidateInsertion:
    """基于信息素和启发式选择最佳插入（轮盘赌）。"""
    if len(candidates) == 1:
        return candidates[0]

    # 计算每个候选的转移概率
    probs = []
    for cand in candidates:
        tau_task = pheromone.get("DEPOT", cand.task_id)
        eta = cand.heuristic_score
        # 双信息素：task-to-task + task-to-gap
        if gap_pheromone is not None:
            tau_gap = gap_pheromone.get(cand.task_id, cand.gap_index)
            prob = (tau_task ** config.alpha) * (tau_gap ** alpha_gap) * ((1.0 / (eta + EPSILON)) ** config.beta)
        else:
            prob = (tau_task ** config.alpha) * ((1.0 / (eta + EPSILON)) ** config.beta)
        probs.append(prob)

    # 归一化
    total = sum(probs)
    if total <= 0:
        return rng.choice(candidates)

    probs = [p / total for p in probs]

    # 轮盘赌
    r = rng.random()
    cumulative = 0.0
    for i, prob in enumerate(probs):
        cumulative += prob
        if r <= cumulative:
            return candidates[i]

    return candidates[-1]


def _compute_delta_distance(
    task: TaskBlock,
    state: RouteState,
    gap_index: int,
    station_map: dict[str, Station],
    matrix: DistanceMatrix | None,
) -> float:
    """计算插入任务后的距离增量。"""
    depot = station_map.get(state.depot_station)
    if not depot:
        return 100.0

    # 获取 gap 的起止站点
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

    # 原距离
    orig = compute_distance(from_station, to_station, matrix)

    # 新距离：from -> pickup -> delivery -> to
    new = (compute_distance(from_station, pickup, matrix)
           + compute_distance(pickup, delivery, matrix)
           + compute_distance(delivery, to_station, matrix))

    return max(0.0, new - orig)


def _compute_delta_duration(
    task: TaskBlock,
    state: RouteState,
    gap_index: int,
    station_map: dict[str, Station],
    matrix: DistanceMatrix | None,
) -> float:
    """计算插入任务后的时间增量（秒）。"""
    depot = station_map.get(state.depot_station)
    if not depot:
        return 3600.0

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


def _compute_passenger_impact(
    task: TaskBlock,
    state: RouteState,
    gap_index: int,
    station_map: dict[str, Station],
    matrix: DistanceMatrix | None,
) -> float:
    """计算插入任务对车上乘客的影响（秒）。"""
    if task.task_type == TaskType.PASSENGER:
        return 0.0

    # 估算当前 gap 中的乘客数
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


def _compute_cargo_detour(
    task: TaskBlock,
    state: RouteState,
    gap_index: int,
    station_map: dict[str, Station],
    matrix: DistanceMatrix | None,
) -> float:
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


def _compute_insertion_score(
    task: TaskBlock,
    state: RouteState,
    gap_index: int,
    station_map: dict[str, Station],
    matrix: DistanceMatrix | None,
    config: HacoConfig,
) -> float:
    """计算插入的综合启发式得分（越小越好）。"""
    delta_dist = _compute_delta_distance(task, state, gap_index, station_map, matrix)
    p_impact = _compute_passenger_impact(task, state, gap_index, station_map, matrix)
    c_detour = _compute_cargo_detour(task, state, gap_index, station_map, matrix)

    # 归一化
    norm_dist = delta_dist / 10.0
    norm_passenger = p_impact / 300.0
    norm_detour = c_detour / 5.0

    # 骨架偏离
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
