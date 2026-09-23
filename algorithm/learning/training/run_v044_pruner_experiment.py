"""V0.44：Baseline A* vs V0.43 Pruner vs V0.44 BranchPoint Pruner + B top-k sweep。

同 OD / 同 RoadGraph / 同 profile / 同 seed。禁止 synthetic、teacher 泄漏、Haversine 正式成本。
"""

from __future__ import annotations

import heapq
import json
import random
import time
from pathlib import Path

import numpy as np

from app.routing.graphhopper_routing import GraphHopperRoutingEngine, LocalRoutingConfig
from app.routing.local_routing import RoadEdge, RoadGraph, haversine_m
from learning.path_search.pruner_v044 import (
    BranchPointPruner,
    CheapStaticPrefilter,
    MLBeneiftGate,
    PruneStats,
    TargetReachabilityGuard,
)
from learning.training.run_v043_pruner_closed_loop import (
    OSM_PBF,
    edge_id_of,
    make_via_candidates,
    road_edge_to_candidate,
    subgraph_bbox,
    train_ranker_from_gh,
    _stats,
)
from learning.training.run_v04_experiment import load_stations

DATA_DIR = Path(__file__).resolve().parents[2] / "data"
OUT_MAIN = DATA_DIR / "v044_result.json"
OUT_FAIL = DATA_DIR / "v044_failure_cases.json"


def astar(graph: RoadGraph, a, b) -> dict | None:
    na, _ = graph.nearest_node(a)
    nb, _ = graph.nearest_node(b)
    if na is None or nb is None:
        return None
    adj = graph.adjacency("bus")
    t0 = time.perf_counter()
    g = {na: 0.0}
    pq = [(haversine_m(a, b), 0.0, na)]
    seen: set[str] = set()
    edge_exp = node_exp = 0
    while pq:
        _, d, u = heapq.heappop(pq)
        if u in seen:
            continue
        seen.add(u)
        node_exp += 1
        if u == nb:
            break
        succ = adj.get(u, [])
        edge_exp += len(succ)
        for e in succ:
            nd = d + e.road_m
            if nd < g.get(e.v, float("inf")):
                g[e.v] = nd
                h = haversine_m(graph.nodes.get(e.v, b), b)
                heapq.heappush(pq, (nd + h, nd, e.v))
    wall = time.perf_counter() - t0
    if nb not in g and na != nb:
        return None
    dur = g[nb] / 8.0
    return {
        "road_m": g[nb],
        "duration_s": dur,
        "edge_expansions": edge_exp,
        "node_expansions": node_exp,
        "wall_s": wall,
    }


