"""Gap 内局部绕行与主线 Rejoin 计算（DISPATCH_CORE_V047）。

骨架 A→B→C→D→E 只允许在 Gap 内插入货运，绕行后必须回到原 mandatory 序列。
禁止跳过 Mandatory Passenger Stop，禁止产生 `B→X→Y→B→C` 回折。

正式口径：

- `baseline`：`B → C`（真实道路）
- `insert`  ：`B → X → Y → C`（真实道路，pickup 在前 delivery 在后）
- `delta = insert - baseline`

直线（Haversine）只允许作为 lower bound / prefilter；拿不到正式真实道路时返回
`status=UNKNOWN`，不得拿直线距离冒充正式 delta。
"""

from __future__ import annotations

import math
from dataclasses import dataclass
from typing import TYPE_CHECKING, Sequence

if TYPE_CHECKING:  # 避免运行期循环导入
    from .route_cost_provider import RouteCostProvider

# 直线下界速度：仅用于 lower bound，不进入正式时长
STRAIGHT_LINE_SPEED_M_S = 25.0 / 3.6


@dataclass(frozen=True)
class GapDetourResult:
    ok: bool
    reason_code: str | None
    original_distance_m: float
    detour_distance_m: float
    delta_distance_m: float
    original_duration_s: float
    detour_duration_s: float
    delta_duration_s: float
    passenger_impact_s: float
    rejoin_station: str
    route_return_feasible: bool
    mandatory_sequence_preserved: bool
    # V047 新增（默认值保证旧调用兼容）
    formal: bool = False
    status: str = "FALLBACK"
    source: str = "haversine_lower_bound"
    waypoints: tuple = ()
    reason_detail: str = ""


def _haversine_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    r = 6_371_000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = math.radians(lat2 - lat1)
    dl = math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return r * 2 * math.asin(math.sqrt(a))


def _duration_s(distance_m: float, speed_m_s: float = STRAIGHT_LINE_SPEED_M_S) -> float:
    return distance_m / max(speed_m_s, 0.1)


def can_detour_within_gap(
    mandatory_stops: Sequence[str],
    gap_index: int,
    planned_stops_after: Sequence[str],
) -> bool:
    """绕行后 planned_stops_after 必须仍按顺序包含全部 mandatory stops。"""
    it = iter(planned_stops_after)
    return all(s in it for s in mandatory_stops)


