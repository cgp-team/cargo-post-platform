"""MapRoutingEngine：Local 优先 → Cache → Top-K AMap 校验。"""

from __future__ import annotations

import time
from typing import Protocol, Sequence

from .geometry_cache import GeometryCache, RoutingCallBudget, route_fingerprint
from .local_routing import LocalRoutingEngine, RoadGraph, haversine_m, straight_lower_bound_m
from .models import (
    GeometrySource,
    GeometryStatus,
    RouteGeometryResult,
    RouteType,
    is_formal_geometry,
    uncertain,
)


class AMapRoutingPort(Protocol):
    def route(self, origin, destination, waypoints=()) -> RouteGeometryResult: ...


class AMapRoutingEngine:
    """高德端口：最终校验/权威 Geometry。失败必须 UNCERTAIN，禁止假成功。"""

    def __init__(self, client=None, version: str = "amap-v1"):
        self.client = client
        self.version = version
        self.calls = 0

    def route(self, origin, destination, waypoints=()) -> RouteGeometryResult:
        self.calls += 1
        straight = straight_lower_bound_m(origin, destination)
        if self.client is None:
            return uncertain("AMAP_UNAVAILABLE", origin=origin, destination=destination,
                             waypoints=tuple(waypoints), straight_distance_m=straight,
                             provider="amap", map_api_called=True)
        try:
            raw = self.client.route(origin, destination, list(waypoints))
            if not raw or not raw.get("polyline"):
                return uncertain("AMAP_EMPTY_GEOMETRY", origin=origin, destination=destination,
                                 waypoints=tuple(waypoints), straight_distance_m=straight,
                                 provider="amap", map_api_called=True)
            return RouteGeometryResult(
                available=True,
                distance_m=float(raw.get("distance_m", 0.0)),
                duration_s=float(raw.get("duration_s", 0.0)),
                polyline=tuple(tuple(p) for p in raw["polyline"]),
                provider="amap",
                geometry_source=GeometrySource.AMAP_ROAD,
                status=GeometryStatus.AMAP_VERIFIED,
                confidence=float(raw.get("confidence", 0.95)),
                reason_code="AMAP_VERIFIED",
                route_fingerprint=route_fingerprint(origin, destination, tuple(waypoints),
                                                    provider="amap", route_version=self.version),
                straight_distance_m=straight,
                origin=origin,
                destination=destination,
                waypoints=tuple(waypoints),
                timestamp=time.time(),
                route_version=self.version,
                map_api_called=True,
            )
        except Exception as ex:  # noqa: BLE001
            return uncertain(f"AMAP_ERROR:{type(ex).__name__}", origin=origin,
                             destination=destination, waypoints=tuple(waypoints),
                             straight_distance_m=straight, provider="amap", map_api_called=True)


class GeometrySanityValidator:
    MAX_ROAD_VS_STRAIGHT = 8.0

    def validate(self, result: RouteGeometryResult) -> tuple[bool, str]:
        if not result.available:
            return False, "NOT_AVAILABLE"
        if result.status in (GeometryStatus.ESTIMATED, GeometryStatus.UNCERTAIN):
            return False, "NOT_FORMAL"
        poly = result.polyline
        if not poly or len(poly) < 2:
            return False, "POLYLINE_TOO_SHORT"
        if result.distance_m < 0 or result.duration_s < 0:
            return False, "NEGATIVE_METRIC"
        if result.origin and haversine_m(poly[0], result.origin) > 2000:
            return False, "ORIGIN_MISMATCH"
        if result.destination and haversine_m(poly[-1], result.destination) > 2000:
            return False, "DEST_MISMATCH"
        if result.straight_distance_m > 0 and result.distance_m < result.straight_distance_m * 0.3:
            return False, "DISTANCE_BELOW_STRAIGHT"
        if result.straight_distance_m > 0 and result.distance_m > result.straight_distance_m * self.MAX_ROAD_VS_STRAIGHT:
            return False, "NETWORK_ANOMALY"
        return True, "OK"


