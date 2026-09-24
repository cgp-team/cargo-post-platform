"""LocalRoutingEngine：真实道路 Graph 上路由。

严禁 Haversine/两点直线作为正式结果；只用于预筛选、下界、吸附。
"""

from __future__ import annotations

import heapq
import math
import time
from dataclasses import dataclass, field
from typing import Sequence

from .geometry_cache import route_fingerprint
from .models import (
    GeometrySource,
    GeometryStatus,
    RouteGeometryResult,
    RouteType,
    estimated_only,
)


def haversine_m(a: tuple[float, float], b: tuple[float, float]) -> float:
    r = 6_371_000.0
    lat1, lon1 = math.radians(a[0]), math.radians(a[1])
    lat2, lon2 = math.radians(b[0]), math.radians(b[1])
    dp = lat2 - lat1
    dl = lon2 - lon1
    h = math.sin(dp / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin(dl / 2) ** 2
    return r * 2 * math.asin(math.sqrt(h))


def straight_lower_bound_m(a: tuple[float, float], b: tuple[float, float]) -> float:
    return haversine_m(a, b)


@dataclass(frozen=True)
class RoadEdge:
    """真实路段：road_m 为道路长度（非直线）。"""
    u: str
    v: str
    road_m: float
    duration_s: float
    polyline: tuple[tuple[float, float], ...]
    road_class: str = "service"
    vehicle_allowed: bool = True
    bidirectional: bool = True


@dataclass
class RoadGraph:
    nodes: dict[str, tuple[float, float]] = field(default_factory=dict)
    edges: list[RoadEdge] = field(default_factory=list)
    _adj_cache: dict[str, dict[str, list[RoadEdge]]] = field(default_factory=dict, repr=False)

    def add_node(self, nid: str, lat: float, lon: float) -> None:
        self.nodes[nid] = (lat, lon)
        self._adj_cache.clear()

    def add_edge(self, edge: RoadEdge) -> None:
        self.edges.append(edge)
        self._adj_cache.clear()

    def adjacency(self, vehicle_profile: str = "bus") -> dict[str, list[RoadEdge]]:
        if vehicle_profile in self._adj_cache:
            return self._adj_cache[vehicle_profile]
        adj: dict[str, list[RoadEdge]] = {n: [] for n in self.nodes}
        for e in self.edges:
            if not e.vehicle_allowed and vehicle_profile == "bus":
                continue
            adj.setdefault(e.u, []).append(e)
            if e.bidirectional:
                adj.setdefault(e.v, []).append(
                    RoadEdge(e.v, e.u, e.road_m, e.duration_s,
                             tuple(reversed(e.polyline)), e.road_class, e.vehicle_allowed, True)
                )
        self._adj_cache[vehicle_profile] = adj
        return adj

    def nearest_node(self, point: tuple[float, float], max_snap_m: float = 2000.0) -> tuple[str | None, float]:
        # 网格索引：避免对百万节点线性扫描
        if not hasattr(self, "_grid") or self._grid is None:
            self._grid = {}
            for nid, (lat, lon) in self.nodes.items():
                key = (int(lat * 50), int(lon * 50))  # ~2km grid
                self._grid.setdefault(key, []).append(nid)
        glat, glon = int(point[0] * 50), int(point[1] * 50)
        candidates: list[str] = []
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                candidates.extend(self._grid.get((glat + dx, glon + dy), ()))
        best_id, best_d = None, float("inf")
        for nid in candidates:
            d = haversine_m(point, self.nodes[nid])
            if d < best_d:
                best_id, best_d = nid, d
        if best_d > max_snap_m:
            return None, best_d
        return best_id, best_d


class LocalRoutingEngine:
    def __init__(self, graph: RoadGraph | None = None, *, speed_m_s: float = 8.0,
                 vehicle_profile: str = "bus", version: str = "local-v1"):
        self.graph = graph or RoadGraph()
        self.speed_m_s = speed_m_s
        self.vehicle_profile = vehicle_profile
        self.version = version
        self._adj_cache: dict | None = None

    def _adj(self) -> dict:
        # 邻接表只建一次（2M+ 节点时每次重建是数量级瓶颈）
        if self._adj_cache is None:
            self._adj_cache = self.graph.adjacency(self.vehicle_profile)
        return self._adj_cache

    @property
    def available(self) -> bool:
        return bool(self.graph.nodes)

    def route(
        self,
        origin: tuple[float, float],
        destination: tuple[float, float],
        waypoints: Sequence[tuple[float, float]] = (),
        *,
        route_type: RouteType = RouteType.FLEXIBLE_GAP,
    ) -> RouteGeometryResult:
        pts = [origin, *list(waypoints), destination]
        straight = sum(haversine_m(pts[i], pts[i + 1]) for i in range(len(pts) - 1))
        total_m = 0.0
        total_s = 0.0
        poly: list[tuple[float, float]] = []
        for i in range(len(pts) - 1):
            seg = self._shortest_path(pts[i], pts[i + 1])
            if seg is None:
                return estimated_only(
                    distance_m=straight * 1.3,
                    straight_m=straight,
                    reason="LOCAL_GRAPH_UNAVAILABLE",
                )
            total_m += seg[0]
            total_s += seg[1]
            if not poly:
                poly.extend(seg[2])
            elif seg[2]:
                poly.extend(seg[2][1:])
        return RouteGeometryResult(
            available=True,
            distance_m=total_m,
            duration_s=total_s,
            polyline=tuple(poly),
            provider="local_graph",
            geometry_source=GeometrySource.LOCAL_GRAPH,
            status=GeometryStatus.LOCAL_ROAD,
            route_type=route_type,
            confidence=0.85,
            reason_code="LOCAL_ROAD_OK",
            route_fingerprint=route_fingerprint(
                origin, destination, tuple(waypoints),
                provider="local_graph", route_version=self.version,
            ),
            straight_distance_m=straight,
            origin=origin,
            destination=destination,
            waypoints=tuple(waypoints),
            timestamp=time.time(),
            route_version=self.version,
            local_routed=True,
        )

    def estimate_only(
        self,
        origin: tuple[float, float],
        destination: tuple[float, float],
        waypoints: Sequence[tuple[float, float]] = (),
    ) -> RouteGeometryResult:
        pts = [origin, *list(waypoints), destination]
        est = sum(haversine_m(pts[i], pts[i + 1]) for i in range(len(pts) - 1))
        return estimated_only(distance_m=est * 1.3, straight_m=est, reason="ESTIMATED_PREFILTER")

    def _shortest_path(
        self,
        a: tuple[float, float],
        b: tuple[float, float],
    ) -> tuple[float, float, list[tuple[float, float]]] | None:
        na, _ = self.graph.nearest_node(a)
        nb, _ = self.graph.nearest_node(b)
        if na is None or nb is None:
            return None
        adj = self._adj()
        dist: dict[str, float] = {na: 0.0}
        prev: dict[str, tuple[str, RoadEdge]] = {}
        pq: list[tuple[float, str]] = [(0.0, na)]
        seen: set[str] = set()
        # A* 启发式 + 扩展上限：真实道路优先，巨图上避免全图展开
        def _h(nid: str) -> float:
            la, lo = self.graph.nodes.get(nid, (0.0, 0.0))
            return haversine_m((la, lo), b)
        expanded = 0
        max_expand = 80000
        while pq:
            d, u = heapq.heappop(pq)
            if u in seen:
                continue
            seen.add(u)
            expanded += 1
            if u == nb:
                break
            if expanded > max_expand:
                break
            # d 是 f=g+h；扩展时必须用 dist[u] 作为真实 g
            g_u = dist.get(u, 0.0)
            for e in adj.get(u, []):
                nd = g_u + e.road_m
                if nd < dist.get(e.v, float("inf")):
                    dist[e.v] = nd
                    prev[e.v] = (u, e)
                    heapq.heappush(pq, (nd + _h(e.v), e.v))
        if nb not in dist and na != nb:
            return None
        edges_rev: list[RoadEdge] = []
        cur = nb
        while cur != na:
            if cur not in prev:
                return None
            u, e = prev[cur]
            edges_rev.append(e)
            cur = u
        edges_rev.reverse()
        poly = [a]
        for e in edges_rev:
            poly.extend(e.polyline if e.polyline else [self.graph.nodes.get(e.v, b)])
        poly.append(b)
        road_m = sum(e.road_m for e in edges_rev)
        dur = sum(e.duration_s if e.duration_s > 0 else e.road_m / self.speed_m_s for e in edges_rev)
        return road_m, dur, poly