def calculate_gap_detour(
    *,
    from_station: str,
    to_station: str,
    via_pickup: str,
    via_delivery: str,
    rejoin_station: str,
    coords: dict[str, tuple[float, float]],
    mandatory_stops: Sequence[str],
    planned_stops_after: Sequence[str],
    passenger_count: int = 0,
    max_detour_m: float | None = None,
    max_passenger_impact_s: float | None = None,
    trip_detour_remaining_m: float | None = None,
    speed_m_s: float = STRAIGHT_LINE_SPEED_M_S,
    route_provider: "RouteCostProvider | None" = None,
) -> GapDetourResult:
    """计算 Gap 内局部绕行：`from → via_pickup → via_delivery → rejoin(to)`。

    传入 `route_provider` 时走正式真实道路；否则退回直线下界
    （`status=FALLBACK`、`formal=False`），仅供 prefilter / 单测使用。
    """

    def coord(sid: str) -> tuple[float, float] | None:
        return coords.get(sid)

    c_from = coord(from_station)
    c_to = coord(rejoin_station or to_station)
    c_pu = coord(via_pickup)
    c_de = coord(via_delivery)
    if not all([c_from, c_to, c_pu, c_de]):
        return GapDetourResult(
            False, "MISSING_COORD", 0, 0, 0, 0, 0, 0, 0,
            rejoin_station or to_station, False, False,
            formal=False, status="UNKNOWN", source="none",
            reason_detail="missing coordinate for gap or waypoint",
        )

    seq_ok = can_detour_within_gap(mandatory_stops, 0, planned_stops_after)
    rejoin_ok = rejoin_station in mandatory_stops or rejoin_station == to_station

    # ── 正式真实道路路径 ──
    if route_provider is not None and route_provider.is_formal():
        pair = route_provider.route_gap(c_from, c_to, pickup=c_pu, delivery=c_de)
        if not pair.both_formal:
            # 拿不到正式道路：返回 UNKNOWN，绝不冒充正式成本
            return GapDetourResult(
                ok=False,
                reason_code="ROUTE_UNKNOWN",
                original_distance_m=pair.baseline.distance_m,
                detour_distance_m=pair.insert.distance_m,
                delta_distance_m=0.0,
                original_duration_s=pair.baseline.duration_s,
                detour_duration_s=pair.insert.duration_s,
                delta_duration_s=0.0,
                passenger_impact_s=0.0,
                rejoin_station=rejoin_station or to_station,
                route_return_feasible=False,
                mandatory_sequence_preserved=seq_ok,
                formal=False,
                status="UNKNOWN",
                source=f"{pair.baseline.provider}/{pair.insert.provider}",
                waypoints=tuple(pair.waypoints),
                reason_detail=(
                    pair.baseline.reason_code or pair.insert.reason_code or "ROUTE_UNKNOWN"
                ),
            )

        orig_d = pair.baseline.distance_m
        orig_t = pair.baseline.duration_s
        detour_d = pair.insert.distance_m
        detour_t = pair.insert.duration_s
        delta_d = max(0.0, detour_d - orig_d)
        delta_t = max(0.0, detour_t - orig_t)
        pax_impact = delta_t * max(0, passenger_count)

        reason = _gap_reason(
            seq_ok, rejoin_ok,
            *_check_budget(
                delta_d, pax_impact,
                max_detour_m, trip_detour_remaining_m, max_passenger_impact_s,
            ),
        )
        return GapDetourResult(
            ok=reason is None,
            reason_code=reason,
            original_distance_m=orig_d,
            detour_distance_m=detour_d,
            delta_distance_m=delta_d,
            original_duration_s=orig_t,
            detour_duration_s=detour_t,
            delta_duration_s=delta_t,
            passenger_impact_s=pax_impact,
            rejoin_station=rejoin_station or to_station,
            route_return_feasible=rejoin_ok and seq_ok,
            mandatory_sequence_preserved=seq_ok,
            formal=True,
            status="FORMAL",
            source=pair.insert.provider,
            waypoints=tuple(pair.waypoints),
        )

    # ── 直线下界路径（仅 prefilter / 单测；非正式成本） ──
    orig_d = _haversine_m(c_from[0], c_from[1], c_to[0], c_to[1])
    detour_d = (
        _haversine_m(c_from[0], c_from[1], c_pu[0], c_pu[1])
        + _haversine_m(c_pu[0], c_pu[1], c_de[0], c_de[1])
        + _haversine_m(c_de[0], c_de[1], c_to[0], c_to[1])
    )
    delta_d = max(0.0, detour_d - orig_d)
    orig_t = _duration_s(orig_d, speed_m_s)
    detour_t = _duration_s(detour_d, speed_m_s)
    delta_t = max(0.0, detour_t - orig_t)
    # 代理指标：绕行时间 × 车上乘客（与 evaluator 口径一致，标记为 PROXY）
    pax_impact = delta_t * max(0, passenger_count)

    reason = _gap_reason(
        seq_ok, rejoin_ok,
        *_check_budget(
            delta_d, pax_impact,
            max_detour_m, trip_detour_remaining_m, max_passenger_impact_s,
        ),
    )
    return GapDetourResult(
        ok=reason is None,
        reason_code=reason,
        original_distance_m=orig_d,
        detour_distance_m=detour_d,
        delta_distance_m=delta_d,
        original_duration_s=orig_t,
        detour_duration_s=detour_t,
        delta_duration_s=delta_t,
        passenger_impact_s=pax_impact,
        rejoin_station=rejoin_station or to_station,
        route_return_feasible=rejoin_ok and seq_ok,
        mandatory_sequence_preserved=seq_ok,
        formal=False,
        status="FALLBACK",
        source="haversine_lower_bound",
    )


def _gap_reason(
    seq_ok: bool,
    rejoin_ok: bool,
    budget_ok: bool,
    budget_reason: str,
) -> str | None:
    if not seq_ok:
        return "MANDATORY_STOP_VIOLATION"
    if not rejoin_ok:
        return "REJOIN_INVALID"
    if not budget_ok:
        return budget_reason
    return None


def _check_budget(
    delta_d: float,
    pax_impact: float,
    max_detour_m: float | None,
    trip_detour_remaining_m: float | None,
    max_passenger_impact_s: float | None,
) -> tuple[bool, str]:
    if max_detour_m is not None and delta_d > max_detour_m:
        return False, "DETOUR_BUDGET_EXCEEDED"
    if trip_detour_remaining_m is not None and delta_d > trip_detour_remaining_m:
        # 与既有产品契约保持一致（trip 预算与距离阈值共用该 reason）
        return False, "DETOUR_BUDGET_EXCEEDED"
    if max_passenger_impact_s is not None and pax_impact > max_passenger_impact_s:
        return False, "PASSENGER_IMPACT_EXCEEDED"
    return True, ""


__all__ = [
    "GapDetourResult",
    "STRAIGHT_LINE_SPEED_M_S",
    "calculate_gap_detour",
    "can_detour_within_gap",
]
