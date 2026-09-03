"""HACO-CPS 1.2.0 主求解器：Domain-aware ACO + Advanced Neighborhood + Adaptive LNS。

核心改进（相比1.1.0）：
1. Task-to-Gap 信息素：引导任务分配到合适的骨架间隙
2. 双信息素系统：task-to-task + task-to-gap
3. 高级邻域：2-opt, Or-opt, SWAP*
4. 自适应 LNS：Shaw/Worst/Segment 破坏 + Greedy/Regret-2/Regret-3 修复
5. 精英存档：质量 + 多样性平衡
6. 自适应参数：alpha/beta 根据多样性调整
7. 自适应惩罚：根据可行解比例调整
8. 增强启发式：时间窗风险、容量风险、乘客敏感度
"""

from __future__ import annotations

import copy
import logging
import random
import time
from dataclasses import dataclass, field
from math import hypot

from ..distance import DistanceMatrix, EUCLIDEAN_AVG_SPEED_KMH, haversine_km
from ..models import (
    CargoSource,
    OrderType,
    PlanRequest,
    RouteStop,
    StopAction,
    VehiclePlan,
)
from ..validators import validate_time_window, validate_vehicle_plan
from .archive import EliteArchive, compute_route_signature
from .config import HacoConfig
from .construction import construct_ant_solution
from .destroy_repair import (
    AdaptiveOperatorSelector,
    destroy_random,
    destroy_segment,
    destroy_shaw,
    destroy_worst,
    repair_gap_best,
    repair_greedy,
    repair_regret2,
    repair_regret3,
)
from .encoding import ObjectiveVector, TaskBlock, TaskType
from .evaluator import compute_diversity, evaluate_route_states
from .feasibility import validate_route_states
from .gap_pheromone import GapPheromone
from .local_search import local_search
from .pheromone import PheromoneMatrix
from .route_state import RouteState

logger = logging.getLogger(__name__)

# 版本信息
HACO_VERSION = "haco-cps-1.2.0"
HACO_PARAMETER_VERSION = "haco-cps-default-v1.2"
BASELINE_VERSION = "ortools-1.3.0"

# 距离缩放
DISTANCE_SCALE = 1000


@dataclass
class SolveOutcome:
    status: str
    reason_code: str | None = None
    vehicle_plans: list[VehiclePlan] = field(default_factory=list)
    total_distance: float = 0.0
    algorithm_version: str = HACO_VERSION
    parameter_version: str = HACO_PARAMETER_VERSION
    warnings: list[str] = field(default_factory=list)
    iteration_stats: list[dict] = field(default_factory=list)


