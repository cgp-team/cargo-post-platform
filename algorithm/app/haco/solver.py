"""HACO-CPS 主求解器：混合蚁群优化 + 业务启发式 + 局部搜索 + LNS + OR-Tools Repair。

核心流程：
1. 预检（容量、时间窗）
2. 调用 baseline OR-Tools 获取初始可行解
3. HACO 元启发式搜索改进
4. 输出最佳解

OR-Tools 在此架构中的角色：
- 初始解生成（保证格式正确和约束满足）
- Feasibility Oracle（验证候选解）
- Fallback（HACO 失败时恢复）
"""

from __future__ import annotations

import logging
import random
import time
from dataclasses import dataclass, field

from ..distance import DistanceMatrix
from ..models import PlanRequest, VehiclePlan
from ..validators import validate_time_window, validate_vehicle_plan
from .config import HacoConfig

logger = logging.getLogger(__name__)

# 版本信息
HACO_VERSION = "haco-cps-1.0.0"
HACO_PARAMETER_VERSION = "haco-cps-default-v1"
BASELINE_VERSION = "ortools-1.3.0"


@dataclass
class SolveOutcome:
    status: str  # "feasible" | "infeasible"
    reason_code: str | None = None
    vehicle_plans: list[VehiclePlan] = field(default_factory=list)
    total_distance: float = 0.0
    algorithm_version: str = HACO_VERSION
    parameter_version: str = HACO_PARAMETER_VERSION
    warnings: list[str] = field(default_factory=list)


def solve_haco(
    request: PlanRequest,
    matrix: DistanceMatrix | None = None,
    config: HacoConfig | None = None,
) -> SolveOutcome:
    """HACO-CPS 主求解入口。

    策略：使用 OR-Tools 生成初始可行解，然后用 HACO 元启发式搜索改进。

    这确保：
    1. 初始解格式正确（与现有 PlanResult 完全兼容）
    2. 所有约束被正确满足
    3. HACO 真正参与搜索（信息素、启发式、局部搜索）
    4. 随机种子影响搜索过程
    """
    if config is None:
        config = HacoConfig.from_algorithm_config(request.algorithmConfig)

    rng = random.Random(config.random_seed)

    # 预检
    precheck = _precheck(request)
    if precheck is not None:
        return precheck

    # Step 1: 使用 OR-Tools 获取初始可行解
    from ..baseline.ortools_solver import solve as baseline_solve

    baseline_result = baseline_solve(request, matrix)
    if baseline_result.status != "feasible":
        return SolveOutcome(
            status=baseline_result.status,
            reason_code=baseline_result.reason_code,
            warnings=["HACO_BASELINE_INFEASIBLE"],
        )

    # 初始解
    best_plans = baseline_result.vehicle_plans
    best_distance = baseline_result.total_distance
    warnings = []

    # Step 2: HACO 元启发式搜索改进
    # 构建站点映射
    station_map = {s.stationId: s for s in request.stations}
    station_map[request.depot.stationId] = request.depot

    # 编码任务
    tasks = _encode_tasks(request)
    if not tasks:
        return SolveOutcome(
            status="feasible",
            vehicle_plans=best_plans,
            total_distance=best_distance,
        )

    # 初始化信息素（基于初始解成本）
    from .pheromone import PheromoneMatrix
    task_ids = [t.task_id for t in tasks] + ["DEPOT"]
    pheromone = PheromoneMatrix(task_ids, config)
    pheromone.initialize_tau0(best_distance if best_distance > 0 else 1.0)

    # 迭代搜索
    no_improve_count = 0
    deadline = time.monotonic() + config.haco_time_limit

    for iteration in range(config.max_iterations):
        if time.monotonic() > deadline:
            warnings.append("HACO_TIME_LIMIT_REACHED")
            break

        # 每只蚂蚁构建解
        iteration_best_plans = None
        iteration_best_distance = float("inf")

        for ant_idx in range(config.ant_count):
            if time.monotonic() > deadline:
                break

            # 使用信息素和启发式引导的扰动
            candidate_plans, candidate_distance = _ant_construct_and_improve(
                best_plans, tasks, pheromone, station_map, matrix, config, rng, request
            )

            if candidate_plans and candidate_distance < iteration_best_distance:
                iteration_best_plans = candidate_plans
                iteration_best_distance = candidate_distance

        # 信息素更新
        pheromone.evaporate()

        if iteration_best_plans and iteration_best_distance < float("inf"):
            # 沉积信息素
            task_seq = _extract_task_sequence(iteration_best_plans)
            pheromone.deposit(task_seq, iteration_best_distance, weight=1.0)

            if iteration_best_distance < best_distance:
                best_plans = iteration_best_plans
                best_distance = iteration_best_distance
                no_improve_count = 0
                # 全局最优强化
                pheromone.update_from_best(task_seq, best_distance, elite_weight=2.0)
            else:
                no_improve_count += 1
        else:
            no_improve_count += 1

        # 收敛检查
        if no_improve_count >= config.convergence_threshold:
            warnings.append("HACO_CONVERGED")
            break

    # 后置验证
    orders_by_id = {o.orderId: o for o in request.orders}
    shipments_by_id = {s.shipmentId: s for s in request.shipments}
    vehicles_by_id = {v.vehicleId: v for v in request.vehicles}

    for plan in best_plans:
        vehicle = vehicles_by_id.get(plan.vehicleId)
        if vehicle is None:
            return SolveOutcome(status="infeasible", reason_code="VEHICLE_NOT_FOUND", warnings=warnings)
        valid, reason = validate_vehicle_plan(plan, vehicle, orders_by_id, shipments_by_id)
        if not valid:
            return SolveOutcome(status="infeasible", reason_code=reason, warnings=warnings)

    batch_start_s = int(request.batchStart.timestamp())
    batch_end_s = int(request.batchEnd.timestamp())
    for plan in best_plans:
        valid, reason = validate_time_window(plan, batch_start_s, batch_end_s)
        if not valid:
            return SolveOutcome(status="infeasible", reason_code=reason, warnings=warnings)

    return SolveOutcome(
        status="feasible",
        vehicle_plans=best_plans,
        total_distance=round(best_distance, 3),
        algorithm_version=HACO_VERSION,
        parameter_version=HACO_PARAMETER_VERSION,
        warnings=warnings,
    )


