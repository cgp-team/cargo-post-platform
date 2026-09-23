"""GraphHopper Provider / 可插拔 / Cache graph-version / 禁直线冒充。"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.routing import (
    GeometryCache,
    GeometryStatus,
    MapRoutingEngine,
    RoadEdge,
    RoadGraph,
    RoutingCallBudget,
    is_formal_geometry,
    route_fingerprint,
)
from app.routing.graphhopper_routing import (
    DEFAULT_PROFILE,
    GraphHopperRoutingEngine,
    LocalGraphRoutingProvider,
    LocalRoutingConfig,
    LocalRoutingProviderFactory,
)


def test_default_provider_is_graphhopper():
    p = LocalRoutingProviderFactory(LocalRoutingConfig()).create()
    assert p.name == "graphhopper"
    assert isinstance(p, GraphHopperRoutingEngine)


def test_provider_pluggable_valhalla_osrm_reserved():
    for name in ("valhalla", "osrm"):
        p = LocalRoutingProviderFactory(LocalRoutingConfig(provider=name)).create()
        r = p.route((29.5, 106.5), (29.52, 106.5))
        assert not is_formal_geometry(r.status)
        assert "LOCAL_ROUTING_UNAVAILABLE" in r.reason_code


def test_local_graph_provider_when_configured():
    g = RoadGraph()
    g.add_node("A", 29.5, 106.5)
    g.add_node("B", 29.52, 106.5)
    g.add_edge(RoadEdge("A", "B", 3000, 240, ((29.5, 106.5), (29.51, 106.5), (29.52, 106.5))))
    f = LocalRoutingProviderFactory(LocalRoutingConfig(provider="local_graph"))
    f.set_local_graph(LocalGraphRoutingProvider(graph=g))
    p = f.create()
    r = p.route((29.5, 106.5), (29.52, 106.5))
    assert r.is_formal
    assert r.status == GeometryStatus.LOCAL_ROAD


def test_graphhopper_unavailable_not_fake_haversine():
    gh = GraphHopperRoutingEngine(
        LocalRoutingConfig(graphhopper_base_url="http://127.0.0.1:1", graphhopper_timeout_ms=50)
    )
    r = gh.route((29.50, 106.50), (29.52, 106.50))
    assert not is_formal_geometry(r.status)
    assert r.status == GeometryStatus.UNCERTAIN
    assert "LOCAL_ROUTING_UNAVAILABLE" in r.reason_code
    assert r.available is False


def test_profile_recorded_in_fingerprint():
    f1 = route_fingerprint((29.5, 106.5), (29.52, 106.5), profile="bus", graph_version="g1")
    f2 = route_fingerprint((29.5, 106.5), (29.52, 106.5), profile="cargo_bus", graph_version="g1")
    f3 = route_fingerprint((29.5, 106.5), (29.52, 106.5), profile="bus", graph_version="g2")
    assert f1 != f2
    assert f1 != f3
    assert DEFAULT_PROFILE == "bus"


def test_cache_not_reuse_across_graph_version():
    cache = GeometryCache()
    k_old = route_fingerprint((29.5, 106.5), (29.52, 106.5), graph_version="osm-v1")
    k_new = route_fingerprint((29.5, 106.5), (29.52, 106.5), graph_version="osm-v2")
    assert k_old != k_new
    cache.mark_stale(k_old)
    assert cache.get(k_old) is None


def test_map_engine_accepts_graphhopper_provider():
    g = RoadGraph()
    g.add_node("B", 29.50, 106.50)
    g.add_node("C", 29.52, 106.50)
    g.add_edge(RoadEdge("B", "C", 3200, 240, ((29.50, 106.50), (29.51, 106.50), (29.52, 106.50))))
    eng = MapRoutingEngine(
        LocalGraphRoutingProvider(graph=g),
        profile="bus",
        graph_version="osm-test",
    )
    r = eng.resolve((29.50, 106.50), (29.52, 106.50))
    assert r.is_formal
    assert r.profile == "bus"
    assert r.graph_version == "osm-test"


def test_formal_route_never_haversine_provider():
    g = RoadGraph()
    g.add_node("B", 29.50, 106.50)
    g.add_node("C", 29.52, 106.50)
    g.add_edge(RoadEdge("B", "C", 3200, 240, ((29.50, 106.50), (29.51, 106.50), (29.52, 106.50))))
    eng = MapRoutingEngine(LocalGraphRoutingProvider(graph=g))
    r = eng.resolve((29.50, 106.50), (29.52, 106.50))
    assert r.provider not in ("haversine", "HAVERSINE", "straight")
    assert is_formal_geometry(r.status)


def test_config_from_mapping():
    cfg = LocalRoutingConfig.from_mapping({
        "provider": "graphhopper",
        "profile": "bus",
        "graphhopper.osm-file": "/data/chongqing.osm.pbf",
        "graph_version": "osm-cq-v1",
    })
    assert cfg.provider == "graphhopper"
    assert cfg.profile == "bus"
    assert cfg.graphhopper_osm_file.endswith(".osm.pbf")
    assert cfg.graph_version == "osm-cq-v1"


def test_budget_straight_formal_zero():
    budget = RoutingCallBudget()
    g = RoadGraph()
    g.add_node("B", 29.50, 106.50)
    g.add_node("C", 29.52, 106.50)
    g.add_edge(RoadEdge("B", "C", 3200, 240, ((29.50, 106.50), (29.51, 106.50), (29.52, 106.50))))
    eng = MapRoutingEngine(LocalGraphRoutingProvider(graph=g), budget=budget)
    eng.finalize(eng.resolve((29.50, 106.50), (29.52, 106.50)))
    assert budget.straight_fallback_formal == 0
