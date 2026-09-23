"""Branch / Edge 静态特征缓存：同一 branch 不重复构造 STATIC 特征。

Cache key: graph_version | node_id | target_region_id | profile
仅缓存与 cum/origin 动态无关的列；DYNAMIC 在调用时增量拼接。
"""

from __future__ import annotations

import time
from dataclasses import dataclass, field

import numpy as np

from app.routing.local_routing import RoadEdge, RoadGraph, haversine_m

# EDGE_FEATURES 中静态列（顺序与 learning.path_search.EDGE_FEATURES 对齐的子集）
STATIC_KEYS = (
    "edge_road_m", "edge_duration_s", "edge_class_code", "edge_speed",
    "is_oneway", "intersection_degree", "turn_angle_deg",
    "is_bridge_like", "is_tunnel_like", "is_highway_like",
)
DYNAMIC_KEYS = (
    "origin_dist_m", "dest_dist_m", "remaining_lower_bound_m",
    "cum_dist_m", "cum_time_s", "progress_ratio", "gap_index",
    "passenger_impact_est", "cargo_detour_est", "trip_locked",
    "sla_remaining_s", "capacity_remaining", "multi_leg_state",
    "urban_density_proxy",
)


def static_vector(e: RoadEdge) -> np.ndarray:
    class_code = {"motorway": 5, "trunk": 4, "primary": 3, "secondary": 2, "tertiary": 1}.get(
        (e.road_class or "").lower(), 0.0
    )
    speed = (e.road_m / e.duration_s * 3.6) if e.duration_s > 0 else 30.0
    # 静态航向：首尾点方位差
    heading = 0.0
    if e.polyline and len(e.polyline) >= 2:
        (la0, lo0), (la1, lo1) = e.polyline[0], e.polyline[-1]
        heading = float(np.degrees(np.arctan2(lo1 - lo0, la1 - la0)))
    return np.asarray([
        float(e.road_m),
        float(e.duration_s or e.road_m / 8.0),
        float(class_code),
        float(max(1.0, speed)),
        0.0 if e.bidirectional else 1.0,
        0.0,
        heading,
        0.0,
        0.0,
        1.0 if class_code >= 3 else 0.0,
    ], dtype=np.float64)


def edge_key(e: RoadEdge) -> str:
    return f"{e.u}|{e.v}|{round(e.road_m, 1)}"


@dataclass
class CacheCounters:
    hit: int = 0
    miss: int = 0
    saved_ms: float = 0.0
    lookup_ms: float = 0.0
    build_ms: float = 0.0

    @property
    def hit_rate(self) -> float:
        n = self.hit + self.miss
        return self.hit / n if n else 0.0

    def as_dict(self) -> dict:
        return {
            "feature_cache_hit": self.hit,
            "feature_cache_miss": self.miss,
            "feature_cache_hit_rate": round(self.hit_rate, 4),
            "feature_cache_saved_ms": round(self.saved_ms, 3),
            "feature_cache_lookup_ms": round(self.lookup_ms, 3),
            "feature_cache_build_ms": round(self.build_ms, 3),
        }


class BranchFeatureCache:
    """node+target_region+profile 级 branch 静态特征；边级 static 可跨 branch 复用。"""

    def __init__(self, graph_version: str = "osm-1", profile: str = "bus"):
        self.graph_version = graph_version
        self.profile = profile
        self.edge_static: dict[str, np.ndarray] = {}
        self.branch_static: dict[tuple, tuple[tuple[str, ...], np.ndarray]] = {}
        self.counters = CacheCounters()

    def _bkey(self, node_id: str, target_region_id: str) -> tuple:
        return (self.graph_version, node_id, target_region_id, self.profile)

    def edge_static_row(self, e: RoadEdge) -> np.ndarray:
        k = edge_key(e)
        t0 = time.perf_counter()
        row = self.edge_static.get(k)
        if row is not None:
            self.counters.hit += 1
            self.counters.lookup_ms += (time.perf_counter() - t0) * 1000
            self.counters.saved_ms += 0.05  # 保守：省一次 static 计算
            return row
        self.counters.miss += 1
        row = static_vector(e)
        self.edge_static[k] = row
        self.counters.build_ms += (time.perf_counter() - t0) * 1000
        return row

    def branch_batch(
        self,
        node_id: str,
        target_region_id: str,
        edges: list[RoadEdge],
    ) -> tuple[list[str], np.ndarray]:
        """返回 (edge_ids, static_matrix[len,n_static])。"""
        key = self._bkey(node_id, target_region_id)
        t0 = time.perf_counter()
        cached = self.branch_static.get(key)
        ids_now = tuple(edge_key(e) for e in edges)
        if cached is not None and cached[0] == ids_now:
            self.counters.hit += 1
            self.counters.lookup_ms += (time.perf_counter() - t0) * 1000
            self.counters.saved_ms += 0.08
            return list(cached[0]), cached[1]
        self.counters.miss += 1
        mat = np.vstack([self.edge_static_row(e) for e in edges]) if edges else np.zeros((0, len(STATIC_KEYS)))
        self.branch_static[key] = (ids_now, mat)
        self.counters.build_ms += (time.perf_counter() - t0) * 1000
        return list(ids_now), mat

    def counters_dict(self) -> dict:
        return self.counters.as_dict()


class GuardCache:
    """同 graph_version+target_node+profile 的反向可达 / 桥接 只算一次。"""

    def __init__(self):
        self.store: dict[tuple, object] = {}
        self.hit = 0
        self.miss = 0
        self.build_ms = 0.0
        self.lookup_ms = 0.0

    def get(self, graph_version: str, target_node: str, profile: str, builder):
        key = (graph_version, target_node, profile)
        t0 = time.perf_counter()
        if key in self.store:
            self.hit += 1
            self.lookup_ms += (time.perf_counter() - t0) * 1000
            return self.store[key]
        self.miss += 1
        t1 = time.perf_counter()
        obj = builder()
        self.build_ms += (time.perf_counter() - t1) * 1000
        self.store[key] = obj
        return obj

    def as_dict(self) -> dict:
        return {
            "guard_cache_hit": self.hit,
            "guard_cache_miss": self.miss,
            "guard_cache_build_ms": round(self.build_ms, 3),
            "guard_cache_lookup_ms": round(self.lookup_ms, 3),
        }
