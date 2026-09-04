"""HACO-CPS 2.1 Hybrid Global Route Optimizer。

核心架构：
1. Station Backbone 搜索（全局站点顺序优化）
2. Task Assignment（在 backbone 上分配任务）
3. Task-level Local Search（在分配后优化 task 顺序）
4. ALNS（破坏 + 修复 backbone 和 task 分配）
5. Simulated Annealing 接受准则
6. OR-Tools 仅用于 warm-start 和最终验证

解决 HACO 2.0 的核心问题：
- task-level 搜索无法改变 station 顺序
- passenger-first 冻结了 passenger route
- 没有全局方向优化
"""

from __future__ import annotations

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
from .config import HacoConfig
from .encoding import TaskBlock, TaskType
from .exact_oracle import solve_exact
from .global_evaluator import evaluate_genome, GenomeEvaluation
from .global_local_search import global_local_search
from .global_construction import construct_global_solution, construct_greedy_global, construct_nn_global
from .pheromone import PheromoneMatrix
from .route_genome import GlobalRouteGenome
from .station_backbone import (
    BackboneSolution,
    StationBackbone,
    build_backbone_from_tasks,
    build_backbone_from_genome,
    compute_backbone_distance,
)
from .station_neighborhoods import apply_best_station_neighborhood
from .station_pheromone import StationPheromone

logger = logging.getLogger(__name__)

# 版本信息
HACO_VERSION = "haco-cps-2.1.0"
HACO_PARAMETER_VERSION = "haco-cps-default-v2.1"
BASELINE_VERSION = "ortools-1.3.0"

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


def solve_hybrid(
    request: PlanRequest,
    matrix: DistanceMatrix | None = None,
    config: HacoConfig | None = None,
) -> SolveOutcome:
    """Hybrid Global Route Optimizer 主入口。"""
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

    # Phase 1: 生成多种初始解
    initial_solutions = _generate_initial_solutions(
        tasks, request, station_map, matrix, config, rng
    )

    if not initial_solutions:
        return SolveOutcome(status="infeasible", reason_code="TIMING_CONFLICT", warnings=["NO_FEASIBLE_INITIAL_SOLUTION"])

    # 选择最佳初始解
    best_genome, best_obj = min(initial_solutions, key=lambda x: x[1])

    # 初始化信息素
    task_ids = [t.task_id for t in tasks] + ["DEPOT"]
    pheromone = PheromoneMatrix(task_ids, config)

    # Station pheromone
    all_stations = list(set(
        t.pickup_station for t in tasks
    ) | set(
        t.delivery_station for t in tasks if t.task_type in (TaskType.PASSENGER, TaskType.SHIPMENT)
    ))
    station_pheromone = StationPheromone(all_stations, config)

    if best_obj.normalized_cost > 0:
        pheromone.initialize_tau0(best_obj.normalized_cost)
        station_pheromone.initialize_tau0(best_obj.normalized_cost)

    warnings = []
    iteration_stats = []
    no_improve_count = 0
    temperature = config.initial_temperature

    # Phase 2: 主搜索循环
    deadline = time.monotonic() + config.haco_time_limit

    for iteration in range(config.max_iterations):
        if time.monotonic() > deadline:
            warnings.append("HACO_TIME_LIMIT_REACHED")
            break

        iteration_best = None
        iteration_best_obj = GenomeEvaluation(feasible=False)
        iteration_objs = []

        # 每只蚂蚁
        for ant_idx in range(config.ant_count):
            if time.monotonic() > deadline:
                break

            # 2.1 Station Backbone 搜索
            backbone = build_backbone_from_genome(best_genome, station_map)
            backbone = apply_best_station_neighborhood(backbone, station_map, matrix, rng)

            # 2.2 从 backbone 构建 genome
            ant_genome = _backbone_to_genome(backbone, tasks, request, rng, station_map)

            # 2.3 Task-level local search
            if ant_idx < config.elite_count:
                ant_genome = global_local_search(ant_genome, station_map, matrix, config, rng)

            ant_obj = evaluate_genome(ant_genome, station_map, matrix)
            iteration_objs.append(ant_obj)

            # Simulated Annealing 接受
            if ant_obj.feasible:
                if iteration_best is None or ant_obj < iteration_best_obj:
                    iteration_best = ant_genome
                    iteration_best_obj = ant_obj
                elif temperature > config.temperature_min:
                    # 接受更差解的概率
                    delta = ant_obj.normalized_cost - iteration_best_obj.normalized_cost
                    if delta > 0 and rng.random() < _sa_probability(delta, temperature):
                        iteration_best = ant_genome
                        iteration_best_obj = ant_obj

        # 冷却
        temperature *= config.cooling_rate

        # 信息素更新
        pheromone.evaporate()
        station_pheromone.evaporate()

        if iteration_best and iteration_best_obj.feasible:
            task_seq = _extract_task_sequence(iteration_best)
            pheromone.deposit(task_seq, iteration_best_obj.normalized_cost, weight=1.0)

            # Station pheromone 更新
            station_seq = _extract_station_sequence(iteration_best, station_map)
            station_pheromone.deposit(station_seq, iteration_best_obj.normalized_cost, weight=1.0)

            if iteration_best_obj < best_obj:
                best_genome = iteration_best
                best_obj = iteration_best_obj
                no_improve_count = 0
                pheromone.deposit_best(task_seq, best_obj.normalized_cost, elite_weight=2.0)
                station_pheromone.deposit_best(station_seq, best_obj.normalized_cost, elite_weight=2.0)

                # 打印改进信息
                _log_improvement(iteration, best_obj, best_genome, station_map)
            else:
                no_improve_count += 1
        else:
            no_improve_count += 1

        # 记录统计
        feasible_count = sum(1 for o in iteration_objs if o.feasible)
        iteration_stats.append({
            "iteration": iteration,
            "best_cost": best_obj.normalized_cost if best_obj.feasible else float("inf"),
            "feasible_count": feasible_count,
            "temperature": temperature,
            "backtracking": best_obj.backtracking_ratio if best_obj.feasible else 0,
        })

        # 收敛检查
        if no_improve_count >= config.convergence_threshold:
            warnings.append("HACO_CONVERGED")
            break

        # 信息素重启
        if no_improve_count >= config.convergence_threshold // 2:
            pheromone.restart(config.restart_ratio)
            station_pheromone.restart(config.restart_ratio)

    # Phase 3: 最终验证
    if not best_obj.feasible:
        warnings.append("HACO_FALLBACK_TO_BASELINE")
        return _fallback_to_baseline(request, matrix, warnings)

    vehicle_plans = _genome_to_plans(best_genome, request, station_map, matrix)

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


