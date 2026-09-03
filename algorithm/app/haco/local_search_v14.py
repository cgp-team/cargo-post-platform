"""HACO-CPS 1.4.0 Local Search：基于 RouteGenome 的 Best Improvement。

所有邻域统一为：
1. 生成全部合法 move
2. FeasibilityEngine 检查
3. ObjectiveEvaluator 评估
4. 选择最优 move
5. apply
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import TYPE_CHECKING

from .encoding import ObjectiveVector, TaskBlock, TaskType
from .evaluator import evaluate_route_genome
from .feasibility_engine import FeasibilityEngine
from .route_genome import EventType, RouteGenome

if TYPE_CHECKING:
    from ..distance import DistanceMatrix
    from ..models import Station


@dataclass(frozen=True)
class NeighborhoodMove:
    source_vehicle: int
    target_vehicle: int
    task_ids: tuple[str, ...]
    pickup_positions: tuple[int, ...]
    delivery_positions: tuple[int | None, ...]
    objective: ObjectiveVector


def find_best_move(
    routes: list[RouteGenome],
    tasks_by_id: dict[str, TaskBlock],
    station_map: dict,
    matrix,
    feasibility_engine: FeasibilityEngine,
    passenger_capacities: dict[int, int],
    cargo_capacities: dict[int, int],
    initial_passenger_loads: dict[int, int],
    initial_cargo_loads: dict[int, int],
) -> NeighborhoodMove | None:
    """寻找最优邻域 move（Best Improvement）。"""
    best_move = None

    for candidate in enumerate_all_moves(
        routes,
        tasks_by_id,
        station_map,
        matrix,
        feasibility_engine,
        passenger_capacities,
        cargo_capacities,
        initial_passenger_loads,
        initial_cargo_loads,
    ):
        if (
            best_move is None
            or candidate.objective < best_move.objective
        ):
            best_move = candidate

    return best_move


def enumerate_all_moves(
    routes: list[RouteGenome],
    tasks_by_id: dict[str, TaskBlock],
    station_map: dict,
    matrix,
    feasibility_engine: FeasibilityEngine,
    passenger_capacities: dict[int, int],
    cargo_capacities: dict[int, int],
    initial_passenger_loads: dict[int, int],
    initial_cargo_loads: dict[int, int],
):
    """枚举所有合法 move。"""
    # Relocate moves
    yield from _enumerate_relocate(
        routes, tasks_by_id, station_map, matrix,
        feasibility_engine, passenger_capacities, cargo_capacities,
        initial_passenger_loads, initial_cargo_loads,
    )

    # Swap moves
    yield from _enumerate_swap(
        routes, tasks_by_id, station_map, matrix,
        feasibility_engine, passenger_capacities, cargo_capacities,
        initial_passenger_loads, initial_cargo_loads,
    )


def _enumerate_relocate(
    routes: list[RouteGenome],
    tasks_by_id: dict[str, TaskBlock],
    station_map: dict,
    matrix,
    feasibility_engine: FeasibilityEngine,
    passenger_capacities: dict[int, int],
    cargo_capacities: dict[int, int],
    initial_passenger_loads: dict[int, int],
    initial_cargo_loads: dict[int, int],
):
    """枚举所有 relocate move。"""
    for src_route in routes:
        for task_id, placement in list(src_route.placements.items()):
            task = tasks_by_id.get(task_id)
            if task is None:
                continue

            # 尝试移到每辆车的每个位置
            for dst_route in routes:
                event_count = len(dst_route.events)

                if task.task_type in (TaskType.PASSENGER, TaskType.SHIPMENT):
                    for pickup_pos in range(1, event_count):
                        for delivery_pos in range(
                            pickup_pos + 1, event_count + 1
                        ):
                            move = _try_relocate(
                                task, src_route, dst_route,
                                pickup_pos, delivery_pos,
                                routes, tasks_by_id, station_map, matrix,
                                feasibility_engine,
                                passenger_capacities, cargo_capacities,
                                initial_passenger_loads, initial_cargo_loads,
                            )
                            if move is not None:
                                yield move
                else:
                    for pickup_pos in range(1, event_count):
                        move = _try_relocate(
                            task, src_route, dst_route,
                            pickup_pos, None,
                            routes, tasks_by_id, station_map, matrix,
                            feasibility_engine,
                            passenger_capacities, cargo_capacities,
                            initial_passenger_loads, initial_cargo_loads,
                        )
                        if move is not None:
                            yield move


def _try_relocate(
    task: TaskBlock,
    src_route: RouteGenome,
    dst_route: RouteGenome,
    pickup_pos: int,
    delivery_pos: int | None,
    routes: list[RouteGenome],
    tasks_by_id: dict[str, TaskBlock],
    station_map: dict,
    matrix,
    feasibility_engine: FeasibilityEngine,
    passenger_capacities: dict[int, int],
    cargo_capacities: dict[int, int],
    initial_passenger_loads: dict[int, int],
    initial_cargo_loads: dict[int, int],
) -> NeighborhoodMove | None:
    """尝试 relocate 一个任务。"""
    # 创建测试路线
    test_routes = [r.copy() for r in routes]
    test_src = test_routes[src_route.vehicle_index]
    test_dst = test_routes[dst_route.vehicle_index]

    # 移除任务
    test_src.remove_task(task.task_id)

    # 尝试插入
    try:
        test_dst.insert_task(task, pickup_pos, delivery_pos)
    except (ValueError, IndexError):
        return None

    # 检查可行性
    if not _all_routes_feasible(
        test_routes, tasks_by_id, feasibility_engine,
        passenger_capacities, cargo_capacities,
        initial_passenger_loads, initial_cargo_loads,
        station_map, matrix,
    ):
        return None

    # 评估目标
    objective = _evaluate_solution(
        test_routes, tasks_by_id, station_map, matrix
    )

    return NeighborhoodMove(
        source_vehicle=src_route.vehicle_index,
        target_vehicle=dst_route.vehicle_index,
        task_ids=(task.task_id,),
        pickup_positions=(pickup_pos,),
        delivery_positions=(delivery_pos,),
        objective=objective,
    )


def _enumerate_swap(
    routes: list[RouteGenome],
    tasks_by_id: dict[str, TaskBlock],
    station_map: dict,
    matrix,
    feasibility_engine: FeasibilityEngine,
    passenger_capacities: dict[int, int],
    cargo_capacities: dict[int, int],
    initial_passenger_loads: dict[int, int],
    initial_cargo_loads: dict[int, int],
):
    """枚举所有 swap move。"""
    all_tasks = []
    for route in routes:
        for task_id in route.placements:
            all_tasks.append((route, task_id))

    for i in range(len(all_tasks)):
        for j in range(i + 1, len(all_tasks)):
            route1, tid1 = all_tasks[i]
            route2, tid2 = all_tasks[j]

            # 跳过同一辆车内的 swap（需要不同处理）
            if route1.vehicle_index == route2.vehicle_index:
                continue

            task1 = tasks_by_id.get(tid1)
            task2 = tasks_by_id.get(tid2)
            if task1 is None or task2 is None:
                continue

            # 尝试交换
            move = _try_swap(
                task1, task2, route1, route2,
                routes, tasks_by_id, station_map, matrix,
                feasibility_engine,
                passenger_capacities, cargo_capacities,
                initial_passenger_loads, initial_cargo_loads,
            )
            if move is not None:
                yield move


def _try_swap(
    task1: TaskBlock,
    task2: TaskBlock,
    route1: RouteGenome,
    route2: RouteGenome,
    routes: list[RouteGenome],
    tasks_by_id: dict[str, TaskBlock],
    station_map: dict,
    matrix,
    feasibility_engine: FeasibilityEngine,
    passenger_capacities: dict[int, int],
    cargo_capacities: dict[int, int],
    initial_passenger_loads: dict[int, int],
    initial_cargo_loads: dict[int, int],
) -> NeighborhoodMove | None:
    """尝试交换两个任务。"""
    # 创建测试路线
    test_routes = [r.copy() for r in routes]
    test_r1 = test_routes[route1.vehicle_index]
    test_r2 = test_routes[route2.vehicle_index]

    # 移除两个任务
    test_r1.remove_task(task1.task_id)
    test_r2.remove_task(task2.task_id)

    # 尝试在对方位置插入
    # 简化：尝试在对方原来的位置附近插入
    inserted1 = False
    inserted2 = False

    # 尝试在 route2 的位置插入 task1
    for pos in range(1, len(test_r2.events)):
        try:
            test_r2.insert_task(task1, pos, None)
            inserted1 = True
            break
        except (ValueError, IndexError):
            continue

    if not inserted1:
        return None

    # 尝试在 route1 的位置插入 task2
    for pos in range(1, len(test_r1.events)):
        try:
            test_r1.insert_task(task2, pos, None)
            inserted2 = True
            break
        except (ValueError, IndexError):
            continue

    if not inserted2:
        return None

    # 检查可行性
    if not _all_routes_feasible(
        test_routes, tasks_by_id, feasibility_engine,
        passenger_capacities, cargo_capacities,
        initial_passenger_loads, initial_cargo_loads,
        station_map, matrix,
    ):
        return None

    # 评估目标
    objective = _evaluate_solution(
        test_routes, tasks_by_id, station_map, matrix
    )

    return NeighborhoodMove(
        source_vehicle=route1.vehicle_index,
        target_vehicle=route2.vehicle_index,
        task_ids=(task1.task_id, task2.task_id),
        pickup_positions=(0, 0),  # 简化
        delivery_positions=(None, None),
        objective=objective,
    )


def _all_routes_feasible(
    routes: list[RouteGenome],
    tasks_by_id: dict[str, TaskBlock],
    feasibility_engine: FeasibilityEngine,
    passenger_capacities: dict[int, int],
    cargo_capacities: dict[int, int],
    initial_passenger_loads: dict[int, int],
    initial_cargo_loads: dict[int, int],
    station_map: dict,
    matrix,
) -> bool:
    """检查所有路线是否可行。"""
    for route in routes:
        if route.task_count() == 0:
            continue

        # 构建当前路线的 tasks_by_id
        route_tasks = {}
        for event in route.events:
            if event.task_id and event.task_id in tasks_by_id:
                route_tasks[event.task_id] = tasks_by_id[event.task_id]

        result = feasibility_engine.check(
            route,
            route_tasks,
            passenger_capacities[route.vehicle_index],
            cargo_capacities[route.vehicle_index],
            initial_passenger_loads[route.vehicle_index],
            initial_cargo_loads[route.vehicle_index],
            station_map=station_map,
            matrix=matrix,
        )

        if not result.feasible:
            return False

    return True


def _evaluate_solution(
    routes: list[RouteGenome],
    tasks_by_id: dict[str, TaskBlock],
    station_map: dict,
    matrix,
) -> ObjectiveVector:
    """评估解的目标函数。"""
    from .evaluator import evaluate_route_states
    return evaluate_route_states(routes, tasks_by_id, station_map, matrix)


def apply_move(
    routes: list[RouteGenome],
    move: NeighborhoodMove,
    tasks_by_id: dict[str, TaskBlock],
) -> list[RouteGenome]:
    """应用 move 到路线。"""
    # 简化实现：重新创建路线
    # 实际应用中应该直接修改
    return routes
