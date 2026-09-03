"""Stop-Level HACO 主求解器。

验证 stop-level representation 是否能进入 task-level 无法表达的解空间。
"""

from __future__ import annotations

import logging
import random
import time
from math import hypot
from typing import TYPE_CHECKING

from ..config import HacoConfig
from ..encoding import TaskType
from .constructor import (
    construct_greedy_stop_level,
    construct_interleaved_stop_level,
    construct_passenger_first_stop_level,
)
from .evaluator import evaluate_solution
from .local_search import stop_level_local_search
from .models import (
    Activity,
    ActionType,
    Request,
    StopLevelEvaluation,
    StopLevelRoute,
    StopLevelSolution,
)

if TYPE_CHECKING:
    from ..distance import DistanceMatrix
    from ..models import PlanRequest, Station

logger = logging.getLogger(__name__)


def solve_stop_level(
    request: PlanRequest,
    station_map: dict,
    matrix: DistanceMatrix | None = None,
    config: HacoConfig | None = None,
) -> tuple[StopLevelSolution, StopLevelEvaluation]:
    """Stop-level HACO 主入口。"""
    if config is None:
        config = HacoConfig()

    rng = random.Random(config.random_seed)

    # 编码请求
    requests = _encode_requests(request)
    if not requests:
        empty = StopLevelSolution(routes={}, requests={}, depot_station=request.depot.stationId)
        return empty, StopLevelEvaluation(feasible=True)

    # 构建路线模板
    route_templates = _build_route_templates(request)

    # 生成多种初始解
    solutions = []

    # 1. Greedy
    greedy = construct_greedy_stop_level(requests, route_templates, station_map, matrix, rng)
    greedy_obj = evaluate_solution(greedy, station_map, matrix)
    if greedy_obj.feasible:
        solutions.append((greedy, greedy_obj, "greedy"))

    # 2. Passenger-first
    pf = construct_passenger_first_stop_level(requests, route_templates, station_map, matrix, rng)
    pf_obj = evaluate_solution(pf, station_map, matrix)
    if pf_obj.feasible:
        solutions.append((pf, pf_obj, "passenger-first"))

    # 3. Interleaved
    il = construct_interleaved_stop_level(requests, route_templates, station_map, matrix, rng)
    il_obj = evaluate_solution(il, station_map, matrix)
    if il_obj.feasible:
        solutions.append((il, il_obj, "interleaved"))

    if not solutions:
        empty = StopLevelSolution(routes={}, requests={}, depot_station=request.depot.stationId)
        return empty, StopLevelEvaluation(feasible=False)

    # 选择最佳初始解
    best_sol, best_obj, best_name = min(solutions, key=lambda x: x[1])
    logger.info("Best initial: %s, distance=%.4f", best_name, best_obj.total_distance)

    # HACO 迭代
    deadline = time.monotonic() + config.haco_time_limit
    no_improve = 0

    for iteration in range(config.max_iterations):
        if time.monotonic() > deadline:
            break

        # 构造新解
        new_sol = construct_interleaved_stop_level(requests, route_templates, station_map, matrix, rng)

        # 局部搜索
        new_sol = stop_level_local_search(new_sol, station_map, matrix, config, rng)

        new_obj = evaluate_solution(new_sol, station_map, matrix)

        if new_obj.feasible and new_obj < best_obj:
            best_sol = new_sol
            best_obj = new_obj
            no_improve = 0
            logger.info("Improvement #%d: distance=%.4f", iteration, new_obj.total_distance)
        else:
            no_improve += 1

        if no_improve >= config.convergence_threshold:
            break

    return best_sol, best_obj


def solution_to_plans(
    solution: StopLevelSolution,
    request: PlanRequest,
    station_map: dict,
    matrix: DistanceMatrix | None = None,
) -> list:
    """将 stop-level 解转换为 VehiclePlan 格式。"""
    from ..route_genome import GlobalRouteGenome
    from ..hybrid_optimizer import _genome_to_plans

    # 将 stop-level 解转换为 genome 格式
    task_blocks = {}
    vehicle_routes = {}

    for vi, route in solution.routes.items():
        if not route.activities:
            vehicle_routes[vi] = []
            continue

        # 从 activities 推导 task 序列
        task_order = []
        seen = set()
        for activity in route.activities:
            if activity.request_id and activity.request_id not in seen:
                task_order.append(activity.request_id)
                seen.add(activity.request_id)

        vehicle_routes[vi] = task_order

    # 构建 task_blocks
    for req_id, req in solution.requests.items():
        from ..encoding import TaskBlock
        if req.request_type == "PASSENGER":
            task_blocks[req_id] = TaskBlock(
                task_id=req_id,
                task_type=TaskType.PASSENGER,
                pickup_station=req.pickup_station,
                delivery_station=req.delivery_station,
                size=req.size,
                order_ids=[req_id],
            )
        elif req.request_type == "DELIVERY":
            task_blocks[req_id] = TaskBlock(
                task_id=req_id,
                task_type=TaskType.DELIVERY,
                pickup_station=req.pickup_station,
                delivery_station=req.delivery_station,
                size=req.size,
                order_ids=[req_id],
            )
        elif req.request_type == "PICKUP":
            task_blocks[req_id] = TaskBlock(
                task_id=req_id,
                task_type=TaskType.PICKUP,
                pickup_station=req.pickup_station,
                delivery_station=req.delivery_station,
                size=req.size,
                order_ids=[req_id],
            )
        elif req.request_type == "SHIPMENT":
            task_blocks[req_id] = TaskBlock(
                task_id=req_id,
                task_type=TaskType.SHIPMENT,
                pickup_station=req.pickup_station,
                delivery_station=req.delivery_station,
                size=req.size,
                order_ids=[req_id],
            )

    genome = GlobalRouteGenome(
        vehicle_routes=vehicle_routes,
        task_blocks=task_blocks,
        skeletons={vi: route.skeleton for vi, route in solution.routes.items()},
        depot_station=request.depot.stationId,
        vehicle_caps={
            vi: (route.passenger_capacity, route.cargo_capacity, route.initial_passenger_load, route.initial_cargo_load)
            for vi, route in solution.routes.items()
        },
    )

    return _genome_to_plans(genome, request, station_map, matrix)


def _encode_requests(request: PlanRequest) -> list[Request]:
    """编码请求。"""
    requests = []
    for order in request.orders:
        if order.orderType == "PASSENGER":
            requests.append(Request(
                request_id=f"P:{order.orderId}",
                request_type="PASSENGER",
                pickup_station=order.boardingStationId,
                delivery_station=order.alightingStationId,
                size=1,
            ))
        elif order.orderType == "DELIVERY":
            requests.append(Request(
                request_id=f"D:{order.orderId}",
                request_type="DELIVERY",
                pickup_station=order.stationId,
                delivery_station=order.stationId,
                size=order.itemCount,
            ))
        elif order.orderType == "PICKUP":
            requests.append(Request(
                request_id=f"K:{order.orderId}",
                request_type="PICKUP",
                pickup_station=order.stationId,
                delivery_station=order.stationId,
                size=order.itemCount,
            ))
    for shipment in request.shipments:
        requests.append(Request(
            request_id=f"S:{shipment.shipmentId}",
            request_type="SHIPMENT",
            pickup_station=shipment.pickupStationId,
            delivery_station=shipment.deliveryStationId,
            size=shipment.quantity,
        ))
    return requests


def _build_route_templates(request: PlanRequest) -> list[StopLevelRoute]:
    """构建路线模板。"""
    templates = []
    for i, vehicle in enumerate(request.vehicles):
        templates.append(StopLevelRoute(
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
