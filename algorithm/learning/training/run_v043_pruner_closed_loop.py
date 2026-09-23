"""v0.4.3 Pruner 真实搜索闭环：GH 调用次数 + p50/p95 墙钟。

两条闭环（均为真实道路，禁止 Haversine 正式路线）：
  A. LocalGraph Dijkstra 扩展剪枝（RouteSearchPruner）→ 扩展次数 / 墙钟 / 距离 regret
  B. 多分支 via 搜索：Baseline 全分支 GH；LSR 用剪枝局部代价预选 top-m，再 GH
     → GH 调用数 / 墙钟 p50/p95 / 距离 regret
指标只观测，不调参抬高。
"""

from __future__ import annotations

import heapq
import json
import random
import time
from pathlib import Path

import numpy as np

from app.routing.graphhopper_routing import GraphHopperRoutingEngine, LocalRoutingConfig
from app.routing.local_routing import LocalRoutingEngine, RoadEdge, RoadGraph, haversine_m
from app.routing.models import RouteType
from learning.path_search import (
    EDGE_FEATURES,
    EdgeCandidate,
    RouteSearchPruner,
    RouteSearchRanker,
    feature_vector,
)
from learning.training.run_v04_experiment import (
    DATA_DIR,
    _edge_features,
    load_stations,
)
from learning.training.run_v042_pool_sweep import build_master_pool

OUT_PATH = DATA_DIR / "v043_pruner_closed_loop.json"
OSM_PBF = Path(__file__).resolve().parents[3] / "tools" / "osm-data" / "chongqing-260921.osm.pbf"


def _stats(vals):
    vals = [v for v in vals if v is not None]
    if not vals:
        return {"n": 0, "mean": None, "p50": None, "p95": None, "p99": None, "min": None, "max": None}
    s = sorted(vals)
    def pct(q):
        return float(s[min(len(s) - 1, max(0, int(round(q * (len(s) - 1)))))])
    return {
        "n": len(s),
        "mean": float(sum(s) / len(s)),
        "p50": pct(0.50),
        "p95": pct(0.95),
        "p99": pct(0.99),
        "min": float(s[0]),
        "max": float(s[-1]),
    }


def edge_id_of(e: RoadEdge) -> str:
    return f"{e.u}|{e.v}|{round(e.road_m, 1)}"


