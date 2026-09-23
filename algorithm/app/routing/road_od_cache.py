"""RoadODCache / StationSnapCache / RoutingSession：图只加载一次，OD 不重复搜索。"""

from __future__ import annotations

import json
import time
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Sequence

from .local_routing import RoadGraph, haversine_m
from .models import RouteGeometryResult, RouteType


@dataclass
class ODEntry:
    origin_snap: str
    dest_snap: str
    profile: str
    graph_version: str
    routing_mode: str
    distance_m: float
    duration_s: float
    polyline: tuple[tuple[float, float], ...]
    path_nodes: tuple[str, ...]
    status: str
    provider: str
    timestamp: float = field(default_factory=time.time)


@dataclass
class SnapEntry:
    station_id: str
    lat: float
    lon: float
    road_node_id: str | None
    snap_distance_m: float
    graph_version: str


class StationSnapCache:
    """真实站点一次性 snap；禁止每样本重 snap。"""

    def __init__(self, graph_version: str = ""):
        self.graph_version = graph_version
        self._snaps: dict[str, SnapEntry] = {}
        self.hits = 0
        self.misses = 0

    def snap(self, station_id: str, lat: float, lon: float, graph: RoadGraph) -> SnapEntry:
        key = f"{station_id}|{self.graph_version}"
        if key in self._snaps:
            self.hits += 1
            return self._snaps[key]
        self.misses += 1
        nid, d = graph.nearest_node((lat, lon), max_snap_m=2000.0)
        entry = SnapEntry(station_id, lat, lon, nid, d, self.graph_version)
        self._snaps[key] = entry
        return entry

    def stats(self) -> dict:
        total = self.hits + self.misses
        return {"hits": self.hits, "misses": self.misses,
                "hit_rate": self.hits / total if total else 0.0}


class RoadODCache:
    """origin_snap + dest_snap + profile + graph_version + mode 为键。"""

    def __init__(self, disk_path: str | Path | None = None, graph_version: str = ""):
        self.graph_version = graph_version
        self._mem: dict[tuple, ODEntry] = {}
        self.disk_path = Path(disk_path) if disk_path else None
        self.hits = 0
        self.misses = 0
        if self.disk_path and self.disk_path.exists():
            self._load_disk()

    def _key(self, o: str, d: str, profile: str, mode: str) -> tuple:
        return (o, d, profile, self.graph_version, mode)

    def get(self, origin_snap: str, dest_snap: str, profile: str = "bus",
            mode: str = "ch") -> ODEntry | None:
        k = self._key(origin_snap, dest_snap, profile, mode)
        e = self._mem.get(k)
        if e is not None:
            self.hits += 1
            return e
        self.misses += 1
        return None

    def put(self, entry: ODEntry) -> None:
        if entry.graph_version != self.graph_version:
            return  # graph_version 变化自动失效
        k = self._key(entry.origin_snap, entry.dest_snap, entry.profile, entry.routing_mode)
        self._mem[k] = entry

    def _load_disk(self) -> None:
        try:
            for line in self.disk_path.read_text(encoding="utf-8").splitlines():
                if not line.strip():
                    continue
                d = json.loads(line)
                e = ODEntry(
                    origin_snap=d["origin_snap"], dest_snap=d["dest_snap"],
                    profile=d["profile"], graph_version=d["graph_version"],
                    routing_mode=d["routing_mode"], distance_m=d["distance_m"],
                    duration_s=d["duration_s"],
                    polyline=tuple(tuple(p) for p in d.get("polyline", ())),
                    path_nodes=tuple(d.get("path_nodes", ())),
                    status=d["status"], provider=d.get("provider", "local"),
                    timestamp=d.get("timestamp", 0.0),
                )
                if e.graph_version == self.graph_version:
                    self.put(e)
        except Exception:
            pass

    def save_disk(self) -> None:
        if not self.disk_path:
            return
        self.disk_path.parent.mkdir(parents=True, exist_ok=True)
        with self.disk_path.open("w", encoding="utf-8") as f:
            for e in self._mem.values():
                d = asdict(e)
                d["polyline"] = [list(p) for p in e.polyline]
                d["path_nodes"] = list(e.path_nodes)
                f.write(json.dumps(d) + "\n")

    def stats(self) -> dict:
        total = self.hits + self.misses
        return {"hits": self.hits, "misses": self.misses,
                "hit_rate": self.hits / total if total else 0.0,
                "size": len(self._mem)}


class RoutingSession:
    """一次训练运行：图/缓存只初始化一次；禁止 sample 级重建。"""

    def __init__(self, graph: RoadGraph, graph_version: str = "", od_disk: str | Path | None = None):
        self.graph = graph
        self.graph_version = graph_version
        self.snap_cache = StationSnapCache(graph_version)
        self.od_cache = RoadODCache(od_disk, graph_version)
        self.routing_mode = "ch"  # CH/LM 预处理模式；本地 graph 用 cached-dijkstra
        self.graph_loads = 1
        self.query_count = 0

    def od_key(self, origin: tuple[float, float], destination: tuple[float, float],
               waypoints: Sequence[tuple[float, float]] = ()) -> str:
        def f(p):
            return f"{round(p[0], 5)},{round(p[1], 5)}"
        return f"{f(origin)}|{','.join(f(w) for w in waypoints)}|{f(destination)}"

    def cached_od(
        self,
        origin_snap: str,
        dest_snap: str,
        origin: tuple[float, float],
        destination: tuple[float, float],
        *,
        profile: str = "bus",
        compute=None,
    ) -> ODEntry | None:
        """命中 cache 直接返回；否则 compute() 一次并写入。"""
        self.query_count += 1
        hit = self.od_cache.get(origin_snap, dest_snap, profile, self.routing_mode)
        if hit is not None:
            return hit
        if compute is None:
            return None
        result = compute()
        if result is None:
            return None
        entry = ODEntry(
            origin_snap=origin_snap, dest_snap=dest_snap, profile=profile,
            graph_version=self.graph_version, routing_mode=self.routing_mode,
            distance_m=result.get("distance_m", 0.0),
            duration_s=result.get("duration_s", 0.0),
            polyline=tuple(tuple(p) for p in result.get("polyline", ())),
            path_nodes=tuple(result.get("path_nodes", ())),
            status=result.get("status", "LOCAL_ROAD"),
            provider=result.get("provider", "local_graph"),
        )
        self.od_cache.put(entry)
        return entry

    def stats(self) -> dict:
        return {
            "graph_loads": self.graph_loads,
            "query_count": self.query_count,
            "od_cache": self.od_cache.stats(),
            "snap_cache": self.snap_cache.stats(),
            "graph_nodes": len(self.graph.nodes),
            "graph_edges": len(self.graph.edges),
            "routing_mode": self.routing_mode,
        }
