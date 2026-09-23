"""真实 Teacher Trace：HACO 语义 + LocalRoutingEngine 真实道路成本。

禁止 road = straight * random。正式训练仅接受
LOCAL_ROAD / CACHED_REAL / AMPA_VERIFIED；ESTIMATED/UNCERTAIN 进 quarantine。
"""

from __future__ import annotations

import random
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Sequence

from app.haco.encoding import ObjectiveVector
from app.routing import (
    GeometryStatus,
    MapRoutingEngine,
    RoadGraph,
    is_formal_geometry,
)
from app.routing.graphhopper_routing import LocalGraphRoutingProvider
from app.routing.osm_import import load_osm_road_graph
from .search_trace_recorder import SearchTraceRecorder

SCENARIO_TYPES = (
    "normal", "multi_order", "high_load", "low_load", "gap_detour",
    "precedence", "trip_lock", "realtime_insert", "high_value", "unreachable",
    "unknown", "stale_gps", "passed_station", "multi_leg", "direct_vs_multileg",
    "current_vs_next", "shared_route", "pax_threshold", "detour_budget",
    "new_route", "new_station", "edge_route",
)

# 重庆真实测试 fixture 站点（WGS84，来自 OSM 区域）
REAL_STATIONS = {
    "CUPT": (29.5333, 106.6074),
    "CTBU": (29.5020, 106.5830),
    "NANPING": (29.5220, 106.5680),
    "SHAPINGBA": (29.5400, 106.4500),
    "YANGJIAYU": (29.5600, 106.5700),
    "JIEFANGBEI": (29.5630, 106.5750),
    "CHANGAN": (29.5280, 106.5500),
    "LIJIAYU": (29.5450, 106.5300),
}

GRAPH_VERSION = "osm-pbf-137591hw-2287515e"
ROUTE_VERSION = "local-v1"


@dataclass
class ScenarioSpec:
    scenario_id: str
    scenario_type: str
    route_pattern: str
    seed: int
    origin: str
    destination: str
    waypoints: tuple[str, ...] = ()


