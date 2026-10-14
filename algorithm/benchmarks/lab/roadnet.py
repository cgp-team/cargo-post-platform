"""真实路网接入层：重庆 OSM 图 + 真实公交线路 + OD 距离磁盘缓存。

为什么要这一层（而不是继续用合成坐标 + 直线距离）：
- 直线距离下 max_detour_km=2.0 这类以公里计的硬约束几乎没有约束力，
  「绕行」这个业务核心量根本没被真正测过；
- 合成网格坐标会让参数往某一种几何上漂（过拟合），真实路网的非欧性
  （江河、山体、单行、桥隧）才是算法要面对的分布。

数据来源（全部本地、可追溯，不凭空造点）：
- 图：data/road_graph_cq.pkl —— 由 tools/osm-data/chongqing-260921.osm.pbf 导入
  （2,230,689 节点 / 2,287,515 无向边），bus 车型 profile，限速按道路等级（step0 口径）。
- 线路：data/transit/snapshots/osm_routes_full.json —— 477 条真实公交线路，
  11,678 个真实站点（WGS84），stop_ids 为按序停靠站。
- 算路：app/routing/local_routing.LocalRoutingEngine（A* + 真实路段长度/等级速度）。

口径约定：
- 算路一律用 WGS84（图就是 WGS84）；写进 PlanRequest 的坐标转成 GCJ-02（契约要求），
  两者分别存 RealStop.wgs / RealStop.gcj，不做二次换算。
- OD 结果落盘缓存并随语料一起冻结：同一对站点在任何版本的任何一次测量里读到
  同一个 (km, s)，否则版本间的 gap 不是同一把尺子。
"""

from __future__ import annotations

import hashlib
import json
import os
import pickle
import time
from dataclasses import dataclass

from app.routing.coordinate import to_gcj02
from app.routing.local_routing import LocalRoutingEngine

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
GRAPH_PKL = os.path.join(ROOT, "data", "road_graph_cq.pkl")
ROUTES_JSON = os.path.join(ROOT, "data", "transit", "snapshots", "osm_routes_full.json")
CACHE_DIR = os.path.join(os.path.dirname(__file__), "cache")
SPEED_M_S = 8.0  # 与 step0_build_local_graph 的等级速度口径一致的公交均速


@dataclass(frozen=True)
class RealStop:
    station_id: str
    name: str
    wgs: tuple[float, float]   # (lat, lon) WGS84，算路用
    gcj: tuple[float, float]   # (lat, lon) GCJ-02，写进请求用


@dataclass(frozen=True)
class RealLine:
    route_id: str
    name: str
    stops: tuple[RealStop, ...]


def _graph_version() -> str:
    st = os.stat(GRAPH_PKL)
    return hashlib.sha1(f"{os.path.basename(GRAPH_PKL)}:{st.st_size}:{int(st.st_mtime)}".encode()).hexdigest()[:12]


class RoadNet:
    """真实路网门面：懒加载图 + 持久化 OD 缓存。"""

    def __init__(self, cache_name: str = "roadod"):
        self.version = _graph_version()
        self._cache_path = os.path.join(CACHE_DIR, f"{cache_name}-{self.version}.json")
        self._cache: dict[str, list[float]] = {}
        if os.path.exists(self._cache_path):
            with open(self._cache_path, encoding="utf-8") as fh:
                self._cache = json.load(fh)
        self._eng: LocalRoutingEngine | None = None
        self._routes: dict | None = None

    # ── 图 ────────────────────────────────────────────────
    @property
    def engine(self) -> LocalRoutingEngine:
        if self._eng is None:
            t0 = time.perf_counter()
            with open(GRAPH_PKL, "rb") as fh:
                graph = pickle.load(fh)
            eng = LocalRoutingEngine(graph, speed_m_s=SPEED_M_S, vehicle_profile="bus")
            eng._adj()  # 预热邻接表（2.2M 边，冷建 6s）
            self._eng = eng
            self.load_seconds = round(time.perf_counter() - t0, 1)
        return self._eng

    # ── 真实线路 ──────────────────────────────────────────
    def stops_index(self) -> dict[str, RealStop]:
        if self._routes is None:
            with open(ROUTES_JSON, encoding="utf-8") as fh:
                self._routes = json.load(fh)
            idx = {}
            for s in self._routes["stations"]:
                wgs = (s["latitude"], s["longitude"])
                idx[s["station_id"]] = RealStop(
                    station_id=s["station_id"], name=s.get("name", ""),
                    wgs=wgs, gcj=to_gcj02(wgs),
                )
            self._stops = idx
        return self._stops

    def lines(self, min_stops: int = 6, max_stops: int = 24) -> list[RealLine]:
        idx = self.stops_index()
        out = []
        for r in self._routes["routes"]:
            stops = tuple(idx[s] for s in r["stop_ids"] if s in idx)
            if min_stops <= len(stops) <= max_stops and len(set(s.station_id for s in stops)) == len(stops):
                out.append(RealLine(route_id=r["route_id"], name=r["name"], stops=stops))
        return sorted(out, key=lambda l: l.route_id)

    # ── OD ───────────────────────────────────────────────
    def od(self, a: RealStop, b: RealStop) -> tuple[float, float]:
        """真实路段口径的 (公里, 秒)。同一条 OD 永远读缓存，保证跨版本可比。"""
        key = "|".join(sorted((a.station_id, b.station_id)))
        if a.station_id == b.station_id:
            return (0.0, 0.0)
        hit = self._cache.get(key)
        if hit is not None:
            return (hit[0], hit[1])
        eng = self.engine
        org, dst = (a.wgs, b.wgs) if a.station_id < b.station_id else (b.wgs, a.wgs)
        res = eng.route(org, dst)
        if not res.available:
            # 图不可达：按直线 1.3 倍估算并标注，交给上层决定是否剔除该样本
            straight = self._straight_km(a, b)
            val = [round(straight * 1.3, 4), round(straight * 1.3 / SPEED_M_S * 1000, 1)]
        else:
            val = [round(res.distance_m / 1000.0, 4), round(res.duration_s, 1)]
        self._cache[key] = val
        return (val[0], val[1])

    @staticmethod
    def _straight_km(a: RealStop, b: RealStop) -> float:
        from app.routing.local_routing import haversine_m
        return haversine_m(a.wgs, b.wgs) / 1000.0

    def matrix(self, stops: list[RealStop]) -> dict[tuple[str, str], tuple[float, float]]:
        m: dict[tuple[str, str], tuple[float, float]] = {}
        for a in stops:
            for b in stops:
                if a.station_id == b.station_id:
                    m[(a.station_id, b.station_id)] = (0.0, 0.0)
                else:
                    km, s = self.od(a, b)
                    m[(a.station_id, b.station_id)] = (km, s)
        return m

    def save(self) -> None:
        os.makedirs(CACHE_DIR, exist_ok=True)
        with open(self._cache_path, "w", encoding="utf-8") as fh:
            json.dump(self._cache, fh)

    @property
    def cache_size(self) -> int:
        return len(self._cache)
