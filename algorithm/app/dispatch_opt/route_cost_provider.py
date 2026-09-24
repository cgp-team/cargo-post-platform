"""统一 Route Cost Provider：正式调度成本的唯一来源。

规则（DISPATCH_CORE_V047）：

- 正式成本只允许 `LOCAL_ROAD / CACHED_REAL / AMAP_VERIFIED / REAL_ROAD`（见 `routing.models.FORMAL_GEOMETRY_STATUSES`）；
- `Haversine` 只允许做 `prefilter / lower bound / cheap reject / snap reference`，**不得**作为
  `formal distance / formal duration / formal detour / formal marginal cost`；
- routing 返回不确定时**不得伪装**成真实：显式 `UNKNOWN / HOLD / FALLBACK`；
- 坐标边界：业务侧 GCJ-02 → GraphHopper/OSM WGS-84 → 回程 WGS-84 → GCJ-02，**只跨边界一次**。
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
from typing import Protocol, Sequence

from ..routing import coordinate
from ..routing.engine import MapRoutingEngine
from ..routing.local_routing import haversine_m, straight_lower_bound_m
from ..routing.models import (
    GeometryStatus,
    RouteGeometryResult,
    RouteType,
    is_formal_geometry,
)

Point = tuple[float, float]  # (lat, lon)，GCJ-02（业务坐标系）

# 直线速度只用于「下界估计」，永不进入正式时长
STRAIGHT_LINE_SPEED_M_S = 25.0 / 3.6


class RouteCostStatus(str, Enum):
    FORMAL = "FORMAL"
    CACHED_REAL = "CACHED_REAL"
    UNKNOWN = "UNKNOWN"
    FALLBACK = "FALLBACK"
    UNAVAILABLE = "UNAVAILABLE"


@dataclass(frozen=True)
class RouteCost:
    """一次路由成本结果。`is_formal=False` 的结果**禁止**进入正式成本/绕行/增量。"""

    status: RouteCostStatus
    distance_m: float = 0.0
    duration_s: float = 0.0
    provider: str = "none"
    geometry_status: str = GeometryStatus.UNCERTAIN.value
    reason_code: str = ""
    polyline: tuple[Point, ...] = ()
    cache_hit: bool = False

    @property
    def is_formal(self) -> bool:
        return self.status in (RouteCostStatus.FORMAL, RouteCostStatus.CACHED_REAL)

    @property
    def is_known(self) -> bool:
        return self.status not in (
            RouteCostStatus.UNKNOWN,
            RouteCostStatus.UNAVAILABLE,
        )

    @property
    def is_lower_bound(self) -> bool:
        return self.status == RouteCostStatus.FALLBACK

    def as_dict(self) -> dict:
        return {
            "status": self.status.value,
            "distance_m": self.distance_m,
            "duration_s": self.duration_s,
            "provider": self.provider,
            "geometry_status": self.geometry_status,
            "reason_code": self.reason_code,
            "is_formal": self.is_formal,
            "cache_hit": self.cache_hit,
        }


@dataclass(frozen=True)
class GapRoutePair:
    """Gap 内真实道路对比：baseline `B→C` vs insert `B→X→Y→C`。"""

    baseline: RouteCost
    insert: RouteCost
    waypoints: tuple[Point, ...] = ()

    @property
    def both_formal(self) -> bool:
        return self.baseline.is_formal and self.insert.is_formal

    def delta_distance_m(self) -> float:
        return max(0.0, self.insert.distance_m - self.baseline.distance_m)

    def delta_duration_s(self) -> float:
        return max(0.0, self.insert.duration_s - self.baseline.duration_s)


class RouteCostProvider(Protocol):
    def route(
        self,
        origin: Point,
        destination: Point,
        *,
        waypoints: Sequence[Point] = (),
        route_type: RouteType = RouteType.FLEXIBLE_GAP,
    ) -> RouteCost: ...

    def route_gap(
        self,
        gap_from: Point,
        gap_to: Point,
        *,
        pickup: Point | None = None,
        delivery: Point | None = None,
        extra: Sequence[Point] = (),
    ) -> GapRoutePair: ...

    def route_insert(
        self,
        insert_from: Point,
        insert_to: Point,
        insert_points: Sequence[Point],
    ) -> RouteCost: ...

    def route_candidate(self, waypoints: Sequence[Point]) -> RouteCost: ...

    def is_formal(self) -> bool: ...


def build_gap_waypoints(
    pickup: Point | None = None,
    delivery: Point | None = None,
    extra: Sequence[Point] = (),
) -> tuple[Point, ...]:
    """Gap 内 cargo 中间点序列（**不含**起终点）。

    强制 pickup 在前、delivery 在后；返回结果会被 `route_gap` 拼成
    `gap_from → ...mids... → gap_to`，从构造上保证不产生 `B→X→Y→B→C` 回折。
    """
    mids: list[Point] = []
    if pickup is not None:
        mids.append(pickup)
    mids.extend(extra)
    if delivery is not None and delivery != pickup:
        mids.append(delivery)
    elif delivery is not None and delivery == pickup and pickup not in mids:
        mids.append(delivery)
    return tuple(mids)


class HaversineLowerBoundProvider:
    """仅做 prefilter / lower bound：**永不 formal**。"""

    def __init__(self, speed_m_s: float = STRAIGHT_LINE_SPEED_M_S):
        self.speed_m_s = speed_m_s

    def is_formal(self) -> bool:
        return False

    def route(
        self,
        origin: Point,
        destination: Point,
        *,
        waypoints: Sequence[Point] = (),
        route_type: RouteType = RouteType.FLEXIBLE_GAP,
    ) -> RouteCost:
        pts = [origin, *list(waypoints), destination]
        dist = sum(haversine_m(pts[i], pts[i + 1]) for i in range(len(pts) - 1))
        return RouteCost(
            status=RouteCostStatus.FALLBACK,
            distance_m=dist,
            duration_s=dist / max(self.speed_m_s, 0.1),
            provider="haversine_lower_bound",
            geometry_status=GeometryStatus.ESTIMATED.value,
            reason_code="ESTIMATED_LOWER_BOUND_NOT_FORMAL",
        )

    def route_gap(
        self,
        gap_from: Point,
        gap_to: Point,
        *,
        pickup: Point | None = None,
        delivery: Point | None = None,
        extra: Sequence[Point] = (),
    ) -> GapRoutePair:
        mids = build_gap_waypoints(pickup, delivery, extra)
        return GapRoutePair(
            baseline=self.route(gap_from, gap_to),
            insert=self.route(gap_from, gap_to, waypoints=mids),
            waypoints=mids,
        )

    def route_insert(
        self,
        insert_from: Point,
        insert_to: Point,
        insert_points: Sequence[Point],
    ) -> RouteCost:
        return self.route(insert_from, insert_to, waypoints=insert_points)

    def route_candidate(self, waypoints: Sequence[Point]) -> RouteCost:
        if len(waypoints) < 2:
            return RouteCost(
                status=RouteCostStatus.UNAVAILABLE,
                reason_code="NEED_AT_LEAST_TWO_POINTS",
            )
        return self.route(waypoints[0], waypoints[-1], waypoints=waypoints[1:-1])


class MapEngineRouteCostProvider:
    """基于 `MapRoutingEngine`（Local/GraphHopper → Cache → AMap 校验）的正式 provider。

    `MapRoutingEngine` 内部使用 **WGS-84**（OSM/GraphHopper）；业务入参为 GCJ-02，
    因此在本 provider 边界内做 **一次** `GCJ-02 → WGS-84`，回程再做 **一次** `WGS-84 → GCJ-02`。
    """

    def __init__(
        self,
        engine: MapRoutingEngine | None = None,
        *,
        lower_bound: HaversineLowerBoundProvider | None = None,
    ):
        self.engine = engine or MapRoutingEngine()
        self.lower_bound = lower_bound or HaversineLowerBoundProvider()

    def is_formal(self) -> bool:
        return True

    # ── 内部：GCJ-02 (lat,lon) → WGS-84 (lat,lon) ──
    @staticmethod
    def _to_wgs(point: Point) -> Point:
        return coordinate.to_wgs84(point)

    @staticmethod
    def _polyline_to_gcj(poly: Sequence[Point]) -> tuple[Point, ...]:
        return tuple(coordinate.to_gcj02(p) for p in poly)

    def _from_geometry(self, res: RouteGeometryResult) -> RouteCost:
        if res.is_formal and is_formal_geometry(res.status):
            status = (
                RouteCostStatus.CACHED_REAL
                if res.cache_hit or res.status == GeometryStatus.CACHED_REAL
                else RouteCostStatus.FORMAL
            )
            return RouteCost(
                status=status,
                distance_m=float(res.distance_m),
                duration_s=float(res.duration_s),
                provider=res.provider,
                geometry_status=res.status.value,
                reason_code=res.reason_code or "FORMAL_OK",
                polyline=self._polyline_to_gcj(res.polyline),
                cache_hit=res.cache_hit,
            )
        if res.status == GeometryStatus.ESTIMATED:
            # 估算：只允许做下界
            return RouteCost(
                status=RouteCostStatus.FALLBACK,
                distance_m=float(res.distance_m),
                duration_s=float(res.duration_s),
                provider=res.provider,
                geometry_status=res.status.value,
                reason_code=res.reason_code or "ESTIMATED_NOT_FORMAL",
            )
        return RouteCost(
            status=RouteCostStatus.UNKNOWN,
            provider=res.provider,
            geometry_status=res.status.value if isinstance(res.status, GeometryStatus) else str(res.status),
            reason_code=res.reason_code or "ROUTE_UNKNOWN",
        )

    def route(
        self,
        origin: Point,
        destination: Point,
        *,
        waypoints: Sequence[Point] = (),
        route_type: RouteType = RouteType.FLEXIBLE_GAP,
    ) -> RouteCost:
        wgs_origin = self._to_wgs(origin)
        wgs_dest = self._to_wgs(destination)
        wgs_wps = tuple(self._to_wgs(p) for p in waypoints)
        res = self.engine.resolve(
            wgs_origin, wgs_dest, wgs_wps, route_type=route_type
        )
        return self._from_geometry(res)

    def route_gap(
        self,
        gap_from: Point,
        gap_to: Point,
        *,
        pickup: Point | None = None,
        delivery: Point | None = None,
        extra: Sequence[Point] = (),
    ) -> GapRoutePair:
        mids = build_gap_waypoints(pickup, delivery, extra)
        # 下界预筛：insert 直线显著劣于 baseline 时提前便宜拒绝
        base_lb = straight_lower_bound_m(gap_from, gap_to)
        ins_lb = straight_lower_bound_m(gap_from, mids[0]) if mids else 0.0
        if mids:
            for i in range(len(mids) - 1):
                ins_lb += straight_lower_bound_m(mids[i], mids[i + 1])
            ins_lb += straight_lower_bound_m(mids[-1], gap_to)
        if mids and base_lb > 0 and ins_lb > base_lb * 6 + 30_000:
            return GapRoutePair(
                baseline=RouteCost(
                    status=RouteCostStatus.FALLBACK,
                    distance_m=base_lb,
                    duration_s=base_lb / STRAIGHT_LINE_SPEED_M_S,
                    provider="haversine_lower_bound",
                    geometry_status=GeometryStatus.ESTIMATED.value,
                    reason_code="PREFILTER_REJECT_LOWER_BOUND",
                ),
                insert=self.lower_bound.route(gap_from, gap_to, waypoints=mids),
                waypoints=mids,
            )
        return GapRoutePair(
            baseline=self.route(gap_from, gap_to, route_type=RouteType.FLEXIBLE_GAP),
            insert=self.route(
                gap_from, gap_to, waypoints=mids, route_type=RouteType.FLEXIBLE_GAP
            ),
            waypoints=mids,
        )

    def route_insert(
        self,
        insert_from: Point,
        insert_to: Point,
        insert_points: Sequence[Point],
    ) -> RouteCost:
        return self.route(
            insert_from, insert_to, waypoints=insert_points, route_type=RouteType.CARGO_DETOUR
        )

    def route_candidate(self, waypoints: Sequence[Point]) -> RouteCost:
        if len(waypoints) < 2:
            return RouteCost(
                status=RouteCostStatus.UNAVAILABLE, reason_code="NEED_AT_LEAST_TWO_POINTS"
            )
        return self.route(
            waypoints[0],
            waypoints[-1],
            waypoints=waypoints[1:-1],
            route_type=RouteType.POSITION_TO_SERVICE,
        )


class CachedRealRouteProvider:
    """只读缓存 provider：命中 formal 缓存即返回 `CACHED_REAL`，否则委托底层 provider。"""

    def __init__(self, base: RouteCostProvider | None = None):
        self.base = base or MapEngineRouteCostProvider()
        self._cache: dict[tuple, RouteCost] = {}

    def is_formal(self) -> bool:
        return True

    @staticmethod
    def _key(pts: Sequence[Point]) -> tuple:
        return tuple((round(p[0], 5), round(p[1], 5)) for p in pts)

    def route(
        self,
        origin: Point,
        destination: Point,
        *,
        waypoints: Sequence[Point] = (),
        route_type: RouteType = RouteType.FLEXIBLE_GAP,
    ) -> RouteCost:
        key = ("route", self._key([origin, *waypoints, destination]))
        hit = self._cache.get(key)
        if hit is not None:
            return RouteCost(
                status=RouteCostStatus.CACHED_REAL,
                distance_m=hit.distance_m,
                duration_s=hit.duration_s,
                provider=hit.provider,
                geometry_status=hit.geometry_status,
                reason_code="CACHED_REAL",
                polyline=hit.polyline,
                cache_hit=True,
            )
        res = self.base.route(origin, destination, waypoints=waypoints, route_type=route_type)
        if res.is_formal:
            self._cache[key] = res
        return res

    def route_gap(self, gap_from, gap_to, *, pickup=None, delivery=None, extra=()) -> GapRoutePair:
        mids = build_gap_waypoints(pickup, delivery, extra)
        return GapRoutePair(
            baseline=self.route(gap_from, gap_to),
            insert=self.route(gap_from, gap_to, waypoints=mids),
            waypoints=mids,
        )

    def route_insert(self, insert_from, insert_to, insert_points) -> RouteCost:
        return self.route(insert_from, insert_to, waypoints=insert_points)

    def route_candidate(self, waypoints) -> RouteCost:
        if len(waypoints) < 2:
            return RouteCost(status=RouteCostStatus.UNAVAILABLE, reason_code="NEED_AT_LEAST_TWO_POINTS")
        return self.route(waypoints[0], waypoints[-1], waypoints=waypoints[1:-1])


# 进程级单例：GraphHopper 连接与 formal 路网缓存跨请求复用。
# 原先每次 allocate/plan 都 new 一份 provider，缓存完全失效、GH 客户端反复建连。
_DEFAULT_PROVIDER: RouteCostProvider | None = None


def default_route_cost_provider(
    engine: MapRoutingEngine | None = None,
    *,
    refresh: bool = False,
) -> RouteCostProvider:
    """生产默认：Cache(Local/GraphHopper/AMap) provider；拿不到 formal 时返回 UNKNOWN。

    进程内单例；`refresh=True` 强制重建（测试或切换 GRAPHHOPPER_URL 时用）。
    """
    global _DEFAULT_PROVIDER
    if _DEFAULT_PROVIDER is not None and not refresh and engine is None:
        return _DEFAULT_PROVIDER
    provider = CachedRealRouteProvider(MapEngineRouteCostProvider(engine or build_default_map_engine()))
    if engine is None:
        _DEFAULT_PROVIDER = provider
    return provider


def build_default_map_engine() -> MapRoutingEngine:
    """DISPATCH_CORE_V047.1：优先本机 GraphHopper(OSM)，否则空 Local（仍非 Haversine formal）。"""
    import os

    from app.routing.engine import MapRoutingEngine
    from app.routing.graphhopper_routing import GraphHopperRoutingEngine, LocalRoutingConfig

    base = os.environ.get("GRAPHHOPPER_URL", "http://127.0.0.1:8080")
    cfg = LocalRoutingConfig(
        graphhopper_base_url=base,
        graphhopper_timeout_ms=4000,
        graph_version=os.environ.get("GRAPH_VERSION", "osm-chongqing"),
    )
    gh = GraphHopperRoutingEngine(cfg)
    return MapRoutingEngine(local=gh, profile="bus", graph_version=cfg.graph_version)


def build_formal_distance_matrix(
    points: Sequence[Point],
    provider: RouteCostProvider | None = None,
    *,
    max_workers: int = 8,
) -> tuple[list[list[float]] | None, str]:
    """站点两两 formal 距离矩阵（km）。全部 formal 才返回矩阵；否则 (None, reason)。

    并行查路网：O(n²) 串行在 30~100 站时会拖垮 10s 契约预算。
    """
    from concurrent.futures import ThreadPoolExecutor, as_completed

    prov = provider or default_route_cost_provider()
    n = len(points)
    m = [[0.0] * n for _ in range(n)]
    pairs = [(i, j) for i in range(n) for j in range(i + 1, n)]
    if not pairs:
        return m, "FORMAL_MATRIX"

    def _pair(ij: tuple[int, int]) -> tuple[int, int, RouteCost]:
        i, j = ij
        return i, j, prov.route(points[i], points[j])

    workers = max(1, min(max_workers, len(pairs)))
    with ThreadPoolExecutor(max_workers=workers, thread_name_prefix="formal-matrix") as pool:
        futures = [pool.submit(_pair, ij) for ij in pairs]
        for fut in as_completed(futures):
            i, j, rc = fut.result()
            if not rc.is_formal:
                return None, f"NOT_FORMAL:{rc.reason_code}"
            km = rc.distance_m / 1000.0
            m[i][j] = km
            m[j][i] = km
    return m, "FORMAL_MATRIX"


__all__ = [
    "Point",
    "RouteCostStatus",
    "RouteCost",
    "GapRoutePair",
    "RouteCostProvider",
    "build_gap_waypoints",
    "HaversineLowerBoundProvider",
    "MapEngineRouteCostProvider",
    "CachedRealRouteProvider",
    "default_route_cost_provider",
    "build_default_map_engine",
    "build_formal_distance_matrix",
]
