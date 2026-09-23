"""ReachabilityDecision + 不可达恢复（动态事件，不是静态拒单）。

复用 StationAccessUtil / warnPickupAlreadyPassed / VehicleLocation 语义。
未知路况 ≠ 物理不可达。
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Sequence

from .models import (
    ReachabilityDecision,
    ReachabilityReason,
    ReachabilityStatus,
    RecoveryAction,
    TripCandidate,
)


@dataclass
class LocationSnapshot:
    vehicle_id: int
    lat: float
    lon: float
    timestamp: float
    now: float
    fresh_threshold_s: float = 60.0

    @property
    def freshness_s(self) -> float:
        return max(0.0, self.now - self.timestamp)

    @property
    def is_fresh(self) -> bool:
        return self.freshness_s <= self.fresh_threshold_s

    @property
    def is_stale(self) -> bool:
        return self.freshness_s > self.fresh_threshold_s


@dataclass
class AccessFlags:
    vehicle_access: bool = True
    user_access: bool = True
    road_reachable: bool = True
    network_known: bool = True  # False = 地图服务无法确认
    already_passed: bool = False
    eta_feasible: bool = True
    detour_feasible: bool = True
    driver_shift_ok: bool = True
    cargo_capacity_ok: bool = True


def classify_reachability(
    *,
    flags: AccessFlags,
    location: LocationSnapshot | None,
    service_point: str | None,
    original_point: str | None,
    route_distance_m: float = 0.0,
    route_duration_s: float = 0.0,
) -> ReachabilityDecision:
    """分类当前位置 → 订单服务点可达性。UNKNOWN 不得当成 UNREACHABLE。"""
    base = dict(
        service_point=service_point,
        original_point=original_point,
        route_distance_m=route_distance_m,
        route_duration_s=route_duration_s,
    )
    if location is not None:
        base.update(
            last_known_location_time=location.timestamp,
            location_freshness_s=location.freshness_s,
            from_location=(location.lat, location.lon),
        )

    # 定位过期：不直接判不可达
    if location is None or location.is_stale:
        return ReachabilityDecision(
            reachable=False,
            reason_code=ReachabilityReason.LOCATION_STALE,
            confidence=0.3,
            status=ReachabilityStatus.UNKNOWN,
            suggested_action=RecoveryAction.WAIT_LOCATION,
            explanation="实时位置缺失或过期，暂不判定为不可达；保持锁定计划并等待位置刷新。",
            **base,
        )

    if not flags.network_known:
        return ReachabilityDecision(
            reachable=False,
            reason_code=ReachabilityReason.NETWORK_UNCERTAIN,
            confidence=0.2,
            status=ReachabilityStatus.UNKNOWN,
            suggested_action=RecoveryAction.WAIT_LOCATION,
            explanation="路网/地图服务暂时无法确认可达性，不作不可达判定。",
            **base,
        )

    if flags.already_passed:
        return ReachabilityDecision(
            reachable=False,
            reason_code=ReachabilityReason.ALREADY_PASSED,
            status=ReachabilityStatus.FUTURE_TRIP_REQUIRED,
            suggested_action=RecoveryAction.SAME_ROUTE_FUTURE_TRIP,
            passed_already=True,
            explanation="当前车辆已驶过订单位置，禁止掉头；转未来班次/其他线路/联运。",
            **base,
        )

    if not flags.road_reachable:
        return ReachabilityDecision(
            reachable=False,
            reason_code=ReachabilityReason.ROAD_UNREACHABLE,
            status=ReachabilityStatus.TRANSFER_REQUIRED,
            suggested_action=RecoveryAction.MULTI_LEG,
            explanation="当前道路网络无法到达订单位置，尝试替代道路/未来班次/其他线路/MultiLeg。",
            **base,
        )

    if not flags.vehicle_access:
        return ReachabilityDecision(
            reachable=False,
            reason_code=ReachabilityReason.VEHICLE_ACCESS_BLOCKED,
            status=ReachabilityStatus.NEAREST_STATION_REQUIRED,
            vehicle_access_allowed=False,
            suggested_action=RecoveryAction.NEAREST_STATION,
            explanation="公交车辆不可进入该区域，切换最近合法服务点或重新分配承运。",
            **base,
        )

    if not flags.user_access:
        return ReachabilityDecision(
            reachable=False,
            reason_code=ReachabilityReason.USER_POINT_UNSERVABLE,
            status=ReachabilityStatus.NEAREST_STATION_REQUIRED,
            user_access_allowed=False,
            suggested_action=RecoveryAction.NEAREST_STATION,
            explanation="用户原始点不适合作为车辆服务点，保留原始位置并改派合法服务点。",
            **base,
        )

    if not flags.cargo_capacity_ok:
        return ReachabilityDecision(
            reachable=False,
            reason_code=ReachabilityReason.VEHICLE_CAPACITY_UNAVAILABLE,
            status=ReachabilityStatus.FUTURE_TRIP_REQUIRED,
            suggested_action=RecoveryAction.SAME_ROUTE_FUTURE_TRIP,
            explanation="当前位置可达但车辆货运容量不足，转未来班次或其他车辆。",
            **base,
        )

    if not flags.eta_feasible:
        return ReachabilityDecision(
            reachable=False,
            reason_code=ReachabilityReason.ETA_MISSED,
            status=ReachabilityStatus.FUTURE_TRIP_REQUIRED,
            suggested_action=RecoveryAction.SAME_ROUTE_FUTURE_TRIP,
            explanation="物理可达但会破坏后续 Mandatory Stop / 班次 SLA，转未来承运。",
            **base,
        )

    if not flags.detour_feasible:
        return ReachabilityDecision(
            reachable=False,
            reason_code=ReachabilityReason.DETOUR_TOO_LARGE,
            status=ReachabilityStatus.FUTURE_TRIP_REQUIRED,
            suggested_action=RecoveryAction.SAME_ROUTE_FUTURE_TRIP,
            explanation="可达但超过当前 Trip 绕行预算，转未来班次重新获得预算。",
            **base,
        )

    if not flags.driver_shift_ok:
        return ReachabilityDecision(
            reachable=False,
            reason_code=ReachabilityReason.DRIVER_SHIFT_CONFLICT,
            status=ReachabilityStatus.FUTURE_TRIP_REQUIRED,
            suggested_action=RecoveryAction.SAME_ROUTE_FUTURE_TRIP,
            explanation="可达但司机/班次冲突，转未来调度。",
            **base,
        )

    status = (
        ReachabilityStatus.REACHABLE
        if route_distance_m <= 200
        else ReachabilityStatus.REACHABLE_WITH_DETOUR
    )
    return ReachabilityDecision(
        reachable=True,
        reason_code=ReachabilityReason.REACHABLE,
        confidence=1.0,
        status=status,
        vehicle_access_allowed=True,
        user_access_allowed=True,
        eta_feasible=True,
        detour_feasible=True,
        suggested_action=RecoveryAction.SAME_TRIP_LOCAL_REPAIR,
        explanation="当前车辆可到达合法服务点。",
        **base,
    )


# 恢复搜索顺序（不是固定永远优先当前车）
RECOVERY_ORDER: tuple[RecoveryAction, ...] = (
    RecoveryAction.SAME_TRIP_LOCAL_REPAIR,
    RecoveryAction.SAME_ROUTE_FUTURE_TRIP,
    RecoveryAction.OTHER_ROUTE,
    RecoveryAction.MULTI_LEG,
    RecoveryAction.NEAREST_STATION,
    RecoveryAction.CUSTOMER_ACTION_REQUIRED,
    RecoveryAction.MANUAL_REVIEW,
    RecoveryAction.UNSERVICEABLE,
)


def plan_recovery(
    decision: ReachabilityDecision,
    *,
    future_trips: Sequence[TripCandidate] | None = None,
    other_routes: Sequence[TripCandidate] | None = None,
    multileg: Sequence[TripCandidate] | None = None,
    nearest_station: TripCandidate | None = None,
    same_trip_repair: TripCandidate | None = None,
) -> list[TripCandidate]:
    """UNREACHABLE → Reallocation Candidate Generation（不是 ORDER FAILED）。"""
    out: list[TripCandidate] = []

    def add(c: TripCandidate | None, action: RecoveryAction):
        if c is None:
            return
        c.recovery_action = action
        c.with_efficiency()
        out.append(c)

    if decision.status in (
        ReachabilityStatus.REACHABLE,
        ReachabilityStatus.REACHABLE_WITH_DETOUR,
    ):
        add(same_trip_repair, RecoveryAction.SAME_TRIP_LOCAL_REPAIR)

    if decision.reason_code == ReachabilityReason.ALREADY_PASSED:
        for c in future_trips or []:
            add(c, RecoveryAction.SAME_ROUTE_FUTURE_TRIP)
        for c in other_routes or []:
            add(c, RecoveryAction.OTHER_ROUTE)
        for c in multileg or []:
            add(c, RecoveryAction.MULTI_LEG)
        return out

    if decision.status == ReachabilityStatus.UNKNOWN:
        # 保持计划，不生成失败单
        return out

    # 分层响应
    add(same_trip_repair, RecoveryAction.SAME_TRIP_LOCAL_REPAIR)
    for c in future_trips or []:
        add(c, RecoveryAction.SAME_ROUTE_FUTURE_TRIP)
    for c in other_routes or []:
        add(c, RecoveryAction.OTHER_ROUTE)
    for c in multileg or []:
        add(c, RecoveryAction.MULTI_LEG)
    add(nearest_station, RecoveryAction.NEAREST_STATION)

    if not out:
        # 仍不是永久失败：留给人工/不可服务
        placeholder = TripCandidate(
            source_kind="RECOVERY",
            route_id="",
            shift_id="",
            vehicle_id=0,
            feasibility=False,
            reason_code="UNSERVICEABLE",
            recovery_action=RecoveryAction.UNSERVICEABLE,
            explanation="全部恢复方案不可行，进入人工处理/不可服务。",
        )
        out.append(placeholder)
    return out


# ---------------------------------------------------------------------------
# Candidate-specific reachability (P0 / section 11)
# ---------------------------------------------------------------------------


@dataclass
class CandidateReachabilityInput:
    """Per-candidate reachability inputs (NOT a single global location check)."""

    vehicle_id: int
    route_id: str
    shift_id: str
    execution_state: "TripExecutionState"
    service_point: str | None = None
    original_point: str | None = None
    # access / road
    network_known: bool = True
    road_reachable: bool = True
    vehicle_access: bool = True
    user_access: bool = True
    already_passed: bool = False
    location_fresh: bool = True
    # timing / budget
    eta_feasible: bool = True
    detour_feasible: bool = True
    driver_shift_ok: bool = True
    cargo_capacity_ok: bool = True
    # recovery options
    has_future_trip: bool = False
    has_other_route: bool = False
    has_transfer_option: bool = False
    has_nearest_station: bool = False
    # real-road measures (formal only)
    route_distance_m: float = 0.0
    route_duration_s: float = 0.0
    marginal_detour_m: float = 0.0
    remaining_detour_m: float | None = None
    cost_is_formal: bool = False


@dataclass
class CandidateReachability:
    status: ReachabilityStatus
    reason: ReachabilityReason
    reachable: bool
    detail: str = ""

    @property
    def is_unknown(self) -> bool:
        return self.status == ReachabilityStatus.UNKNOWN


def classify_candidate_reachability(
    inp: CandidateReachabilityInput,
) -> CandidateReachability:
    """Independent per-candidate reachability. UNKNOWN must NOT be treated as UNREACHABLE."""

    def mk(status: ReachabilityStatus, reason: ReachabilityReason, detail: str = "") -> CandidateReachability:
        return CandidateReachability(
            status=status,
            reason=reason,
            reachable=status
            in (ReachabilityStatus.REACHABLE, ReachabilityStatus.REACHABLE_WITH_DETOUR),
            detail=detail,
        )

    # Unknown first: stale GPS / unknown map state never becomes a rejection.
    if not inp.location_fresh:
        return mk(
            ReachabilityStatus.UNKNOWN,
            ReachabilityReason.LOCATION_STALE,
            "vehicle location stale for this candidate",
        )
    if not inp.network_known:
        return mk(
            ReachabilityStatus.UNKNOWN,
            ReachabilityReason.NETWORK_UNCERTAIN,
            "routing network unavailable for this candidate",
        )
    if inp.already_passed:
        return mk(
            ReachabilityStatus.FUTURE_TRIP_REQUIRED,
            ReachabilityReason.ALREADY_PASSED,
            "candidate vehicle already passed the pickup corridor",
        )
    if not inp.road_reachable:
        if inp.has_transfer_option:
            return mk(
                ReachabilityStatus.TRANSFER_REQUIRED,
                ReachabilityReason.ROAD_UNREACHABLE,
                "road blocked; transfer/multileg required",
            )
        return mk(
            ReachabilityStatus.UNREACHABLE,
            ReachabilityReason.ROAD_UNREACHABLE,
            "road blocked and no transfer option",
        )
    if not inp.vehicle_access:
        return mk(
            ReachabilityStatus.NEAREST_STATION_REQUIRED,
            ReachabilityReason.VEHICLE_ACCESS_BLOCKED,
            "vehicle not allowed into the area",
        )
    if not inp.user_access:
        return mk(
            ReachabilityStatus.NEAREST_STATION_REQUIRED,
            ReachabilityReason.USER_POINT_UNSERVABLE,
            "user point is not a legal service point",
        )
    if not inp.cargo_capacity_ok:
        return mk(
            ReachabilityStatus.FUTURE_TRIP_REQUIRED,
            ReachabilityReason.VEHICLE_CAPACITY_UNAVAILABLE,
            "candidate vehicle cargo capacity exhausted",
        )
    if not inp.driver_shift_ok:
        return mk(
            ReachabilityStatus.FUTURE_TRIP_REQUIRED,
            ReachabilityReason.DRIVER_SHIFT_CONFLICT,
            "driver shift/route conflict",
        )
    if not inp.eta_feasible:
        return mk(
            ReachabilityStatus.FUTURE_TRIP_REQUIRED,
            ReachabilityReason.ETA_MISSED,
            "ETA breaks downstream mandatory stop / SLA",
        )
    if not inp.detour_feasible:
        return mk(
            ReachabilityStatus.FUTURE_TRIP_REQUIRED,
            ReachabilityReason.DETOUR_TOO_LARGE,
            "candidate detour exceeds budget",
        )
    if (
        inp.remaining_detour_m is not None
        and inp.marginal_detour_m > inp.remaining_detour_m + 1e-6
    ):
        return mk(
            ReachabilityStatus.FUTURE_TRIP_REQUIRED,
            ReachabilityReason.DETOUR_TOO_LARGE,
            "candidate detour exceeds remaining trip budget",
        )
    if inp.marginal_detour_m > 1.0:
        return mk(
            ReachabilityStatus.REACHABLE_WITH_DETOUR,
            ReachabilityReason.REACHABLE,
            "reachable with local detour",
        )
    return mk(ReachabilityStatus.REACHABLE, ReachabilityReason.REACHABLE, "reachable")
