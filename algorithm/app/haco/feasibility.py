"""HACO-CPS 快速可行性检查：不启动 OR-Tools，轻量级约束验证。"""

from __future__ import annotations

from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from .encoding import TaskBlock, VehicleRoute, Solution
    from ..models import Vehicle


def fast_feasible_insert(
    task: TaskBlock,
    route: VehicleRoute,
    insert_pos: int,
) -> tuple[bool, str | None]:
    """快速检查将 task 插入 route 在 insert_pos 位置是否可行。

    检查：
    1. 容量约束（乘客和货物）
    2. Shipment pickup < delivery（同一 block 内自动满足）

    返回 (可行, 原因码)。"""
    # 容量检查：统计插入后的峰值载荷
    passenger_load = route.initial_passenger_load
    cargo_load = route.initial_cargo_load

    # 模拟插入后的路线
    new_insertions = list(route.insertions)
    new_insertions.insert(insert_pos, type("Insertion", (), {"task": task})())

    for ins in new_insertions:
        t = ins.task
        if t.task_type == "PASSENGER":
            passenger_load += 1  # 上车
            if passenger_load > route.passenger_capacity:
                return False, "PASSENGER_CAPACITY_EXCEEDED"
            # 下车在后续位置
        elif t.task_type == "SHIPMENT":
            cargo_load += t.size
            if cargo_load > route.cargo_capacity:
                return False, "CARGO_CAPACITY_EXCEEDED"
        elif t.task_type == "DELIVERY":
            cargo_load += t.size
            if cargo_load > route.cargo_capacity:
                return False, "CARGO_CAPACITY_EXCEEDED"
        elif t.task_type == "PICKUP":
            cargo_load += t.size
            if cargo_load > route.cargo_capacity:
                return False, "CARGO_CAPACITY_EXCEEDED"

    return True, None


def validate_solution_feasibility(
    solution: Solution,
    vehicles: list[Vehicle],
) -> tuple[bool, str | None]:
    """验证完整解的可行性。"""
    for route in solution.routes:
        if route.task_count == 0:
            continue

        vehicle = vehicles[route.vehicle_index]

        # 乘客容量
        passenger_load = vehicle.initialPassengerLoad
        for ins in route.insertions:
            if ins.task.task_type == "PASSENGER":
                passenger_load += 1
                if passenger_load > vehicle.passengerCapacity:
                    return False, "PASSENGER_CAPACITY_EXCEEDED"
                passenger_load -= 1  # 假设在终点下车

        # 货物容量（简化）
        cargo_load = vehicle.initialCargoLoad
        for ins in route.insertions:
            t = ins.task
            if t.task_type in ("SHIPMENT", "DELIVERY", "PICKUP"):
                cargo_load += t.size
                if cargo_load > vehicle.cargoCapacity:
                    return False, "CARGO_CAPACITY_EXCEEDED"

    return True, None