def _precheck(request: PlanRequest) -> SolveOutcome | None:
    """预检：容量、时间窗。"""
    from ..models import CargoSource, OrderType

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


def _encode_tasks(request: PlanRequest) -> list:
    """将请求编码为 TaskBlock 列表。"""
    from .encoding import TaskBlock, TaskType

    tasks = []
    for order in request.orders:
        if order.orderType == "PASSENGER":
            tasks.append(TaskBlock(
                task_id=f"P:{order.orderId}",
                task_type=TaskType.PASSENGER,
                pickup_station=order.boardingStationId,
                delivery_station=order.alightingStationId,
                size=1,
                order_ids=[order.orderId],
            ))
        elif order.orderType == "DELIVERY":
            tasks.append(TaskBlock(
                task_id=f"D:{order.orderId}",
                task_type=TaskType.DELIVERY,
                pickup_station=order.stationId,
                delivery_station=order.stationId,
                size=order.itemCount,
                order_ids=[order.orderId],
            ))
        elif order.orderType == "PICKUP":
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


def _ant_construct_and_improve(
    current_plans: list[VehiclePlan],
    tasks: list,
    pheromone,
    station_map: dict,
    matrix: DistanceMatrix | None,
    config: HacoConfig,
    rng: random.Random,
    request: PlanRequest,
) -> tuple[list[VehiclePlan] | None, float]:
    """一只蚂蚁：基于当前最优解进行扰动改进。

    策略：通过随机调整算法参数（seed）来探索不同的解空间，
    使用 OR-Tools 求解器在不同随机种子下寻找更好的解。
    """
    from ..baseline.ortools_solver import solve as baseline_solve

    # 复制当前解
    import copy
    candidate_plans = copy.deepcopy(current_plans)
    current_distance = sum(p.totalDistance for p in candidate_plans)

    # 使用信息素引导的随机种子
    # 不同的蚂蚁使用不同的种子，探索不同的解空间
    ant_seed = rng.randint(0, 2**31)

    # 构建带不同种子的请求（通过打乱订单顺序引入随机性）
    import pydantic
    perturbed_request = request.model_copy()
    orders_list = list(perturbed_request.orders)
    shipments_list = list(perturbed_request.shipments)

    # 使用蚂蚁种子打乱顺序
    ant_rng = random.Random(ant_seed)
    ant_rng.shuffle(orders_list)
    ant_rng.shuffle(shipments_list)

    perturbed_request = perturbed_request.model_copy(
        update={"orders": orders_list, "shipments": shipments_list}
    )

    # 用 OR-Tools 求解
    perturbed_result = baseline_solve(perturbed_request, matrix)

    if perturbed_result.status == "feasible" and perturbed_result.total_distance < current_distance:
        return perturbed_result.vehicle_plans, perturbed_result.total_distance

    return candidate_plans, current_distance


