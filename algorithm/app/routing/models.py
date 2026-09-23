"""统一路由抽象：真实道路 Geometry，禁止 Haversine 冒充正式路线。"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Sequence


class GeometryStatus(str, Enum):
    REAL_ROAD = "REAL_ROAD"
    LOCAL_ROAD = "LOCAL_ROAD"
    AMAP_VERIFIED = "AMAP_VERIFIED"
    CACHED_REAL = "CACHED_REAL"
    ESTIMATED = "ESTIMATED"
    UNCERTAIN = "UNCERTAIN"


class GeometrySource(str, Enum):
    LOCAL_GRAPH = "LOCAL_GRAPH"
    AMAP_ROAD = "AMAP_ROAD"
    AMAP_TRANSIT = "AMAP_TRANSIT"
    LOCAL_CORRIDOR = "LOCAL_CORRIDOR"
    CACHED_REAL_ROUTE = "CACHED_REAL_ROUTE"
    ESTIMATED = "ESTIMATED"
    NONE = "NONE"


class RouteType(str, Enum):
    TRANSIT_BACKBONE = "TRANSIT_BACKBONE"
    CARGO_DETOUR = "CARGO_DETOUR"
    FLEXIBLE_GAP = "FLEXIBLE_GAP"
    TRANSFER = "TRANSFER"
    POSITION_TO_SERVICE = "POSITION_TO_SERVICE"
    POSITION_TO_STATION = "POSITION_TO_STATION"
    ROAD_FALLBACK = "ROAD_FALLBACK"


# 正式执行允许的状态：有真实道路几何
FORMAL_GEOMETRY_STATUSES = {
    GeometryStatus.REAL_ROAD,
    GeometryStatus.LOCAL_ROAD,
    GeometryStatus.AMAP_VERIFIED,
    GeometryStatus.CACHED_REAL,
}


def is_formal_geometry(status: GeometryStatus | str) -> bool:
    s = GeometryStatus(status) if not isinstance(status, GeometryStatus) else status
    return s in FORMAL_GEOMETRY_STATUSES


@dataclass(frozen=True)
class EdgeSegment:
    """GraphHopper path detail：真实边（edge_id membership 标签用）。"""
    edge_id: int
    distance_m: float
    speed_kmh: float
    road_class: str
    mid_lat: float
    mid_lon: float


@dataclass
class RouteGeometryResult:
    """统一路由结果。straightDistance 仅预筛选，禁止当正式成本。"""
    available: bool
    distance_m: float = 0.0
    duration_s: float = 0.0
    polyline: tuple[tuple[float, float], ...] = ()
    edge_segments: tuple[EdgeSegment, ...] = ()
    provider: str = "none"
    geometry_source: GeometrySource = GeometrySource.NONE
    status: GeometryStatus = GeometryStatus.UNCERTAIN
    route_type: RouteType = RouteType.FLEXIBLE_GAP
    confidence: float = 0.0
    reason_code: str = ""
    cache_hit: bool = False
    route_fingerprint: str = ""
    straight_distance_m: float = 0.0
    origin: tuple[float, float] | None = None
    destination: tuple[float, float] | None = None
    waypoints: tuple[tuple[float, float], ...] = ()
    timestamp: float = 0.0
    route_version: str = "v1"
    map_api_called: bool = False
    local_routed: bool = False
    profile: str = "bus"
    graph_version: str = ""
    local_distance_m: float | None = None
    local_duration_s: float | None = None

    @property
    def is_formal(self) -> bool:
        return self.available and is_formal_geometry(self.status)

    def as_dict(self) -> dict:
        return {
            "available": self.available,
            "status": self.status.value,
            "source": self.geometry_source.value,
            "distance_m": self.distance_m,
            "duration_s": self.duration_s,
            "straight_distance_m": self.straight_distance_m,
            "cache_hit": self.cache_hit,
            "fingerprint": self.route_fingerprint,
            "reason_code": self.reason_code,
            "map_api_called": self.map_api_called,
            "local_routed": self.local_routed,
            "points": len(self.polyline),
        }


def uncertain(reason: str, **kw) -> RouteGeometryResult:
    return RouteGeometryResult(
        available=False,
        status=GeometryStatus.UNCERTAIN,
        geometry_source=GeometrySource.NONE,
        reason_code=reason,
        **kw,
    )


def estimated_only(distance_m: float, straight_m: float, reason: str = "ESTIMATED_WITHOUT_GEOMETRY") -> RouteGeometryResult:
    """只有估算距离、无真实 polyline：只能 ESTIMATED，不得正式执行。"""
    return RouteGeometryResult(
        available=False,
        distance_m=distance_m,
        straight_distance_m=straight_m,
        status=GeometryStatus.ESTIMATED,
        geometry_source=GeometrySource.ESTIMATED,
        reason_code=reason,
        confidence=0.1,
    )