class MapRoutingEngine:
    """Local(默认 GraphHopper) → Cache → Top-K AMap 校验。业务层不直接依赖 GraphHopper。"""

    def __init__(
        self,
        local: LocalRoutingEngine | None = None,
        amap: AMapRoutingPort | None = None,
        cache: GeometryCache | None = None,
        budget: RoutingCallBudget | None = None,
        sanity: GeometrySanityValidator | None = None,
        *,
        profile: str = "bus",
        graph_version: str = "",
    ):
        self.local = local if local is not None else LocalRoutingEngine(RoadGraph())
        self.amap = amap
        self.cache = cache or GeometryCache()
        self.budget = budget or RoutingCallBudget()
        self.sanity = sanity or GeometrySanityValidator()
        self.profile = profile
        self.graph_version = graph_version or getattr(self.local, "version", "")

    def resolve(
        self,
        origin: tuple[float, float],
        destination: tuple[float, float],
        waypoints: Sequence[tuple[float, float]] = (),
        *,
        route_type: RouteType = RouteType.FLEXIBLE_GAP,
        force_refresh: bool = False,
    ) -> RouteGeometryResult:
        key = route_fingerprint(
            origin, destination, tuple(waypoints), route_type=route_type,
            provider="hybrid",
            route_version=f"{getattr(self.local, 'version', '')}+{getattr(self.amap, 'version', 'none')}",
            profile=self.profile,
            graph_version=self.graph_version,
        )
        if not force_refresh:
            hit = self.cache.get(key)
            if hit is not None:
                self.budget.record_cache(True)
                hit.cache_hit = True
                return hit
        self.budget.record_cache(False)

        self.budget.record_local()
        local_res = self.local.route(origin, destination, waypoints, route_type=route_type)
        local_res.profile = getattr(local_res, "profile", self.profile) or self.profile
        local_res.graph_version = self.graph_version
        local_res.route_fingerprint = local_res.route_fingerprint or key
        ok, reason = self.sanity.validate(local_res)
        if local_res.status == GeometryStatus.LOCAL_ROAD and not ok:
            local_res = uncertain(reason, origin=origin, destination=destination,
                                  waypoints=tuple(waypoints),
                                  straight_distance_m=local_res.straight_distance_m)

        if local_res.is_formal:
            self.cache.put(key, local_res, force=force_refresh)
            return local_res

        # 非 formal：仅此处允许 AMap 校验（等价 Top-K/最终校验）
        if self.amap is not None and self.budget.record_amap():
            amap_res = self.amap.route(origin, destination, waypoints)
            if amap_res.is_formal and self.sanity.validate(amap_res)[0]:
                # Local vs AMap 偏差记录（不简单覆盖）
                if local_res.distance_m > 0:
                    self.budget.record_diff(local_res.distance_m, amap_res.distance_m)
                    amap_res.local_distance_m = local_res.distance_m
                    amap_res.local_duration_s = local_res.duration_s
                amap_res.profile = self.profile
                amap_res.graph_version = self.graph_version
                self.cache.put(key, amap_res, force=force_refresh)
                return amap_res
            out = uncertain("ROUTE_UNAVAILABLE", origin=origin, destination=destination,
                            waypoints=tuple(waypoints),
                            straight_distance_m=straight_lower_bound_m(origin, destination),
                            map_api_called=True)
            self.cache.put(key, out, force=force_refresh)
            return out

        out = uncertain(local_res.reason_code or "ROUTE_UNAVAILABLE",
                        origin=origin, destination=destination, waypoints=tuple(waypoints),
                        straight_distance_m=straight_lower_bound_m(origin, destination),
                        route_fingerprint=key)
        self.cache.put(key, out, force=force_refresh)
        return out

    def select_topk(
        self,
        candidates: Sequence[tuple[tuple[float, float], tuple[tuple[float, float], ...], tuple[float, float]]],
        *,
        route_type: RouteType = RouteType.FLEXIBLE_GAP,
        top_k: int = 3,
    ) -> list[RouteGeometryResult]:
        staged: list[tuple[float, int, RouteGeometryResult]] = []
        for i, (origin, wps, dest) in enumerate(candidates):
            est = self.local.estimate_only(origin, dest, wps)
            direct = straight_lower_bound_m(origin, dest)
            if est.distance_m > direct * 5 + 20000:
                self.budget.prefilter_rejected += 1
                continue
            res = self.resolve(origin, dest, wps, route_type=route_type)
            score = res.distance_m if res.distance_m > 0 else est.distance_m
            staged.append((score, i, res))
        staged.sort(key=lambda x: (x[0], x[1]))
        top = staged[:top_k]
        self.budget.topk_selected += len(top)
        return [r for _, _, r in top]

    def finalize(self, result: RouteGeometryResult) -> RouteGeometryResult:
        """DispatchPlan 绑定前校验：非 formal 一律 UNCERTAIN。"""
        ok, reason = self.sanity.validate(result)
        if not ok or not result.is_formal:
            if result.status == GeometryStatus.ESTIMATED and len(result.polyline) <= 2:
                self.budget.record_formal_straight()
            return uncertain(result.reason_code or reason,
                             origin=result.origin, destination=result.destination,
                             waypoints=result.waypoints,
                             straight_distance_m=result.straight_distance_m,
                             route_fingerprint=result.route_fingerprint)
        return result
