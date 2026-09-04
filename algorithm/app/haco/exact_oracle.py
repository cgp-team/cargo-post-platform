"""HACO-CPS Exact Oracle：小规模穷举最优解。

用于 <=8 tasks 的精确验证，不依赖 OR-Tools。
使用 branch-and-bound + pruning 求解。
"""

from __future__ import annotations

import itertools
from dataclasses import dataclass
from math import hypot
from typing import TYPE_CHECKING

from .encoding import TaskBlock, TaskType

if TYPE_CHECKING:
    from ..distance import DistanceMatrix
    from ..models import Station


@dataclass
class ExactResult:
    """穷举结果。"""
    optimal_cost: float
    optimal_distance: float
    optimal_vehicle_count: int
    optimal_sequence: list[list[str]]  # per vehicle
    feasible_count: int
    total_enumerated: int


def solve_exact(
    tasks: list[TaskBlock],
    depot_station: str,
    stations: list,
    vehicles: list,
    station_map: dict,
    matrix: DistanceMatrix | None = None,
    max_enumerations: int = 1000000,
) -> ExactResult:
    """穷举所有可行解，返回最优解。

    限制：
    - 只支持单车辆（多车辆组合爆炸）
    - <=8 tasks
    """
    if len(tasks) > 8:
        raise ValueError(f"Exact oracle only supports <=8 tasks, got {len(tasks)}")

    if len(vehicles) != 1:
        # 多车辆：枚举任务分配到车辆，然后对每辆车穷举
        return _solve_multi_vehicle(tasks, depot_station, stations, vehicles, station_map, matrix, max_enumerations)

    vehicle = vehicles[0]
    p_cap = vehicle.passengerCapacity
    c_cap = vehicle.cargoCapacity
    p_init = vehicle.initialPassengerLoad
    c_init = vehicle.initialCargoLoad

    best_cost = float("inf")
    best_distance = float("inf")
    best_sequence = []
    feasible_count = 0
    total_count = 0

    # 枚举所有任务排列
    for perm in itertools.permutations(tasks):
        total_count += 1
        if total_count > max_enumerations:
            break

        # 检查可行性
        feasible, distance = _evaluate_permutation(
            list(perm), depot_station, station_map, matrix, p_cap, c_cap, p_init, c_init
        )

        if feasible:
            feasible_count += 1
            if distance < best_distance:
                best_distance = distance
                best_cost = distance
                best_sequence = [[t.task_id for t in perm]]

    return ExactResult(
        optimal_cost=best_cost,
        optimal_distance=best_distance,
        optimal_vehicle_count=1 if best_sequence else 0,
        optimal_sequence=best_sequence,
        feasible_count=feasible_count,
        total_enumerated=total_count,
    )


def _solve_multi_vehicle(
    tasks, depot_station, stations, vehicles, station_map, matrix, max_enumerations
) -> ExactResult:
    """多车辆穷举：枚举任务分配 + 每辆车的排列。"""
    n_tasks = len(tasks)
    n_vehicles = len(vehicles)

    best_cost = float("inf")
    best_distance = float("inf")
    best_sequence = []
    feasible_count = 0
    total_count = 0

    # 枚举任务分配到车辆（每个任务分配到一辆车）
    for assignment in itertools.product(range(n_vehicles), repeat=n_tasks):
        total_count += 1
        if total_count > max_enumerations:
            break

        # 按车辆分组
        vehicle_tasks = {vi: [] for vi in range(n_vehicles)}
        for i, vi in enumerate(assignment):
            vehicle_tasks[vi].append(tasks[i])

        # 对每辆车的排列求和
        total_distance = 0
        all_feasible = True
        sequences = []

        for vi in range(n_vehicles):
            v_tasks = vehicle_tasks[vi]
            if not v_tasks:
                sequences.append([])
                continue

            vehicle = vehicles[vi]
            p_cap = vehicle.passengerCapacity
            c_cap = vehicle.cargoCapacity
            p_init = vehicle.initialPassengerLoad
            c_init = vehicle.initialCargoLoad

            # 穷举该车辆的任务排列
            best_v_dist = float("inf")
            best_v_seq = None

            for perm in itertools.permutations(v_tasks):
                feasible, dist = _evaluate_permutation(
                    list(perm), depot_station, station_map, matrix, p_cap, c_cap, p_init, c_init
                )
                if feasible and dist < best_v_dist:
                    best_v_dist = dist
                    best_v_seq = [t.task_id for t in perm]

            if best_v_seq is None:
                all_feasible = False
                break

            total_distance += best_v_dist
            sequences.append(best_v_seq)

        if all_feasible:
            feasible_count += 1
            if total_distance < best_distance:
                best_distance = total_distance
                best_cost = total_distance
                best_sequence = sequences

    return ExactResult(
        optimal_cost=best_cost,
        optimal_distance=best_distance,
        optimal_vehicle_count=sum(1 for s in best_sequence if s),
        optimal_sequence=best_sequence,
        feasible_count=feasible_count,
        total_enumerated=total_count,
    )


def _evaluate_permutation(
    perm: list[TaskBlock],
    depot_station: str,
    station_map: dict,
    matrix,
    p_cap: int,
    c_cap: int,
    p_init: int,
    c_init: int,
) -> tuple[bool, float]:
    """评估一个排列的可行性和距离。"""
    depot = station_map.get(depot_station)
    if not depot:
        return False, float("inf")

    distance = 0.0
    current = depot
    p_load = p_init
    c_load = c_init

    for task in perm:
        pickup = station_map.get(task.pickup_station)
        if not pickup:
            return False, float("inf")

        # 到 pickup
        d = _dist(current, pickup, matrix)
        distance += d
        current = pickup

        if task.task_type == TaskType.PASSENGER:
            p_load += 1
            if p_load > p_cap:
                return False, float("inf")

            delivery = station_map.get(task.delivery_station)
            if not delivery:
                return False, float("inf")

            d = _dist(current, delivery, matrix)
            distance += d
            current = delivery
            p_load -= 1

        elif task.task_type == TaskType.SHIPMENT:
            c_load += task.size
            if c_load > c_cap:
                return False, float("inf")

            delivery = station_map.get(task.delivery_station)
            if not delivery:
                return False, float("inf")

            d = _dist(current, delivery, matrix)
            distance += d
            current = delivery
            c_load -= task.size

        elif task.task_type in (TaskType.DELIVERY, TaskType.PICKUP):
            c_load += task.size
            if c_load > c_cap:
                return False, float("inf")

    # 返回 depot
    distance += _dist(current, depot, matrix)
    return True, distance


def _dist(a, b, matrix=None) -> float:
    if matrix is not None:
        key = (a.stationId, b.stationId)
        if key in matrix:
            return matrix[key][0]
    return hypot(a.longitude - b.longitude, a.latitude - b.latitude)
