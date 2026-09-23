"""GraphHopper + OSM：默认 Local Routing Provider（可插拔 Valhalla/OSRM）。

业务层只依赖 LocalRoutingProvider 接口，禁止直接 new GraphHopper。
Haversine 不得作为正式结果；GH 不可用 → LOCAL_ROUTING_UNAVAILABLE。
"""

from __future__ import annotations

import json
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from typing import Protocol, Sequence

from .geometry_cache import route_fingerprint
from .local_routing import LocalRoutingEngine, RoadGraph, haversine_m
from .models import (
    EdgeSegment,
    GeometrySource,
    GeometryStatus,
    RouteGeometryResult,
    RouteType,
    estimated_only,
    uncertain,
)

ROAD_CLASS_CODES = {
    "motorway": 5.0, "trunk": 4.0, "primary": 3.0, "secondary": 2.0,
    "tertiary": 1.0, "unclassified": 0.0, "residential": 0.0, "service": 0.0,
    "living_street": 0.0, "motorway_link": 4.0, "trunk_link": 3.0,
    "primary_link": 2.0, "secondary_link": 1.0, "tertiary_link": 0.0,
}

DEFAULT_PROFILE = "bus"


class LocalRoutingProvider(Protocol):
    """统一本地路由接口：GraphHopper / Valhalla / OSRM / LocalGraph。"""

    name: str
    version: str

    def route(
        self,
        origin: tuple[float, float],
        destination: tuple[float, float],
        waypoints: Sequence[tuple[float, float]] = (),
        *,
        profile: str = DEFAULT_PROFILE,
        route_type: RouteType = RouteType.FLEXIBLE_GAP,
    ) -> RouteGeometryResult: ...


@dataclass
class LocalRoutingConfig:
    """local-routing.* 配置，禁止硬编码绝对路径。"""
    enabled: bool = True
    provider: str = "graphhopper"  # graphhopper | local_graph | valhalla | osrm
    profile: str = DEFAULT_PROFILE
    # GraphHopper
    graphhopper_enabled: bool = True
    graphhopper_base_url: str = "http://127.0.0.1:8080"
    graphhopper_timeout_ms: int = 3000
    graphhopper_data_dir: str = ""  # osm/graph 工作目录（部署时配置）
    graphhopper_osm_file: str = ""
    graphhopper_graph_dir: str = ""
    graphhopper_max_alternatives: int = 0
    graph_version: str = "osm-unknown"
    timeout_ms: int = 3000
    max_snap_m: float = 2000.0

    @classmethod
    def from_mapping(cls, raw: dict | None) -> "LocalRoutingConfig":
        raw = raw or {}
        return cls(
            enabled=bool(raw.get("enabled", True)),
            provider=str(raw.get("provider", "graphhopper")),
            profile=str(raw.get("profile", DEFAULT_PROFILE)),
            graphhopper_enabled=bool(raw.get("graphhopper.enabled", raw.get("graphhopper_enabled", True))),
            graphhopper_base_url=str(raw.get("graphhopper.base-url", raw.get("graphhopper_base_url", "http://127.0.0.1:8989"))),
            graphhopper_timeout_ms=int(raw.get("graphhopper.timeout-ms", raw.get("graphhopper_timeout_ms", 3000))),
            graphhopper_data_dir=str(raw.get("graphhopper.data-dir", raw.get("graphhopper_data_dir", ""))),
            graphhopper_osm_file=str(raw.get("graphhopper.osm-file", raw.get("graphhopper_osm_file", ""))),
            graphhopper_graph_dir=str(raw.get("graphhopper.graph-dir", raw.get("graphhopper_graph_dir", ""))),
            graphhopper_max_alternatives=int(raw.get("graphhopper.max-alternatives", raw.get("graphhopper_max_alternatives", 0))),
            graph_version=str(raw.get("graph_version", raw.get("graphVersion", "osm-unknown"))),
            timeout_ms=int(raw.get("timeout-ms", raw.get("timeout_ms", 3000))),
            max_snap_m=float(raw.get("max-snap-m", raw.get("max_snap_m", 2000.0))),
        )


