"""Route Geometry Cache + RoutingCallBudget：稳定指纹，降高德调用。"""

from __future__ import annotations

import hashlib
import time
from dataclasses import dataclass

from .models import GeometryStatus, RouteGeometryResult, RouteType


def route_fingerprint(
    origin: tuple[float, float],
    destination: tuple[float, float],
    waypoints: tuple[tuple[float, float], ...] = (),
    *,
    route_type: RouteType | str = RouteType.FLEXIBLE_GAP,
    provider: str = "local",
    route_version: str = "v1",
    profile: str = "bus",
    graph_version: str = "",
    precision: int = 5,
) -> str:
    def fmt(p: tuple[float, float]) -> str:
        return f"{round(p[0], precision)},{round(p[1], precision)}"

    parts = [
        fmt(origin),
        *(fmt(w) for w in waypoints),
        fmt(destination),
        str(route_type),
        provider,
        route_version,
        profile,
        graph_version,
    ]
    return hashlib.sha1("|".join(parts).encode("utf-8")).hexdigest()[:16]


@dataclass
class CacheEntry:
    result: RouteGeometryResult
    retrieved_at: float
    expires_at: float
    failure: bool = False

    def is_expired(self, now: float) -> bool:
        return now >= self.expires_at


class GeometryCache:
    def __init__(self, *, ttl_s: float = 300.0, failure_ttl_s: float = 30.0, max_size: int = 2048):
        self.ttl_s = ttl_s
        self.failure_ttl_s = failure_ttl_s
        self.max_size = max_size
        self._store: dict[str, CacheEntry] = {}
        self.hits = 0
        self.misses = 0
        self.forced_refreshes = 0

    def get(self, key: str, *, now: float | None = None, allow_stale: bool = False) -> RouteGeometryResult | None:
        now = time.time() if now is None else now
        entry = self._store.get(key)
        if entry is None:
            self.misses += 1
            return None
        if entry.is_expired(now) and not allow_stale:
            self.misses += 1
            return None
        self.hits += 1
        entry.result.cache_hit = True
        return entry.result

    def put(self, key: str, result: RouteGeometryResult, *, now: float | None = None, force: bool = False) -> None:
        now = time.time() if now is None else now
        if force:
            self.forced_refreshes += 1
        if not force and key in self._store and not self._store[key].is_expired(now):
            return
        failure = (not result.available) or result.status == GeometryStatus.UNCERTAIN
        ttl = self.failure_ttl_s if failure else self.ttl_s
        if result.status == GeometryStatus.ESTIMATED:
            ttl = min(ttl, 10.0)
        if len(self._store) >= self.max_size:
            oldest = min(self._store.items(), key=lambda kv: kv[1].retrieved_at)[0]
            self._store.pop(oldest, None)
        self._store[key] = CacheEntry(result=result, retrieved_at=now, expires_at=now + ttl, failure=failure)

    def mark_stale(self, key: str) -> None:
        e = self._store.get(key)
        if e:
            e.expires_at = 0.0

    def stats(self) -> dict:
        total = self.hits + self.misses
        return {
            "hits": self.hits,
            "misses": self.misses,
            "hit_rate": self.hits / total if total else 0.0,
            "size": len(self._store),
            "forced_refreshes": self.forced_refreshes,
        }


class RoutingCallBudget:
    def __init__(self, amap_limit: int | None = None):
        self.local_calls = 0
        self.amap_calls = 0
        self.cache_hits = 0
        self.cache_misses = 0
        self.prefilter_rejected = 0
        self.topk_selected = 0
        self.straight_fallback_formal = 0
        self.amap_limit = amap_limit
        self.local_distance_sum = 0.0
        self.amap_distance_sum = 0.0
        self.compare_n = 0

    def record_local(self) -> None:
        self.local_calls += 1

    def record_amap(self) -> bool:
        if self.amap_limit is not None and self.amap_calls >= self.amap_limit:
            return False
        self.amap_calls += 1
        return True

    def record_cache(self, hit: bool) -> None:
        if hit:
            self.cache_hits += 1
        else:
            self.cache_misses += 1

    def record_formal_straight(self) -> None:
        self.straight_fallback_formal += 1

    def record_diff(self, local_m: float, amap_m: float) -> None:
        self.local_distance_sum += local_m
        self.amap_distance_sum += amap_m
        self.compare_n += 1

    def summary(self) -> dict:
        total = self.cache_hits + self.cache_misses
        baseline = self.local_calls + self.amap_calls
        return {
            "local_routing_calls": self.local_calls,
            "amap_calls": self.amap_calls,
            "cache_hits": self.cache_hits,
            "cache_misses": self.cache_misses,
            "cache_hit_rate": self.cache_hits / total if total else 0.0,
            "amap_reduction_rate": 1.0 - self.amap_calls / baseline if baseline else 0.0,
            "prefilter_rejected": self.prefilter_rejected,
            "topk_selected": self.topk_selected,
            "straight_line_formal_plan_count": self.straight_fallback_formal,
        }