def solve_haco(
    request: PlanRequest,
    matrix: DistanceMatrix | None = None,
    config: HacoConfig | None = None,
) -> SolveOutcome:
    """HACO-CPS 1.2.0 主求解入口。"""
    if config is None:
        config = HacoConfig.from_algorithm_config(request.algorithmConfig)

    rng = random.Random(config.random_seed)

    # 预检
    precheck = _precheck(request)
    if precheck is not None:
        return precheck

    # 构建站点映射
    station_map = {s.stationId: s for s in request.stations}
    station_map[request.depot.stationId] = request.depot

    # 任务编码
    tasks = _encode_tasks(request)
    if not tasks:
        return SolveOutcome(status="feasible")

    # 初始化 RouteState 模板
    route_templates = _build_route_templates(request)

    # 初始化双信息素系统
    task_ids = [t.task_id for t in tasks] + ["DEPOT"]
    pheromone = PheromoneMatrix(task_ids, config)
    gap_count = len(route_templates[0].gaps) if route_templates else 5
    gap_pheromone = GapPheromone(task_ids, gap_count, config)

    # 精英存档
    archive = EliteArchive(max_size=config.archive_size)

    # 自适应算子选择
    op_selector = AdaptiveOperatorSelector(
        destroy_names=["random", "worst", "shaw", "segment"],
        repair_names=["greedy", "regret2", "regret3", "gap_best"],
    )

    # 生成初始解
    initial_states = construct_ant_solution(
        tasks, route_templates, pheromone, station_map, matrix, config, rng
    )
    initial_obj = evaluate_route_states(initial_states, station_map, matrix)

    if initial_obj.normalized_cost() > 0:
        pheromone.initialize_tau0(initial_obj.normalized_cost())
        gap_pheromone.initialize_tau0(initial_obj.normalized_cost())

    best_states = initial_states
    best_obj = initial_obj
    warnings = []
    iteration_stats = []
    no_improve_count = 0
    feasible_count = 0
    total_count = 0

    # 迭代搜索
    deadline = time.monotonic() + config.haco_time_limit

    for iteration in range(config.max_iterations):
        if time.monotonic() > deadline:
            warnings.append("HACO_TIME_LIMIT_REACHED")
            break

        iteration_solutions = []
        iteration_objs = []
        iteration_best_states = None
        iteration_best_obj = ObjectiveVector(infeasibility=float("inf"))

        # 自适应 alpha/beta
        diversity = compute_diversity(iteration_solutions) if iteration_solutions else 0.5
        adaptive_alpha, adaptive_beta = _adapt_parameters(config, diversity)

        # 每只蚂蚁构造解
        for ant_idx in range(config.ant_count):
            if time.monotonic() > deadline:
                break

            # 蚂蚁构造（使用双信息素）
            ant_states = construct_ant_solution(
                tasks, route_templates, pheromone, station_map, matrix, config, rng,
                gap_pheromone=gap_pheromone, alpha_gap=config.alpha_gap,
            )

            # 局部搜索（对精英蚂蚁）
            if ant_idx < config.elite_count:
                ant_states = local_search(
                    ant_states, station_map, matrix, config, rng
                )

            ant_obj = evaluate_route_states(ant_states, station_map, matrix)
            iteration_solutions.append(ant_states)
            iteration_objs.append(ant_obj)

            total_count += 1
            if ant_obj.infeasibility == 0:
                feasible_count += 1

            if ant_obj < iteration_best_obj:
                iteration_best_states = ant_states
                iteration_best_obj = ant_obj

            # 添加到存档
            if ant_obj.infeasibility == 0:
                sig = compute_route_signature(ant_states)
                archive.add(ant_states, ant_obj, sig)

        # 自适应 LNS
        if iteration_best_states and rng.random() < config.lns_probability:
            destroy_name = op_selector.select_destroy(rng)
            repair_name = op_selector.select_repair(rng)

            lns_states, d_name, r_name = _adaptive_lns(
                iteration_best_states, station_map, matrix, config, rng,
                op_selector, destroy_name, repair_name,
            )
            lns_obj = evaluate_route_states(lns_states, station_map, matrix)

            if lns_obj < iteration_best_obj:
                iteration_best_states = lns_states
                iteration_best_obj = lns_obj
                op_selector.reward(d_name, r_name, "iteration_best")
            else:
                op_selector.record_usage(d_name, r_name)

        # 信息素更新
        pheromone.evaporate()
        gap_pheromone.evaporate()

        if iteration_best_states and iteration_best_obj.normalized_cost() < float("inf"):
            task_seq = _extract_task_sequence(iteration_best_states)
            gap_assignments = _extract_gap_assignments(iteration_best_states)

            pheromone.deposit(task_seq, iteration_best_obj.normalized_cost(), weight=1.0)
            gap_pheromone.deposit(gap_assignments, iteration_best_obj.normalized_cost(), weight=1.0)

            if iteration_best_obj < best_obj:
                best_states = [s.copy() for s in iteration_best_states]
                best_obj = iteration_best_obj
                no_improve_count = 0
                pheromone.update_from_best(task_seq, best_obj.normalized_cost(), elite_weight=2.0)
                gap_pheromone.update_from_best(gap_assignments, best_obj.normalized_cost(), elite_weight=2.0)
            else:
                no_improve_count += 1
        else:
            no_improve_count += 1

        # 记录迭代统计
        diversity = compute_diversity(iteration_solutions) if iteration_solutions else 0.0
        iteration_stats.append({
            "iteration": iteration,
            "best_cost": best_obj.normalized_cost(),
            "feasible_count": sum(1 for o in iteration_objs if o.infeasibility == 0),
            "average_cost": sum(o.normalized_cost() for o in iteration_objs) / max(1, len(iteration_objs)),
            "diversity": diversity,
            "alpha": adaptive_alpha,
            "beta": adaptive_beta,
        })

        # 收敛检查
        if no_improve_count >= config.convergence_threshold:
            warnings.append("HACO_CONVERGED")
            break

        # 自适应信息素重启
        if no_improve_count >= config.convergence_threshold // 2:
            _adaptive_pheromone_restart(pheromone, gap_pheromone, config, rng)

    # 如果 HACO 没找到可行解，fallback 到 OR-Tools
    if best_obj.infeasibility > 0:
        warnings.append("HACO_FALLBACK_TO_BASELINE")
        return _fallback_to_baseline(request, matrix, warnings)

    # 转换 RouteState 为 VehiclePlan
    vehicle_plans = _route_states_to_plans(best_states, request, station_map, matrix)

    # OR-Tools 最终验证
    validation_result = _ortools_validate(request, vehicle_plans, matrix)
    if validation_result is not None:
        return validation_result

    return SolveOutcome(
        status="feasible",
        vehicle_plans=vehicle_plans,
        total_distance=round(sum(p.totalDistance for p in vehicle_plans), 3),
        algorithm_version=HACO_VERSION,
        parameter_version=HACO_PARAMETER_VERSION,
        warnings=warnings,
        iteration_stats=iteration_stats,
    )


