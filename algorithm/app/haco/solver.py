"""HACO-CPS 2.0 主求解器：Global Route Genome + Skeleton-Aware Hybrid Optimization。

核心改进（相比1.3.0）：
1. 从 Gap-based 搜索升级为 Global Route Sequence 搜索
2. GlobalRouteGenome 直接表达每辆车的任务序列
3. Passenger-first construction：先构造乘客骨架，再插入货运
4. 全局回溯惩罚：避免 S1→S3→S2→S4 的反向路线
5. 站点聚类：同一站点的任务倾向连续服务
6. Skeleton 作为 prior（方向偏好），不是硬约束
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
from .global_construction import (
    construct_global_solution,
    construct_greedy_global,
    construct_nn_global,
)
from .global_evaluator import (
    GenomeEvaluation,
    evaluate_genome,
    compute_genome_signature,
    compute_genome_diversity,
)
from .global_local_search import global_local_search
from .pheromone import PheromoneMatrix, extract_vehicle_task_sequences
from .route_genome import GlobalRouteGenome

logger = logging.getLogger(__name__)

# 版本信息
HACO_VERSION = "haco-cps-2.0.0"
HACO_PARAMETER_VERSION = "haco-cps-default-v2"
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
    """HACO-CPS 2.0 主求解入口。"""
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

    # 构建 GlobalRouteGenome 模板
    genome_template = _build_genome_template(request, tasks)

    # 初始化信息素
    task_ids = [t.task_id for t in tasks] + ["DEPOT"]
    pheromone = PheromoneMatrix(task_ids, config)

    # 生成多种初始解
    solutions = []

    # 1. Nearest Neighbor
    nn_genome = construct_nn_global(tasks, genome_template, station_map, matrix)
    nn_obj = evaluate_genome(nn_genome, station_map, matrix)
    if nn_obj.feasible:
        solutions.append((nn_genome, nn_obj))

    # 2. Greedy
    greedy_genome = construct_greedy_global(tasks, genome_template, station_map, matrix, rng)
    greedy_obj = evaluate_genome(greedy_genome, station_map, matrix)
    if greedy_obj.feasible:
        solutions.append((greedy_genome, greedy_obj))

    # 3. ACO construction
    aco_genome = construct_global_solution(
        tasks, genome_template, pheromone, station_map, matrix, config, rng
    )
    aco_obj = evaluate_genome(aco_genome, station_map, matrix)
    if aco_obj.feasible:
        solutions.append((aco_genome, aco_obj))

    # 选择最佳初始解
    if not solutions:
        return SolveOutcome(status="infeasible", reason_code="TIMING_CONFLICT", warnings=["NO_FEASIBLE_INITIAL_SOLUTION"])

    best_genome, best_obj = min(solutions, key=lambda x: x[1])

    # 初始化信息素
    if best_obj.normalized_cost > 0:
        pheromone.initialize_tau0(best_obj.normalized_cost)

    warnings = []
    iteration_stats = []
    no_improve_count = 0

    # 迭代搜索
    deadline = time.monotonic() + config.haco_time_limit

    # 计算初始多样性
    diversity = 0.5

    for iteration in range(config.max_iterations):
        if time.monotonic() > deadline:
            warnings.append("HACO_TIME_LIMIT_REACHED")
            break

        # 自适应 alpha/beta
        adaptive_alpha, adaptive_beta = _adapt_parameters(config, diversity)

        iteration_best_genome = None
        iteration_best_obj = GenomeEvaluation(feasible=False)
        iteration_objs = []

        # 每只蚂蚁构造解
        for ant_idx in range(config.ant_count):
            if time.monotonic() > deadline:
                break

            # 构造解（传入自适应 alpha/beta）
            ant_genome = construct_global_solution(
                tasks, genome_template, pheromone, station_map, matrix, config, rng,
                alpha=adaptive_alpha, beta=adaptive_beta
            )

            # 局部搜索（对精英蚂蚁）
            if ant_idx < config.elite_count:
                ant_genome = global_local_search(
                    ant_genome, station_map, matrix, config, rng
                )

            ant_obj = evaluate_genome(ant_genome, station_map, matrix)
            iteration_objs.append(ant_obj)

            if ant_obj.feasible and (iteration_best_genome is None or ant_obj < iteration_best_obj):
                iteration_best_genome = ant_genome
                iteration_best_obj = ant_obj

        # 信息素更新
        pheromone.evaporate()

        if iteration_best_genome and iteration_best_obj.feasible:
            # 多车辆信息素沉积（禁止跨车边）
            vehicle_seqs = _extract_vehicle_sequences(iteration_best_genome)
            pheromone.deposit_multi_vehicle(vehicle_seqs, iteration_best_obj.normalized_cost, weight=1.0)

            if iteration_best_obj < best_obj:
                best_genome = iteration_best_genome
                best_obj = iteration_best_obj
                no_improve_count = 0
                pheromone.deposit_multi_vehicle(vehicle_seqs, best_obj.normalized_cost, weight=2.0)
            else:
                no_improve_count += 1

            # 更新多样性
            iteration_sigs = set()
            for obj in iteration_objs:
                if obj.feasible:
                    iteration_sigs.add(obj.key())
            diversity = len(iteration_sigs) / max(len([o for o in iteration_objs if o.feasible]), 1)
        else:
            no_improve_count += 1

        # 记录迭代统计
        feasible_count = sum(1 for o in iteration_objs if o.feasible)
        iteration_stats.append({
            "iteration": iteration,
            "best_cost": best_obj.normalized_cost if best_obj.feasible else float("inf"),
            "feasible_count": feasible_count,
            "average_cost": sum(o.normalized_cost for o in iteration_objs if o.feasible) / max(feasible_count, 1),
            "backtracking": best_obj.backtracking_ratio if best_obj.feasible else 0,
        })

        # 收敛检查
        if no_improve_count >= config.convergence_threshold:
            warnings.append("HACO_CONVERGED")
            break

        # 信息素重启
        if no_improve_count >= config.convergence_threshold // 2:
            _pheromone_restart(pheromone, config, rng)

    # 如果 HACO 没找到可行解，fallback 到 OR-Tools
    if not best_obj.feasible:
        warnings.append("HACO_FALLBACK_TO_BASELINE")
        return _fallback_to_baseline(request, matrix, warnings)

    # 转换为 VehiclePlan
    vehicle_plans = _genome_to_plans(best_genome, request, station_map, matrix)

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
    """编码任务。"""
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
                cargo_source=order.cargoSource,
            ))
        elif order.orderType == OrderType.PICKUP:
            tasks.append(TaskBlock(
                task_id=f"K:{order.orderId}",
                task_type=TaskType.PICKUP,
                pickup_station=order.stationId,
                delivery_station=order.stationId,
                size=order.itemCount,
                order_ids=[order.orderId],
                cargo_source=order.cargoSource,
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


def _build_genome_template(request: PlanRequest, tasks: list[TaskBlock] = None) -> GlobalRouteGenome:
    """构建 GlobalRouteGenome 模板。"""
    task_blocks = {}
    if tasks:
        task_blocks = {t.task_id: t for t in tasks}

    vehicle_routes = {}
    skeletons = {}
    vehicle_caps = {}

    for i, vehicle in enumerate(request.vehicles):
        vehicle_routes[i] = []
        skeletons[i] = vehicle.skeleton or []
        vehicle_caps[i] = (
            vehicle.passengerCapacity,
            vehicle.cargoCapacity,
            vehicle.initialPassengerLoad,
            vehicle.initialCargoLoad,
        )

    return GlobalRouteGenome(
        vehicle_routes=vehicle_routes,
        task_blocks=task_blocks,
        skeletons=skeletons,
        depot_station=request.depot.stationId,
        vehicle_caps=vehicle_caps,
    )


def _extract_vehicle_sequences(genome: GlobalRouteGenome) -> dict[int, list[str]]:
    """提取每辆车的任务序列（用于多车辆信息素更新）。

    每个序列以 "DEPOT" 开头，禁止跨车边。
    """
    result = {}
    for vi, route in genome.vehicle_routes.items():
        seq = ["DEPOT"]
        for task_id in route:
            seq.append(task_id)
        result[vi] = seq
    return result


def _genome_to_plans(
    genome: GlobalRouteGenome,
    request: PlanRequest,
    station_map: dict,
    matrix: DistanceMatrix | None,
) -> list[VehiclePlan]:
    """将 GlobalRouteGenome 转换为 VehiclePlan 格式。"""
    vehicle_plans = []

    for vi in sorted(genome.vehicle_routes.keys()):
        route = genome.get_route(vi)
        if not route:
            continue

        vehicle = request.vehicles[vi]
        skeleton = genome.skeletons.get(vi, [])
        caps = genome.vehicle_caps.get(vi, (5, 4, 0, 0))
        p_cap, c_cap, p_init, c_init = caps

        stops = []
        scaled_total = 0
        current_station = request.depot
        current_passengers = p_init

        # DEPART
        stops.append(RouteStop(
            stationId=request.depot.stationId,
            action=StopAction.DEPART,
            segmentDistance=0.0,
            segmentDuration=None,
        ))

        # 按任务序列展开
        for task_id in route:
            task = genome.task_blocks.get(task_id)
            if not task:
                continue

            if task.task_type == TaskType.PASSENGER:
                # BOARD
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

                # ALIGHT
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
                # PICKUP
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

                # DELIVERY
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
                    stops.append(RouteStop(
                        stationId=task.pickup_station,
                        orderId=task.order_ids[0] if task.order_ids else None,
                        action=StopAction.DELIVER,
                        segmentDistance=round(seg_km, 3),
                        segmentDuration=seg_sec,
                        accepted=True,
                        serviceMode="NEAREST_STATION",
                        servicePoint=task.pickup_station,
                        detourDistance=0.0,
                        reasonCode=None,
                    ))
                    current_station = station

            elif task.task_type == TaskType.PICKUP:
                station = station_map.get(task.pickup_station)
                if station:
                    seg_km = _distance(current_station, station, matrix)
                    seg_sec = _duration(current_station, station, matrix)
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
                    current_station = station

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
            vehicleId=vehicle.vehicleId,
            stops=stops,
            totalDistance=round(scaled_total / DISTANCE_SCALE, 3),
        ))

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


def _pheromone_restart(pheromone, config, rng) -> None:
    """信息素重启。"""
    for i in range(pheromone.n):
        for j in range(pheromone.n):
            pheromone.tau[i][j] = pheromone.tau0 * 0.5 + pheromone.tau[i][j] * 0.5


def _adapt_parameters(config: HacoConfig, diversity: float) -> tuple[float, float]:
    """自适应 alpha/beta：根据多样性调整探索/利用平衡。

    低多样性 → 增大 beta（更贪婪，增加多样性）
    高多样性 → 增大 alpha（更依赖信息素，加速收敛）
    """
    alpha = config.alpha
    beta = config.beta

    if diversity < config.adaptive_diversity_low:
        # 太相似，增加探索
        beta = min(config.beta_max, config.beta * 1.2)
        alpha = max(config.alpha_min, config.alpha * 0.9)
    elif diversity > config.adaptive_diversity_high:
        # 太分散，增加利用
        alpha = min(config.alpha_max, config.alpha * 1.2)
        beta = max(config.beta_min, config.beta * 0.9)

    return alpha, beta


def _ortools_validate(request, vehicle_plans, matrix) -> SolveOutcome | None:
    """OR-Tools 最终验证。"""
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
        parameter_version="haco-cps-fallback-v2",
        warnings=warnings,
    )
