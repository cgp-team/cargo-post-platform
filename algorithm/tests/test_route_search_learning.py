"""Route Search Learning 测试。"""

from __future__ import annotations

import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.routing.local_routing import LocalRoutingEngine, RoadEdge, RoadGraph
from app.routing.road_od_cache import ODEntry, RoadODCache, RoutingSession, StationSnapCache
from learning.path_search import (
    EDGE_FEATURES,
    EdgeCandidate,
    RouteSearchDatasetBuilder,
    RouteSearchPruner,
    RouteSearchRanker,
    RouteSearchTeacher,
    assert_no_leakage,
    classify_failure,
    real_coordinate_order_sampler,
)


def build_graph() -> RoadGraph:
    g = RoadGraph()
    for nid, la, lo in (
        ("A", 29.50, 106.50), ("B", 29.51, 106.50), ("C", 29.52, 106.50),
        ("X", 29.51, 106.52), ("Y", 29.53, 106.52),
    ):
        g.add_node(nid, la, lo)
    g.add_edge(RoadEdge("A", "B", 1200, 90, ((29.50, 106.50), (29.51, 106.50))))
    g.add_edge(RoadEdge("B", "C", 1200, 90, ((29.51, 106.50), (29.52, 106.50))))
    g.add_edge(RoadEdge("A", "X", 1800, 140, ((29.50, 106.50), (29.51, 106.52))))
    g.add_edge(RoadEdge("X", "C", 1800, 140, ((29.51, 106.52), (29.52, 106.50))))
    g.add_edge(RoadEdge("X", "Y", 800, 60, ((29.51, 106.52), (29.53, 106.52))))
    g.add_edge(RoadEdge("Y", "C", 1500, 110, ((29.53, 106.52), (29.52, 106.50))))
    return g


def test_no_teacher_leakage_in_features():
    assert assert_no_leakage()


def test_teacher_labels_from_real_graph():
    teacher = RouteSearchTeacher(LocalRoutingEngine(build_graph()))
    r = teacher.teacher_path((29.50, 106.50), (29.52, 106.50))
    assert r.available and r.distance_m > 0


def test_real_coordinate_sampler_not_synthetic():
    import random

    _a, _b, src = real_coordinate_order_sampler(build_graph(), {}, random.Random(1), mode="OSM_ROAD_NODE")
    assert src == "OSM_ROAD_NODE"


def test_od_cache_hit_and_version_invalidate():
    cache = RoadODCache(graph_version="g1")
    e = ODEntry("o1", "d1", "bus", "g1", "ch", 100, 10, ((0, 0), (1, 1)), ("n1", "n2"), "LOCAL_ROAD", "local")
    cache.put(e)
    assert cache.get("o1", "d1") is not None
    cache2 = RoadODCache(graph_version="g2")
    cache2.put(e)
    assert cache2.get("o1", "d1") is None


def test_snap_cache_hits():
    sc = StationSnapCache("g1")
    g = build_graph()
    assert sc.snap("S1", 29.50, 106.50, g) is sc.snap("S1", 29.50, 106.50, g)
    assert sc.stats()["hits"] == 1


def test_routing_session_reuses_od():
    g = build_graph()
    eng = LocalRoutingEngine(g)
    sess = RoutingSession(g, graph_version="g1")
    calls = {"n": 0}

    def compute():
        calls["n"] += 1
        r = eng.route((29.50, 106.50), (29.52, 106.50))
        return {"distance_m": r.distance_m, "duration_s": r.duration_s,
                "polyline": [list(p) for p in r.polyline], "path_nodes": [], "status": "LOCAL_ROAD"}

    sess.cached_od("A", "B", (29.50, 106.50), (29.52, 106.50), compute=compute)
    sess.cached_od("A", "B", (29.50, 106.50), (29.52, 106.50), compute=compute)
    assert calls["n"] == 1


def test_route_search_ranker_and_pruner():
    samples = []
    rng = np.random.RandomState(0)
    for gi in range(6):
        for ei in range(8):
            feats = {k: float(rng.rand()) for k in EDGE_FEATURES}
            feats["od_key"] = f"g{gi}"
            samples.append(EdgeCandidate(f"e{gi}_{ei}", f"u{ei}", f"v{ei}", feats))
    labeled = RouteSearchTeacher(LocalRoutingEngine(build_graph())).label_expansion(
        samples, ["u0", "v0", "u1", "v1"]
    )
    X_list, y_list, sizes = RouteSearchDatasetBuilder().build(labeled)
    ranker = RouteSearchRanker()
    ranker.train(X_list, y_list, sizes, n_estimators=20)
    assert not ranker.fallback
    kept = RouteSearchPruner(ranker, keep_ratio=0.5).prune(labeled[:20], protect=(labeled[0].edge_id,))
    assert labeled[0].edge_id in [c.edge_id for c in kept]


def test_classify_failure():
    assert classify_failure(ValueError("cache miss")) == "CACHE_ERROR"