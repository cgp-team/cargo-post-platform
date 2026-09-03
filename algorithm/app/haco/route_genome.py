"""HACO-CPS 1.4.0 真实路线基因 + 旧版全局路线基因（兼容）。

Phase 1 核心：
- RouteGenome：per-vehicle 事件序列（DEPOT → events → RETURN）
- RouteEvent / EventType / TaskPlacement：事件级路线表示
- 旧 GlobalRouteGenome 保留供 global_construction/evaluator/local_search 使用
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
    3. Skeleton PASS 是固定骨架事件。
    4. 不再把 Task -> Gap 作为真实路线表示。
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

    def copy(self) -> "RouteGenome":
        result = RouteGenome(
            vehicle_index=self.vehicle_index,
            vehicle_id=self.vehicle_id,
            depot_station=self.depot_station,
            skeleton=list(self.skeleton),
        )
        result.events = list(self.events)
        result.placements = dict(self.placements)
        return result

    def task_count(self) -> int:
        return len(self.placements)

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
        """在 before_index 之前插入 pickup；delivery 在 delivery_before_index 之前（如有）。"""
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

        if pickup_index > len(self.events) - 1:
            raise IndexError(
                f"pickup_index out of range: {pickup_index}"
            )

        if delivery_index is not None:
            if delivery_index <= pickup_index:
                raise ValueError(
                    "delivery_index must be after pickup_index"
                )

            if delivery_index > len(self.events):
                raise IndexError(
                    f"delivery_index out of range: {delivery_index}"
                )

        pickup_event = _pickup_event(task)

        # 先插入 pickup
        self.events.insert(
            pickup_index,
            pickup_event,
        )

        # pickup 插入后，后续 index +1
        adjusted_delivery_index = delivery_index
        if adjusted_delivery_index is not None:
            adjusted_delivery_index += 1

            self.events.insert(
                adjusted_delivery_index,
                _delivery_event(task),
            )

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
        # standalone DELIVERY 只有一个事件
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

    if task_type == TaskType.SHIPMENT:
        return EventType.DELIVER

    return EventType.DELIVER


# ═══════════════════════════════════════════════════════════════
# 旧版全局路线基因（保留兼容 global_construction / global_evaluator / global_local_search）
# ═══════════════════════════════════════════════════════════════


@dataclass
class GlobalRouteGenome:
    """旧版全局路线基因组：每辆车的任务序列。保留供 2.0 模块使用。"""
    # 每辆车的任务序列 (vehicle_index -> [task_id, ...])
    vehicle_routes: dict[int, list[str]]
    # 任务块映射 (task_id -> TaskBlock)
    task_blocks: dict[str, TaskBlock]
    # 骨架映射 (vehicle_index -> [station_id, ...])
    skeletons: dict[int, list[str]]
    # 场站
    depot_station: str
    # 车辆容量
    vehicle_caps: dict[int, tuple[int, int, int, int]]  # (p_cap, c_cap, p_init, c_init)

    def copy(self) -> GlobalRouteGenome:
        """深拷贝。"""
        return GlobalRouteGenome(
            vehicle_routes={k: list(v) for k, v in self.vehicle_routes.items()},
            task_blocks=dict(self.task_blocks),
            skeletons=dict(self.skeletons),
            depot_station=self.depot_station,
            vehicle_caps=dict(self.vehicle_caps),
        )

    def get_route(self, vehicle_index: int) -> list[str]:
        """获取指定车辆的任务序列。"""
        return self.vehicle_routes.get(vehicle_index, [])

    def set_route(self, vehicle_index: int, route: list[str]) -> None:
        """设置指定车辆的任务序列。"""
        self.vehicle_routes[vehicle_index] = route

    def get_all_tasks(self) -> list[str]:
        """获取所有已分配的任务 ID。"""
        return [tid for route in self.vehicle_routes.values() for tid in route]

    def get_unassigned(self, all_task_ids: list[str]) -> list[str]:
        """获取未分配的任务 ID。"""
        assigned = set(self.get_all_tasks())
        return [tid for tid in all_task_ids if tid not in assigned]

    def insert_task(self, vehicle_index: int, position: int, task_id: str) -> None:
        """将任务插入到指定车辆的指定位置。"""
        if vehicle_index not in self.vehicle_routes:
            self.vehicle_routes[vehicle_index] = []
        self.vehicle_routes[vehicle_index].insert(position, task_id)

    def remove_task(self, task_id: str) -> tuple[int, int] | None:
        """移除任务。返回 (vehicle_index, position)。"""
        for vi, route in self.vehicle_routes.items():
            if task_id in route:
                pos = route.index(task_id)
                route.remove(task_id)
                return (vi, pos)
        return None

    def swap_tasks(self, vi1: int, pos1: int, vi2: int, pos2: int) -> None:
        """交换两个位置的任务。"""
        r1 = self.vehicle_routes[vi1]
        r2 = self.vehicle_routes[vi2]
        r1[pos1], r2[pos2] = r2[pos2], r1[pos1]

    def relocate_task(self, src_vi: int, src_pos: int, dst_vi: int, dst_pos: int) -> None:
        """将任务从一个位置移到另一个位置。"""
        task_id = self.vehicle_routes[src_vi].pop(src_pos)
        if dst_vi not in self.vehicle_routes:
            self.vehicle_routes[dst_vi] = []
        self.vehicle_routes[dst_vi].insert(dst_pos, task_id)

    def compute_station_sequence(self, vehicle_index: int, station_map: dict) -> list[str]:
        """计算指定车辆的完整站点序列（含 depot 和骨架 PASS）。"""
        route = self.get_route(vehicle_index)
        skeleton = self.skeletons.get(vehicle_index, [])
        depot = self.depot_station

        # 构建站点序列
        stations = [depot]

        # 如果有骨架，按骨架顺序插入任务
        if skeleton:
            # 将任务按最近骨架站分组
            task_groups: dict[str, list[str]] = {s: [] for s in skeleton}
            task_groups[depot] = []
            task_groups["_after"] = []

            for task_id in route:
                task = self.task_blocks[task_id]
                best_station = depot
                best_dist = float("inf")

                # 找最近的骨架站
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

            # 按骨架顺序展开
            for skel_station in skeleton:
                for task_id in task_groups.get(skel_station, []):
                    task = self.task_blocks[task_id]
                    stations.append(task.pickup_station)
                    if task.task_type in (TaskType.PASSENGER, TaskType.SHIPMENT):
                        stations.append(task.delivery_station)
                stations.append(skel_station)

            # 骨架后的任务
            for task_id in task_groups.get("_after", []):
                task = self.task_blocks[task_id]
                stations.append(task.pickup_station)
                if task.task_type in (TaskType.PASSENGER, TaskType.SHIPMENT):
                    stations.append(task.delivery_station)
        else:
            # 无骨架：直接按任务序列展开
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

    def __lt__(self, other: GenomeEvaluation) -> bool:
        """分层比较。"""
        if self.feasible != other.feasible:
            return self.feasible
        if self.vehicle_count != other.vehicle_count:
            return self.vehicle_count < other.vehicle_count
        if abs(self.passenger_total_impact - other.passenger_total_impact) > 0.1:
            return self.passenger_total_impact < other.passenger_total_impact
        if abs(self.backtracking_ratio - other.backtracking_ratio) > 0.01:
            return self.backtracking_ratio < other.backtracking_ratio
        if abs(self.cargo_detour - other.cargo_detour) > 0.001:
            return self.cargo_detour < other.cargo_detour
        if abs(self.total_distance - other.total_distance) > 0.001:
            return self.total_distance < other.total_distance
        return self.total_duration < other.total_duration