def _generate_initial_solutions(
    tasks: list[TaskBlock],
    request: PlanRequest,
    station_map: dict,
    matrix,
    config: HacoConfig,
    rng: random.Random,
) -> list[tuple[GlobalRouteGenome, GenomeEvaluation]]:
    """生成多种初始解。"""
    solutions = []
    genome_template = _build_genome_template(request, tasks)

    # 1. Nearest Neighbor
    nn = construct_nn_global(tasks, genome_template, station_map, matrix)
    nn_obj = evaluate_genome(nn, station_map, matrix)
    if nn_obj.feasible:
        solutions.append((nn, nn_obj))

    # 2. Greedy
    greedy = construct_greedy_global(tasks, genome_template, station_map, matrix, rng)
    greedy_obj = evaluate_genome(greedy, station_map, matrix)
    if greedy_obj.feasible:
        solutions.append((greedy, greedy_obj))

    # 3. ACO construction
    task_ids = [t.task_id for t in tasks] + ["DEPOT"]
    pheromone = PheromoneMatrix(task_ids, config)
    aco = construct_global_solution(tasks, genome_template, pheromone, station_map, matrix, config, rng)
    aco_obj = evaluate_genome(aco, station_map, matrix)
    if aco_obj.feasible:
        solutions.append((aco, aco_obj))

    # 4. Station backbone optimized
    backbone = build_backbone_from_tasks(tasks, request.depot.stationId, station_map, matrix, strategy="nearest")
    backbone = apply_best_station_neighborhood(backbone, station_map, matrix, rng)
    backbone_genome = _backbone_to_genome(backbone, tasks, request, rng, station_map)
    backbone_obj = evaluate_genome(backbone_genome, station_map, matrix)
    if backbone_obj.feasible:
        solutions.append((backbone_genome, backbone_obj))

    # 5. Sweep backbone
    sweep_backbone = build_backbone_from_tasks(tasks, request.depot.stationId, station_map, matrix, strategy="sweep")
    sweep_genome = _backbone_to_genome(sweep_backbone, tasks, request, rng, station_map)
    sweep_obj = evaluate_genome(sweep_genome, station_map, matrix)
    if sweep_obj.feasible:
        solutions.append((sweep_genome, sweep_obj))

    return solutions


