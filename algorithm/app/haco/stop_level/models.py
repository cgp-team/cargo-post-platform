"""Stop-Level 数据模型。

核心：Activity 是搜索原子，不再是 TaskBlock。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from ..distance import DistanceMatrix
    from ..models import Station


class ActionType(str, Enum):
    DEPART = "DEPART"
    BOARD = "BOARD"
    ALIGHT = "ALIGHT"
    PICKUP = "PICKUP"
    DELIVER = "DELIVER"
    PASS = "PASS"
    RETURN = "RETURN"


@dataclass
class Activity:
    """一个 stop-event：路线中的一个原子操作。"""
    station_id: str
    action: ActionType
    request_id: str | None = None  # 关联的请求 ID (P0, D1, etc.)

    def __repr__(self):
        return f"{self.action.value}@{self.station_id}({self.request_id or '-'})"


@dataclass
class Request:
    """业务请求约束层。"""
    request_id: str
    request_type: str  # PASSENGER, DELIVERY, PICKUP, SHIPMENT
    pickup_station: str
    delivery_station: str
    size: int = 1

    @property
    def board_activity(self) -> Activity:
        if self.request_type == "PASSENGER":
            return Activity(self.pickup_station, ActionType.BOARD, self.request_id)
        elif self.request_type == "SHIPMENT":
            return Activity(self.pickup_station, ActionType.PICKUP, self.request_id)
        elif self.request_type == "DELIVERY":
            return Activity(self.pickup_station, ActionType.DELIVER, self.request_id)
        elif self.request_type == "PICKUP":
            return Activity(self.pickup_station, ActionType.PICKUP, self.request_id)
        return Activity(self.pickup_station, ActionType.DELIVER, self.request_id)

    @property
    def alight_activity(self) -> Activity | None:
        if self.request_type == "PASSENGER":
            return Activity(self.delivery_station, ActionType.ALIGHT, self.request_id)
        elif self.request_type == "SHIPMENT":
            return Activity(self.delivery_station, ActionType.DELIVER, self.request_id)
        return None  # DELIVERY/PICKUP 只有一个 activity


@dataclass
class StopLevelRoute:
    """一辆车的 stop-level 路线。"""
    vehicle_index: int
    vehicle_id: int
    passenger_capacity: int
    cargo_capacity: int
    initial_passenger_load: int
    initial_cargo_load: int
    skeleton: list[str]
    depot_station: str
    activities: list[Activity] = field(default_factory=list)

    def copy(self) -> StopLevelRoute:
        return StopLevelRoute(
            vehicle_index=self.vehicle_index,
            vehicle_id=self.vehicle_id,
            passenger_capacity=self.passenger_capacity,
            cargo_capacity=self.cargo_capacity,
            initial_passenger_load=self.initial_passenger_load,
            initial_cargo_load=self.initial_cargo_load,
            skeleton=list(self.skeleton),
            depot_station=self.depot_station,
            activities=list(self.activities),
        )


@dataclass
class StopLevelSolution:
    """完整的 stop-level 解。"""
    routes: dict[int, StopLevelRoute]  # vehicle_index -> route
    requests: dict[str, Request]  # request_id -> Request
    depot_station: str

    def copy(self) -> StopLevelSolution:
        return StopLevelSolution(
            routes={k: v.copy() for k, v in self.routes.items()},
            requests=dict(self.requests),
            depot_station=self.depot_station,
        )

    def get_all_activities(self) -> list[Activity]:
        return [a for route in self.routes.values() for a in route.activities]

    def get_request_state(self, request_id: str) -> dict:
        """获取请求的状态：boarded/alighted/picked_up/delivered。"""
        state = {"boarded": False, "alighted": False, "picked_up": False, "delivered": False}
        for route in self.routes.values():
            for a in route.activities:
                if a.request_id == request_id:
                    if a.action == ActionType.BOARD:
                        state["boarded"] = True
                    elif a.action == ActionType.ALIGHT:
                        state["alighted"] = True
                    elif a.action == ActionType.PICKUP:
                        state["picked_up"] = True
                    elif a.action == ActionType.DELIVER:
                        state["delivered"] = True
        return state


@dataclass
class StopLevelEvaluation:
    """Stop-level 解的评估结果。"""
    feasible: bool = True
    vehicle_count: int = 0
    total_distance: float = 0.0
    total_duration: float = 0.0
    passenger_total_impact: float = 0.0
    passenger_max_impact: float = 0.0
    cargo_detour: float = 0.0
    backtracking_ratio: float = 0.0
    station_revisits: int = 0
    normalized_cost: float = float("inf")
    violations: list[str] = field(default_factory=list)

    def __lt__(self, other: StopLevelEvaluation) -> bool:
        if self.feasible != other.feasible:
            return self.feasible
        if self.vehicle_count != other.vehicle_count:
            return self.vehicle_count < other.vehicle_count
        if abs(self.passenger_total_impact - other.passenger_total_impact) > 0.1:
            return self.passenger_total_impact < other.passenger_total_impact
        if abs(self.backtracking_ratio - other.backtracking_ratio) > 0.01:
            return self.backtracking_ratio < other.backtracking_ratio
        if abs(self.total_distance - other.total_distance) > 0.001:
            return self.total_distance < other.total_distance
        return self.total_duration < other.total_duration