def _precheck(request: PlanRequest) -> SolveOutcome | None:
    """预检。"""
    passengers = sum(1 for o in request.orders if o.orderType == OrderType.PASSENGER)
    deliveries = sum(
        o.itemCount for o in request.orders
        if o.orderType == OrderType.DELIVERY and o.cargoSource != CargoSource.PRELOADED
    )
    pickups = sum(o.itemCount for o in request.orders if o.orderType == OrderType.PICKUP)
    shipment_qty = sum(s.quantity for s in request.shipments)
    deliveries += shipment_qty
    pickups += shipment_qty

    if not request.orders and not request.shipments:
        return SolveOutcome(status="feasible")

    for v in request.vehicles:
        if v.initialPassengerLoad >= v.passengerCapacity:
            return SolveOutcome(status="infeasible", reason_code="OVER_CAPACITY")
        if v.initialCargoLoad > v.cargoCapacity:
            return SolveOutcome(status="infeasible", reason_code="OVER_CAPACITY")

    total_pcap = sum(v.passengerCapacity - v.initialPassengerLoad for v in request.vehicles)
    total_ccap = sum(v.cargoCapacity - v.initialCargoLoad for v in request.vehicles)
    if passengers > total_pcap or deliveries > total_ccap or pickups > total_ccap:
        return SolveOutcome(status="infeasible", reason_code="OVER_CAPACITY")

    time_window = int(request.batchEnd.timestamp() - request.batchStart.timestamp())
    if time_window <= 0:
        return SolveOutcome(status="infeasible", reason_code="TIME_WINDOW_EXCEEDED")

    return None


def _encode_tasks(request: PlanRequest) -> list[TaskBlock]:
    """将请求编码为 TaskBlock 列表。"""
    tasks = []
    for order in request.orders:
        if order.orderType == OrderType.PASSENGER:
            tasks.append(TaskBlock(
                task_id=f"P:{order.orderId}",
                task_type=TaskType.PASSENGER,
                pickup_station=order.boardingStationId,
                delivery_station=order.alightingStationId,
                size=1,
                order_ids=[order.orderId],
            ))
        elif order.orderType == OrderType.DELIVERY:
            tasks.append(TaskBlock(
                task_id=f"D:{order.orderId}",
                task_type=TaskType.DELIVERY,
                pickup_station=order.stationId,
                delivery_station=order.stationId,
                size=order.itemCount,
                order_ids=[order.orderId],
            ))
        elif order.orderType == OrderType.PICKUP:
            tasks.append(TaskBlock(
                task_id=f"K:{order.orderId}",
                task_type=TaskType.PICKUP,
                pickup_station=order.stationId,
                delivery_station=order.stationId,
                size=order.itemCount,
                order_ids=[order.orderId],
            ))

    for shipment in request.shipments:
        tasks.append(TaskBlock(
            task_id=f"S:{shipment.shipmentId}",
            task_type=TaskType.SHIPMENT,
            pickup_station=shipment.pickupStationId,
            delivery_station=shipment.deliveryStationId,
            size=shipment.quantity,
            order_ids=[shipment.shipmentId],
        ))

    return tasks


def _build_route_templates(request: PlanRequest) -> list[RouteState]:
    """构建 RouteState 模板。"""
    templates = []
    for i, vehicle in enumerate(request.vehicles):
        templates.append(RouteState(
            vehicle_index=i,
            vehicle_id=vehicle.vehicleId,
            passenger_capacity=vehicle.passengerCapacity,
            cargo_capacity=vehicle.cargoCapacity,
            initial_passenger_load=vehicle.initialPassengerLoad,
            initial_cargo_load=vehicle.initialCargoLoad,
            skeleton=vehicle.skeleton or [],
            depot_station=request.depot.stationId,
        ))
    return templates