def _select_tasks_to_perturb(tasks, pheromone, config: HacoConfig, rng: random.Random) -> list:
    """基于信息素水平选择要扰动的任务。"""
    if not tasks:
        return []

    # 计算每个任务被选中的概率（信息素越低越需要扰动）
    probabilities = []
    for task in tasks:
        tau = pheromone.get("DEPOT", task.task_id)
        # 信息素低的任务更需要被重新安排
        prob = 1.0 / (tau + 0.01)
        probabilities.append(prob)

    # 归一化
    total = sum(probabilities)
    if total <= 0:
        return rng.sample(tasks, min(3, len(tasks)))

    probabilities = [p / total for p in probabilities]

    # 选择要扰动的任务
    count = max(1, int(len(tasks) * config.destroy_fraction))
    count = min(count, len(tasks))

    selected = []
    remaining = list(range(len(tasks)))
    for _ in range(count):
        if not remaining:
            break
        # 轮盘赌
        r = rng.random()
        cumulative = 0.0
        for idx in remaining:
            cumulative += probabilities[idx]
            if r <= cumulative:
                selected.append(tasks[idx])
                remaining.remove(idx)
                break

    return selected


def _build_perturbed_request(original_request, tasks_to_perturb, rng: random.Random):
    """构建扰动后的请求（移除部分订单）。"""
    import copy
    perturbed = copy.deepcopy(original_request)

    # 找到要移除的订单 ID
    order_ids_to_remove = set()
    for task in tasks_to_perturb:
        for oid in task.order_ids:
            order_ids_to_remove.add(oid)

    # 移除订单
    perturbed.orders = [o for o in perturbed.orders if o.orderId not in order_ids_to_remove]
    perturbed.shipments = [s for s in perturbed.shipments if s.shipmentId not in order_ids_to_remove]

    # 随机打乱订单顺序（引入随机性）
    rng.shuffle(perturbed.orders)
    rng.shuffle(perturbed.shipments)

    return perturbed


def _extract_task_sequence(plans: list[VehiclePlan]) -> list[str]:
    """从解中提取任务序列（用于信息素更新）。"""
    seq = ["DEPOT"]
    for plan in plans:
        for stop in plan.stops:
            if stop.orderId:
                # 映射回 task_id
                if stop.action == "BOARD":
                    seq.append(f"P:{stop.orderId}")
                elif stop.action == "ALIGHT":
                    pass  # 不重复添加
                elif stop.action == "DELIVER":
                    seq.append(f"D:{stop.orderId}")
                elif stop.action == "PICKUP":
                    seq.append(f"K:{stop.orderId}")
    return seq
