"""Gap 内局部绕行与主线 Rejoin 计算（DISPATCH_CORE_V047）。

骨架 A→B→C→D→E 只允许在 Gap 内插入货运。产品模型（站间真实道路，**不是跳站**）：

- 「不回固定公交路线」只发生在**站与站之间**（如 A→B 间隙）：可用真实道路
  绕去订单点服务，不必贴着公交走廊原路折返；
- **下一站（本 Gap 的 `to_station`，如 B）必到**。禁止因为订单点离 C 更近，
  就抄近路跳过 B 直去 C（`NEXT_MANDATORY_SKIPPED`）；
- 间隙内可多单：订单1 送货 + 订单2 取货送另一站 —— 本 Gap 只做本地取送，
  订单2 若送往更远站（C/D…），属于**跨 Gap 配送**，B 必到之后才送，不得把
  更远站塞进本 Gap 路径而跳过 B；
- 禁止产生 `B→X→Y→B→C` 回折。

正式口径：

- `baseline`：`B → C`（真实道路）
- `insert`  ：`B → X → Y → C`（真实道路，pickup 在前 delivery 在后，**终点=to_station**）
  多单链式：`B → X1 → Y1 → X2 → Y2 → C`
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
class ShortcutDecision:
    """站间真实道路（不贴公交走廊折返）：是否走「捷径」去**下一站**。

    注意：捷径终点永远是本 Gap 的 `to_station`（下一站，如 B）。
    **不是**抄近路跳过下一站去更远站（那是 `NEXT_MANDATORY_SKIPPED`）。
    """

    prefer_shortcut: bool
    reason_code: str
    value_to_cost_ratio: float | None = None
    explanation: str = ""


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
    # 链式 / 捷径（默认值保证旧调用兼容）
    order_count: int = 1
    uses_shortcut_to_next_stop: bool = False
    returned_to_fixed_route: bool = True
    shortcut: ShortcutDecision | None = None


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


def prefer_shortcut_to_next_stop(
    *,
    economic_value: float | None = None,
    delta_cost: float = 0.0,
    min_value_cost_ratio: float = 1.0,
    route_formal: bool = True,
    delta_distance_m: float = 0.0,
    max_shortcut_detour_m: float | None = None,
) -> ShortcutDecision:
    """站间真实道路去**下一站**且综合性价比高 → 不必贴公交走廊折返。

    「捷径」= 订单点与下一站之间走真实道路（A→B 间隙内），**下一站必到**；
    不是跳过下一站去更远站。无经济价值时按「有正式道路即可捷径」放行
    （`FEASIBILITY_ONLY`），不伪造价格。
    """
    if not route_formal:
        return ShortcutDecision(
            False, "SHORTCUT_ROUTE_NOT_FORMAL",
            None,
            "拿不到正式真实道路，不能走捷径去下一站。",
        )
    if max_shortcut_detour_m is not None and delta_distance_m > max_shortcut_detour_m:
        return ShortcutDecision(
            False, "SHORTCUT_DETOUR_TOO_LARGE",
            None,
            "捷径绕行超过上限，回固定公交路线。",
        )
    if economic_value is None:
        return ShortcutDecision(
            True, "SHORTCUT_FEASIBILITY_ONLY",
            None,
            "未提供经济价值；有正式真实道路即可捷径去下一站。",
        )
    if delta_cost <= 0:
        return ShortcutDecision(
            True, "SHORTCUT_ZERO_COST",
            None,
            "捷径增量成本为 0，直接不回固定公交路线。",
        )
    ratio = economic_value / delta_cost
    if ratio >= min_value_cost_ratio:
        return ShortcutDecision(
            True, "SHORTCUT_COST_EFFECTIVE",
            ratio,
            f"性价比 {ratio:.2f} ≥ {min_value_cost_ratio:.2f}，走真实道路捷径去下一站。",
        )
    return ShortcutDecision(
        False, "SHORTCUT_NOT_COST_EFFECTIVE",
        ratio,
        f"性价比 {ratio:.2f} < {min_value_cost_ratio:.2f}，回到固定公交路线。",
    )


def _mandatory_index(sid: str, mandatory_stops: Sequence[str]) -> int | None:
    try:
        return list(mandatory_stops).index(sid)
    except ValueError:
        return None


def _is_mandatory_after(
    sid: str, to_station: str, mandatory_stops: Sequence[str]
) -> bool:
    """sid 是否为骨架上位于 to_station **之后** 的 Mandatory（更远站）。"""
    i_to = _mandatory_index(to_station, mandatory_stops)
    i_sid = _mandatory_index(sid, mandatory_stops)
    if i_to is None or i_sid is None:
        return False
    return i_sid > i_to


def _flatten_order_stops(
    via_pickup: str | None,
    via_delivery: str | None,
    extra_orders: Sequence[tuple[str, str | None]],
) -> list[tuple[str, str | None]]:
    """归一化订单链：首单 (via_pickup, via_delivery) + 后续 extra_orders。

    `delivery` 允许为 `None`/空：表示本 Gap 只取货（跨 Gap 配送，送站更远）。
    """
    pairs: list[tuple[str, str | None]] = []
    if via_pickup is not None:
        pairs.append((via_pickup, via_delivery or None))
    for item in extra_orders:
        pu, de = item[0], item[1] if len(item) > 1 else None
        pairs.append((pu, de or None))
    return pairs


def _visit_order_names(
    pairs: Sequence[tuple[str, str | None]],
    *,
    to_station: str,
    mandatory_stops: Sequence[str],
) -> list[str]:
    """本 Gap 内实际访问的订单点（pickup 前 delivery 后，去相邻重复）。

    跨 Gap 配送：delivery 是 to_station 之后的 Mandatory 时**不进本 Gap 路径**
    （B 必到之后才送），只保留 pickup。
    """
    names: list[str] = []
    for pu, de in pairs:
        if pu and not _is_mandatory_after(pu, to_station, mandatory_stops):
            if not names or names[-1] != pu:
                names.append(pu)
        if de and de != pu and not _is_mandatory_after(de, to_station, mandatory_stops):
            if not names or names[-1] != de:
                names.append(de)
    return names


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
    extra_orders: Sequence[tuple[str, str | None]] = (),
    economic_value: float | None = None,
    min_shortcut_ratio: float | None = None,
    max_shortcut_detour_m: float | None = None,
) -> GapDetourResult:
    """计算 Gap 内局部绕行：`from → via… → to_station`（**下一站必到**）。

    多单（以此类推）：`extra_orders=((pu2, de2), …)` 时路径为
    `from → pu1 → de1 → pu2 → de2 → … → to_station`，各单 pickup 前、delivery 后。

    硬约束：
    - 路径终点固定为本 Gap 的 `to_station`（下一站，如 B），**禁止**跳过它直去更远站 C；
    - `rejoin_station` 若指向 to_station 之后的 Mandatory → `NEXT_MANDATORY_SKIPPED`；
    - 跨 Gap 配送（delivery 为更远 Mandatory）本 Gap 只取货，不把更远站塞进路径。

    传入 `route_provider` 时走正式真实道路；否则退回直线下界
    （`status=FALLBACK`、`formal=False`），仅供 prefilter / 单测使用。

    传入 `min_shortcut_ratio` 时启用「站间真实道路捷径」性价比门：
    不达标返回 `SHORTCUT_NOT_COST_EFFECTIVE`（回公交走廊折返语义，不是跳站）。
    """

    def coord(sid: str) -> tuple[float, float] | None:
        return coords.get(sid)

    pairs = _flatten_order_stops(via_pickup, via_delivery, extra_orders)
    order_count = len(pairs) if pairs else 1
    visit_names = _visit_order_names(
        pairs, to_station=to_station, mandatory_stops=mandatory_stops
    ) if pairs else [
        n for n in (via_pickup, via_delivery)
        if n and not _is_mandatory_after(n, to_station, mandatory_stops)
    ]

    # ── 硬约束：下一站（to_station）必到 ──
    if rejoin_station and rejoin_station != to_station:
        if _is_mandatory_after(rejoin_station, to_station, mandatory_stops):
            return GapDetourResult(
                False, "NEXT_MANDATORY_SKIPPED", 0, 0, 0, 0, 0, 0, 0,
                to_station, False, False,
                formal=False, status="REJECTED", source="policy",
                reason_detail=(
                    f"rejoin={rejoin_station} 在下一站 {to_station} 之后，"
                    f"会跳过 {to_station} 直去更远站，禁止。"
                ),
                order_count=order_count,
            )

    # 路径终点永远是下一站 to_station（B 必到）
    c_from = coord(from_station)
    c_to = coord(to_station)
    visit_coords = [coord(name) for name in visit_names]
    if c_from is None or c_to is None or any(c is None for c in visit_coords):
        return GapDetourResult(
            False, "MISSING_COORD", 0, 0, 0, 0, 0, 0, 0,
            to_station, False, False,
            formal=False, status="UNKNOWN", source="none",
            reason_detail="missing coordinate for gap or waypoint",
            order_count=order_count,
        )

    seq_ok = can_detour_within_gap(mandatory_stops, 0, planned_stops_after)
    # 终点固定为 to_station：rejoin 有效当且仅当指向 to_station（或未指定）
    rejoin_ok = rejoin_station in (None, "", to_station)

    # ── 正式真实道路路径 ──
    if route_provider is not None and route_provider.is_formal():
        # 多单：pickup=首取、delivery=末送、extra=中间点；终点仍为 to_station
        #（route_gap 的 gap_to 已是 to_station）
        if len(visit_coords) >= 2:
            mid_pts = visit_coords[1:-1]
            first = visit_coords[0]
            last = visit_coords[-1]
        elif len(visit_coords) == 1:
            first = visit_coords[0]
            last = visit_coords[0]
            mid_pts = []
        else:
            # 本 Gap 无本地取送点（例如跨 Gap 只取货点被过滤后为空）
            first = None
            last = None
            mid_pts = []
        pair = route_provider.route_gap(
            c_from, c_to, pickup=first, delivery=last, extra=mid_pts
        )
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
                rejoin_station=to_station,
                route_return_feasible=False,
                mandatory_sequence_preserved=seq_ok,
                formal=False,
                status="UNKNOWN",
                source=f"{pair.baseline.provider}/{pair.insert.provider}",
                waypoints=tuple(pair.waypoints),
                reason_detail=(
                    pair.baseline.reason_code or pair.insert.reason_code or "ROUTE_UNKNOWN"
                ),
                order_count=order_count,
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
        shortcut = prefer_shortcut_to_next_stop(
            economic_value=economic_value,
            delta_cost=0.0 if reason is not None else max(0.0, delta_t),
            min_value_cost_ratio=min_shortcut_ratio or 1.0,
            route_formal=True,
            delta_distance_m=delta_d,
            max_shortcut_detour_m=max_shortcut_detour_m,
        )
        if reason is None and min_shortcut_ratio is not None and not shortcut.prefer_shortcut:
            reason = shortcut.reason_code

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
            rejoin_station=to_station,
            route_return_feasible=rejoin_ok and seq_ok,
            mandatory_sequence_preserved=seq_ok,
            formal=True,
            status="FORMAL",
            source=pair.insert.provider,
            waypoints=tuple(pair.waypoints),
            order_count=order_count,
            uses_shortcut_to_next_stop=shortcut.prefer_shortcut and reason is None,
            returned_to_fixed_route=not (shortcut.prefer_shortcut and reason is None),
            shortcut=shortcut,
        )

    # ── 直线下界路径（仅 prefilter / 单测；非正式成本） ──
    flat_pts = [c_from, *visit_coords, c_to]
    orig_d = _haversine_m(c_from[0], c_from[1], c_to[0], c_to[1])
    detour_d = sum(
        _haversine_m(flat_pts[i][0], flat_pts[i][1], flat_pts[i + 1][0], flat_pts[i + 1][1])
        for i in range(len(flat_pts) - 1)
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
        rejoin_station=to_station,
        route_return_feasible=rejoin_ok and seq_ok,
        mandatory_sequence_preserved=seq_ok,
        formal=False,
        status="FALLBACK",
        source="haversine_lower_bound",
        order_count=order_count,
        uses_shortcut_to_next_stop=False,
        returned_to_fixed_route=True,
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


@dataclass(frozen=True)
class ChainedGapSegment:
    """链式一段：骨架 from→to 间隙内可插多单；**终点固定为 to（下一站）**。

    不得把更远站当 rejoin 跳过 to；跨站配送只在本段取货。
    """

    from_station: str
    to_station: str
    via_orders: tuple[tuple[str, str | None], ...] = ()
    rejoin_station: str | None = None


@dataclass
class ChainedGapDetourResult:
    segments: list[GapDetourResult]
    all_ok: bool
    uses_shortcut_chain: bool
    total_delta_distance_m: float
    total_delta_duration_s: float
    reason_code: str | None = None
    explanation: str = ""


def calculate_chained_gap_detour(
    *,
    segments: Sequence[ChainedGapSegment],
    coords: dict[str, tuple[float, float]],
    mandatory_stops: Sequence[str],
    planned_stops_after: Sequence[str],
    passenger_count: int = 0,
    max_detour_m: float | None = None,
    max_passenger_impact_s: float | None = None,
    trip_detour_remaining_m: float | None = None,
    speed_m_s: float = STRAIGHT_LINE_SPEED_M_S,
    route_provider: "RouteCostProvider | None" = None,
    economic_value: float | None = None,
    min_shortcut_ratio: float | None = None,
    max_shortcut_detour_m: float | None = None,
) -> ChainedGapDetourResult:
    """以此类推：多段 Gap 链——站间真实道路服务订单，**每段终点=下一站**。

    例：A→B 间订单1 送货 + 订单2 取货送另一站 ⇒ 路径 `A → 送货点 → 取货点 → B`；
    订单2 的送站是更远 Mandatory 时属跨段配送，**不得**在 A→B 段把路径指向 C 跳过 B。

    每段独立算 delta（与 `test_gap_service_time_split` 口径一致，不 double count）；
    任一段失败则整链 `all_ok=False`，并给出该段 reason。
    """
    results: list[GapDetourResult] = []
    total_d = 0.0
    total_t = 0.0
    all_shortcut = bool(segments)

    for seg in segments:
        # 硬约束：本段终点必须是 seg.to_station，禁止 rejoin 更远站跳站
        if seg.rejoin_station and seg.rejoin_station != seg.to_station:
            if _is_mandatory_after(seg.rejoin_station, seg.to_station, mandatory_stops):
                results.append(
                    GapDetourResult(
                        False, "NEXT_MANDATORY_SKIPPED", 0, 0, 0, 0, 0, 0, 0,
                        seg.to_station, False, False,
                        formal=False, status="REJECTED", source="policy",
                        reason_detail=(
                            f"segment rejoin={seg.rejoin_station} 会跳过 {seg.to_station}"
                        ),
                    )
                )
                all_shortcut = False
                break

        orders = list(seg.via_orders)
        if not orders:
            results.append(
                GapDetourResult(
                    False, "NO_ORDER_IN_SEGMENT", 0, 0, 0, 0, 0, 0, 0,
                    seg.to_station, False, False,
                    formal=False, status="UNKNOWN", source="none",
                    reason_detail="chained segment has no order",
                )
            )
            all_shortcut = False
            break
        first_pu, first_de = orders[0][0], (orders[0][1] if len(orders[0]) > 1 else None)
        res = calculate_gap_detour(
            from_station=seg.from_station,
            to_station=seg.to_station,
            via_pickup=first_pu,
            via_delivery=first_de,
            rejoin_station=seg.rejoin_station or seg.to_station,
            coords=coords,
            mandatory_stops=mandatory_stops,
            planned_stops_after=planned_stops_after,
            passenger_count=passenger_count,
            max_detour_m=max_detour_m,
            max_passenger_impact_s=max_passenger_impact_s,
            trip_detour_remaining_m=trip_detour_remaining_m,
            speed_m_s=speed_m_s,
            route_provider=route_provider,
            extra_orders=tuple(
                (item[0], item[1] if len(item) > 1 else None) for item in orders[1:]
            ),
            economic_value=economic_value,
            min_shortcut_ratio=min_shortcut_ratio,
            max_shortcut_detour_m=max_shortcut_detour_m,
        )
        results.append(res)
        total_d += res.delta_distance_m
        total_t += res.delta_duration_s
        if not res.ok:
            all_shortcut = False
            break
        if not res.uses_shortcut_to_next_stop:
            all_shortcut = False

    ok = bool(results) and all(r.ok for r in results)
    reason = None if ok else (results[-1].reason_code if results else "EMPTY_CHAIN")
    return ChainedGapDetourResult(
        segments=results,
        all_ok=ok,
        uses_shortcut_chain=all_shortcut and ok,
        total_delta_distance_m=total_d,
        total_delta_duration_s=total_t,
        reason_code=reason,
        explanation=(
            "链式成立：各段站间真实道路服务订单，每段终点均为下一站，未跳站。"
            if all_shortcut and ok
            else (
                "链式未全程成立，相关段回公交走廊或转未来调度（绝不跳过下一站）。"
                if results
                else "空链。"
            )
        ),
    )


__all__ = [
    "GapDetourResult",
    "ShortcutDecision",
    "ChainedGapSegment",
    "ChainedGapDetourResult",
    "STRAIGHT_LINE_SPEED_M_S",
    "calculate_gap_detour",
    "calculate_chained_gap_detour",
    "can_detour_within_gap",
    "prefer_shortcut_to_next_stop",
]