def _adaptive_lns(
    states, station_map, matrix, config, rng, op_selector, destroy_name, repair_name
) -> tuple[list[RouteState], str, str]:
    """自适应 LNS。"""
    all_tasks = []
    for state in states:
        all_tasks.extend(state.tasks)

    if not all_tasks:
        return states, destroy_name, repair_name

    # 选择破坏算子
    if destroy_name == "random":
        removed, partial = destroy_random(states, config.destroy_fraction, rng)
    elif destroy_name == "worst":
        removed, partial = destroy_worst(states, station_map, matrix, config.destroy_fraction, rng)
    elif destroy_name == "shaw":
        removed, partial = destroy_shaw(states, station_map, matrix, config.destroy_fraction, rng)
    elif destroy_name == "segment":
        removed, partial = destroy_segment(states, station_map, matrix, config.destroy_fraction, rng)
    else:
        removed, partial = destroy_random(states, config.destroy_fraction, rng)

    if not removed:
        return states, destroy_name, repair_name

    # 选择修复算子
    if repair_name == "greedy":
        repaired = repair_greedy(partial, removed, station_map, matrix, config, rng)
    elif repair_name == "regret2":
        repaired = repair_regret2(partial, removed, station_map, matrix, config, rng)
    elif repair_name == "regret3":
        repaired = repair_regret3(partial, removed, station_map, matrix, config, rng)
    elif repair_name == "gap_best":
        repaired = repair_gap_best(partial, removed, station_map, matrix, config, rng)
    else:
        repaired = repair_greedy(partial, removed, station_map, matrix, config, rng)

    return repaired, destroy_name, repair_name


def _adapt_parameters(config: HacoConfig, diversity: float) -> tuple[float, float]:
    """自适应调整 alpha 和 beta。"""
    if diversity < config.adaptive_diversity_low:
        # 多样性太低，加强探索
        alpha = max(config.alpha_min, config.alpha * 0.9)
        beta = max(config.beta_min, config.beta * 0.9)
    elif diversity > config.adaptive_diversity_high:
        # 多样性太高，加强利用
        alpha = min(config.alpha_max, config.alpha * 1.1)
        beta = min(config.beta_max, config.beta * 1.1)
    else:
        alpha = config.alpha
        beta = config.beta

    return alpha, beta


def _adaptive_pheromone_restart(pheromone, gap_pheromone, config, rng) -> None:
    """自适应信息素重启。"""
    ratio = config.restart_ratio
    # 重启 task-to-task 信息素
    for i in range(pheromone.n):
        for j in range(pheromone.n):
            pheromone.tau[i][j] = pheromone.tau0 * ratio + pheromone.tau[i][j] * (1 - ratio)

    # 重启 gap 信息素
    gap_pheromone.restart(ratio)


def _extract_task_sequence(states: list[RouteState]) -> list[str]:
    """提取任务序列。"""
    seq = ["DEPOT"]
    for state in states:
        for task in state.tasks:
            seq.append(task.task_id)
    return seq


def _extract_gap_assignments(states: list[RouteState]) -> dict[str, int]:
    """提取任务到 gap 的分配。"""
    assignments = {}
    for state in states:
        for task_id, gap_index in state.task_gap_map.items():
            assignments[task_id] = gap_index
    return assignments


