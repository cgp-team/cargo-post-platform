"""HACO-CPS 1.4.0 Local Search：基于 RouteGenome 的 Best Improvement。

所有邻域统一为：
1. 生成全部合法 move
2. FeasibilityEngine 检查
3. ObjectiveEvaluator 评估
4. 选择最优 move
5. apply

Move 契约（P0）：
- evaluate(move) 与 apply(move) 操作完全相同的邻域
- SWAP 是原子双向交换，不是“只动 task1”
- 失败时完整 rollback
- skeleton / RETURN 不被修改
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import TYPE_CHECKING

from .deadline import SearchDeadline
from .encoding import ObjectiveVector, TaskBlock, TaskType
from .evaluator import evaluate_route_genome
from .feasibility_engine import FeasibilityEngine
from .route_genome import EventType, RouteGenome

if TYPE_CHECKING:
    from ..distance import DistanceMatrix
    from ..models import Station


class MoveType(str, Enum):
    RELOCATE = "RELOCATE"
    SWAP = "SWAP"
    # 本轮预留接口，不强制启用
    OR_OPT = "OR_OPT"
    PAIR_RELOCATE = "PAIR_RELOCATE"


@dataclass(frozen=True)
class NeighborhoodMove:
    """统一邻域移动。

    evaluate 与 apply 必须使用同一组字段描述的邻域：
    - RELOCATE: 把 task_ids[0] 从 source_vehicle 移到 target_vehicle
      的 (pickup_positions[0], delivery_positions[0])
    - SWAP: 在 source_vehicle 与 target_vehicle 之间原子交换
      task_ids[0] ↔ task_ids[1]，插入位分别为各自 pickup/delivery_positions
    """
    move_type: MoveType
    source_vehicle: int
    target_vehicle: int
    task_ids: tuple[str, ...]
    pickup_positions: tuple[int, ...]
    delivery_positions: tuple[int | None, ...]
    objective: ObjectiveVector
    # 调试/回滚辅助：评估时使用的 gap 归属（可选）
    gap_indices: tuple[tuple[int | None, int | None], ...] = ()


@dataclass
class MoveApplyResult:
    ok: bool
    routes: list[RouteGenome]
    reason: str | None = None


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
    """尝试 relocate 一个任务。evaluate 与 apply 共用同一描述。"""
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
        test_routes, tasks_by_id, station_map, matrix,
        initial_passenger_loads=initial_passenger_loads,
    )

    return NeighborhoodMove(
        move_type=MoveType.RELOCATE,
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


def _paired_positions(
    route: RouteGenome,
    task: TaskBlock,
) -> list[tuple[int, int | None]]:
    """枚举 task 在 route 中的合法 (pickup, delivery) 插入位。"""
    event_count = len(route.events)
    if task.task_type in (TaskType.PASSENGER, TaskType.SHIPMENT):
        return [
            (p, d)
            for p in range(1, event_count)
            for d in range(p + 1, event_count + 1)
        ]
    return [(p, None) for p in range(1, event_count)]


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
    """原子双向交换 task1 ↔ task2（在对方原位置附近）。

    evaluate 与 apply 使用完全相同的插入位：
    - task1 插入 route2 中 task2 原位置
    - task2 插入 route1 中 task1 原位置
    不枚举全部位置组合（避免 O(n^2) 爆炸），只做“换位”邻域。
    """
    p1o = route1.placements.get(task1.task_id)
    p2o = route2.placements.get(task2.task_id)
    if p1o is None or p2o is None:
        return None

    # 记录原插入位（移除前）
    pos_for_task1_in_r2 = (p2o.pickup_index, p2o.delivery_index)
    pos_for_task2_in_r1 = (p1o.pickup_index, p1o.delivery_index)

    test_routes = [r.copy() for r in routes]
    test_r1 = test_routes[route1.vehicle_index]
    test_r2 = test_routes[route2.vehicle_index]

    if task1.task_id not in test_r1.placements:
        return None
    if task2.task_id not in test_r2.placements:
        return None

    # 原子移除
    test_r1.remove_task(task1.task_id)
    test_r2.remove_task(task2.task_id)

    # 移除后下标会左移：原位置需按被移除事件数调整
    # 简化且与 apply 一致：在移除后的 route 上用“对方原 placement 位置”尝试插入，
    # 若越界则夹到合法范围。
    def _clamp(pickup: int, delivery: int | None, route: RouteGenome) -> tuple[int, int | None]:
        last = max(1, len(route.events) - 1)
        p = min(max(1, pickup), last)
        if delivery is None:
            return p, None
        d = delivery
        if d <= p:
            d = p + 1
        d = min(d, len(route.events))  # 允许 == len 表示尽可能晚
        return p, d

    p1, d1 = _clamp(pos_for_task1_in_r2[0], pos_for_task1_in_r2[1], test_r2)
    p2, d2 = _clamp(pos_for_task2_in_r1[0], pos_for_task2_in_r1[1], test_r1)

    # paired 任务必须有 delivery
    if task1.task_type in (TaskType.PASSENGER, TaskType.SHIPMENT) and d1 is None:
        d1 = min(p1 + 1, len(test_r2.events))
    if task2.task_type in (TaskType.PASSENGER, TaskType.SHIPMENT) and d2 is None:
        d2 = min(p2 + 1, len(test_r1.events))

    trial = [r.copy() for r in test_routes]
    tr1 = trial[route1.vehicle_index]
    tr2 = trial[route2.vehicle_index]
    try:
        tr2.insert_task(task1, p1, d1)
        tr1.insert_task(task2, p2, d2)
    except (ValueError, IndexError):
        return None

    if task1.task_id not in tr2.placements or task2.task_id not in tr1.placements:
        return None

    ids = [tid for r in trial for tid in r.placements]
    if len(ids) != len(set(ids)):
        return None
    expected = {tid for r in routes for tid in r.placements}
    if set(ids) != expected:
        return None

    if not _all_routes_feasible(
        trial, tasks_by_id, feasibility_engine,
        passenger_capacities, cargo_capacities,
        initial_passenger_loads, initial_cargo_loads,
        station_map, matrix,
    ):
        return None

    objective = _evaluate_solution(
        trial, tasks_by_id, station_map, matrix,
        initial_passenger_loads=initial_passenger_loads,
    )

    return NeighborhoodMove(
        move_type=MoveType.SWAP,
        source_vehicle=route1.vehicle_index,
        target_vehicle=route2.vehicle_index,
        task_ids=(task1.task_id, task2.task_id),
        # index 0 = task1 → target(route2) @ (p1,d1)
        # index 1 = task2 → source(route1) @ (p2,d2)
        pickup_positions=(p1, p2),
        delivery_positions=(d1, d2),
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
    initial_passenger_loads: dict[int, int] | None = None,
) -> ObjectiveVector:
    """评估解的目标函数。与 Final Evaluator 共用 passenger impact 定义。"""
    from .evaluator import evaluate_route_states
    return evaluate_route_states(
        routes, tasks_by_id, station_map, matrix,
        initial_passenger_loads=initial_passenger_loads,
    )


def apply_move(
    routes: list[RouteGenome],
    move: NeighborhoodMove | None,
    tasks_by_id: dict[str, TaskBlock],
) -> list[RouteGenome]:
    """应用 move 到路线。失败时完整 rollback，返回原 routes。

    与 evaluate 使用完全相同的邻域描述：
    - RELOCATE：source 移除 task，target 按记录 (pickup, delivery) 重插
    - SWAP：source/target 原子交换两个 task，插入位与 evaluate 一致
    """
    if move is None:
        return routes

    result = apply_move_detailed(routes, move, tasks_by_id)
    return result.routes


def apply_move_detailed(
    routes: list[RouteGenome],
    move: NeighborhoodMove | None,
    tasks_by_id: dict[str, TaskBlock],
) -> MoveApplyResult:
    """应用 move，返回是否成功及原因。失败自动 rollback。"""
    if move is None:
        return MoveApplyResult(ok=True, routes=routes, reason="EMPTY_MOVE")

    # before snapshot
    snapshot = [r.copy() for r in routes]
    new_routes = [r.copy() for r in routes]

    try:
        if move.move_type == MoveType.RELOCATE:
            ok = _apply_relocate(new_routes, move, tasks_by_id)
        elif move.move_type == MoveType.SWAP:
            ok = _apply_swap(new_routes, move, tasks_by_id)
        else:
            return MoveApplyResult(
                ok=False,
                routes=snapshot,
                reason=f"UNSUPPORTED_MOVE_TYPE:{move.move_type}",
            )

        if not ok:
            return MoveApplyResult(
                ok=False,
                routes=snapshot,
                reason="APPLY_FAILED_ROLLED_BACK",
            )

        # 最终不变量
        for r in new_routes:
            inv_ok, inv_reason = r.assert_invariants()
            if not inv_ok:
                return MoveApplyResult(
                    ok=False,
                    routes=snapshot,
                    reason=f"INVARIANT_VIOLATION:{inv_reason}",
                )

        return MoveApplyResult(ok=True, routes=new_routes)
    except Exception as exc:  # noqa: BLE001 — 任何异常都必须 rollback
        return MoveApplyResult(
            ok=False,
            routes=snapshot,
            reason=f"APPLY_EXCEPTION:{type(exc).__name__}:{exc}",
        )


def _apply_relocate(
    routes: list[RouteGenome],
    move: NeighborhoodMove,
    tasks_by_id: dict[str, TaskBlock],
) -> bool:
    task_id = move.task_ids[0]
    task = tasks_by_id.get(task_id)
    if task is None:
        return False

    src = routes[move.source_vehicle]
    dst = routes[move.target_vehicle]
    if task_id not in src.placements:
        return False

    src.remove_task(task_id)
    pickup_pos = move.pickup_positions[0]
    delivery_pos = move.delivery_positions[0]
    try:
        dst.insert_task(task, pickup_pos, delivery_pos)
    except (ValueError, IndexError):
        return False
    return task_id in dst.placements


def _apply_swap(
    routes: list[RouteGenome],
    move: NeighborhoodMove,
    tasks_by_id: dict[str, TaskBlock],
) -> bool:
    """原子双向交换：与 _try_swap 记录的插入位完全一致。"""
    if len(move.task_ids) != 2:
        return False
    tid1, tid2 = move.task_ids
    task1 = tasks_by_id.get(tid1)
    task2 = tasks_by_id.get(tid2)
    if task1 is None or task2 is None:
        return False

    r1 = routes[move.source_vehicle]
    r2 = routes[move.target_vehicle]

    if tid1 not in r1.placements or tid2 not in r2.placements:
        return False

    # 原子移除
    r1.remove_task(tid1)
    r2.remove_task(tid2)

    # 与 evaluate 一致：task1 → target(r2) @ (p1,d1)，task2 → source(r1) @ (p2,d2)
    p1, p2 = move.pickup_positions[0], move.pickup_positions[1]
    d1, d2 = move.delivery_positions[0], move.delivery_positions[1]

    try:
        r2.insert_task(task1, p1, d1)
        r1.insert_task(task2, p2, d2)
    except (ValueError, IndexError):
        return False

    # 两个任务都必须真正交换到位
    return tid1 in r2.placements and tid2 in r1.placements


def _reinsert_greedy(route, task, tasks_by_id, station_map, matrix) -> bool:
    """把 task 贪心插回 route 的某个可行位置（无候选则失败）。"""
    for p, d in _paired_positions(route, task):
        try:
            route.insert_task(task, p, d)
            return True
        except (ValueError, IndexError):
            continue
    return False


def local_search_improve(
    routes: list[RouteGenome],
    tasks_by_id: dict[str, TaskBlock],
    station_map: dict,
    matrix,
    feasibility_engine: FeasibilityEngine,
    passenger_capacities: dict[int, int],
    cargo_capacities: dict[int, int],
    initial_passenger_loads: dict[int, int],
    initial_cargo_loads: dict[int, int],
    rounds: int = 3,
    deadline: SearchDeadline | None = None,
    budget=None,
) -> tuple[list[RouteGenome], ObjectiveVector]:
    """Best Improvement：Relocate + Swap。budget 可选 SearchBudget 确定性截断。"""
    current = [r.copy() for r in routes]
    current_obj = _evaluate_solution(
        current, tasks_by_id, station_map, matrix,
        initial_passenger_loads=initial_passenger_loads,
    )
    if budget is not None:
        budget.start(0.0)

    for _ in range(max(1, rounds)):
        if deadline is not None and deadline.expired():
            break
        if budget is not None and not budget.tick_iteration():
            break
        best_neighbor = None
        best_obj = current_obj

        task_ids = [
            tid
            for r in current
            for tid in r.placements
        ]

        for tid in task_ids:
            task = tasks_by_id.get(tid)
            if task is None:
                continue

            base = [r.copy() for r in current]
            src_idx = None
            for i, r in enumerate(base):
                if tid in r.placements:
                    r.remove_task(tid)
                    src_idx = i
                    break
            if src_idx is None:
                continue

            for dst_idx, dst_route in enumerate(base):
                for pickup_pos, delivery_pos in _paired_positions(dst_route, task):
                    # Deadline check: before candidate start
                    if deadline is not None and deadline.expired():
                        return current, current_obj
                    cand = [r.copy() for r in base]
                    try:
                        cand[dst_idx].insert_task(
                            task, pickup_pos, delivery_pos
                        )
                    except (ValueError, IndexError):
                        continue

                    # Deadline check: before feasibility check
                    if deadline is not None and deadline.expired():
                        return current, current_obj

                    if not _all_routes_feasible(
                        cand, tasks_by_id, feasibility_engine,
                        passenger_capacities, cargo_capacities,
                        initial_passenger_loads, initial_cargo_loads,
                        station_map, matrix,
                    ):
                        continue

                    # Deadline check: before evaluation
                    if deadline is not None and deadline.expired():
                        return current, current_obj

                    obj = _evaluate_solution(
                        cand, tasks_by_id, station_map, matrix,
                        initial_passenger_loads=initial_passenger_loads,
                    )
                    if obj < best_obj:
                        best_obj = obj
                        best_neighbor = cand

        # Swap 邻域：与 evaluate 完全一致的原子交换
        for move in _enumerate_swap(
            current, tasks_by_id, station_map, matrix,
            feasibility_engine, passenger_capacities, cargo_capacities,
            initial_passenger_loads, initial_cargo_loads,
        ):
            if deadline is not None and deadline.expired():
                break
            if move.objective < best_obj:
                applied = apply_move_detailed(current, move, tasks_by_id)
                if applied.ok:
                    best_obj = move.objective
                    best_neighbor = applied.routes

        if best_neighbor is None or not (best_obj < current_obj):
            break

        current = best_neighbor
        current_obj = best_obj

    return current, current_obj
