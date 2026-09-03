"""HACO-CPS 快速可行性检查：不启动 OR-Tools，轻量级约束验证。

支持 RouteState 模型：检查任务插入到骨架间隙后是否满足约束。
"""

from __future__ import annotations

from typing import TYPE_CHECKING

from .encoding import TaskType

if TYPE_CHECKING:
    from .encoding import TaskBlock
    from .route_state import RouteState


def fast_feasible_insert_state(
    task: TaskBlock,
    state: RouteState,
    gap_index: int,
) -> tuple[bool, str | None]:
    """快速检查将 task 插入 state 的 gap_index 间隙是否可行。

    检查：
    1. 乘客容量
    2. 货物容量
    3. 骨架顺序（跨 gap 的 pickup/delivery 必须 i <= j）

    返回 (可行, 原因码)。"""
    # 容量检查
    ok, reason = _check_capacity_after_insert(task, state)
    if not ok:
        return False, reason

    # 骨架顺序检查
    if not _check_skeleton_gap_order(task, state, gap_index):
        return False, "SKELETON_ORDER_VIOLATION"

    return True, None


def _check_capacity_after_insert(
    task: TaskBlock,
    state: RouteState,
) -> tuple[bool, str | None]:
    """检查插入任务后的容量约束。"""
    # 乘客容量
    p_load = state.initial_passenger_load
    for t in state.tasks:
        if t.task_type == TaskType.PASSENGER:
            p_load += 1
            if p_load > state.passenger_capacity:
                return False, "PASSENGER_CAPACITY_EXCEEDED"
            p_load -= 1

    # 加上新任务
    if task.task_type == TaskType.PASSENGER:
        p_load += 1
        if p_load > state.passenger_capacity:
            return False, "PASSENGER_CAPACITY_EXCEEDED"

    # 货物容量
    c_load = state.initial_cargo_load
    for t in state.tasks:
        if t.task_type in (TaskType.SHIPMENT, TaskType.DELIVERY, TaskType.PICKUP):
            c_load += t.size
            if c_load > state.cargo_capacity:
                return False, "CARGO_CAPACITY_EXCEEDED"
            if t.task_type == TaskType.SHIPMENT:
                c_load -= t.size

    # 加上新任务
    if task.task_type in (TaskType.SHIPMENT, TaskType.DELIVERY, TaskType.PICKUP):
        c_load += task.size
        if c_load > state.cargo_capacity:
            return False, "CARGO_CAPACITY_EXCEEDED"

    return True, None


def _check_skeleton_gap_order(
    task: TaskBlock,
    state: RouteState,
    gap_index: int,
) -> bool:
    """检查骨架间隙顺序是否合法。

    对于 SHIPMENT/PASSENGER：pickup 和 delivery 在同一个 gap 中，
    自动满足 pickup before delivery。
    对于跨 gap 的情况（未来扩展）：要求 pickup_gap <= delivery_gap。
    """
    # 当前实现中，一个 task block 的所有动作在同一个 gap 中
    # 所以自动满足顺序约束
    return True


def validate_route_states(
    states: list[RouteState],
) -> tuple[bool, str | None]:
    """验证完整解的所有路线状态。"""
    for state in states:
        if not state.tasks:
            continue

        ok, reason = state.check_capacity()
        if not ok:
            return False, reason

        if not state.check_skeleton_order():
            return False, "SKELETON_ORDER_VIOLATION"

    return True, None