def road_edge_to_candidate(e: RoadEdge, origin, dest, cum_m: float) -> EdgeCandidate:
    if e.polyline:
        mid = e.polyline[len(e.polyline) // 2]
    else:
        mid = (origin[0] + dest[0]) / 2, (origin[1] + dest[1]) / 2
    class_code = {"motorway": 5, "trunk": 4, "primary": 3, "secondary": 2, "tertiary": 1}.get(
        (e.road_class or "").lower(), 0.0
    )
    speed = (e.road_m / e.duration_s * 3.6) if e.duration_s > 0 else 30.0
    o = haversine_m(mid, origin)
    d = haversine_m(mid, dest)
    feats = {
        "edge_road_m": float(e.road_m),
        "edge_duration_s": float(e.duration_s or e.road_m / 8.0),
        "edge_class_code": float(class_code),
        "edge_speed": float(max(1.0, speed)),
        "is_oneway": 0.0 if e.bidirectional else 1.0,
        "intersection_degree": 0.0,
        "turn_angle_deg": 0.0,
        "is_bridge_like": 0.0,
        "is_tunnel_like": 0.0,
        "is_highway_like": 1.0 if class_code >= 3 else 0.0,
        "origin_dist_m": float(o),
        "dest_dist_m": float(d),
        "remaining_lower_bound_m": float(d),
        "cum_dist_m": float(cum_m),
        "cum_time_s": float(cum_m / 8.0),
        "progress_ratio": float(o / (o + d + 1.0)),
        "gap_index": 0.0,
        "passenger_impact_est": 0.0,
        "cargo_detour_est": 0.0,
        "trip_locked": 0.0,
        "sla_remaining_s": 1800.0,
        "capacity_remaining": 3.0,
        "multi_leg_state": 0.0,
        "urban_density_proxy": 0.0,
    }
    return EdgeCandidate(edge_id_of(e), e.u, e.v, feats)


def subgraph_bbox(graph: RoadGraph, pts, margin_m: float = 4000.0) -> RoadGraph:
    """OD 走廊子图：降低 Dijkstra 成本，仍是真实边。"""
    lats = [p[0] for p in pts]
    lons = [p[1] for p in pts]
    dlat = margin_m / 111000.0
    dlon = margin_m / (111000.0 * max(0.2, abs(np.cos(np.radians(np.mean(lats))))))
    lat0, lat1 = min(lats) - dlat, max(lats) + dlat
    lon0, lon1 = min(lons) - dlon, max(lons) + dlon
    keep_nodes = {nid for nid, (la, lo) in graph.nodes.items() if lat0 <= la <= lat1 and lon0 <= lo <= lon1}
    sub = RoadGraph()
    for nid in keep_nodes:
        sub.nodes[nid] = graph.nodes[nid]
    for e in graph.edges:
        if e.u in keep_nodes and e.v in keep_nodes:
            sub.edges.append(e)
    return sub


def dijkstra_stats(graph: RoadGraph, a, b, pruner: RouteSearchPruner | None = None,
                   keep_ratio: float = 0.5):
    """A*（haversine 仅作启发，不作正式成本）；返回 road_m / 扩展次数 / 墙钟。"""
    na, _ = graph.nearest_node(a)
    nb, _ = graph.nearest_node(b)
    if na is None or nb is None:
        return None
    adj = graph.adjacency("bus")
    t0 = time.perf_counter()
    g = {na: 0.0}
    pq = [(haversine_m(a, b), 0.0, na)]
    came: dict[str, tuple[str, RoadEdge]] = {}
    seen = set()
    edge_expansions = 0
    node_expansions = 0
    pruned_away = 0
    origin, dest = a, b
    while pq:
        _, d, u = heapq.heappop(pq)
        if u in seen:
            continue
        seen.add(u)
        node_expansions += 1
        if u == nb:
            break
        succ = adj.get(u, [])
        # 真实路口度数常为 2–5：min_candidates=2 才能生效
        if pruner is not None and len(succ) >= 2:
            cands = [road_edge_to_candidate(e, origin, dest, d) for e in succ]
            # 保护启发式最优后继（非 teacher）：避免过度剪枝断路
            def _h(e: RoadEdge) -> float:
                mid = e.polyline[len(e.polyline) // 2] if e.polyline else dest
                return haversine_m(mid, dest)
            protect = [edge_id_of(min(succ, key=_h))]
            pruner.keep_ratio = keep_ratio
            kept = pruner.prune(cands, protect=protect, min_candidates=2)
            kept_ids = {c.edge_id for c in kept}
            succ2 = [e for e in succ if edge_id_of(e) in kept_ids]
            if not succ2:
                succ2 = [succ[0]]
            pruned_away += max(0, len(succ) - len(succ2))
            succ = succ2
        edge_expansions += len(succ)
        for e in succ:
            nd = d + e.road_m
            if nd < g.get(e.v, float("inf")):
                g[e.v] = nd
                came[e.v] = (u, e)
                h = haversine_m(graph.nodes.get(e.v, dest), dest)
                heapq.heappush(pq, (nd + h, nd, e.v))
    wall = time.perf_counter() - t0
    if nb not in g and na != nb:
        return None
    return {
        "road_m": g[nb],
        "edge_expansions": edge_expansions,
        "node_expansions": node_expansions,
        "pruned_away": pruned_away,
        "wall_s": wall,
    }


def all_pairs_dist_from(graph: RoadGraph, src_xy, pruner=None, keep_ratio=0.5):
    """单源距离表：给全部 via 打分用（替代 per-via 双向搜索）。"""
    ns, _ = graph.nearest_node(src_xy)
    if ns is None:
        return None
    adj = graph.adjacency("bus")
    g = {ns: 0.0}
    pq = [(0.0, ns)]
    seen = set()
    origin = src_xy
    while pq:
        d, u = heapq.heappop(pq)
        if u in seen:
            continue
        seen.add(u)
        succ = adj.get(u, [])
        if pruner is not None and len(succ) >= 2:
            cands = [road_edge_to_candidate(e, origin, origin, d) for e in succ]
            pruner.keep_ratio = keep_ratio
            kept = pruner.prune(cands, min_candidates=2)
            kept_ids = {c.edge_id for c in kept}
            succ2 = [e for e in succ if edge_id_of(e) in kept_ids] or [succ[0]]
            succ = succ2
        for e in succ:
            nd = d + e.road_m
            if nd < g.get(e.v, float("inf")):
                g[e.v] = nd
                heapq.heappush(pq, (nd, e.v))
    return g


def train_ranker_from_gh(gh, stations, rng, n=30, seed=42):
    names = sorted(stations)
    X_list, y_list, sizes = [], [], []
    for i in range(n):
        a, b = rng.sample(names, 2)
        m = build_master_pool(gh, stations[a][:2], stations[b][:2], rng, f"t{i}", 8, 8, max_alts=4)
        if not m:
            continue
        pos = m["pos_ids"][:8]
        neg = m["neg_ids"][:8]
        ids = pos + neg
        rng.shuffle(ids)
        X = np.array([
            list(_edge_features(m["by_id"][eid], m["origin"], m["dest"]).get(k, 0.0) for k in EDGE_FEATURES)
            for eid in ids
        ])
        y = np.array([1 if eid in m["teacher_ids"] else 0 for eid in ids], dtype=np.int32)
        X_list.append(X)
        y_list.append(y)
        sizes.append(len(y))
    ranker = RouteSearchRanker()
    if X_list:
        ranker.train(X_list, y_list, sizes, seed=seed, n_estimators=60)
    return ranker


def make_via_candidates(seed_poly, n_via=16, rng=None):
    """真实折线旁侧偏移 via 点（搜索分支），非合成路网。"""
    rng = rng or random.Random(0)
    pts = list(seed_poly)
    if len(pts) < 5:
        return []
    vias = []
    for i in range(n_via):
        t = (i + 1) / (n_via + 1)
        idx = int(t * (len(pts) - 1))
        la, lo = pts[idx]
        # 下一个点定方向，法向偏移
        la2, lo2 = pts[min(len(pts) - 1, idx + 1)]
        dla, dlo = la2 - la, lo2 - lo
        nla, nlo = -dlo, dla
        norm = (nla * nla + nlo * nlo) ** 0.5 or 1.0
        off = (0.008 + 0.004 * (i % 5)) * (1 if i % 2 == 0 else -1)
        vias.append((la + off * nla / norm, lo + off * nlo / norm))
    return vias


def run_closed_loop(n_od=40, seed=42, n_via=16, keep_m=4, prune_ratio=0.5):
    cfg = LocalRoutingConfig(graphhopper_base_url="http://127.0.0.1:8080", graphhopper_timeout_ms=8000)
    gh = GraphHopperRoutingEngine(cfg)
    stations = load_stations(60)
    rng = random.Random(seed)

    t_load = time.perf_counter()
    if not OSM_PBF.exists():
        return {"status": "OSM_PBF_MISSING", "path": str(OSM_PBF)}
    from app.routing.osm_import import load_osm_road_graph
    full_graph, _stats_osm = load_osm_road_graph(str(OSM_PBF))
    load_s = time.perf_counter() - t_load

    ranker = train_ranker_from_gh(gh, stations, rng, n=25, seed=seed)
    pruner = RouteSearchPruner(ranker=ranker, keep_ratio=prune_ratio)

    names = sorted(stations)
    rows = []
    for i in range(n_od):
        a, b = rng.sample(names, 2)
        sa, sb = stations[a][:2], stations[b][:2]
        if i % 2 == 0:
            target = "jiangjin" if stations[a][2] == "chongqing_core" else "chongqing_core"
            pool = [n for n in names if stations[n][2] == target]
            if pool:
                b = rng.choice(pool)
                sb = stations[b][:2]

        seed_t0 = time.perf_counter()
        seed_route = gh.route(sa, sb, profile="bus")
        seed_latency = time.perf_counter() - seed_t0
        if not seed_route.available or len(seed_route.polyline) < 5:
            continue

        sub = subgraph_bbox(full_graph, [sa, sb, *seed_route.polyline[:: max(1, len(seed_route.polyline)//8)]], margin_m=3500)
        if len(sub.nodes) < 50:
            continue

        # --- A. LocalGraph 扩展剪枝 ---
        base = dijkstra_stats(sub, sa, sb, pruner=None)
        ml = dijkstra_stats(sub, sa, sb, pruner=pruner, keep_ratio=prune_ratio)
        if not base:
            continue
        ml_failed = ml is None

        # --- B. 多分支 GH：全扩展 vs LSR 预选 ---
        vias = make_via_candidates(seed_route.polyline, n_via=n_via, rng=rng)
        if len(vias) < 8:
            continue

        # Baseline：全部 via 都走 GH（逐次计延迟）
        base_lat, base_dists, base_calls = [], [], 0
        for w in vias:
            t0 = time.perf_counter()
            r = gh.route(sa, sb, (w,), profile="bus")
            base_lat.append(time.perf_counter() - t0)
            base_calls += 1
            if r.available:
                base_dists.append(r.distance_m)
        base_wall = float(sum(base_lat))
        best_base = min(base_dists) if base_dists else None

        # LSR：两次单源距离表给全部 via 打分，只 GH top-m（不再 per-via 双向搜）
        score_t0 = time.perf_counter()
        dist_o = all_pairs_dist_from(sub, sa, pruner=pruner, keep_ratio=prune_ratio)
        dist_d = all_pairs_dist_from(sub, sb, pruner=pruner, keep_ratio=prune_ratio)
        scored = []
        for w in vias:
            n_w, _ = sub.nearest_node(w)
            if dist_o and dist_d and n_w and n_w in dist_o and n_w in dist_d:
                scored.append((dist_o[n_w] + dist_d[n_w], w))
            else:
                scored.append((float("inf"), w))
        score_wall = time.perf_counter() - score_t0
        scored.sort(key=lambda x: x[0])
        kept_vias = [w for _, w in scored[:keep_m]]

        ls_lat, ls_dists, ls_calls = [], [], 0
        for w in kept_vias:
            t0 = time.perf_counter()
            r = gh.route(sa, sb, (w,), profile="bus")
            ls_lat.append(time.perf_counter() - t0)
            ls_calls += 1
            if r.available:
                ls_dists.append(r.distance_m)
        best_ls = min(ls_dists) if ls_dists else None
        ls_wall = score_wall + float(sum(ls_lat))

        regret = None
        if best_base and best_ls and best_base > 0:
            regret = max(0.0, (best_ls - best_base) / best_base)

        rows.append({
            "od": f"od{i}",
            "subgraph_nodes": len(sub.nodes),
            "subgraph_edges": len(sub.edges),
            "seed_gh_latency_s": seed_latency,
            # A
            "local_base_expansions": base["edge_expansions"],
            "local_ml_expansions": ml["edge_expansions"] if ml else None,
            "local_base_nodes": base["node_expansions"],
            "local_ml_nodes": ml["node_expansions"] if ml else None,
            "local_base_wall_s": base["wall_s"],
            "local_ml_wall_s": ml["wall_s"] if ml else None,
            "local_base_m": base["road_m"],
            "local_ml_m": ml["road_m"] if ml else None,
            "local_regret": (
                max(0.0, (ml["road_m"] - base["road_m"]) / base["road_m"])
                if ml and base["road_m"] > 0 else None
            ),
            "local_pruned_away": ml["pruned_away"] if ml else None,
            "ml_unreachable": ml_failed,
            # B
            "gh_base_calls": base_calls,
            "gh_ls_calls": ls_calls,
            "gh_base_wall_s": base_wall,
            "gh_ls_wall_s": ls_wall,
            "gh_base_lat_ms": [x * 1000 for x in base_lat],
            "gh_ls_lat_ms": [x * 1000 for x in ls_lat],
            "best_base_m": best_base,
            "best_ls_m": best_ls,
            "via_regret": regret,
            "n_via": len(vias),
        })

    if not rows:
        return {"status": "NO_ROWS", "load_s": load_s}

    exp_red = [
        1 - r["local_ml_expansions"] / max(1, r["local_base_expansions"])
        for r in rows if r.get("local_ml_expansions") is not None
    ]
    call_red = [1 - r["gh_ls_calls"] / max(1, r["gh_base_calls"]) for r in rows]
    wall_red = [1 - r["gh_ls_wall_s"] / max(1e-9, r["gh_base_wall_s"]) for r in rows]
    local_wall_red = [
        1 - r["local_ml_wall_s"] / max(1e-9, r["local_base_wall_s"])
        for r in rows if r.get("local_ml_wall_s") is not None
    ]
    via_regrets = [r["via_regret"] for r in rows if r["via_regret"] is not None]
    local_regrets = [r["local_regret"] for r in rows if r["local_regret"] is not None]
    all_base_ms = [x for r in rows for x in r["gh_base_lat_ms"]]
    all_ls_ms = [x for r in rows for x in r["gh_ls_lat_ms"]]
    n_ml_fail = sum(1 for r in rows if r.get("ml_unreachable"))

    out = {
        "exp_id": "v0.4.3-pruner-closed-loop",
        "seed": seed,
        "n_od": n_od,
        "rows_ok": len(rows),
        "ml_unreachable_count": n_ml_fail,
        "osm_load_s": round(load_s, 2),
        "graph_nodes": len(full_graph.nodes),
        "graph_edges": len(full_graph.edges),
        "prune_ratio": prune_ratio,
        "keep_m": keep_m,
        "n_via": n_via,
        "definitions": {
            "local_expansion_reduction": "1 - ml_edge_expansions/base_edge_expansions",
            "gh_call_reduction": "1 - ls_calls/base_calls",
            "gh_wall_reduction": "1 - ls_wall/base_wall（ls_wall 含预选评分）",
            "via_regret": "(best_ls_dist - best_base_dist)/best_base_dist",
            "local_regret": "(ml_road_m - base_road_m)/base_road_m",
        },
        "A_local_graph": {
            "expansion_reduction": _stats(exp_red),
            "wall_reduction": _stats(local_wall_red),
            "distance_regret": _stats(local_regrets),
            "base_expansions": _stats([r["local_base_expansions"] for r in rows]),
            "ml_expansions": _stats([r["local_ml_expansions"] for r in rows]),
            "base_wall_s": _stats([r["local_base_wall_s"] for r in rows]),
            "ml_wall_s": _stats([r["local_ml_wall_s"] for r in rows]),
        },
        "B_gh_multibranch": {
            "call_reduction": _stats(call_red),
            "wall_reduction": _stats(wall_red),
            "via_distance_regret": _stats(via_regrets),
            "gh_calls_base": _stats([r["gh_base_calls"] for r in rows]),
            "gh_calls_ls": _stats([r["gh_ls_calls"] for r in rows]),
            "gh_latency_ms_base": _stats(all_base_ms),
            "gh_latency_ms_ls": _stats(all_ls_ms),
            "gh_wall_s_base": _stats([r["gh_base_wall_s"] for r in rows]),
            "gh_wall_s_ls": _stats([r["gh_ls_wall_s"] for r in rows]),
        },
        "rows": rows,
    }
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    OUT_PATH.write_text(json.dumps(out, indent=2, ensure_ascii=False, default=str), encoding="utf-8")
    return out


if __name__ == "__main__":
    out = run_closed_loop()
    slim = {k: v for k, v in out.items() if k != "rows"}
    print(json.dumps(slim, indent=2, ensure_ascii=False, default=str))
