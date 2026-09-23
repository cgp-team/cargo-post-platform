"""RoutingTeacherSelector：GraphHopper CH/LM 优先，LocalGraph 为 fallback。

复用开源 GraphHopper 作为真实道路 Teacher；本地 RoadGraph Dijkstra
仅在 GH 不可用时兜底，且结果仍为真实道路边（非 Haversine）。
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Sequence

from .engine import AMapRoutingEngine
from .graphhopper_routing import GraphHopperRoutingEngine, LocalGraphRoutingProvider, LocalRoutingConfig
from .local_routing import LocalRoutingEngine, RoadGraph
from .models import GeometryStatus, RouteGeometryResult, RouteType, is_formal_geometry


@dataclass
class TeacherDecision:
    mode: str  # CH / LM / FLEXIBLE / LOCAL_GRAPH_FALLBACK
    provider: str
    reason: str


class RoutingTeacherSelector:
    """STATIC_STANDARD → GraphHopper CH；动态 → LM；特殊 → Flexible A*。

    GraphHopper 不可用时 fallback LocalGraph（仍真实道路），并明确标记。
    """

    def __init__(
        self,
        config: LocalRoutingConfig | None = None,
        fallback_graph: RoadGraph | None = None,
    ):
        self.config = config or LocalRoutingConfig(provider="graphhopper", profile="bus")
        self.gh = GraphHopperRoutingEngine(config=self.config)
        self.local = LocalRoutingEngine(fallback_graph) if fallback_graph is not None else None
        self.last_decision: TeacherDecision | None = None

    def select_mode(self, *, dynamic_constraint: bool = False, special_constraint: bool = False) -> str:
        if special_constraint:
            return "FLEXIBLE"
        if dynamic_constraint:
            return "LM"
        return "CH"

    def teacher_route(
        self,
        origin: tuple[float, float],
        destination: tuple[float, float],
        waypoints: Sequence[tuple[float, float]] = (),
        *,
        dynamic_constraint: bool = False,
        special_constraint: bool = False,
        profile: str = "bus",
        route_type: RouteType = RouteType.FLEXIBLE_GAP,
    ) -> RouteGeometryResult:
        mode = self.select_mode(
            dynamic_constraint=dynamic_constraint, special_constraint=special_constraint
        )
        # 1) GraphHopper（CH/LM/Flexible 由服务端 speed mode 决定；请求语义记 mode）
        res = self.gh.route(origin, destination, waypoints, profile=profile, route_type=route_type)
        if is_formal_geometry(res.status):
            self.last_decision = TeacherDecision(mode, "graphhopper", "GH_OK")
            return res

        # 2) LocalGraph fallback（真实道路边；非 Haversine）
        if self.local is not None:
            loc = self.local.route(origin, destination, waypoints, route_type=route_type)
            if is_formal_geometry(loc.status):
                self.last_decision = TeacherDecision(
                    "LOCAL_GRAPH_FALLBACK", "local_graph", "GH_UNAVAILABLE"
                )
                return loc

        self.last_decision = TeacherDecision(mode, "none", "TEACHER_UNAVAILABLE")
        return res