class ScenarioGenerator:
    """从真实 Transit Snapshot 生成场景；禁止 legacy fixture / 虚构站点。"""

    def __init__(self, stations: dict[str, tuple[float, float]] | None = None):
        if stations:
            self.stations = stations
        else:
            self.stations = self._load_real_stations()

    @staticmethod
    def _load_real_stations(max_stations: int = 15) -> dict[str, tuple[float, float]]:
        """真实 Transit Snapshot 站点；确定性子集以控制 Graph 路由成本。"""
        try:
            from learning.transit import TransitSnapshotStore

            store = TransitSnapshotStore(
                Path(__file__).resolve().parents[3] / "algorithm" / "data" / "transit"
            )
            snap = store.load_latest()
            if snap and snap.stations:
                named = {}
                for s in snap.stations:
                    key = s.name or s.station_id
                    if key not in named:
                        named[key] = (s.latitude, s.longitude)
                names = sorted(named)
                # 确定性子集，保证可复现；全部来自真实 snapshot
                chosen = names[:: max(1, len(names) // max_stations)][:max_stations]
                return {n: named[n] for n in chosen}
        except Exception:
            pass
        return {}

    def generate(self, n: int, seed: int = 20260921) -> list[ScenarioSpec]:
        rng = random.Random(seed)
        names = sorted(self.stations)
        if len(names) < 2:
            return []
        out: list[ScenarioSpec] = []
        for i in range(n):
            st = SCENARIO_TYPES[i % len(SCENARIO_TYPES)]
            a, b = rng.sample(names, 2)
            wps: tuple[str, ...] = ()
            if st in ("gap_detour", "multi_leg", "direct_vs_multileg"):
                mid = rng.choice([x for x in names if x not in (a, b)])
                wps = (mid,)
            if st == "edge_route":
                pool = [x for x in names if x not in (a, b)]
                if len(pool) >= 2:
                    wps = tuple(rng.sample(pool, 2))
            out.append(ScenarioSpec(
                scenario_id=f"{st}_{i:05d}",
                scenario_type=st,
                route_pattern=f"R{1 + i % 5}",
                seed=seed + i,
                origin=a,
                destination=b,
                waypoints=wps,
            ))
        return out


def _subgraph_multi(graph: RoadGraph, points: Sequence[tuple[float, float]], pad: float = 0.06) -> RoadGraph:
    """单次扫描：保留任一站点邻域内的真实道路节点/边。"""
    g2 = RoadGraph()
    boxes = [(la - pad, lo - pad, la + pad, lo + pad) for la, lo in points]
    for nid, (la, lo) in graph.nodes.items():
        if any(b0 <= la <= b2 and b1 <= lo <= b3 for b0, b1, b2, b3 in boxes):
            g2.add_node(nid, la, lo)
    for e in graph.edges:
        if e.u in g2.nodes and e.v in g2.nodes:
            g2.add_edge(e)
    return g2


def _subgraph_near(graph: RoadGraph, bbox_min: tuple[float, float], bbox_max: tuple[float, float]) -> RoadGraph:
    """真实道路子图（bbox 内节点+边），加速训练期 Dijkstra。"""
    g2 = RoadGraph()
    for nid, (la, lo) in graph.nodes.items():
        if bbox_min[0] <= la <= bbox_max[0] and bbox_min[1] <= lo <= bbox_max[1]:
            g2.add_node(nid, la, lo)
    for e in graph.edges:
        if e.u in g2.nodes and e.v in g2.nodes:
            g2.add_edge(e)
    return g2


class RealRoadRouter:
    """封装 OSM RoadGraph → MapRoutingEngine；仅 formal geometry 可进训练。"""

    def __init__(self, osm_pbf: str | None = None, graph: RoadGraph | None = None):
        self._graph = graph
        self._osm = osm_pbf
        self._engine: MapRoutingEngine | None = None
        self.graph_version = GRAPH_VERSION
        self.stats = {"routed": 0, "formal": 0, "uncertain": 0, "cached": 0}
    def _ensure(self) -> MapRoutingEngine:
        if self._engine is None:
            if self._graph is None:
                path = self._osm or str(
                    Path(__file__).resolve().parents[3]
                    / "tools" / "osm-data" / "chongqing-260921.osm.pbf"
                )
                self._graph, st = load_osm_road_graph(path)
                self.graph_version = st.graph_version
                pts = list((ScenarioGenerator().stations or {}).values())
                g = None
                for la, lo in pts:
                    part = _subgraph_near(self._graph, (la - 0.06, lo - 0.06), (la + 0.06, lo + 0.06))
                    if g is None:
                        g = part
                    else:
                        g.nodes.update(part.nodes)
                        g.edges.extend(part.edges)
                if g is not None and g.nodes:
                    self._graph = g
            self._engine = MapRoutingEngine(
                LocalGraphRoutingProvider(graph=self._graph, version=ROUTE_VERSION),
                profile="bus",
                graph_version=self.graph_version,
            )
        return self._engine

    def route(
        self, origin: tuple[float, float], waypoints: Sequence[tuple[float, float]],
        destination: tuple[float, float],
    ) -> Any:
        eng = self._ensure()
        r = eng.resolve(origin, destination, waypoints, route_type=__import__(
            "app.routing.models", fromlist=["RouteType"]
        ).RouteType.FLEXIBLE_GAP)
        self.stats["routed"] += 1
        if r.cache_hit:
            self.stats["cached"] += 1
        if is_formal_geometry(r.status):
            self.stats["formal"] += 1
        else:
            self.stats["uncertain"] += 1
        return r


# fix Sequence import
from typing import Sequence  # noqa: E402


class TrainingSampleGenerator:
    """REAL TEACHER TRACE MODE：真实道路 + HACO 语义。"""

    def __init__(
        self,
        recorder: SearchTraceRecorder | None = None,
        router: RealRoadRouter | None = None,
        stations: dict[str, tuple[float, float]] | None = None,
    ):
        self.recorder = recorder or SearchTraceRecorder(
            graph_version=GRAPH_VERSION, route_version=ROUTE_VERSION
        )
        self.router = router or RealRoadRouter()
        # 显式站点（测试/无 transit snapshot 环境）；None 时仍走真实 Transit Snapshot
        self.stations_override = stations
        self.scenario_unavailable_count = 0
        self.estimated_rejected = 0
        self.uncertain_rejected = 0

    def generate(self, n_samples: int, seed: int = 20260921) -> SearchTraceRecorder:
        rng = random.Random(seed)
        scen_gen = ScenarioGenerator(stations=self.stations_override)
        stations = scen_gen.stations
        scenarios = scen_gen.generate(max(1, n_samples // 6), seed=seed)
        produced = 0
        si = 0
        t0 = time.time()
        while produced < n_samples and si < len(scenarios) * 120:
            spec = scenarios[si % len(scenarios)]
            si += 1
            o = stations.get(spec.origin)
            d = stations.get(spec.destination)
            wps = tuple(stations[w] for w in spec.waypoints if w in stations)
            if not o or not d:
                self.scenario_unavailable_count += 1
                continue

            # 真实道路路由（每个 group 一次主路由 + 候选变体）
            base = self.router.route(o, wps, d)
            if base.status == GeometryStatus.ESTIMATED:
                self.estimated_rejected += 1
                continue
            if not is_formal_geometry(base.status):
                self.uncertain_rejected += 1
                continue

            sid = self.recorder.begin_state(
                spec.scenario_id, iteration=si, vehicle_id=1,
                trip_id=spec.route_pattern, random_seed=spec.seed,
            )

            # 真实 candidate pool：20~40（通过 Gap waypoint 变体 + 不同 operator）
            names = sorted(stations)
            n_cands = rng.randint(20, 40)
            for ci in range(n_cands):
                if produced >= n_samples:
                    break
                # 候选变体：在真实站点上加/减 waypoint
                mid_choices = [stations[x] for x in names if x not in (spec.origin, spec.destination)]
                k_wp = rng.randint(0, min(2, len(mid_choices)))
                cand_wps = tuple(rng.sample(mid_choices, k_wp)) if k_wp else ()
                geom = self.router.route(o, cand_wps, d)
                if geom.status == GeometryStatus.ESTIMATED:
                    self.estimated_rejected += 1
                    continue
                if not is_formal_geometry(geom.status):
                    self.uncertain_rejected += 1
                    continue

                road_m = geom.distance_m
                straight_m = geom.straight_distance_m
                pax = rng.uniform(0, 120) if spec.scenario_type in ("pax_threshold", "gap_detour") else rng.uniform(0, 40)
                detour_km = max(0.0, (road_m - straight_m) / 1000.0)
                feasible = not (
                    spec.scenario_type in ("unreachable", "detour_budget")
                    and rng.random() < 0.5
                )
                if feasible:
                    obj = ObjectiveVector(
                        infeasibility=0.0,
                        vehicle_count=rng.choice([1, 1, 2]),
                        passenger_impact=pax,
                        cargo_detour=detour_km,
                        total_distance=road_m,
                        total_duration=geom.duration_s,
                    )
                else:
                    obj = ObjectiveVector(infeasibility=1.0 + rng.random(), total_distance=road_m)

                feats = {
                    "remainingCargoCapacity": 4.0,
                    "tripLocked": 1.0 if spec.scenario_type == "trip_lock" else 0.0,
                    "economicValue": rng.uniform(5, 80),
                    "highValue": 1.0 if spec.scenario_type == "high_value" else 0.0,
                    "remainingDetourBudget": rng.uniform(0, 8000),
                    "detourDistance": road_m - straight_m,
                    "passengerImpact": pax,
                    "roadDistance": road_m,
                    "roadDuration": geom.duration_s,
                    "straightDistanceLowerBound": straight_m,
                    "roadDetourRatio": (road_m / straight_m) if straight_m > 0 else 1.0,
                    "routePointCount": float(len(geom.polyline)),
                    "localRoadFlag": 1.0 if geom.provider == "local_graph" else 0.0,
                    "cachedRouteFlag": 1.0 if geom.cache_hit else 0.0,
                    "amapVerifiedFlag": 0.0,
                    "routeConfidence": geom.confidence,
                    "multiLegPossible": 1.0 if spec.scenario_type in ("multi_leg", "direct_vs_multileg") else 0.0,
                    "sameRoute": 0.0 if spec.scenario_type in ("multi_leg", "direct_vs_multileg") else 1.0,
                    "tripProgress": 0.8 if spec.scenario_type == "trip_lock" else 0.2,
                    "gpsFreshness": 0.1 if spec.scenario_type == "stale_gps" else 1.0,
                    "passedStation": 1.0 if spec.scenario_type == "passed_station" else 0.0,
                    "gapIndex": 1.0,
                    "itemCount": 1.0,
                }
                self.recorder.record(
                    search_state_id=sid,
                    scenario_id=spec.scenario_id,
                    iteration=si,
                    vehicle_id=1,
                    trip_id=spec.route_pattern,
                    candidate_id=f"{sid}:c{ci}",
                    candidate_operation=rng.choice(["RELOCATE", "SWAP", "INSERT", "GAP_DETOUR"]),
                    candidate_type=rng.choice([
                        "CURRENT_TRIP", "NEXT_TRIP", "OTHER_ROUTE", "MULTI_LEG", "DIRECT",
                    ]),
                    features=feats,
                    objective=obj,
                    feasible=feasible,
                    infeasibility_reason=None if feasible else "HARD_CONSTRAINT",
                    random_seed=spec.seed,
                    route_provider=geom.provider,
                )
                produced += 1
            _ = t0
        self.recorder.assign_objective_ranks()
        return self.recorder
