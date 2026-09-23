"""HACO-CPS 1.4.0 真实路线基因 + 旧版全局路线基因（兼容）。

Phase 1 核心：
- RouteGenome：per-vehicle 事件序列（DEPOT → events → RETURN）
- RouteEvent / EventType / TaskPlacement：事件级路线表示
- 旧 GlobalRouteGenome 保留供 global_construction/evaluator/local_search 使用

不变量（assert_invariants）：
- DEPOT 首事件
- RETURN 唯一且恒为最后业务终点事件
- pickup 先于 delivery / BOARD 先于 ALIGHT
- skeleton PASS 顺序不变
- 任务不丢失、不重复、不跨 route 重复
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import TYPE_CHECKING

from .encoding import TaskBlock, TaskType

if TYPE_CHECKING:
    from ..distance import DistanceMatrix
    from ..models import Station


# ═══════════════════════════════════════════════════════════════
# 1.4.0 新核心：EventType / RouteEvent / TaskPlacement / RouteGenome
# ═══════════════════════════════════════════════════════════════


class EventType(str, Enum):
    DEPOT = "DEPOT"
    PASS = "PASS"
    BOARD = "BOARD"
    ALIGHT = "ALIGHT"
    PICKUP = "PICKUP"
    DELIVER = "DELIVER"
    RETURN = "RETURN"


@dataclass(frozen=True)
class RouteEvent:
    station_id: str
    event_type: EventType
    task_id: str | None = None
    order_id: str | None = None
    task_type: TaskType | None = None

    @property
    def is_task_event(self) -> bool:
        return self.task_id is not None


@dataclass(frozen=True)
class TaskPlacement:
    task_id: str
    pickup_index: int
    delivery_index: int | None


class RouteGenome:
    """
    HACO-CPS 1.4.0 真实路线基因。

    核心原则：
    1. events 是唯一真实路线顺序。
    2. Passenger / Shipment 的 pickup 和 delivery 可以位于不同位置。
    3. Skeleton PASS 是固定骨架事件：每站必停拉客（计划停靠，不是过站不停）。
       公交线路上的每一个站点都必须按序停靠，禁止任何跳站/掠站。
    4. 不再把 Task -> Gap 作为真实路线表示。
    5. 普通任务插入必须走 Gap-aware 校验，禁止绕开 gap 穿透 skeleton。
    """

    def __init__(
        self,
        vehicle_index: int,
        vehicle_id: int,
        depot_station: str,
        skeleton: list[str] | None = None,
    ):
        self.vehicle_index = vehicle_index
        self.vehicle_id = vehicle_id
        self.depot_station = depot_station
        self.skeleton = list(skeleton or [])

        self.events: list[RouteEvent] = [
            RouteEvent(
                station_id=depot_station,
                event_type=EventType.DEPOT,
            )
        ]

        for station_id in self.skeleton:
            self.events.append(
                RouteEvent(
                    station_id=station_id,
                    event_type=EventType.PASS,
                )
            )

        self.events.append(
            RouteEvent(
                station_id=depot_station,
                event_type=EventType.RETURN,
            )
        )

        self.placements: dict[str, TaskPlacement] = {}
        # Trip 绕行预算累计（km 量级用 m 存；由调用方/Feasibility 裁决）
        self.trip_detour_consumed_m: float = 0.0
        self.trip_detour_budget_m: float | None = None

    def copy(self) -> "RouteGenome":
        """浅拷贝不可变事件 + 独立 placements/skeleton，禁止共享可变对象串改。"""
        result = RouteGenome(
            vehicle_index=self.vehicle_index,
            vehicle_id=self.vehicle_id,
            depot_station=self.depot_station,
            skeleton=list(self.skeleton),
        )
        # RouteEvent / TaskPlacement 均为 frozen，list/dict 浅拷贝即可隔离
        result.events = list(self.events)
        result.placements = dict(self.placements)
        result.trip_detour_consumed_m = self.trip_detour_consumed_m
        result.trip_detour_budget_m = self.trip_detour_budget_m
        return result

    def task_count(self) -> int:
        return len(self.placements)

    # ─── Trip Detour Budget ─────────────────────────────────

    @property
    def trip_detour_remaining_m(self) -> float | None:
        if self.trip_detour_budget_m is None:
            return None
        return max(0.0, self.trip_detour_budget_m - self.trip_detour_consumed_m)

    def can_consume_detour(self, delta_m: float) -> bool:
        if self.trip_detour_budget_m is None:
            return True
        return self.trip_detour_consumed_m + max(0.0, delta_m) <= self.trip_detour_budget_m + 1e-6

    def consume_detour(self, delta_m: float) -> bool:
        if not self.can_consume_detour(delta_m):
            return False
        self.trip_detour_consumed_m += max(0.0, delta_m)
        return True

    # ─── 不变量 / Gap-aware 操作层 ──────────────────────────

    def assert_invariants(self) -> tuple[bool, str | None]:
        """统一数据结构不变量检查。

        检查项：
        1. DEPOT 为首事件
        2. RETURN 存在、唯一且为最末业务事件
        3. RETURN 后不存在普通任务 / PASS 事件
        4. pickup 先于 delivery（BOARD 先于 ALIGHT）
        5. skeleton PASS 顺序保持不变
        6. 任务不丢失、不重复（placements 与 events 一致）
        7. 空 route 合法（只有 DEPOT + RETURN）
        """
        if not self.events:
            return False, "EMPTY_EVENTS"

        if self.events[0].event_type != EventType.DEPOT:
            return False, "DEPOT_NOT_FIRST"

        ok, reason = self.validate_terminal_return()
        if not ok:
            return False, reason

        ok, reason = self.validate_precedence()
        if not ok:
            return False, reason

        ok, reason = self.validate_paired_task()
        if not ok:
            return False, reason

        ok, reason = self.validate_skeleton()
        if not ok:
            return False, reason

        for task_id, placement in self.placements.items():
            events_for = [e for e in self.events if e.task_id == task_id]
            if not events_for:
                return False, f"TASK_LOST:{task_id}"
            if placement.pickup_index >= len(self.events):
                return False, f"PLACEMENT_INDEX_OOB:{task_id}"
            if (
                placement.delivery_index is not None
                and placement.delivery_index >= len(self.events)
            ):
                return False, f"PLACEMENT_INDEX_OOB:{task_id}"

        for event in self.events:
            if event.task_id and event.task_id not in self.placements:
                return False, f"TASK_NOT_IN_PLACEMENTS:{event.task_id}"

        return True, None

    def get_gap_ranges(self) -> list[tuple[int, int]]:
        """返回 skeleton gap 的事件下标闭区间 [start, end]。

        gap 0: DEPOT 之后到第一个 PASS（含 PASS 下标，插在其前）
        gap i: PASS[i-1] 之后到 PASS[i]
        最后一个 gap: 最后一个 PASS 之后到 RETURN（含 RETURN 下标，插在其前）
        """
        pass_indices = [
            i for i, e in enumerate(self.events)
            if e.event_type == EventType.PASS
        ]
        return_indices = [
            i for i, e in enumerate(self.events)
            if e.event_type == EventType.RETURN
        ]
        return_end = return_indices[0] if return_indices else len(self.events) - 1

        ranges: list[tuple[int, int]] = []
        start = 1  # skip DEPOT
        for pi in pass_indices:
            ranges.append((start, pi))
            start = pi + 1
        ranges.append((start, return_end))
        return ranges

    def get_gap_for_task(self, task_id: str) -> tuple[int, int] | None:
        """返回 task 的 (pickup_gap, delivery_gap)；standalone 时二者相同。"""
        placement = self.placements.get(task_id)
        if placement is None:
            return None
        gaps = self.get_gap_ranges()
        pickup_gap = self._gap_index_for_event(placement.pickup_index, gaps)
        if placement.delivery_index is not None:
            delivery_gap = self._gap_index_for_event(placement.delivery_index, gaps)
        else:
            delivery_gap = pickup_gap
        return (pickup_gap, delivery_gap)

    @staticmethod
    def _gap_index_for_event(event_index: int, gaps: list[tuple[int, int]]) -> int:
        for gi, (start, end) in enumerate(gaps):
            if start <= event_index <= end:
                return gi
        if gaps:
            return len(gaps) - 1
        return 0

    def _pass_index(self, station_id: str) -> int | None:
        for i, e in enumerate(self.events):
            if e.event_type == EventType.PASS and e.station_id == station_id:
                return i
        return None

    def _skeleton_service_window_ok(
        self,
        task: TaskBlock,
        pickup_index: int,
        delivery_index: int | None,
    ) -> tuple[bool, str | None]:
        """骨架站客运上/下客必须贴着该站停靠（每站必停拉客）。

        - **乘客**：BOARD/ALIGHT 只能落在该站 PASS 的紧邻窗口（±1 格），
          禁止过站后再上客、过站绕回后再下客；
        - **货运**：允许 Gap 间隙绕行 / 返程派送（服务点可以是村部等非骨架点，
          也可以在回程再次服务骨架站），仅受 Gap 约束。
        """
        if not self.skeleton or task.task_type != TaskType.PASSENGER:
            return True, None
        skel = set(self.skeleton)

        pu = task.pickup_station
        if pu in skel:
            pi = self._pass_index(pu)
            if pi is not None and abs(pickup_index - pi) > 1:
                return False, f"PICKUP_NOT_AT_PASS:{pu}"

        if delivery_index is not None:
            de = task.delivery_station
            if de in skel:
                di = self._pass_index(de)
                if di is not None:
                    if delivery_index >= len(self.events):
                        # 约定：len(events) = “尽可能晚”（RETURN 前）。
                        # 仅当下站是最后一个骨架站时，这才等价于贴站下客。
                        last_skel = self.skeleton[-1] if self.skeleton else None
                        if de != last_skel:
                            return False, f"DELIVERY_NOT_AT_PASS:{de}"
                    elif abs(delivery_index - di) > 1:
                        return False, f"DELIVERY_NOT_AT_PASS:{de}"
        return True, None

    def can_insert_into_gap(
        self,
        pickup_index: int,
        delivery_index: int | None,
        *,
        pickup_gap: int | None = None,
        delivery_gap: int | None = None,
    ) -> tuple[bool, str | None]:
        """校验插入位是否落在合法 gap 内，且不穿透 skeleton PASS。

        delivery_index == len(events) 是“尽可能晚”的合法表达，映射到最后 gap。
        """
        gaps = self.get_gap_ranges()
        if not gaps:
            return False, "NO_GAPS"

        return_pos = len(self.events) - 1
        for i, e in enumerate(self.events):
            if e.event_type == EventType.RETURN:
                return_pos = i
                break

        ok, reason = self._index_in_gap(pickup_index, gaps, pickup_gap)
        if not ok:
            return False, reason
        if pickup_index > return_pos:
            return False, "PICKUP_AFTER_RETURN"

        if delivery_index is not None:
            if delivery_index <= pickup_index:
                return False, "DELIVERY_BEFORE_PICKUP"
            if delivery_index == len(self.events):
                dg = len(gaps) - 1
                if delivery_gap is not None and dg != delivery_gap:
                    return False, f"GAP_MISMATCH:expected={delivery_gap},actual={dg}"
            else:
                ok, reason = self._index_in_gap(delivery_index, gaps, delivery_gap)
                if not ok:
                    return False, reason
                dg = self._gap_index_for_event(delivery_index, gaps)
            pg = self._gap_index_for_event(pickup_index, gaps)
            if pg > dg:
                return False, "GAP_ORDER_VIOLATION"

        return True, None

    @staticmethod
    def _index_in_gap(
        event_index: int,
        gaps: list[tuple[int, int]],
        expected_gap: int | None,
    ) -> tuple[bool, str | None]:
        if event_index < 1:
            return False, "INDEX_BEFORE_DEPOT"
        for gi, (start, end) in enumerate(gaps):
            if start <= event_index <= end:
                if expected_gap is not None and gi != expected_gap:
                    return False, f"GAP_MISMATCH:expected={expected_gap},actual={gi}"
                return True, None
        return False, f"INDEX_OUTSIDE_GAPS:{event_index}"

    def move_task_between_gaps(
        self,
        task: TaskBlock,
        pickup_index: int,
        delivery_index: int | None,
    ) -> bool:
        """Gap-aware 任务移动：先校验 gap，再执行 remove + insert。"""
        if task.task_id not in self.placements:
            return False
        ok, _ = self.can_insert_into_gap(pickup_index, delivery_index)
        if not ok:
            return False
        self.remove_task(task.task_id)
        try:
            self.insert_task(task, pickup_index, delivery_index)
        except (ValueError, IndexError):
            return False
        return True

    def swap_tasks_between_gaps(
        self,
        other: "RouteGenome",
        task1_id: str,
        task2_id: str,
        task1_new: tuple[int, int | None],
        task2_new: tuple[int, int | None],
        task1: TaskBlock,
        task2: TaskBlock,
    ) -> bool:
        """Gap-aware 双向交换：失败时完整 rollback。"""
        snapshot_self = self.copy()
        snapshot_other = other.copy()

        ok1, _ = self.can_insert_into_gap(*task2_new)
        ok2, _ = other.can_insert_into_gap(*task1_new)
        if not ok1 or not ok2:
            return False

        try:
            self.remove_task(task1_id)
            other.remove_task(task2_id)
            other.insert_task(task1, *task1_new)
            self.insert_task(task2, *task2_new)
        except (ValueError, IndexError):
            self.events = snapshot_self.events
            self.placements = snapshot_self.placements
            other.events = snapshot_other.events
            other.placements = snapshot_other.placements
            return False

        if task2_id not in self.placements or task1_id not in other.placements:
            self.events = snapshot_self.events
            self.placements = snapshot_self.placements
            other.events = snapshot_other.events
            other.placements = snapshot_other.placements
            return False

        ok, _ = self.assert_invariants()
        ok2, _ = other.assert_invariants()
        if not (ok and ok2):
            self.events = snapshot_self.events
            self.placements = snapshot_self.placements
            other.events = snapshot_other.events
            other.placements = snapshot_other.placements
            return False

        return True

    # ─── RETURN 末位不变量 ───────────────────────────────────

    def _normalize_return_last(self) -> None:
        """保证 RETURN 恒为最后事件。

        insert_task 允许调用方用 ``delivery_index == len(events)`` 表达"尽可能晚"，
        该插入会把业务事件追加到 RETURN 之后；本方法把 RETURN 移到末尾，
        使事件序列恒满足 DEPOT → TASK/PASS → RETURN。
        """
        if not self.events:
            return
        if self.events[-1].event_type == EventType.RETURN:
            return
        for idx, event in enumerate(self.events):
            if event.event_type == EventType.RETURN:
                self.events.pop(idx)
                self.events.append(event)
                break

    def validate_terminal_return(self) -> tuple[bool, str | None]:
        """RETURN 必须存在、唯一且为最末事件，其后不得再有任何业务/PASS 事件。

        返回 (ok, reason)；reason ∈ {MISSING_RETURN, DUPLICATE_RETURN, BUSINESS_EVENT_AFTER_RETURN}。
        """
        returns = [
            i for i, e in enumerate(self.events)
            if e.event_type == EventType.RETURN
        ]
        if not returns:
            return False, "MISSING_RETURN"
        if len(returns) > 1:
            return False, "DUPLICATE_RETURN"
        if returns[0] != len(self.events) - 1:
            return False, "BUSINESS_EVENT_AFTER_RETURN"
        return True, None

    # ─── insert helpers ────────────────────────────────────────

    def insert_after_index(
        self,
        task: TaskBlock,
        after_index: int,
        delivery_after_index: int | None = None,
    ) -> None:
        """在 after_index 之后插入 pickup；delivery 在 delivery_after_index 之后（如有）。"""
        pickup_index = after_index + 1
        delivery_index = (
            delivery_after_index + 1
            if delivery_after_index is not None
            else None
        )
        self.insert_task(task, pickup_index, delivery_index)

    def insert_before_index(
        self,
        task: TaskBlock,
        before_index: int,
        delivery_before_index: int | None = None,
    ) -> None:
        """Insert pickup before before_index; delivery before delivery_before_index if given."""
        self.insert_task(task, before_index, delivery_before_index)

    def insert_task(
        self,
        task: TaskBlock,
        pickup_index: int,
        delivery_index: int | None = None,
    ) -> None:
        if task.task_id in self.placements:
            raise ValueError(
                f"Task already exists: {task.task_id}"
            )

        if pickup_index < 1:
            raise ValueError(
                "Task cannot be inserted before DEPOT"
            )

        # pickup 允许落在 RETURN 下标处（list.insert = 插在 RETURN 之前）。
        if pickup_index > len(self.events) - 1:
            raise IndexError(
                f"pickup_index out of range: {pickup_index}"
            )

        if delivery_index is not None:
            if delivery_index <= pickup_index:
                raise ValueError(
                    "delivery_index must be after pickup_index"
                )
            # delivery_index == len(events) 表示“尽可能晚”
            if delivery_index > len(self.events):
                raise IndexError(
                    f"delivery_index out of range: {delivery_index}"
                )

        gap_ok, gap_reason = self.can_insert_into_gap(pickup_index, delivery_index)
        if not gap_ok:
            raise ValueError(f"Gap violation: {gap_reason}")

        win_ok, win_reason = self._skeleton_service_window_ok(
            task, pickup_index, delivery_index
        )
        if not win_ok:
            raise ValueError(f"Skeleton service window: {win_reason}")

        pickup_event = _pickup_event(task)

        self.events.insert(
            pickup_index,
            pickup_event,
        )

        adjusted_delivery_index = delivery_index
        if adjusted_delivery_index is not None:
            adjusted_delivery_index += 1

            self.events.insert(
                adjusted_delivery_index,
                _delivery_event(task),
            )

        # RETURN 恒为最末事件
        self._normalize_return_last()

        pickup_pos = self._find_task_event(
            task.task_id,
            _pickup_event_type(task.task_type),
        )

        delivery_pos = None
        if delivery_index is not None:
            delivery_pos = self._find_task_event(
                task.task_id,
                _delivery_event_type(task.task_type),
            )

        self.placements[task.task_id] = TaskPlacement(
            task_id=task.task_id,
            pickup_index=pickup_pos,
            delivery_index=delivery_pos,
        )

    def remove_task(self, task_id: str) -> None:
        self.events = [
            event
            for event in self.events
            if event.task_id != task_id
        ]
        self.placements.pop(task_id, None)

        self._rebuild_placements()

    def _rebuild_placements(self) -> None:
        new_placements = {}

        grouped: dict[str, dict[EventType, int]] = {}

        for index, event in enumerate(self.events):
            if not event.task_id:
                continue

            grouped.setdefault(
                event.task_id,
                {},
            )[event.event_type] = index

        for task_id, event_map in grouped.items():
            pickup_index = None
            delivery_index = None

            for event_type, index in event_map.items():
                if event_type in (
                    EventType.BOARD,
                    EventType.PICKUP,
                    EventType.DELIVER,
                ):
                    if pickup_index is None:
                        pickup_index = index

            if EventType.ALIGHT in event_map:
                delivery_index = event_map[EventType.ALIGHT]

            elif (
                EventType.DELIVER in event_map
                and any(
                    e.task_id == task_id
                    and e.event_type == EventType.PICKUP
                    for e in self.events
                )
            ):
                delivery_index = event_map[EventType.DELIVER]

            if pickup_index is not None:
                new_placements[task_id] = TaskPlacement(
                    task_id=task_id,
                    pickup_index=pickup_index,
                    delivery_index=delivery_index,
                )

        self.placements = new_placements

    def _find_task_event(
        self,
        task_id: str,
        event_type: EventType,
    ) -> int:
        for index, event in enumerate(self.events):
            if (
                event.task_id == task_id
                and event.event_type == event_type
            ):
                return index

        raise RuntimeError(
            f"Task event not found: "
            f"task={task_id}, event={event_type}"
        )

    def validate_precedence(
        self,
    ) -> tuple[bool, str | None]:
        for task_id, placement in self.placements.items():
            if placement.delivery_index is not None:
                if (
                    placement.pickup_index
                    >= placement.delivery_index
                ):
                    return (
                        False,
                        "PICKUP_AFTER_DELIVER",
                    )

        return True, None

    def validate_paired_task(
        self,
    ) -> tuple[bool, str | None]:
        """验证 paired task（乘客/PASSENGER 或 货运/SHIPMENT）的完整性Invariants。"""
        task_event_counts: dict[str, dict[EventType, int]] = {}

        for event in self.events:
            if not event.task_id:
                continue
            task_id = event.task_id
            if task_id not in task_event_counts:
                task_event_counts[task_id] = {
                    EventType.PICKUP: 0,
                    EventType.DELIVER: 0,
                    EventType.BOARD: 0,
                    EventType.ALIGHT: 0,
                }
            if event.event_type in task_event_counts[task_id]:
                task_event_counts[task_id][event.event_type] += 1

        for task_id, placement in self.placements.items():
            if task_id not in task_event_counts:
                continue

            counts = task_event_counts[task_id]

            is_paired_task = (
                counts[EventType.BOARD] > 0
                or counts[EventType.ALIGHT] > 0
                or counts[EventType.PICKUP] > 0
                or counts[EventType.DELIVER] > 0
            )

            if not is_paired_task:
                continue

            pickup_count = counts[EventType.PICKUP] + counts[EventType.BOARD]
            if pickup_count != 1:
                return False, "DUPLICATE_PICKUP"

            delivery_count = counts[EventType.DELIVER] + counts[EventType.ALIGHT]
            if delivery_count != 1:
                return False, "DUPLICATE_DELIVERY"

            if placement.delivery_index is not None:
                if placement.pickup_index >= placement.delivery_index:
                    return False, "PICKUP_AFTER_DELIVER"

        return True, None

    def validate_skeleton(
        self,
    ) -> tuple[bool, str | None]:
        if not self.skeleton:
            return True, None

        skeleton_index = 0

        for event in self.events:
            if event.event_type != EventType.PASS:
                continue

            if skeleton_index >= len(self.skeleton):
                break

            if (
                event.station_id
                == self.skeleton[skeleton_index]
            ):
                skeleton_index += 1

        if skeleton_index != len(self.skeleton):
            return (
                False,
                "SKELETON_ORDER_VIOLATION",
            )

        return True, None


# ─── module-level helpers ──────────────────────────────────────


def _pickup_event(task: TaskBlock) -> RouteEvent:
    if task.task_type == TaskType.PASSENGER:
        event_type = EventType.BOARD
    elif task.task_type in (
        TaskType.SHIPMENT,
        TaskType.PICKUP,
    ):
        event_type = EventType.PICKUP
    else:
        event_type = EventType.DELIVER

    return RouteEvent(
        station_id=task.pickup_station,
        event_type=event_type,
        task_id=task.task_id,
        order_id=(
            task.order_ids[0]
            if task.order_ids
            else None
        ),
        task_type=task.task_type,
    )


def _delivery_event(task: TaskBlock) -> RouteEvent:
    if task.task_type == TaskType.PASSENGER:
        event_type = EventType.ALIGHT
    else:
        event_type = EventType.DELIVER

    return RouteEvent(
        station_id=task.delivery_station,
        event_type=event_type,
        task_id=task.task_id,
        order_id=(
            task.order_ids[0]
            if task.order_ids
            else None
        ),
        task_type=task.task_type,
    )


def _pickup_event_type(
    task_type: TaskType,
) -> EventType:
    if task_type == TaskType.PASSENGER:
        return EventType.BOARD

    if task_type in (
        TaskType.SHIPMENT,
        TaskType.PICKUP,
    ):
        return EventType.PICKUP

    return EventType.DELIVER


def _delivery_event_type(
    task_type: TaskType,
) -> EventType:
    if task_type == TaskType.PASSENGER:
        return EventType.ALIGHT

    return EventType.DELIVER


# ═══════════════════════════════════════════════════════════════
# 旧版全局路线基因（保留兼容 global_construction / global_evaluator / global_local_search）
# ═══════════════════════════════════════════════════════════════


@dataclass
class GlobalRouteGenome:
    """旧版全局路线基因组：每辆车的任务序列。保留供 2.0 模块使用。"""
    vehicle_routes: dict[int, list[str]]
    task_blocks: dict[str, TaskBlock]
    skeletons: dict[int, list[str]]
    depot_station: str
    vehicle_caps: dict[int, tuple[int, int, int, int]]

    def copy(self) -> GlobalRouteGenome:
        """深拷贝：禁止共享可变对象导致串改。"""
        return GlobalRouteGenome(
            vehicle_routes={k: list(v) for k, v in self.vehicle_routes.items()},
            task_blocks=dict(self.task_blocks),
            skeletons={k: list(v) for k, v in self.skeletons.items()},
            depot_station=self.depot_station,
            vehicle_caps=dict(self.vehicle_caps),
        )

    def get_route(self, vehicle_index: int) -> list[str]:
        return self.vehicle_routes.get(vehicle_index, [])

    def set_route(self, vehicle_index: int, route: list[str]) -> None:
        self.vehicle_routes[vehicle_index] = route

    def get_all_tasks(self) -> list[str]:
        return [tid for route in self.vehicle_routes.values() for tid in route]

    def get_unassigned(self, all_task_ids: list[str]) -> list[str]:
        assigned = set(self.get_all_tasks())
        return [tid for tid in all_task_ids if tid not in assigned]

    def insert_task(self, vehicle_index: int, position: int, task_id: str) -> None:
        if vehicle_index not in self.vehicle_routes:
            self.vehicle_routes[vehicle_index] = []
        self.vehicle_routes[vehicle_index].insert(position, task_id)

    def remove_task(self, task_id: str) -> tuple[int, int] | None:
        for vi, route in self.vehicle_routes.items():
            if task_id in route:
                pos = route.index(task_id)
                route.remove(task_id)
                return (vi, pos)
        return None

    def swap_tasks(self, vi1: int, pos1: int, vi2: int, pos2: int) -> None:
        r1 = self.vehicle_routes[vi1]
        r2 = self.vehicle_routes[vi2]
        r1[pos1], r2[pos2] = r2[pos2], r1[pos1]

    def relocate_task(self, src_vi: int, src_pos: int, dst_vi: int, dst_pos: int) -> None:
        task_id = self.vehicle_routes[src_vi].pop(src_pos)
        if dst_vi not in self.vehicle_routes:
            self.vehicle_routes[dst_vi] = []
        self.vehicle_routes[dst_vi].insert(dst_pos, task_id)

    def compute_station_sequence(self, vehicle_index: int, station_map: dict) -> list[str]:
        route = self.get_route(vehicle_index)
        skeleton = self.skeletons.get(vehicle_index, [])
        depot = self.depot_station

        stations = [depot]

        if skeleton:
            task_groups: dict[str, list[str]] = {s: [] for s in skeleton}
            task_groups[depot] = []
            task_groups["_after"] = []

            for task_id in route:
                task = self.task_blocks[task_id]
                best_station = depot
                best_dist = float("inf")

                pickup = station_map.get(task.pickup_station)
                if pickup:
                    for skel_station_id in skeleton:
                        skel_station = station_map.get(skel_station_id)
                        if skel_station:
                            from .heuristic import compute_distance
                            d = compute_distance(pickup, skel_station, None)
                            if d < best_dist:
                                best_dist = d
                                best_station = skel_station_id

                if best_station in task_groups:
                    task_groups[best_station].append(task_id)
                else:
                    task_groups["_after"].append(task_id)

            for skel_station in skeleton:
                for task_id in task_groups.get(skel_station, []):
                    task = self.task_blocks[task_id]
                    stations.append(task.pickup_station)
                    if task.task_type in (TaskType.PASSENGER, TaskType.SHIPMENT):
                        stations.append(task.delivery_station)
                stations.append(skel_station)

            for task_id in task_groups.get("_after", []):
                task = self.task_blocks[task_id]
                stations.append(task.pickup_station)
                if task.task_type in (TaskType.PASSENGER, TaskType.SHIPMENT):
                    stations.append(task.delivery_station)
        else:
            for task_id in route:
                task = self.task_blocks[task_id]
                stations.append(task.pickup_station)
                if task.task_type in (TaskType.PASSENGER, TaskType.SHIPMENT):
                    stations.append(task.delivery_station)

        stations.append(depot)
        return stations


@dataclass
class GenomeEvaluation:
    """RouteGenome 的评估结果。"""
    feasible: bool = True
    vehicle_count: int = 0
    total_distance: float = 0.0
    total_duration: float = 0.0
    passenger_total_impact: float = 0.0
    passenger_max_impact: float = 0.0
    cargo_detour: float = 0.0
    backtracking_ratio: float = 0.0
    station_revisits: int = 0
    direction_reversals: int = 0
    normalized_cost: float = float("inf")

    def key(self):
        return (
            not self.feasible,
            self.vehicle_count,
            round(self.passenger_total_impact, 3),
            round(self.backtracking_ratio, 4),
            round(self.cargo_detour, 3),
            round(self.total_distance, 3),
            round(self.total_duration, 1),
        )

    def __lt__(self, other: GenomeEvaluation) -> bool:
        return self.key() < other.key()
