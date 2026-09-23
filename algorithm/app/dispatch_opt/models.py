"""动态调度算法内部结构（非数据库表）。

与现有类边界：
- CargoOpportunitySlot ≈ RouteGenome gap 窗口 + Shift/资源余量，不是订单
- TripCandidate ≈ MultiLeg.Candidate 的上层聚合，不替换 MultiLegPlanner
- TripExecutionState 复用 Driver execution / Shift 语义
- ReachabilityDecision 包装 StationAccessUtil / VehicleLocation 结论
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Any


class TripExecutionState(str, Enum):
    PLANNED = "PLANNED"
    READY = "READY"
    DEPARTED = "DEPARTED"
    IN_PROGRESS = "IN_PROGRESS"
    COMPLETED = "COMPLETED"
    FAILED = "FAILED"


class ReachabilityReason(str, Enum):
    REACHABLE = "REACHABLE"
    ROAD_UNREACHABLE = "ROAD_UNREACHABLE"
    VEHICLE_ACCESS_BLOCKED = "VEHICLE_ACCESS_BLOCKED"
    USER_POINT_UNSERVABLE = "USER_POINT_UNSERVABLE"
    ALREADY_PASSED = "ALREADY_PASSED"
    ETA_MISSED = "ETA_MISSED"
    DETOUR_TOO_LARGE = "DETOUR_TOO_LARGE"
    DRIVER_SHIFT_CONFLICT = "DRIVER_SHIFT_CONFLICT"
    VEHICLE_CAPACITY_UNAVAILABLE = "VEHICLE_CAPACITY_UNAVAILABLE"
    LOCATION_STALE = "LOCATION_STALE"
    NETWORK_UNCERTAIN = "NETWORK_UNCERTAIN"


class ReachabilityStatus(str, Enum):
    REACHABLE = "REACHABLE"
    REACHABLE_WITH_DETOUR = "REACHABLE_WITH_DETOUR"
    NEAREST_STATION_REQUIRED = "NEAREST_STATION_REQUIRED"
    TRANSFER_REQUIRED = "TRANSFER_REQUIRED"
    FUTURE_TRIP_REQUIRED = "FUTURE_TRIP_REQUIRED"
    UNREACHABLE = "UNREACHABLE"
    UNKNOWN = "UNKNOWN"


class RecoveryAction(str, Enum):
    SAME_TRIP_LOCAL_REPAIR = "SAME_TRIP_LOCAL_REPAIR"
    SAME_ROUTE_FUTURE_TRIP = "SAME_ROUTE_FUTURE_TRIP"
    OTHER_ROUTE = "OTHER_ROUTE"
    MULTI_LEG = "MULTI_LEG"
    NEAREST_STATION = "NEAREST_STATION"
    CUSTOMER_ACTION_REQUIRED = "CUSTOMER_ACTION_REQUIRED"
    MANUAL_REVIEW = "MANUAL_REVIEW"
    UNSERVICEABLE = "UNSERVICEABLE"
    WAIT_LOCATION = "WAIT_LOCATION"


class HandoverKind(str, Enum):
    SAME_STATION = "SAME_STATION"
    NEARBY_STATION = "NEARBY_STATION"


class DispatchLevel(str, Enum):
    """新订单分级响应（LEVEL 0 最轻，LEVEL 4 最重）。"""
    LEVEL_0_FAST_INSERT = "LEVEL_0_FAST_INSERT"
    LEVEL_1_NEXT_TRIP = "LEVEL_1_NEXT_TRIP"
    LEVEL_2_OTHER_OR_MULTILEG = "LEVEL_2_OTHER_OR_MULTILEG"
    LEVEL_3_LOCAL_REPAIR = "LEVEL_3_LOCAL_REPAIR"
    LEVEL_4_GLOBAL_HACO = "LEVEL_4_GLOBAL_HACO"
    LEVEL_5_HOLD = "LEVEL_5_HOLD"


@dataclass
class CargoOpportunitySlot:
    """算法候选窗口：骨架 Gap 内可做局部货运绕行的机会。

    不是业务订单表，只是插入机会描述。
    """
    vehicle_id: int
    shift_id: str
    route_id: str
    trip_id: str
    from_mandatory_station: str
    to_mandatory_station: str
    gap_index: int
    available_start_time: float
    required_rejoin_station: str
    remaining_cargo_capacity: int
    remaining_passenger_margin: int
    detour_budget_m: float
    passenger_impact_budget_s: float
    current_execution_state: TripExecutionState
    trip_detour_consumed_m: float = 0.0

    @property
    def trip_detour_remaining_m(self) -> float:
        return max(0.0, self.detour_budget_m - self.trip_detour_consumed_m)


@dataclass
class MarginalCostBreakdown:
    """新增订单后的增量成本分解（可追溯，禁止神秘大权重）。"""
    distance_cost: float = 0.0
    time_cost: float = 0.0
    passenger_impact_cost: float = 0.0
    driver_cost: float = 0.0
    vehicle_cost: float = 0.0
    handover_cost: float = 0.0
    waiting_cost: float = 0.0
    risk_cost: float = 0.0
    # 原始量（解释用）
    delta_distance_m: float = 0.0
    delta_duration_s: float = 0.0
    delta_passenger_impact_s: float = 0.0
    delta_cargo_usage: int = 0
    delta_driver_time_s: float = 0.0
    delta_vehicle_time_s: float = 0.0
    delta_handover_count: int = 0
    delta_waiting_s: float = 0.0
    delta_trip_deviation_m: float = 0.0
    delta_delay_risk: float = 0.0

    @property
    def total_incremental_cost(self) -> float:
        return (
            self.distance_cost
            + self.time_cost
            + self.passenger_impact_cost
            + self.driver_cost
            + self.vehicle_cost
            + self.handover_cost
            + self.waiting_cost
            + self.risk_cost
        )

    def as_dict(self) -> dict[str, float]:
        return {
            "distance_cost": self.distance_cost,
            "time_cost": self.time_cost,
            "passenger_impact_cost": self.passenger_impact_cost,
            "driver_cost": self.driver_cost,
            "vehicle_cost": self.vehicle_cost,
            "handover_cost": self.handover_cost,
            "waiting_cost": self.waiting_cost,
            "risk_cost": self.risk_cost,
            "total_incremental_cost": self.total_incremental_cost,
            "delta_distance_m": self.delta_distance_m,
            "delta_duration_s": self.delta_duration_s,
            "delta_passenger_impact_s": self.delta_passenger_impact_s,
            "delta_waiting_s": self.delta_waiting_s,
        }


@dataclass
class HandoverCostBreakdown:
    transfer_distance_m: float = 0.0
    handling_time_s: float = 0.0
    dwell_time_s: float = 0.0
    waiting_time_s: float = 0.0
    operational_cost: float = 0.0
    failure_risk: float = 0.0

    @property
    def total(self) -> float:
        return (
            self.operational_cost
            + self.waiting_time_s / 60.0
            + self.handling_time_s / 60.0
            + self.dwell_time_s / 60.0
            + self.failure_risk
        )


@dataclass
class HandoverDecision:
    feasible: bool
    kind: HandoverKind | None = None
    reason_code: str | None = None
    breakdown: HandoverCostBreakdown = field(default_factory=HandoverCostBreakdown)


@dataclass
class TripCandidate:
    """上层候选（可包装 MultiLeg.Candidate，不替换它）。"""
    source_kind: str  # CURRENT_TRIP / NEXT_TRIP / LATER_TRIP / OTHER_ROUTE / MULTI_LEG
    route_id: str
    shift_id: str
    vehicle_id: int
    driver_id: int | None = None
    departure_time: float = 0.0
    estimated_pickup_time: float = 0.0
    estimated_delivery_time: float = 0.0
    waiting_time_s: float = 0.0
    detour_distance_m: float = 0.0
    detour_duration_s: float = 0.0
    passenger_impact_s: float = 0.0
    handover_count: int = 0
    handover_cost: float = 0.0
    incremental_cost: float = 0.0
    economic_value: float | None = None
    efficiency: float | None = None
    feasibility: bool = True
    reason_code: str = "OK"
    explanation: str = ""
    cost_breakdown: MarginalCostBreakdown | None = None
    legs: tuple[str, ...] = ()
    gap_index: int | None = None
    recovery_action: RecoveryAction | None = None
    raw: Any = None

    def with_efficiency(self) -> "TripCandidate":
        if self.economic_value is None or self.incremental_cost <= 0:
            self.efficiency = None
        else:
            self.efficiency = self.economic_value / self.incremental_cost
        return self


@dataclass
class ReachabilityDecision:
    reachable: bool
    reason_code: ReachabilityReason
    confidence: float = 1.0
    status: ReachabilityStatus = ReachabilityStatus.UNKNOWN
    from_location: tuple[float, float] | None = None
    target_location: tuple[float, float] | None = None
    service_point: str | None = None
    original_point: str | None = None
    route_distance_m: float = 0.0
    route_duration_s: float = 0.0
    last_known_location_time: float | None = None
    location_freshness_s: float | None = None
    vehicle_access_allowed: bool = False
    user_access_allowed: bool = False
    passed_already: bool = False
    eta_feasible: bool = True
    detour_feasible: bool = True
    suggested_action: RecoveryAction = RecoveryAction.MANUAL_REVIEW
    explanation: str = ""