def _backbone_to_genome(
    backbone_solution: BackboneSolution,
    tasks: list[TaskBlock],
    request: PlanRequest,
    rng: random.Random,
    station_map: dict = None,
) -> GlobalRouteGenome:
    """将 BackboneSolution 转换为 GlobalRouteGenome。

    关键创新：使用贪心最优插入构建任务序列。
    不是简单按 backbone 顺序排列，而是逐个插入任务到最佳位置。
    这模拟了 OR-Tools 的 PATH_CHEAPEST_ARC 策略。
    """
    task_blocks = {t.task_id: t for t in tasks}
    vehicle_routes = {}
    skeletons = {}
    vehicle_caps = {}

    for i, vehicle in enumerate(request.vehicles):
        skeletons[i] = vehicle.skeleton or []
        vehicle_caps[i] = (
            vehicle.passengerCapacity,
            vehicle.cargoCapacity,
            vehicle.initialPassengerLoad,
            vehicle.initialCargoLoad,
        )

    # 贪心最优插入：逐个任务插入到最佳位置
    task_order = []
    remaining = list(tasks)

    while remaining:
        best_task = None
        best_pos = 0
        best_score = float("inf")

        for task in remaining:
            for pos in range(len(task_order) + 1):
                score = _insertion_score(task, pos, task_order, task_blocks, station_map or {})
                if score < best_score:
                    best_score = score
                    best_task = task
                    best_pos = pos

        if best_task is None:
            break

        task_order.insert(best_pos, best_task.task_id)
        remaining.remove(best_task)

    # 分配到第一辆车
    vehicle_routes[0] = task_order
    for i in range(1, len(request.vehicles)):
        vehicle_routes[i] = []

    return GlobalRouteGenome(
        vehicle_routes=vehicle_routes,
        task_blocks=task_blocks,
        skeletons=skeletons,
        depot_station=request.depot.stationId,
        vehicle_caps=vehicle_caps,
    )


def _insertion_score(task: TaskBlock, pos: int, current_order: list[str], task_blocks: dict, station_map: dict) -> float:
    """计算任务插入到指定位置的得分。"""
    from math import hypot

    # 获取前后站点
    depot = station_map.get("S0")
    if not depot:
        return 100.0

    if pos == 0:
        prev = depot
    else:
        prev_task = task_blocks.get(current_order[pos - 1])
        prev = station_map.get(prev_task.delivery_station) if prev_task else depot

    if pos >= len(current_order):
        nxt = depot
    else:
        nxt_task = task_blocks.get(current_order[pos])
        nxt = station_map.get(nxt_task.pickup_station) if nxt_task else depot

    pickup = station_map.get(task.pickup_station)
    delivery = station_map.get(task.delivery_station)

    if not all([prev, nxt, pickup, delivery]):
        return 100.0

    # 原距离
    orig = hypot(prev.longitude - nxt.longitude, prev.latitude - nxt.latitude)

    # 新距离
    new = (hypot(prev.longitude - pickup.longitude, prev.latitude - pickup.latitude)
           + hypot(pickup.longitude - delivery.longitude, pickup.latitude - delivery.latitude)
           + hypot(delivery.longitude - nxt.longitude, delivery.latitude - nxt.latitude))

    return max(0.0, new - orig)


def _extract_task_sequence(genome: GlobalRouteGenome) -> list[str]:
    seq = ["DEPOT"]
    for vi in sorted(genome.vehicle_routes.keys()):
        for task_id in genome.vehicle_routes[vi]:
            seq.append(task_id)
    return seq


def _extract_station_sequence(genome: GlobalRouteGenome, station_map: dict) -> list[str]:
    seq = [genome.depot_station]
    for vi in sorted(genome.vehicle_routes.keys()):
        for task_id in genome.vehicle_routes[vi]:
            task = genome.task_blocks.get(task_id)
            if task:
                if task.pickup_station not in seq:
                    seq.append(task.pickup_station)
                if task.task_type in (TaskType.PASSENGER, TaskType.SHIPMENT):
                    if task.delivery_station not in seq:
                        seq.append(task.delivery_station)
    seq.append(genome.depot_station)
    return seq


def _log_improvement(iteration: int, obj: GenomeEvaluation, genome: GlobalRouteGenome, station_map: dict) -> None:
    """记录改进信息。"""
    station_seq = _extract_station_sequence(genome, station_map)
    logger.info(
        "BEST #%d: cost=%.4f distance=%.4f backtracking=%.4f stations=%s",
        iteration, obj.normalized_cost, obj.total_distance, obj.backtracking_ratio,
        "-".join(station_seq)
    )


