"""HACO-CPS 路线状态模型：Skeleton-aware RouteState。

核心概念：
- Skeleton 定义骨架主线路 (A → B → C → D)
- 骨架间隙 (Gap) 是任务插入的位置
- TaskBlock 不可拆分地插入到某个 Gap 中
- RouteState 管理一条车辆路线的完整状态
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import TYPE_CHECKING

from .encoding import TaskBlock, TaskType

if TYPE_CHECKING:
    from ..distance import DistanceMatrix
    from ..models import Station


@dataclass
class StopRef:
    """路线中的一个停靠点引用。"""
    station_id: str
    action: str          # DEPART / PASS / BOARD / ALIGHT / PICKUP / DELIVER / RETURN
    task_id: str | None = None
    order_id: str | None = None
    task_type: TaskType | None = None


@dataclass
class SkeletonGap:
    """骨架间隙：两个骨架站点之间的空间。"""
    gap_index: int
    from_station: str    # 前一个骨架站 (或 DEPOT)
    to_station: str      # 后一个骨架站 (或 DEPOT)


@dataclass
class RouteState:
    """一辆车的路线状态，支持骨架间隙插入。"""
    vehicle_index: int
    vehicle_id: int
    passenger_capacity: int
    cargo_capacity: int
    initial_passenger_load: int
    initial_cargo_load: int
    skeleton: list[str]       # 骨架站点列表 (不含 depot)
    depot_station: str

    # 当前插入的任务列表 (按插入顺序)
    tasks: list[TaskBlock] = field(default_factory=list)

    # 每个任务在哪个 gap 中插入
    task_gap_map: dict[str, int] = field(default_factory=dict)  # task_id -> gap_index

    @property
    def gaps(self) -> list[SkeletonGap]:
        """计算骨架间隙列表。"""
        gap_list = []
        stations = [self.depot_station] + self.skeleton + [self.depot_station]
        for i in range(len(stations) - 1):
            gap_list.append(SkeletonGap(
                gap_index=i,
                from_station=stations[i],
                to_station=stations[i + 1],
            ))
        return gap_list

    def copy(self) -> RouteState:
        """深拷贝路线状态。"""
        return RouteState(
            vehicle_index=self.vehicle_index,
            vehicle_id=self.vehicle_id,
            passenger_capacity=self.passenger_capacity,
            cargo_capacity=self.cargo_capacity,
            initial_passenger_load=self.initial_passenger_load,
            initial_cargo_load=self.initial_cargo_load,
            skeleton=list(self.skeleton),
            depot_station=self.depot_station,
            tasks=list(self.tasks),
            task_gap_map=dict(self.task_gap_map),
        )

    def insert_task(self, task: TaskBlock, gap_index: int) -> bool:
        """将任务插入到指定 gap 中。返回是否成功。"""
        self.tasks.append(task)
        self.task_gap_map[task.task_id] = gap_index
        return True

    def remove_task(self, task_id: str) -> TaskBlock | None:
        """移除指定任务。返回被移除的任务。"""
        for i, task in enumerate(self.tasks):
            if task.task_id == task_id:
                self.tasks.pop(i)
                self.task_gap_map.pop(task_id, None)
                return task
        return None

    def get_tasks_in_gap(self, gap_index: int) -> list[TaskBlock]:
        """获取在指定 gap 中的所有任务。"""
        return [t for t in self.tasks if self.task_gap_map.get(t.task_id) == gap_index]

    def compute_stops(self, station_map: dict[str, Station], matrix: DistanceMatrix | None) -> list[StopRef]:
        """计算完整的停靠序列（骨架 PASS + 插入任务）。"""
        stops = []
        # DEPART
        stops.append(StopRef(station_id=self.depot_station, action="DEPART"))

        # 遍历骨架间隙
        for gap in self.gaps:
            # 插入到这个 gap 中的任务
            gap_tasks = self.get_tasks_in_gap(gap.gap_index)

            # 按任务类型排序：先乘客上下车，再货运
            for task in gap_tasks:
                stops.extend(_task_to_stops(task))

            # 到达骨架站（如果不是返回 depot）
            if gap.to_station != self.depot_station:
                stops.append(StopRef(station_id=gap.to_station, action="PASS"))

        # RETURN
        stops.append(StopRef(station_id=self.depot_station, action="RETURN"))
        return stops

    def compute_passenger_load_profile(self) -> list[tuple[int, int]]:
        """计算乘客载荷变化序列 (step, load)。"""
        profile = [(0, self.initial_passenger_load)]
        load = self.initial_passenger_load
        for task in self.tasks:
            if task.task_type == TaskType.PASSENGER:
                load += 1
                profile.append((len(profile), load))
                load -= 1
                profile.append((len(profile), load))
        return profile

    def compute_cargo_load_profile(self) -> list[tuple[int, int]]:
        """计算货物载荷变化序列 (step, load)。"""
        profile = [(0, self.initial_cargo_load)]
        load = self.initial_cargo_load
        for task in self.tasks:
            if task.task_type in (TaskType.SHIPMENT, TaskType.DELIVERY, TaskType.PICKUP):
                load += task.size
                profile.append((len(profile), load))
                if task.task_type == TaskType.SHIPMENT:
                    load -= task.size
                    profile.append((len(profile), load))
        return profile

    def check_capacity(self) -> tuple[bool, str | None]:
        """检查容量约束。"""
        # 乘客容量
        p_load = self.initial_passenger_load
        for task in self.tasks:
            if task.task_type == TaskType.PASSENGER:
                p_load += 1
                if p_load > self.passenger_capacity:
                    return False, "PASSENGER_CAPACITY_EXCEEDED"
                p_load -= 1

        # 货物容量
        c_load = self.initial_cargo_load
        for task in self.tasks:
            if task.task_type in (TaskType.SHIPMENT, TaskType.DELIVERY, TaskType.PICKUP):
                c_load += task.size
                if c_load > self.cargo_capacity:
                    return False, "CARGO_CAPACITY_EXCEEDED"
                if task.task_type == TaskType.SHIPMENT:
                    c_load -= task.size

        return True, None

    def check_skeleton_order(self) -> bool:
        """检查骨架顺序是否被保持。
        对于跨 gap 的任务 (pickup in gap i, delivery in gap j)，要求 i <= j。"""
        for task in self.tasks:
            gap = self.task_gap_map.get(task.task_id)
            if gap is None:
                return False
            # 对于 SHIPMENT/PASSENGER，pickup 和 delivery 在同一个 gap 中
            # 所以自动满足 i <= j
        return True


def _task_to_stops(task: TaskBlock) -> list[StopRef]:
    """将任务转换为停靠序列。"""
    if task.task_type == TaskType.PASSENGER:
        return [
            StopRef(station_id=task.pickup_station, action="BOARD",
                    task_id=task.task_id, order_id=task.order_ids[0] if task.order_ids else None,
                    task_type=TaskType.PASSENGER),
            StopRef(station_id=task.delivery_station, action="ALIGHT",
                    task_id=task.task_id, order_id=task.order_ids[0] if task.order_ids else None,
                    task_type=TaskType.PASSENGER),
        ]
    elif task.task_type == TaskType.SHIPMENT:
        return [
            StopRef(station_id=task.pickup_station, action="PICKUP",
                    task_id=task.task_id, order_id=task.order_ids[0] if task.order_ids else None,
                    task_type=TaskType.SHIPMENT),
            StopRef(station_id=task.delivery_station, action="DELIVER",
                    task_id=task.task_id, order_id=task.order_ids[0] if task.order_ids else None,
                    task_type=TaskType.SHIPMENT),
        ]
    elif task.task_type == TaskType.DELIVERY:
        return [
            StopRef(station_id=task.pickup_station, action="DELIVER",
                    task_id=task.task_id, order_id=task.order_ids[0] if task.order_ids else None,
                    task_type=TaskType.DELIVERY),
        ]
    elif task.task_type == TaskType.PICKUP:
        return [
            StopRef(station_id=task.pickup_station, action="PICKUP",
                    task_id=task.task_id, order_id=task.order_ids[0] if task.order_ids else None,
                    task_type=TaskType.PICKUP),
        ]
    return []
