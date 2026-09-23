"""Gap 内灵活真实道路：B→C / B→X→C / B→X→Y→C，不要求原路返回。"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Sequence

from .engine import MapRoutingEngine
from .models import RouteGeometryResult, RouteType


@dataclass
class FlexibleGapRoute:
    from_mandatory: tuple[float, float]
    to_mandatory: tuple[float, float]
    cargo_waypoints: tuple[tuple[float, float], ...]
    geometry: RouteGeometryResult
    rejoin_is_next_mandatory: bool = True

    @property
    def is_formal(self) -> bool:
        return self.geometry.is_formal


def gap_candidates(
    gap_from: tuple[float, float],
    gap_to: tuple[float, float],
    pickup: tuple[float, float] | None = None,
    delivery: tuple[float, float] | None = None,
    extra: Sequence[tuple[float, float]] = (),
) -> list[tuple[tuple[float, float], ...]]:
    """生成 Gap 内 cargo waypoint 序列（不含起终点）。"""
    mids: list[tuple[tuple[float, float], ...]] = [()]
    pts: list[tuple[float, float]] = []
    if pickup:
        pts.append(pickup)
    if delivery and delivery != pickup:
        pts.append(delivery)
    pts.extend(extra)
    if pts:
        mids.append(tuple(pts))
        if pickup:
            mids.append((pickup,))
        if delivery:
            mids.append((delivery,))
        if pickup and delivery and extra:
            mids.append((pickup, *extra, delivery))
    uniq: list[tuple[tuple[float, float], ...]] = []
    seen = set()
    for m in mids:
        if m not in seen:
            uniq.append(m)
            seen.add(m)
    return uniq


def plan_gap_routes(
    engine: MapRoutingEngine,
    gap_from: tuple[float, float],
    gap_to: tuple[float, float],
    *,
    pickup: tuple[float, float] | None = None,
    delivery: tuple[float, float] | None = None,
    extra: Sequence[tuple[float, float]] = (),
    top_k: int = 3,
) -> list[FlexibleGapRoute]:
    """Top-K 真实道路 Gap 候选；rejoin=下一 Mandatory，不要求回原入口。"""
    cands = [
        (gap_from, mid, gap_to)
        for mid in gap_candidates(gap_from, gap_to, pickup, delivery, extra)
    ]
    results = engine.select_topk(cands, route_type=RouteType.FLEXIBLE_GAP, top_k=top_k)
    out: list[FlexibleGapRoute] = []
    for i, geom in enumerate(results):
        mid = cands[i][1] if i < len(cands) else ()
        out.append(
            FlexibleGapRoute(
                from_mandatory=gap_from,
                to_mandatory=gap_to,
                cargo_waypoints=mid,
                geometry=geom,
                rejoin_is_next_mandatory=True,
            )
        )
    return out
