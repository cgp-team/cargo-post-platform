"""HACO-CPS 解的编码：TaskBlock / VehicleRoute / Solution / ObjectiveVector。

核心建模创新：
- 乘客订单编码为 PASSENGER block（BOARD+ALIGHT 不可拆分）
- 配对货运编码为 SHIPMENT block（PICKUP+DELIVERY 不可拆分）
- 独立 DELIVERY/PICKUP 编码为单站 block
- Skeleton 定义主线路，算法搜索在骨架间隙中插入任务

评分职责划分（本轮统一）：
- 硬约束：仅 FeasibilityEngine 裁决
- 最终业务比较：ObjectiveVector.key()（lexicographic）
- 搜索内部能量：ObjectiveVector.objective_to_scalar()（SA / 信息素）
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field
from enum import Enum
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from ..models import CargoSource, Station


# ─── 搜索内部能量权重（SA / 信息素专用）────────────────────
# 这些权重只服务 objective_to_scalar()，不是业务最终目标。
# 业务最终比较始终使用 ObjectiveVector.key() 的 lexicographic 顺序。
# 禁止 ALNS / Local Search / Construction / Pheromone 各自再发明一套 weighting。
SEARCH_ENERGY_INFEASIBILITY = 1_000_000.0
SEARCH_ENERGY_VEHICLE = 10_000.0
SEARCH_ENERGY_PASSENGER = 1.0
SEARCH_ENERGY_CARGO_DETOUR = 10.0
SEARCH_ENERGY_DISTANCE = 0.1
SEARCH_ENERGY_DURATION = 0.01


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
    # 可选经济价值（业务层真实运费/结算；缺失=None，不造假）
    economic_value: float | None = None

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
    """分层目标向量：逐级比较，不使用权重混合。

    业务最终比较使用 :meth:`key`（lexicographic）：
    infeasibility → vehicle_count → passenger_impact → cargo_detour
    → total_distance → total_duration。
    """
    infeasibility: float = 0.0    # 0 = feasible, >0 = infeasible severity
    vehicle_count: int = 0
    passenger_impact: float = 0.0  # 总乘客影响（秒）
    cargo_detour: float = 0.0      # 总货物绕行（km）
    total_distance: float = 0.0
    total_duration: float = 0.0    # 总行驶时间（秒）

    def key(self):
        """业务 lexicographic 比较键。最终优劣一律以此为准。"""
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

    def objective_to_scalar(self) -> float:
        """搜索内部能量函数（SA / 信息素 / cheap ranking 专用）。

        **这不是业务最终目标。** 业务最终比较始终使用 :meth:`key`。

        提供一个连续可减的标量，供 simulated annealing 计算真实 delta、
        信息素沉积（Q / cost）等需要数值能量的机制使用。权重集中在
        模块级 ``SEARCH_ENERGY_*`` 常量。

        注意：本标量与 lexicographic key 在病态输入下不保证严格同序；
        SA 判定“更优/更差”应先用 key()，再用本标量求 delta。
        """
        raw = (
            self.infeasibility * SEARCH_ENERGY_INFEASIBILITY
            + self.vehicle_count * SEARCH_ENERGY_VEHICLE
            + self.passenger_impact * SEARCH_ENERGY_PASSENGER
            + self.cargo_detour * SEARCH_ENERGY_CARGO_DETOUR
            + self.total_distance * SEARCH_ENERGY_DISTANCE
            + self.total_duration * SEARCH_ENERGY_DURATION
        )
        if not math.isfinite(raw):
            return float("inf")
        return raw

    def scalar_delta_worse(self, other: "ObjectiveVector") -> float:
        """相对 other 变差的搜索能量差。

        - self better/equal（key）→ 返回 0
        - self worse → 返回 >0 的能量差，供 SA 使用 exp(-delta / T)
        - 非有限 → 返回 +inf
        """
        if self.key() <= other.key():
            return 0.0
        delta = self.objective_to_scalar() - other.objective_to_scalar()
        if not math.isfinite(delta):
            return float("inf")
        if delta <= 0.0:
            # key 说更差但标量未升高：给极小正数保证 SA 方向正确
            return 1e-9
        return delta

    def normalized_cost(self) -> float:
        """向后兼容别名，等价于 :meth:`objective_to_scalar`。"""
        return self.objective_to_scalar()
