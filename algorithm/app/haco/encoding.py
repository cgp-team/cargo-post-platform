"""HACO-CPS 解的编码：TaskBlock / VehicleRoute / Solution / ObjectiveVector。

核心建模创新：
- 乘客订单编码为 PASSENGER block（BOARD+ALIGHT 不可拆分）
- 配对货运编码为 SHIPMENT block（PICKUP+DELIVERY 不可拆分）
- 独立 DELIVERY/PICKUP 编码为单站 block
- Skeleton 定义主线路，算法搜索在骨架间隙中插入任务
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from ..models import CargoSource, Station


class TaskType(str, Enum):
    PASSENGER = "PASSENGER"
    SHIPMENT = "SHIPMENT"
    DELIVERY = "DELIVERY"
    PICKUP = "PICKUP"


@dataclass
class TaskBlock:
    """一个不可拆分的任务单元。"""
    task_id: str
    task_type: TaskType
    pickup_station: str      # 上车/揽收站
    delivery_station: str    # 下车/派送站
    size: int = 1            # 乘客数或货物件数
    order_ids: list[str] = field(default_factory=list)  # 原始订单 ID
    # 货物来源：PRELOADED=场站预装（消耗 initial_cargo_load，CargoLoad 维度不计入、
    # CargoOut 累计派送）；None=上下文推断（DELIVERY 无来源语义按旧逻辑）。
    # 从 PlanRequest 编码时透传 PlanOrder.cargoSource；PASSENGER/SHIPMENT 恒为 None。
    cargo_source: CargoSource | None = None

    # 运行时计算的属性
    pickup_station_obj: Station | None = None
    delivery_station_obj: Station | None = None


@dataclass
class TaskInsertion:
    """任务在路线中的插入记录。"""
    task: TaskBlock
    pickup_position: int     # 在 stops 列表中的位置（pickup 节点）
    delivery_position: int   # 在 stops 列表中的位置（delivery 节点）


@dataclass
class VehicleRoute:
    """一辆车的路线：有序的插入记录列表。"""
    vehicle_index: int
    vehicle_id: int
    passenger_capacity: int
    cargo_capacity: int
    initial_passenger_load: int
    initial_cargo_load: int
    skeleton: list[str] | None
    insertions: list[TaskInsertion] = field(default_factory=list)

    @property
    def task_count(self) -> int:
        return len(self.insertions)


@dataclass
class Solution:
    """完整解：所有车辆的路线。"""
    routes: list[VehicleRoute]
    warnings: list[str] = field(default_factory=list)

    @property
    def used_vehicles(self) -> int:
        return sum(1 for r in self.routes if r.task_count > 0)

    def all_tasks_assigned(self, total_tasks: int) -> bool:
        assigned = sum(r.task_count for r in self.routes)
        return assigned >= total_tasks


@dataclass
class ObjectiveVector:
    """分层目标向量：逐级比较，不使用权重混合。"""
    infeasibility: float = 0.0    # 0 = feasible, >0 = infeasible severity
    vehicle_count: int = 0
    passenger_impact: float = 0.0  # 总乘客影响（秒）
    cargo_detour: float = 0.0      # 总货物绕行（km）
    total_distance: float = 0.0
    total_duration: float = 0.0    # 总行驶时间（秒）

    def key(self):
        return (
            self.infeasibility,
            self.vehicle_count,
            round(self.passenger_impact, 3),
            round(self.cargo_detour, 3),
            round(self.total_distance, 3),
            round(self.total_duration, 1),
        )

    def __lt__(self, other: ObjectiveVector) -> bool:
        """分层比较：infeasibility > vehicle_count > passenger_impact > cargo_detour > distance > duration。"""
        return self.key() < other.key()

    def __le__(self, other: ObjectiveVector) -> bool:
        return self == other or self < other

    def normalized_cost(self) -> float:
        """用于信息素更新的归一化成本。"""
        return (
            self.infeasibility * 10000
            + self.vehicle_count * 1000
            + self.passenger_impact * 0.1
            + self.cargo_detour * 10
            + self.total_distance
            + self.total_duration * 0.01
        )