@dataclass
class GraphHopperRoutingEngine:
    """GraphHopper HTTP adapter（library/service 同构）。

    - origin / destination / ordered waypoints / vehicle profile
    - distance / duration / road polyline / status / fingerprint / graphVersion
    - 不可用 → LOCAL_ROUTING_UNAVAILABLE，禁止 Haversine 冒充
    """

    config: LocalRoutingConfig = field(default_factory=LocalRoutingConfig)
    name: str = "graphhopper"
    calls: int = 0
    last_latency_ms: float = 0.0

    @property
    def version(self) -> str:
        return f"gh-{self.config.graph_version}"

    def route(
        self,
        origin: tuple[float, float],
        destination: tuple[float, float],
        waypoints: Sequence[tuple[float, float]] = (),
        *,
        profile: str = DEFAULT_PROFILE,
        route_type: RouteType = RouteType.FLEXIBLE_GAP,
    ) -> RouteGeometryResult:
        self.calls += 1
        t0 = time.perf_counter()
        straight = sum(
            haversine_m(a, b)
            for a, b in _pairs([origin, *waypoints, destination])
        )
        if not self.config.enabled or not self.config.graphhopper_enabled:
            self.last_latency_ms = (time.perf_counter() - t0) * 1000
            return uncertain(
                "LOCAL_ROUTING_UNAVAILABLE",
                origin=origin,
                destination=destination,
                waypoints=tuple(waypoints),
                straight_distance_m=straight,
                provider=self.name,
                route_type=route_type,
                reason_code="LOCAL_ROUTING_UNAVAILABLE",
            )
        try:
            raw = self._http_route(origin, waypoints, destination, profile)
        except Exception as ex:  # noqa: BLE001
            self.last_latency_ms = (time.perf_counter() - t0) * 1000
            return uncertain(
                f"LOCAL_ROUTING_UNAVAILABLE:{type(ex).__name__}",
                origin=origin,
                destination=destination,
                waypoints=tuple(waypoints),
                straight_distance_m=straight,
                provider=self.name,
                route_type=route_type,
            )
        self.last_latency_ms = (time.perf_counter() - t0) * 1000
        if not raw or not raw.get("polyline"):
            return uncertain(
                "LOCAL_ROUTING_UNAVAILABLE",
                origin=origin,
                destination=destination,
                waypoints=tuple(waypoints),
                straight_distance_m=straight,
                provider=self.name,
                route_type=route_type,
            )
        dist = float(raw.get("distance_m", 0.0))
        # 防御：道路距离不得明显小于直线
        if straight > 0 and dist < straight * 0.3:
            return uncertain(
                "NETWORK_ANOMALY",
                origin=origin,
                destination=destination,
                waypoints=tuple(waypoints),
                straight_distance_m=straight,
                provider=self.name,
                route_type=route_type,
            )
        return RouteGeometryResult(
            available=True,
            distance_m=dist,
            duration_s=float(raw.get("duration_s", 0.0)),
            polyline=tuple(tuple(p) for p in raw["polyline"]),
            edge_segments=tuple(raw.get("edge_segments") or ()),
            provider=self.name,
            geometry_source=GeometrySource.LOCAL_GRAPH,
            status=GeometryStatus.LOCAL_ROAD,
            route_type=route_type,
            confidence=float(raw.get("confidence", 0.9)),
            reason_code="GRAPHOPPER_OK",
            route_fingerprint=route_fingerprint(
                origin, destination, tuple(waypoints),
                route_type=route_type,
                provider=f"graphhopper:{profile}",
                route_version=self.version,
            ),
            straight_distance_m=straight,
            origin=origin,
            destination=destination,
            waypoints=tuple(waypoints),
            timestamp=time.time(),
            route_version=self.version,
            local_routed=True,
            map_api_called=False,
        )

    def _http_route(
        self,
        origin: tuple[float, float],
        waypoints: Sequence[tuple[float, float]],
        destination: tuple[float, float],
        profile: str,
    ) -> dict:
        """GraphHopper /route API（points=lat,lon 有序途经点）。"""
        pts = [origin, *list(waypoints), destination]
        points = "&".join(f"point={p[0]},{p[1]}" for p in pts)
        url = (
            f"{self.config.graphhopper_base_url.rstrip('/')}/route?"
            f"{points}&profile={urllib.parse.quote(profile)}"
            f"&points_encoded=false&instructions=false&calc_points=true"
            f"&details=edge_id&details=distance&details=average_speed&details=road_class"
        )
        timeout_s = max(0.05, self.config.graphhopper_timeout_ms / 1000.0)
        with urllib.request.urlopen(url, timeout=timeout_s) as resp:
            body = json.loads(resp.read().decode("utf-8"))
        paths = body.get("paths") or []
        if not paths:
            return {}
        path = paths[0]
        # points_encoded=false → coordinates [[lon,lat], ...] or [[lat,lon]]
        coords = path.get("points", {}).get("coordinates") or path.get("points") or []
        poly: list[tuple[float, float]] = []
        for c in coords:
            if len(c) >= 2:
                # GraphHopper 默认 [lon, lat]
                poly.append((float(c[1]), float(c[0])))
        return {
            "distance_m": float(path.get("distance", 0.0)),
            "duration_s": float(path.get("time", 0.0)) / 1000.0,
            "polyline": poly,
            "confidence": 0.95,
            "edge_segments": _parse_edge_segments(path.get("details") or {}, poly),
        }