def _route_states_to_plans(states, request, station_map, matrix) -> list[VehiclePlan]:
    """将 RouteState 转换为 VehiclePlan 格式。"""
    vehicle_plans = []

    for state in states:
        if not state.tasks:
            continue

        stops = []
        scaled_total = 0
        current_station = request.depot
        current_passengers = state.initial_passenger_load

        stops.append(RouteStop(
            stationId=request.depot.stationId,
            action=StopAction.DEPART,
            segmentDistance=0.0,
            segmentDuration=None,
        ))

        for gap in state.gaps:
            gap_tasks = state.get_tasks_in_gap(gap.gap_index)

            for task in gap_tasks:
                if task.task_type == TaskType.PASSENGER:
                    pickup = station_map.get(task.pickup_station)
                    if pickup:
                        seg_km = _distance(current_station, pickup, matrix)
                        seg_sec = _duration(current_station, pickup, matrix)
                        scaled_total += int(round(seg_km * DISTANCE_SCALE))
                        stops.append(RouteStop(
                            stationId=task.pickup_station,
                            orderId=task.order_ids[0] if task.order_ids else None,
                            action=StopAction.BOARD,
                            segmentDistance=round(seg_km, 3),
                            segmentDuration=seg_sec,
                        ))
                        current_station = pickup
                        current_passengers += 1

                    delivery = station_map.get(task.delivery_station)
                    if delivery:
                        seg_km = _distance(current_station, delivery, matrix)
                        seg_sec = _duration(current_station, delivery, matrix)
                        scaled_total += int(round(seg_km * DISTANCE_SCALE))
                        stops.append(RouteStop(
                            stationId=task.delivery_station,
                            orderId=task.order_ids[0] if task.order_ids else None,
                            action=StopAction.ALIGHT,
                            segmentDistance=round(seg_km, 3),
                            segmentDuration=seg_sec,
                        ))
                        current_station = delivery
                        current_passengers -= 1

                elif task.task_type == TaskType.SHIPMENT:
                    pickup = station_map.get(task.pickup_station)
                    if pickup:
                        seg_km = _distance(current_station, pickup, matrix)
                        seg_sec = _duration(current_station, pickup, matrix)
                        scaled_total += int(round(seg_km * DISTANCE_SCALE))
                        stops.append(RouteStop(
                            stationId=task.pickup_station,
                            orderId=task.order_ids[0] if task.order_ids else None,
                            action=StopAction.PICKUP,
                            segmentDistance=round(seg_km, 3),
                            segmentDuration=seg_sec,
                            accepted=True,
                            serviceMode="NEAREST_STATION",
                            servicePoint=task.pickup_station,
                            detourDistance=0.0,
                            reasonCode=None,
                        ))
                        current_station = pickup

                    delivery = station_map.get(task.delivery_station)
                    if delivery:
                        seg_km = _distance(current_station, delivery, matrix)
                        seg_sec = _duration(current_station, delivery, matrix)
                        scaled_total += int(round(seg_km * DISTANCE_SCALE))
                        stops.append(RouteStop(
                            stationId=task.delivery_station,
                            orderId=task.order_ids[0] if task.order_ids else None,
                            action=StopAction.DELIVER,
                            segmentDistance=round(seg_km, 3),
                            segmentDuration=seg_sec,
                            accepted=True,
                            serviceMode="NEAREST_STATION",
                            servicePoint=task.delivery_station,
                            reasonCode=None,
                        ))
                        current_station = delivery

                elif task.task_type == TaskType.DELIVERY:
                    station = station_map.get(task.pickup_station)
                    if station:
                        seg_km = _distance(current_station, station, matrix)
                        seg_sec = _duration(current_station, station, matrix)
                        scaled_total += int(round(seg_km * DISTANCE_SCALE))

                        detour_km = 0.0
                        detour_sec = 0.0
                        skeleton_set = set(state.skeleton)
                        if task.pickup_station not in skeleton_set:
                            next_station = station_map.get(gap.to_station)
                            if next_station:
                                direct_km = _distance(current_station, next_station, matrix)
                                via_km = seg_km + _distance(station, next_station, matrix)
                                detour_km = max(0.0, via_km - direct_km)

                        passenger_impact = detour_sec if current_passengers > 0 else None

                        stops.append(RouteStop(
                            stationId=task.pickup_station,
                            orderId=task.order_ids[0] if task.order_ids else None,
                            action=StopAction.DELIVER,
                            segmentDistance=round(seg_km, 3),
                            segmentDuration=seg_sec,
                            accepted=True,
                            serviceMode="NEAREST_STATION",
                            servicePoint=task.pickup_station,
                            detourDistance=round(detour_km, 3),
                            detourDuration=int(detour_sec) if detour_sec > 0 else None,
                            passengerImpact=passenger_impact,
                            reasonCode=None,
                        ))
                        current_station = station

                elif task.task_type == TaskType.PICKUP:
                    station = station_map.get(task.pickup_station)
                    if station:
                        seg_km = _distance(current_station, station, matrix)
                        seg_sec = _duration(current_station, station, matrix)
                        scaled_total += int(round(seg_km * DISTANCE_SCALE))

                        detour_km = 0.0
                        detour_sec = 0.0
                        skeleton_set = set(state.skeleton)
                        if task.pickup_station not in skeleton_set:
                            next_station = station_map.get(gap.to_station)
                            if next_station:
                                direct_km = _distance(current_station, next_station, matrix)
                                via_km = seg_km + _distance(station, next_station, matrix)
                                detour_km = max(0.0, via_km - direct_km)

                        passenger_impact = detour_sec if current_passengers > 0 else None

                        stops.append(RouteStop(
                            stationId=task.pickup_station,
                            orderId=task.order_ids[0] if task.order_ids else None,
                            action=StopAction.PICKUP,
                            segmentDistance=round(seg_km, 3),
                            segmentDuration=seg_sec,
                            accepted=True,
                            serviceMode="NEAREST_STATION",
                            servicePoint=task.pickup_station,
                            detourDistance=round(detour_km, 3),
                            detourDuration=int(detour_sec) if detour_sec > 0 else None,
                            passengerImpact=passenger_impact,
                            reasonCode=None,
                        ))
                        current_station = station

            # 到骨架站
            if gap.to_station != request.depot.stationId:
                to_station = station_map.get(gap.to_station)
                if to_station:
                    seg_km = _distance(current_station, to_station, matrix)
                    seg_sec = _duration(current_station, to_station, matrix)
                    scaled_total += int(round(seg_km * DISTANCE_SCALE))
                    stops.append(RouteStop(
                        stationId=gap.to_station,
                        action=StopAction.PASS,
                        segmentDistance=round(seg_km, 3),
                        segmentDuration=seg_sec,
                    ))
                    current_station = to_station

        # RETURN
        seg_km = _distance(current_station, request.depot, matrix)
        seg_sec = _duration(current_station, request.depot, matrix)
        scaled_total += int(round(seg_km * DISTANCE_SCALE))
        stops.append(RouteStop(
            stationId=request.depot.stationId,
            action=StopAction.RETURN,
            segmentDistance=round(seg_km, 3),
            segmentDuration=seg_sec,
        ))

        vehicle_plans.append(VehiclePlan(
            vehicleId=state.vehicle_id,
            stops=stops,
            totalDistance=round(scaled_total / DISTANCE_SCALE, 3),
        ))

    return vehicle_plans