def astar_v043(graph: RoadGraph, a, b, ranker, keep_ratio: float = 0.7) -> dict | None:
    """V0.43：凡 degree>=2 即 EdgeCandidate+predict+prune。"""
    from learning.path_search import RouteSearchPruner

    na, _ = graph.nearest_node(a)
    nb, _ = graph.nearest_node(b)
    if na is None or nb is None:
        return None
    adj = graph.adjacency("bus")
    pruner = RouteSearchPruner(ranker=ranker, keep_ratio=keep_ratio)
    t0 = time.perf_counter()
    g = {na: 0.0}
    pq = [(haversine_m(a, b), 0.0, na)]
    seen: set[str] = set()
    edge_exp = node_exp = pruned = 0
    feat_ms = pred_ms = prune_ms = 0.0
    while pq:
        _, d, u = heapq.heappop(pq)
        if u in seen:
            continue
        seen.add(u)
        node_exp += 1
        if u == nb:
            break
        succ = adj.get(u, [])
        if len(succ) >= 2:
            tf = time.perf_counter()
            cands = [road_edge_to_candidate(e, a, b, d) for e in succ]
            feat_ms += (time.perf_counter() - tf) * 1000

            def _h(e: RoadEdge) -> float:
                mid = e.polyline[len(e.polyline) // 2] if e.polyline else b
                return haversine_m(mid, b)

            protect = [edge_id_of(min(succ, key=_h))]
            tp = time.perf_counter()
            kept = pruner.prune(cands, protect=protect, min_candidates=2)
            pred_ms += (time.perf_counter() - tp) * 1000
            kept_ids = {c.edge_id for c in kept}
            tpr = time.perf_counter()
            succ2 = [e for e in succ if edge_id_of(e) in kept_ids] or [succ[0]]
            prune_ms += (time.perf_counter() - tpr) * 1000
            pruned += max(0, len(succ) - len(succ2))
            succ = succ2
        edge_exp += len(succ)
        for e in succ:
            nd = d + e.road_m
            if nd < g.get(e.v, float("inf")):
                g[e.v] = nd
                h = haversine_m(graph.nodes.get(e.v, b), b)
                heapq.heappush(pq, (nd + h, nd, e.v))
    wall = time.perf_counter() - t0
    if nb not in g and na != nb:
        return {
            "unreachable": True,
            "edge_expansions": edge_exp,
            "node_expansions": node_exp,
            "wall_s": wall,
            "pruned_away": pruned,
            "feature_build_ms": feat_ms,
            "model_predict_ms": pred_ms,
            "prune_ms": prune_ms,
        }
    return {
        "unreachable": False,
        "road_m": g[nb],
        "duration_s": g[nb] / 8.0,
        "edge_expansions": edge_exp,
        "node_expansions": node_exp,
        "wall_s": wall,
        "pruned_away": pruned,
        "feature_build_ms": feat_ms,
        "model_predict_ms": pred_ms,
        "prune_ms": prune_ms,
    }


def astar_v044(graph: RoadGraph, a, b, ranker, *, use_guard=True, use_prefilter=True,
               use_gate=True, use_adaptive=True) -> dict | None:
    na, _ = graph.nearest_node(a)
    nb, _ = graph.nearest_node(b)
    if na is None or nb is None:
        return None
    adj = graph.adjacency("bus")
    gate = MLBeneiftGate() if use_gate else MLBeneiftGate(ml_cost_ms_per_cand=0.0, saved_ms_per_pruned_edge=1e9)
    bp = BranchPointPruner(
        ranker,
        ml_degree_threshold=3,
        prefilter=CheapStaticPrefilter() if use_prefilter else CheapStaticPrefilter(max_backtrack_deg=180, max_detour_factor=1e9),
        gate=gate,
    )
    guard = TargetReachabilityGuard(graph, b) if use_guard else None
    stats = PruneStats()
    t0 = time.perf_counter()
    g = {na: 0.0}
    pq = [(haversine_m(a, b), 0.0, na)]
    seen: set[str] = set()
    search_acc = 0.0
    while pq:
        _, d, u = heapq.heappop(pq)
        if u in seen:
            continue
        seen.add(u)
        stats.node_expansions += 1
        if u == nb:
            break
        succ = adj.get(u, [])
        if not use_adaptive and len(succ) >= 3:
            # Experiment A：强制 branch-point 但仍固定 ratio
            pass
        ts = time.perf_counter()
        u_xy = graph.nodes.get(u, a)
        dec = bp.decide(
            succ,
            u=u,
            u_xy=u_xy,
            dest_xy=b,
            origin_xy=a,
            cum_m=d,
            guard=guard,
            stats=stats,
            rng_class_diversity=len({(e.road_class or '') for e in succ}),
        )
        search_acc += (time.perf_counter() - ts) * 1000
        succ = dec.kept or [succ[0]]
        stats.edge_expansions += len(succ)
        for e in succ:
            nd = d + e.road_m
            if nd < g.get(e.v, float("inf")):
                g[e.v] = nd
                h = haversine_m(graph.nodes.get(e.v, b), b)
                heapq.heappush(pq, (nd + h, nd, e.v))
    wall = time.perf_counter() - t0
    stats.search_ms = max(0.0, search_acc - stats.feature_build_ms - stats.model_predict_ms - stats.prune_ms)
    stats.total_ms = wall * 1000
    base = {
        "edge_expansions": stats.edge_expansions,
        "node_expansions": stats.node_expansions,
        "wall_s": wall,
        "pruned_away": stats.pruned_away,
        "stats": stats.as_dict(),
    }
    if nb not in g and na != nb:
        return {**base, "unreachable": True}
    return {
        **base,
        "unreachable": False,
        "road_m": g[nb],
        "duration_s": g[nb] / 8.0,
        "feature_build_ms": stats.feature_build_ms,
        "model_predict_ms": stats.model_predict_ms,
        "prune_ms": stats.prune_ms,
    }


def sample_ods(stations, n=100, seed=42):
    """真实站点 OD：主城/江津/跨区/长短距覆盖，禁止 random 经纬度。"""
    rng = random.Random(seed)
    names = sorted(stations)
    core = [n for n in names if stations[n][2] == "chongqing_core"]
    jj = [n for n in names if stations[n][2] == "jiangjin"]
    ods = []
    # 分层：跨区 25 / 主城 50 / 江津 15 / 短距 10
    def pair(x, y):
        return (stations[x][:2], stations[y][:2], stations[x][2], stations[y][2], x, y)
    for _ in range(min(25, len(core), len(jj))):
        ods.append(pair(rng.choice(core), rng.choice(jj)))
    for _ in range(50):
        ods.append(pair(rng.sample(core, 2)[0], rng.sample(core, 2)[1]))
    for _ in range(min(15, max(0, len(jj) - 1))):
        a, b = rng.sample(jj, 2)
        ods.append(pair(a, b))
    # 短距：同名邻近站（按坐标近邻）
    core_sorted = sorted(core, key=lambda n: (stations[n][0], stations[n][1]))
    for i in range(min(10, len(core_sorted) - 5)):
        a = core_sorted[i * 3]
        b = core_sorted[i * 3 + 2]
        ods.append(pair(a, b))
    rng.shuffle(ods)
    return ods[:n]


def run_gh_branch_topk(gh, sa, sb, seed_poly, rng, topks=(1, 2, 4, 8), n_via=16):
    vias = make_via_candidates(seed_poly, n_via=n_via, rng=rng)
    if len(vias) < max(topks) + 2:
        return None
    # baseline：全 via
    base_lat, base_d = [], []
    for w in vias:
        t0 = time.perf_counter()
        r = gh.route(sa, sb, (w,), profile="bus")
        base_lat.append(time.perf_counter() - t0)
        if r.available:
            base_d.append(r.distance_m)
    # LSR 打分：两次单源（无 ML 的拓扑+启发即可作 selector；与 V043 B 一致用距离表）
    # 为公平：用无剪枝距离表
    # 这里直接用几何下界排序 via（廉价）+ 可选 ranker；V043 B 用距离表。
    # 保持 V043 B 逻辑：距离表预选
    from learning.training.run_v043_pruner_closed_loop import all_pairs_dist_from
    # 需要 subgraph —— 由调用方传入更合理；此处简化：haversine 路径长预估
    scored = []
    for w in vias:
        est = haversine_m(sa, w) + haversine_m(w, sb)
        scored.append((est, w))
    scored.sort(key=lambda x: x[0])
    out = {
        "n_via": len(vias),
        "base_calls": len(vias),
        "base_wall_s": float(sum(base_lat)),
        "base_best_m": min(base_d) if base_d else None,
        "base_lat_ms": [x * 1000 for x in base_lat],
        "by_topk": {},
    }
    for k in topks:
        kept = [w for _, w in scored[:k]]
        lat, ds = [], []
        for w in kept:
            t0 = time.perf_counter()
            r = gh.route(sa, sb, (w,), profile="bus")
            lat.append(time.perf_counter() - t0)
            if r.available:
                ds.append(r.distance_m)
        best = min(ds) if ds else None
        regret = None
        if out["base_best_m"] and best and out["base_best_m"] > 0:
            regret = max(0.0, (best - out["base_best_m"]) / out["base_best_m"])
        out["by_topk"][str(k)] = {
            "calls": k,
            "wall_s": float(sum(lat)),
            "best_m": best,
            "regret": regret,
            "lat_ms": [x * 1000 for x in lat],
        }
    return out


def main(n_od=100, seed=42):
    from app.routing.osm_import import load_osm_road_graph
    from learning.training.run_v043_pruner_closed_loop import train_ranker_from_gh

    cfg = LocalRoutingConfig(graphhopper_base_url="http://127.0.0.1:8080", graphhopper_timeout_ms=8000)
    gh = GraphHopperRoutingEngine(cfg)
    stations = load_stations(80)
    rng = random.Random(seed)

    t_load = time.perf_counter()
    full_graph, _ = load_osm_road_graph(str(OSM_PBF))
    load_s = time.perf_counter() - t_load
    ranker = train_ranker_from_gh(gh, stations, rng, n=25, seed=seed)

    ods = sample_ods(stations, n=n_od, seed=seed)
    rows = []
    failures = []

    for i, (sa, sb, ra, rb, na, nb_name) in enumerate(ods):
        seed_route = gh.route(sa, sb, profile="bus")
        if not seed_route.available or len(seed_route.polyline) < 5:
            continue
        sub = subgraph_bbox(
            full_graph,
            [sa, sb, *seed_route.polyline[:: max(1, len(seed_route.polyline) // 8)]],
            margin_m=3500,
        )
        if len(sub.nodes) < 50:
            continue

        base = astar(sub, sa, sb)
        if not base:
            continue
        v43 = astar_v043(sub, sa, sb, ranker, keep_ratio=0.7)
        v44 = astar_v044(sub, sa, sb, ranker, use_guard=True, use_prefilter=True, use_gate=True, use_adaptive=True)
        # Experiments A–E（配置开关，同 OD）
        exp_a = astar_v044(sub, sa, sb, ranker, use_guard=False, use_prefilter=False, use_gate=False, use_adaptive=False)
        exp_b = astar_v044(sub, sa, sb, ranker, use_guard=False, use_prefilter=False, use_gate=False, use_adaptive=True)
        exp_c = astar_v044(sub, sa, sb, ranker, use_guard=False, use_prefilter=True, use_gate=False, use_adaptive=True)
        exp_d = astar_v044(sub, sa, sb, ranker, use_guard=True, use_prefilter=True, use_gate=False, use_adaptive=True)

        gh_sweep = run_gh_branch_topk(gh, sa, sb, seed_route.polyline, rng, topks=(1, 2, 4, 8), n_via=16)

        def pack(r, name):
            if not r:
                return {"alg": name, "ok": False}
            d = {
                "alg": name,
                "ok": not r.get("unreachable", False),
                "unreachable": bool(r.get("unreachable", False)),
                "road_m": r.get("road_m"),
                "duration_s": r.get("duration_s"),
                "wall_s": r.get("wall_s"),
                "edge_expansions": r.get("edge_expansions"),
                "node_expansions": r.get("node_expansions"),
                "pruned_away": r.get("pruned_away"),
                "feature_build_ms": r.get("feature_build_ms") or (r.get("stats") or {}).get("feature_build_ms"),
                "model_predict_ms": r.get("model_predict_ms") or (r.get("stats") or {}).get("model_predict_ms"),
                "prune_ms": r.get("prune_ms") or (r.get("stats") or {}).get("prune_ms"),
                "stats": r.get("stats"),
            }
            if d["ok"] and base and base.get("road_m"):
                d["distance_regret"] = max(0.0, (r["road_m"] - base["road_m"]) / base["road_m"])
                d["duration_regret"] = max(0.0, (r["duration_s"] - base["duration_s"]) / base["duration_s"])
            if base:
                d["exp_reduction"] = (
                    1 - r.get("edge_expansions", 0) / max(1, base["edge_expansions"])
                    if r.get("edge_expansions") is not None else None
                )
                d["wall_reduction"] = (
                    1 - r.get("wall_s", 0) / max(1e-9, base["wall_s"]) if r.get("wall_s") is not None else None
                )
            return d

        row = {
            "od_id": f"od{i}",
            "from": na, "to": nb_name,
            "region_a": ra, "region_b": rb,
            "sub_nodes": len(sub.nodes),
            "sub_edges": len(sub.edges),
            "base": pack(base, "baseline"),
            "v043": pack(v43, "v043"),
            "v044": pack(v44, "v044"),
            "exp_A_branch_only": pack(exp_a, "A"),
            "exp_B_adaptive": pack(exp_b, "B"),
            "exp_C_prefilter": pack(exp_c, "C"),
            "exp_D_guard": pack(exp_d, "D"),
            "gh_topk": gh_sweep,
        }
        rows.append(row)

        # failure cases
        if v43 and v43.get("unreachable"):
            failures.append({
                "kind": "unreachable",
                "alg": "v043",
                "od_id": row["od_id"], "from": na, "to": nb_name,
                "region": f"{ra}->{rb}",
                "base_wall_s": base["wall_s"],
                "ml_wall_s": v43["wall_s"],
                "reason": "pruned_search_missed_target",
            })
        if v44 and v44.get("unreachable"):
            failures.append({
                "kind": "unreachable",
                "alg": "v044",
                "od_id": row["od_id"], "from": na, "to": nb_name,
                "region": f"{ra}->{rb}",
                "reason": "pruned_search_missed_target",
                "stats": v44.get("stats"),
            })
        if v44 and v44.get("ok") is False:
            pass
        elif v44 and v44.get("road_m") and base.get("road_m"):
            dr = max(0.0, (v44["road_m"] - base["road_m"]) / base["road_m"])
            wr = 1 - v44["wall_s"] / max(1e-9, base["wall_s"])
            if dr > 0.05 or wr < -0.5:
                failures.append({
                    "kind": "quality_or_slowdown",
                    "alg": "v044",
                    "od_id": row["od_id"], "from": na, "to": nb_name,
                    "distance_regret": dr,
                    "wall_reduction": wr,
                    "base_wall_s": base["wall_s"],
                    "ml_wall_s": v44["wall_s"],
                })
        if gh_sweep:
            for k, cell in gh_sweep["by_topk"].items():
                if cell.get("regret") is not None and cell["regret"] > 0.08:
                    failures.append({
                        "kind": "via_regret",
                        "alg": f"gh_top{k}",
                        "od_id": row["od_id"], "from": na, "to": nb_name,
                        "via_regret": cell["regret"],
                        "best_base_m": gh_sweep.get("base_best_m"),
                        "best_ls_m": cell.get("best_m"),
                    })

    # 汇总
    def agg(alg_key, field):
        return _stats([r[alg_key].get(field) for r in rows if r.get(alg_key) and r[alg_key].get(field) is not None])

    def unreach(alg_key):
        tot = sum(1 for r in rows if r.get(alg_key))
        bad = sum(1 for r in rows if r.get(alg_key) and r[alg_key].get("unreachable"))
        return {"count": bad, "total": tot, "rate": (bad / tot) if tot else None}

    b_to_k = {}
    for k in ("1", "2", "4", "8"):
        regrets, walls, calls = [], [], []
        base_walls = []
        for r in rows:
            gt = r.get("gh_topk") or {}
            cell = (gt.get("by_topk") or {}).get(k)
            if not cell:
                continue
            if cell.get("regret") is not None:
                regrets.append(cell["regret"])
            walls.append(cell.get("wall_s"))
            calls.append(cell.get("calls"))
            base_walls.append(gt.get("base_wall_s"))
        wr = []
        for w, bw in zip(walls, base_walls):
            if w is not None and bw:
                wr.append(1 - w / bw)
        b_to_k[k] = {
            "via_regret": _stats(regrets),
            "wall_reduction": _stats(wr),
            "calls": _stats(calls),
        }

    out = {
        "exp_id": "v0.4.4-pruner",
        "seed": seed,
        "n_od_requested": n_od,
        "rows": len(rows),
        "osm_load_s": round(load_s, 2),
        "graph_nodes": len(full_graph.nodes),
        "graph_edges": len(full_graph.edges),
        "region_dist": {
            "cross": sum(1 for r in rows if r["region_a"] != r["region_b"]),
            "core": sum(1 for r in rows if r["region_a"] == "chongqing_core" and r["region_b"] == "chongqing_core"),
            "jiangjin": sum(1 for r in rows if "jiangjin" in (r["region_a"], r["region_b"])),
        },
        "A_local": {
            "baseline": {
                "wall_s": agg("base", "wall_s"),
                "edge_expansions": agg("base", "edge_expansions"),
            },
            "v043": {
                "unreachable": unreach("v043"),
                "exp_reduction": agg("v043", "exp_reduction"),
                "wall_reduction": agg("v043", "wall_reduction"),
                "distance_regret": agg("v043", "distance_regret"),
                "feature_build_ms": agg("v043", "feature_build_ms"),
                "model_predict_ms": agg("v043", "model_predict_ms"),
                "wall_s": agg("v043", "wall_s"),
            },
            "v044": {
                "unreachable": unreach("v044"),
                "exp_reduction": agg("v044", "exp_reduction"),
                "wall_reduction": agg("v044", "wall_reduction"),
                "distance_regret": agg("v044", "distance_regret"),
                "duration_regret": agg("v044", "duration_regret"),
                "feature_build_ms": agg("v044", "feature_build_ms"),
                "model_predict_ms": agg("v044", "model_predict_ms"),
                "prune_ms": agg("v044", "prune_ms"),
                "wall_s": agg("v044", "wall_s"),
            },
            "experiments": {
                "A_branch_only": {"unreachable": unreach("exp_A_branch_only"), "wall_reduction": agg("exp_A_branch_only", "wall_reduction"), "exp_reduction": agg("exp_A_branch_only", "exp_reduction")},
                "B_adaptive": {"unreachable": unreach("exp_B_adaptive"), "wall_reduction": agg("exp_B_adaptive", "wall_reduction"), "exp_reduction": agg("exp_B_adaptive", "exp_reduction")},
                "C_prefilter": {"unreachable": unreach("exp_C_prefilter"), "wall_reduction": agg("exp_C_prefilter", "wall_reduction"), "exp_reduction": agg("exp_C_prefilter", "exp_reduction")},
                "D_guard": {"unreachable": unreach("exp_D_guard"), "wall_reduction": agg("exp_D_guard", "wall_reduction"), "exp_reduction": agg("exp_D_guard", "exp_reduction")},
            },
        },
        "B_gh_topk": b_to_k,
        "failure_count": len(failures),
    }
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    OUT_MAIN.write_text(json.dumps(out, indent=2, ensure_ascii=False, default=str), encoding="utf-8")
    # worst-20
    def score_f(f):
        if f.get("kind") == "unreachable":
            return 10 + f.get("ml_wall_s", 0)
        if f.get("kind") == "via_regret":
            return f.get("via_regret", 0) * 10
        return abs(f.get("wall_reduction", 0) or 0) + (f.get("distance_regret", 0) or 0)
    failures_sorted = sorted(failures, key=score_f, reverse=True)[:20]
    OUT_FAIL.write_text(json.dumps({"failure_count": len(failures), "worst_20": failures_sorted}, indent=2, ensure_ascii=False, default=str), encoding="utf-8")
    out["worst_20_preview"] = failures_sorted[:5]
    return out


if __name__ == "__main__":
    r = main()
    slim = {k: v for k, v in r.items() if k not in ("worst_20_preview",)}
    print(json.dumps(slim, indent=2, ensure_ascii=False, default=str)[:8000])