@dataclass
class LocalGraphRoutingProvider:
    """本地 RoadGraph  provider（开发/无 OSM 时的图路由，非 Haversine 正式结果）。"""

    graph: RoadGraph = field(default_factory=RoadGraph)
    name: str = "local_graph"
    version: str = "local-v1"

    def route(self, origin, destination, waypoints=(), *, profile=DEFAULT_PROFILE,
              route_type: RouteType = RouteType.FLEXIBLE_GAP) -> RouteGeometryResult:
        eng = LocalRoutingEngine(self.graph, version=self.version, vehicle_profile=profile)
        r = eng.route(origin, destination, waypoints, route_type=route_type)
        if r.available:
            r.provider = self.name
            r.route_version = self.version
        return r


class LocalRoutingProviderFactory:
    """按配置选择 Local Provider；默认 graphhopper。"""

    def __init__(self, config: LocalRoutingConfig | None = None):
        self.config = config or LocalRoutingConfig()
        self._local_graph: LocalGraphRoutingProvider | None = None

    def set_local_graph(self, provider: LocalGraphRoutingProvider) -> None:
        self._local_graph = provider

    def create(self) -> LocalRoutingProvider:
        provider = (self.config.provider or "graphhopper").lower()
        if provider == "graphhopper":
            return GraphHopperRoutingEngine(config=self.config)
        if provider in ("valhalla", "osrm"):
            # 预留：未接入时明确 LOCAL_ROUTING_UNAVAILABLE
            return _UnavailableProvider(provider)
        if provider == "local_graph":
            return self._local_graph or LocalGraphRoutingProvider()
        return GraphHopperRoutingEngine(config=self.config)


@dataclass
class _UnavailableProvider:
    name: str
    version: str = "unavailable"

    def route(self, origin, destination, waypoints=(), *, profile=DEFAULT_PROFILE,
              route_type: RouteType = RouteType.FLEXIBLE_GAP) -> RouteGeometryResult:
        return uncertain(
            "LOCAL_ROUTING_UNAVAILABLE",
            origin=origin,
            destination=destination,
            waypoints=tuple(waypoints),
            provider=self.name,
            route_type=route_type,
        )


def _pairs(pts: Sequence[tuple[float, float]]):
    return [(pts[i], pts[i + 1]) for i in range(len(pts) - 1)]


def _span_value(spans, idx: int, default):
    for a, b, v in spans or []:
        if a <= idx < b:
            return v
    return default


def _span_overlap_sum(spans, a: int, b: int) -> float:
    total = 0.0
    for p, q, v in spans or []:
        lo, hi = max(a, p), min(b, q)
        if hi > lo and q > p:
            total += float(v) * (hi - lo) / (q - p)
    return total


def _parse_edge_segments(details: dict, poly: Sequence[tuple[float, float]]) -> list[EdgeSegment]:
    """GH path details → 真实边段（edge_id / 里程 / 限速 / 道路等级 / 中点）。"""
    edge_spans = details.get("edge_id") or []
    dist_spans = details.get("distance") or []
    speed_spans = details.get("average_speed") or []
    class_spans = details.get("road_class") or []
    out: list[EdgeSegment] = []
    n = len(poly)
    for a, b, eid in edge_spans:
        if b <= a:
            continue
        mid_i = min(n - 1, max(0, (a + b) // 2)) if n else 0
        mid = poly[mid_i] if n else (0.0, 0.0)
        out.append(EdgeSegment(
            edge_id=int(eid),
            distance_m=float(_span_overlap_sum(dist_spans, int(a), int(b))),
            speed_kmh=float(_span_value(speed_spans, int(a), 30.0) or 30.0),
            road_class=str(_span_value(class_spans, int(a), "unclassified") or "unclassified"),
            mid_lat=float(mid[0]),
            mid_lon=float(mid[1]),
        ))
    return out