def _distance(a, b, matrix=None) -> float:
    """计算距离。"""
    if matrix is not None:
        key = (a.stationId, b.stationId)
        if key in matrix:
            return matrix[key][0]
    return hypot(a.longitude - b.longitude, a.latitude - b.latitude)


def _duration(a, b, matrix=None) -> float:
    """计算行驶时间（秒）。"""
    if matrix is not None:
        key = (a.stationId, b.stationId)
        if key in matrix:
            return matrix[key][1] or 0.0
    km = haversine_km(a.longitude, a.latitude, b.longitude, b.latitude)
    return km / EUCLIDEAN_AVG_SPEED_KMH * 3600


def _ortools_validate(request, vehicle_plans, matrix) -> SolveOutcome | None:
    """使用 OR-Tools 验证解的可行性。"""
    orders_by_id = {o.orderId: o for o in request.orders}
    shipments_by_id = {s.shipmentId: s for s in request.shipments}
    vehicles_by_id = {v.vehicleId: v for v in request.vehicles}

    for plan in vehicle_plans:
        vehicle = vehicles_by_id.get(plan.vehicleId)
        if vehicle is None:
            return SolveOutcome(status="infeasible", reason_code="VEHICLE_NOT_FOUND")
        valid, reason = validate_vehicle_plan(plan, vehicle, orders_by_id, shipments_by_id)
        if not valid:
            return SolveOutcome(status="infeasible", reason_code=reason)

    batch_start_s = int(request.batchStart.timestamp())
    batch_end_s = int(request.batchEnd.timestamp())
    for plan in vehicle_plans:
        valid, reason = validate_time_window(plan, batch_start_s, batch_end_s)
        if not valid:
            return SolveOutcome(status="infeasible", reason_code=reason)

    return None


def _fallback_to_baseline(request, matrix, warnings) -> SolveOutcome:
    """Fallback 到 OR-Tools baseline。"""
    from ..baseline.ortools_solver import solve as baseline_solve

    result = baseline_solve(request, matrix)
    return SolveOutcome(
        status=result.status,
        reason_code=result.reason_code,
        vehicle_plans=result.vehicle_plans,
        total_distance=result.total_distance,
        algorithm_version=HACO_VERSION,
        parameter_version="haco-cps-fallback-v1.2",
        warnings=warnings,
    )
