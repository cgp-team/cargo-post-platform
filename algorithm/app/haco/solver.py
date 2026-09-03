"""HACO-CPS 1.1.0 主求解器：真正的 HACO 路线构造 + OR-Tools 验证/修复。

核心改进（相比1.0.0）：
1. 每只蚂蚁真正自己构造路线（不调用 OR-Tools）
2. Skeleton Gap 模型：骨架间隙中插入任务
3. Task Block 不可拆分：Passenger/Shipments 整体移动
4. Local Search 真正接入主循环
5. LNS Destroy-Repair 真正作用于 RouteState
6. OR-Tools 仅用于最终验证和 fallback

架构：
- HACO 构造 → RouteState
- Local Search → 改进 RouteState
- LNS → 破坏+修复 RouteState
- OR-Tools → 验证可行性 + Fallback
- 输出 → VehiclePlan (API 兼容)
"""

from __future__ import annotations

import copy
import logging
import random
import time
from dataclasses import dataclass, field

from ..distance import DistanceMatrix, EUCLIDEAN_AVG_SPEED_KMH, haversine_km
from ..models import (
    CargoSource,
    OrderType,
    PlanRequest,
    RouteStop,
    StopAction,
    VehiclePlan,
)
from ..validators import service_duration, validate_time_window, validate_vehicle_plan
from .config import HacoConfig
from .construction import construct_ant_solution
from .destroy_repair import destroy_random, destroy_worst, repair_greedy, repair_regret2
from .encoding import ObjectiveVector, TaskBlock, TaskType
from .evaluator import compute_diversity, evaluate_route_states
from .feasibility import validate_route_states
from .local_search import local_search
from .pheromone import PheromoneMatrix
from .route_state import RouteState

logger = logging.getLogger(__name__)

# 版本信息
HACO_VERSION = "haco-cps-1.1.0"
HACO_PARAMETER_VERSION = "haco-cps-default-v1.1"
BASELINE_VERSION = "ortools-1.3.0"
HACO_1_0_VERSION = "haco-cps-1.0.0"

# 距离缩放
DISTANCE_SCALE = 1000


@dataclass
class SolveOutcome:
    status: str  # "feasible" | "infeasible"
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
    """HACO-CPS 1.1.0 主求解入口。

    流程：
    1. 预检（容量、时间窗）
    2. 任务编码（TaskBlock）
    3. 初始化 RouteState（每辆车的骨架+间隙）
    4. HACO 迭代：蚂蚁构造 → 局部搜索 → LNS → 信息素更新
    5. OR-Tools 验证最终解
    6. Fallback 到 OR-Tools baseline（如果 HACO 无解）
    """
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

    # 初始化信息素
    task_ids = [t.task_id for t in tasks] + ["DEPOT"]
    pheromone = PheromoneMatrix(task_ids, config)

    # 生成初始解（用贪婪构造）
    initial_states = construct_ant_solution(
        tasks, route_templates, pheromone, station_map, matrix, config, rng
    )
    initial_obj = evaluate_route_states(initial_states, station_map, matrix)

    # 初始化信息素 tau0
    if initial_obj.normalized_cost() > 0:
        pheromone.initialize_tau0(initial_obj.normalized_cost())

    best_states = initial_states
    best_obj = initial_obj
    warnings = []
    iteration_stats = []
    no_improve_count = 0

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

        # 每只蚂蚁构造解
        for ant_idx in range(config.ant_count):
            if time.monotonic() > deadline:
                break

            # 蚂蚁构造
            ant_states = construct_ant_solution(
                tasks, route_templates, pheromone, station_map, matrix, config, rng
            )

            # 局部搜索（对精英蚂蚁）
            if ant_idx < config.elite_count:
                ant_states = local_search(
                    ant_states, station_map, matrix, config, rng
                )

            ant_obj = evaluate_route_states(ant_states, station_map, matrix)
            iteration_solutions.append(ant_states)
            iteration_objs.append(ant_obj)

            if ant_obj < iteration_best_obj:
                iteration_best_states = ant_states
                iteration_best_obj = ant_obj

        # LNS 对迭代最优解
        if iteration_best_states and rng.random() < config.lns_probability:
            lns_states = _lns_improve(
                iteration_best_states, station_map, matrix, config, rng
            )
            lns_obj = evaluate_route_states(lns_states, station_map, matrix)
            if lns_obj < iteration_best_obj:
                iteration_best_states = lns_states
                iteration_best_obj = lns_obj

        # 信息素更新
        pheromone.evaporate()

        if iteration_best_states and iteration_best_obj.normalized_cost() < float("inf"):
            # 迭代最优沉积
            task_seq = _extract_task_sequence(iteration_best_states)
            pheromone.deposit(task_seq, iteration_best_obj.normalized_cost(), weight=1.0)

            # 全局最优更新
            if iteration_best_obj < best_obj:
                best_states = [s.copy() for s in iteration_best_states]
                best_obj = iteration_best_obj
                no_improve_count = 0
                pheromone.update_from_best(task_seq, best_obj.normalized_cost(), elite_weight=2.0)
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
        })

        # 收敛检查
        if no_improve_count >= config.convergence_threshold:
            warnings.append("HACO_CONVERGED")
            break

        # 信息素重启（防早熟收敛）
        if no_improve_count >= config.convergence_threshold // 2:
            _pheromone_restart(pheromone, config, rng)

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
    """预检：容量、时间窗。"""
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