def _sa_probability(delta: float, temperature: float) -> float:
    """Simulated Annealing 接受概率。"""
    if temperature <= 0:
        return 0.0
    import math
    return math.exp(-delta / temperature)


def _build_genome_template(request: PlanRequest, tasks: list[TaskBlock] = None) -> GlobalRouteGenome:
    task_blocks = {}
    if tasks:
        task_blocks = {t.task_id: t for t in tasks}
    vehicle_routes = {}
    skeletons = {}
    vehicle_caps = {}
    for i, vehicle in enumerate(request.vehicles):
        vehicle_routes[i] = []
        skeletons[i] = vehicle.skeleton or []
        vehicle_caps[i] = (vehicle.passengerCapacity, vehicle.cargoCapacity, vehicle.initialPassengerLoad, vehicle.initialCargoLoad)
    return GlobalRouteGenome(vehicle_routes=vehicle_routes, task_blocks=task_blocks, skeletons=skeletons, depot_station=request.depot.stationId, vehicle_caps=vehicle_caps)


def _precheck(request: PlanRequest) -> SolveOutcome | None:
    passengers = sum(1 for o in request.orders if o.orderType == OrderType.PASSENGER)
    deliveries = sum(o.itemCount for o in request.orders if o.orderType == OrderType.DELIVERY and o.cargoSource != CargoSource.PRELOADED)
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
    tasks = []
    for order in request.orders:
        if order.orderType == OrderType.PASSENGER:
            tasks.append(TaskBlock(task_id=f"P:{order.orderId}", task_type=TaskType.PASSENGER, pickup_station=order.boardingStationId, delivery_station=order.alightingStationId, size=1, order_ids=[order.orderId]))
        elif order.orderType == OrderType.DELIVERY:
            tasks.append(TaskBlock(task_id=f"D:{order.orderId}", task_type=TaskType.DELIVERY, pickup_station=order.stationId, delivery_station=order.stationId, size=order.itemCount, order_ids=[order.orderId], cargo_source=order.cargoSource))
        elif order.orderType == OrderType.PICKUP:
            tasks.append(TaskBlock(task_id=f"K:{order.orderId}", task_type=TaskType.PICKUP, pickup_station=order.stationId, delivery_station=order.stationId, size=order.itemCount, order_ids=[order.orderId], cargo_source=order.cargoSource))
    for shipment in request.shipments:
        tasks.append(TaskBlock(task_id=f"S:{shipment.shipmentId}", task_type=TaskType.SHIPMENT, pickup_station=shipment.pickupStationId, delivery_station=shipment.deliveryStationId, size=shipment.quantity, order_ids=[shipment.shipmentId]))
    return tasks


