"""动态调度运行时接线（DISPATCH_CORE_V047）。

新订单统一走 `DynamicDispatchCoordinator`：
HTTP `/api/v1/dispatch/allocate` → 本模块 → Coordinator → DispatchPlan + Decision Trace。

批次求解（`/api/v1/plan`）仍走 HACO-CPS 1.4.1；两者共用同一套
FeasibilityEngine 硬约束口径与同一套 dispatch 成本口径。
"""

from __future__ import annotations

from typing import Any, Sequence

from pydantic import BaseModel, Field

from ..models import Station
from ..routing.engine import MapRoutingEngine
from .candidate_builder import DispatchOrder, TripView
from .coordinator import DispatchRequest, DynamicDispatchCoordinator
from .models import TripExecutionState
from .route_cost_provider import (
    Point,
    RouteCostProvider,
    default_route_cost_provider,
)


class DispatchTripPayload(BaseModel):
    routeId: str
    shiftId: str
    vehicleId: int
    driverId: int | None = None
    departureTime: float = 0.0
    executionState: str = "PLANNED"
    currentLatitude: float | None = None
    currentLongitude: float | None = None
    remainingCargoCapacity: int = 1
    remainingPassengerCapacity: int = 1
    tripDetourRemainingM: float = 0.0
    passengerImpactBudgetS: float = 0.0
    gapIndex: int = 0
    shiftEndTime: float | None = None
    alreadyPassed: bool = False
    locationFresh: bool = True
    networkKnown: bool = True
    roadReachable: bool = True
    vehicleAccess: bool = True
    userAccess: bool = True
    driverShiftOk: bool = True
    remainingPlannedStops: tuple[str, ...] = ()


class DispatchOrderPayload(BaseModel):
    orderId: str
    pickupServicePoint: str
    deliveryServicePoint: str
    originalPickup: str | None = None
    originalDelivery: str | None = None
    economicValue: float | None = Field(default=None, ge=0)
    isHighValue: bool = False
    readyTime: float = 0.0
    pickupDeadline: float | None = None
    deliveryDeadline: float | None = None
    priority: int = 0
    passengerCount: int = 0
    quantity: int = 1
    weightKg: float | None = Field(default=None, ge=0)
    volumeM3: float | None = Field(default=None, ge=0)
    serviceDurationS: float = 300.0
    # None=按 trip 剩余调度站点自动判定顺路；True/False=显式覆盖
    onPlannedRoute: bool | None = None


class DispatchAllocatePayload(BaseModel):
    order: DispatchOrderPayload
    stations: list[Station] = Field(default_factory=list)
    currentTrips: list[DispatchTripPayload] = Field(default_factory=list)
    futureTrips: list[DispatchTripPayload] = Field(default_factory=list)
    otherRouteTrips: list[DispatchTripPayload] = Field(default_factory=list)
    nearestStationTrip: DispatchTripPayload | None = None
    maxPassengerImpactSeconds: float | None = Field(default=None, ge=0)
    slaUrgency: float = 0.0
    globalHacoAvailable: bool = False
    now: float = 0.0


def _station_coords(stations: Sequence[Station]) -> dict[str, Point]:
    # Station 坐标为 GCJ-02（业务坐标系），与 RouteCostProvider 入参一致
    return {s.stationId: (s.latitude, s.longitude) for s in stations}


def _to_trip_view(payload: DispatchTripPayload, coords: dict[str, Point]) -> TripView:
    location: Point | None = None
    if payload.currentLatitude is not None and payload.currentLongitude is not None:
        location = (payload.currentLatitude, payload.currentLongitude)
    try:
        state = TripExecutionState(payload.executionState)
    except ValueError:
        state = TripExecutionState.PLANNED
    return TripView(
        route_id=payload.routeId,
        shift_id=payload.shiftId,
        vehicle_id=payload.vehicleId,
        driver_id=payload.driverId,
        departure_time=payload.departureTime,
        execution_state=state,
        current_location=location,
        remaining_cargo_capacity=payload.remainingCargoCapacity,
        remaining_passenger_capacity=payload.remainingPassengerCapacity,
        trip_detour_remaining_m=payload.tripDetourRemainingM,
        passenger_impact_budget_s=payload.passengerImpactBudgetS,
        gap_index=payload.gapIndex,
        shift_end_time=payload.shiftEndTime,
        station_coords=coords,
        already_passed=payload.alreadyPassed,
        location_fresh=payload.locationFresh,
        network_known=payload.networkKnown,
        road_reachable=payload.roadReachable,
        vehicle_access=payload.vehicleAccess,
        user_access=payload.userAccess,
        driver_shift_ok=payload.driverShiftOk,
        remaining_planned_stops=tuple(payload.remainingPlannedStops),
    )


def build_dispatch_request(payload: DispatchAllocatePayload) -> DispatchRequest:
    coords = _station_coords(payload.stations)
    order = payload.order
    return DispatchRequest(
        order=DispatchOrder(
            order_id=order.orderId,
            pickup_service_point=order.pickupServicePoint,
            delivery_service_point=order.deliveryServicePoint,
            original_pickup=order.originalPickup,
            original_delivery=order.originalDelivery,
            economic_value=order.economicValue,
            is_high_value=order.isHighValue,
            ready_time=order.readyTime,
            pickup_deadline=order.pickupDeadline,
            delivery_deadline=order.deliveryDeadline,
            priority=order.priority,
            passenger_count=order.passengerCount,
            quantity=order.quantity,
            weight_kg=order.weightKg,
            volume_m3=order.volumeM3,
            service_duration_s=order.serviceDurationS,
            on_planned_route=order.onPlannedRoute,
        ),
        current_trips=[_to_trip_view(t, coords) for t in payload.currentTrips],
        future_trips=[_to_trip_view(t, coords) for t in payload.futureTrips],
        other_route_trips=[_to_trip_view(t, coords) for t in payload.otherRouteTrips],
        nearest_station_trip=(
            None
            if payload.nearestStationTrip is None
            else _to_trip_view(payload.nearestStationTrip, coords)
        ),
        max_passenger_impact_s=payload.maxPassengerImpactSeconds,
        sla_urgency=payload.slaUrgency,
        global_haco_available=payload.globalHacoAvailable,
    )


def get_route_cost_provider(engine: MapRoutingEngine | None = None) -> RouteCostProvider:
    """生产默认 provider：Local/GraphHopper → Cache → AMap 校验。"""
    return default_route_cost_provider(engine)


def allocate_dispatch(
    payload: DispatchAllocatePayload,
    *,
    route_provider: RouteCostProvider | None = None,
) -> dict[str, Any]:
    """运行时入口：返回 DispatchPlan（含 Decision Trace）。"""
    coordinator = DynamicDispatchCoordinator(
        route_provider=route_provider if route_provider is not None else get_route_cost_provider(),
        now=payload.now,
        max_passenger_impact_s=payload.maxPassengerImpactSeconds,
    )
    plan = coordinator.plan(build_dispatch_request(payload))
    out = plan.as_dict()
    out["globalHacoTriggerCount"] = coordinator.global_haco_trigger_count
    return out


__all__ = [
    "DispatchTripPayload",
    "DispatchOrderPayload",
    "DispatchAllocatePayload",
    "build_dispatch_request",
    "get_route_cost_provider",
    "allocate_dispatch",
]