def _lns_improve(
    states: list[RouteState],
    station_map: dict[str, Station],
    matrix: DistanceMatrix | None,
    config: HacoConfig,
    rng: random.Random,
) -> list[RouteState]:
    """LNS 改进：破坏 + 修复。"""
    # 收集所有任务
    all_tasks = []
    for state in states:
        all_tasks.extend(state.tasks)

    if not all_tasks:
        return states

    # 随机选择破坏算子
    destroy_op = rng.choice(["random", "worst", "related"])

    if destroy_op == "random":
        removed, partial = destroy_random(states, config.destroy_fraction, rng)
    elif destroy_op == "worst":
        removed, partial = destroy_worst(states, station_map, matrix, config.destroy_fraction, rng)
    else:
        removed, partial = destroy_random(states, config.destroy_fraction, rng)

    if not removed:
        return states

    # 修复
    if rng.random() < 0.5:
        repaired = repair_greedy(partial, removed, station_map, matrix, config, rng)
    else:
        repaired = repair_regret2(partial, removed, station_map, matrix, config, rng)

    return repaired


def _pheromone_restart(pheromone: PheromoneMatrix, config: HacoConfig, rng: random.Random) -> None:
    """信息素部分重启（防早熟收敛）。"""
    for i in range(pheromone.n):
        for j in range(pheromone.n):
            if rng.random() < 0.5:
                pheromone.tau[i][j] = pheromone.tau0


def _extract_task_sequence(states: list[RouteState]) -> list[str]:
    """从解中提取任务序列（用于信息素更新）。"""
    seq = ["DEPOT"]
    for state in states:
        for task in state.tasks:
            seq.append(task.task_id)
    return seq


def _route_states_to_plans(
    states: list[RouteState],
    request: PlanRequest,
    station_map: dict,
    matrix: DistanceMatrix | None,
) -> list[VehiclePlan]:
    """将 RouteState 转换为 VehiclePlan 格式（API 兼容）。"""
    vehicle_plans = []

    for state in states:
        if not state.tasks:
            continue

        stops = []
        scaled_total = 0
        current_station = request.depot
        current_passengers = state.initial_passenger_load

        # DEPART
        stops.append(RouteStop(
            stationId=request.depot.stationId,
            action=StopAction.DEPART,
            segmentDistance=0.0,
            segmentDuration=0.0,
        ))

        # 遍历骨架间隙
        for gap in state.gaps:
            gap_tasks = state.get_tasks_in_gap(gap.gap_index)

            for task in gap_tasks:
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

                        # 计算绕行
                        delivery = station_map.get(task.delivery_station)
                        detour_km = 0.0
                        detour_sec = 0.0
                        if delivery:
                            direct_km = _distance(pickup, delivery, matrix)
                            detour_km = 0.0  # 在 gap 内不算绕行

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
                            detourDistance=detour_km,
                            detourDuration=detour_sec,
                            passengerImpact=passenger_impact,
                            reasonCode=None,
                        ))
                        current_station = pickup

                    # DELIVERY
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

                        # 计算绕行（off-skeleton delivery）
                        detour_km = 0.0
                        detour_sec = 0.0
                        skeleton_set = set(state.skeleton)
                        if task.pickup_station not in skeleton_set:
                            # 下一站是骨架站或 depot
                            next_station = station_map.get(gap.to_station)
                            if next_station:
                                direct_km = _distance(current_station, next_station, matrix)
                                via_km = seg_km + _distance(station, next_station, matrix)
                                detour_km = max(0.0, via_km - direct_km)
                                direct_sec = _duration(current_station, next_station, matrix)
                                via_sec = seg_sec + _duration(station, next_station, matrix)
                                detour_sec = max(0.0, via_sec - direct_sec)

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

                        # 计算绕行（off-skeleton pickup）
                        detour_km = 0.0
                        detour_sec = 0.0
                        skeleton_set = set(state.skeleton)
                        if task.pickup_station not in skeleton_set:
                            next_station = station_map.get(gap.to_station)
                            if next_station:
                                direct_km = _distance(current_station, next_station, matrix)
                                via_km = seg_km + _distance(station, next_station, matrix)
                                detour_km = max(0.0, via_km - direct_km)
                                direct_sec = _duration(current_station, next_station, matrix)
                                via_sec = seg_sec + _duration(station, next_station, matrix)
                                detour_sec = max(0.0, via_sec - direct_sec)

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


def _distance(a, b, matrix: DistanceMatrix | None) -> float:
    """计算距离。有矩阵用 km，无矩阵用欧氏度（与 baseline 口径一致）。"""
    if matrix is not None:
        key = (a.stationId, b.stationId)
        if key in matrix:
            return matrix[key][0]
    from math import hypot
    return hypot(a.longitude - b.longitude, a.latitude - b.latitude)


def _duration(a, b, matrix: DistanceMatrix | None) -> float:
    """计算行驶时间（秒）。"""
    if matrix is not None:
        key = (a.stationId, b.stationId)
        if key in matrix:
            return matrix[key][1] or 0.0
    km = haversine_km(a.longitude, a.latitude, b.longitude, b.latitude)
    return km / EUCLIDEAN_AVG_SPEED_KMH * 3600


def _ortools_validate(
    request: PlanRequest,
    vehicle_plans: list[VehiclePlan],
    matrix: DistanceMatrix | None,
) -> SolveOutcome | None:
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


def _fallback_to_baseline(
    request: PlanRequest,
    matrix: DistanceMatrix | None,
    warnings: list[str],
) -> SolveOutcome:
    """Fallback 到 OR-Tools baseline。"""
    from ..baseline.ortools_solver import solve as baseline_solve

    result = baseline_solve(request, matrix)
    return SolveOutcome(
        status=result.status,
        reason_code=result.reason_code,
        vehicle_plans=result.vehicle_plans,
        total_distance=result.total_distance,
        algorithm_version=HACO_VERSION,
        parameter_version="haco-cps-fallback-v1.1",
        warnings=warnings,
    )