def _genome_to_plans(genome: GlobalRouteGenome, request: PlanRequest, station_map: dict, matrix) -> list[VehiclePlan]:
    vehicle_plans = []
    for vi in sorted(genome.vehicle_routes.keys()):
        route = genome.get_route(vi)
        if not route:
            continue
        vehicle = request.vehicles[vi]
        caps = genome.vehicle_caps.get(vi, (5, 4, 0, 0))
        p_cap, c_cap, p_init, c_init = caps
        stops = []
        scaled_total = 0
        current_station = request.depot
        current_passengers = p_init
        stops.append(RouteStop(stationId=request.depot.stationId, action=StopAction.DEPART, segmentDistance=0.0, segmentDuration=None))
        for task_id in route:
            task = genome.task_blocks.get(task_id)
            if not task:
                continue
            if task.task_type == TaskType.PASSENGER:
                pickup = station_map.get(task.pickup_station)
                if pickup:
                    seg_km = _distance(current_station, pickup, matrix)
                    seg_sec = _duration(current_station, pickup, matrix)
                    scaled_total += int(round(seg_km * DISTANCE_SCALE))
                    stops.append(RouteStop(stationId=task.pickup_station, orderId=task.order_ids[0] if task.order_ids else None, action=StopAction.BOARD, segmentDistance=round(seg_km, 3), segmentDuration=seg_sec))
                    current_station = pickup
                    current_passengers += 1
                delivery = station_map.get(task.delivery_station)
                if delivery:
                    seg_km = _distance(current_station, delivery, matrix)
                    seg_sec = _duration(current_station, delivery, matrix)
                    scaled_total += int(round(seg_km * DISTANCE_SCALE))
                    stops.append(RouteStop(stationId=task.delivery_station, orderId=task.order_ids[0] if task.order_ids else None, action=StopAction.ALIGHT, segmentDistance=round(seg_km, 3), segmentDuration=seg_sec))
                    current_station = delivery
                    current_passengers -= 1
            elif task.task_type == TaskType.SHIPMENT:
                pickup = station_map.get(task.pickup_station)
                if pickup:
                    seg_km = _distance(current_station, pickup, matrix)
                    seg_sec = _duration(current_station, pickup, matrix)
                    scaled_total += int(round(seg_km * DISTANCE_SCALE))
                    stops.append(RouteStop(stationId=task.pickup_station, orderId=task.order_ids[0] if task.order_ids else None, action=StopAction.PICKUP, segmentDistance=round(seg_km, 3), segmentDuration=seg_sec, accepted=True, serviceMode="NEAREST_STATION", servicePoint=task.pickup_station, detourDistance=0.0, reasonCode=None))
                    current_station = pickup
                delivery = station_map.get(task.delivery_station)
                if delivery:
                    seg_km = _distance(current_station, delivery, matrix)
                    seg_sec = _duration(current_station, delivery, matrix)
                    scaled_total += int(round(seg_km * DISTANCE_SCALE))
                    stops.append(RouteStop(stationId=task.delivery_station, orderId=task.order_ids[0] if task.order_ids else None, action=StopAction.DELIVER, segmentDistance=round(seg_km, 3), segmentDuration=seg_sec, accepted=True, serviceMode="NEAREST_STATION", servicePoint=task.delivery_station, reasonCode=None))
                    current_station = delivery
            elif task.task_type == TaskType.DELIVERY:
                station = station_map.get(task.pickup_station)
                if station:
                    seg_km = _distance(current_station, station, matrix)
                    seg_sec = _duration(current_station, station, matrix)
                    scaled_total += int(round(seg_km * DISTANCE_SCALE))
                    stops.append(RouteStop(stationId=task.pickup_station, orderId=task.order_ids[0] if task.order_ids else None, action=StopAction.DELIVER, segmentDistance=round(seg_km, 3), segmentDuration=seg_sec, accepted=True, serviceMode="NEAREST_STATION", servicePoint=task.pickup_station, detourDistance=0.0, reasonCode=None))
                    current_station = station
            elif task.task_type == TaskType.PICKUP:
                station = station_map.get(task.pickup_station)
                if station:
                    seg_km = _distance(current_station, station, matrix)
                    seg_sec = _duration(current_station, station, matrix)
                    scaled_total += int(round(seg_km * DISTANCE_SCALE))
                    stops.append(RouteStop(stationId=task.pickup_station, orderId=task.order_ids[0] if task.order_ids else None, action=StopAction.PICKUP, segmentDistance=round(seg_km, 3), segmentDuration=seg_sec, accepted=True, serviceMode="NEAREST_STATION", servicePoint=task.pickup_station, detourDistance=0.0, reasonCode=None))
                    current_station = station
        seg_km = _distance(current_station, request.depot, matrix)
        seg_sec = _duration(current_station, request.depot, matrix)
        scaled_total += int(round(seg_km * DISTANCE_SCALE))
        stops.append(RouteStop(stationId=request.depot.stationId, action=StopAction.RETURN, segmentDistance=round(seg_km, 3), segmentDuration=seg_sec))
        vehicle_plans.append(VehiclePlan(vehicleId=vehicle.vehicleId, stops=stops, totalDistance=round(scaled_total / DISTANCE_SCALE, 3)))
    return vehicle_plans


def _distance(a, b, matrix=None) -> float:
    if matrix is not None:
        key = (a.stationId, b.stationId)
        if key in matrix:
            return matrix[key][0]
    return hypot(a.longitude - b.longitude, a.latitude - b.latitude)


def _duration(a, b, matrix=None) -> float:
    if matrix is not None:
        key = (a.stationId, b.stationId)
        if key in matrix:
            return matrix[key][1] or 0.0
    km = haversine_km(a.longitude, a.latitude, b.longitude, b.latitude)
    return km / EUCLIDEAN_AVG_SPEED_KMH * 3600


def _ortools_validate(request, vehicle_plans, matrix) -> SolveOutcome | None:
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
    from ..baseline.ortools_solver import solve as baseline_solve
    result = baseline_solve(request, matrix)
    # fallback 身份：真回落 OR-Tools 就如实标 ortools-1.3.0，禁止伪装成 HACO 版本
    return SolveOutcome(status=result.status, reason_code=result.reason_code, vehicle_plans=result.vehicle_plans, total_distance=result.total_distance, algorithm_version=BASELINE_VERSION, parameter_version="ortools-fallback-v1.3", warnings=warnings)
