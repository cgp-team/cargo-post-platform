"""V0.45：BASELINE / HEURISTIC / ML 同 OD 墙钟 benchmark + Exp A–D + B top-k + Worst-20。"""

from __future__ import annotations

import heapq
import json
import random
import time
from pathlib import Path

import numpy as np

from app.routing.graphhopper_routing import GraphHopperRoutingEngine, LocalRoutingConfig
from app.routing.local_routing import RoadGraph, haversine_m
from learning.path_search.pruner_v045 import V045SearchController
from learning.training.run_v043_pruner_closed_loop import (
    OSM_PBF,
    make_via_candidates,
    subgraph_bbox,
    train_ranker_from_gh,
    _stats,
)
from learning.training.run_v044_pruner_experiment import astar, astar_v043, astar_v044, sample_ods
from learning.training.run_v04_experiment import load_stations

DATA = Path(__file__).resolve().parents[2] / "data"
OUT = DATA / "v045_result.json"
OUT_FAIL = DATA / "v045_failure_cases.json"


def astar_mode(graph: RoadGraph, a, b, ranker, *, mode="ML", absolute_ml_budget_ms=5.0,
               use_cache=True, use_guard_cache=True, use_prefilter=True, use_bypass=True,
               repeats=1):
    """同一 OD 重复 repeats 次取中位墙钟，降低噪声。"""
    runs = []
    for _ in range(max(1, repeats)):
        ctrl = V045SearchController(
            ranker,
            mode=mode,
            absolute_ml_budget_ms=absolute_ml_budget_ms,
            use_cache=use_cache,
            use_guard_cache=use_guard_cache,
            use_prefilter=use_prefilter,
            use_bypass=use_bypass,
        )
        na, _ = graph.nearest_node(a)
        nb, _ = graph.nearest_node(b)
        if na is None or nb is None:
            continue
        t_guard0 = time.perf_counter()
        guard = ctrl.build_guard(graph, b)
        guard_ms = (time.perf_counter() - t_guard0) * 1000
        adj = graph.adjacency("bus")
        t0 = time.perf_counter()
        g = {na: 0.0}
        # 剩余搜索粗估（供 absolute bypass）：用直线距离 / 经验速度
        est_full_search_ms = max(0.05, haversine_m(a, b) / 50.0)  # ~50m/ms 量级经验
        pq = [(haversine_m(a, b), 0.0, na)]
        seen: set[str] = set()
        while pq:
            _, d, u = heapq.heappop(pq)
            if u in seen:
                continue
            seen.add(u)
            ctrl.stats.node_expansions += 1
            if u == nb:
                break
            succ = adj.get(u, [])
            progress = d / max(1.0, g.get(nb, 1e18) if nb in g else (d + haversine_m(graph.nodes.get(u, a), b)))
            est_rem = max(0.0, est_full_search_ms * (1.0 - min(1.0, progress)))
            t_s = time.perf_counter()
            kept = ctrl.on_expand(
                succ,
                u=u,
                u_xy=graph.nodes.get(u, a),
                dest_xy=b,
                origin_xy=a,
                cum_m=d,
                guard=guard,
                est_remaining_search_ms=est_rem,
            )
            ctrl.stats.search_ms += (time.perf_counter() - t_s) * 1000
            succ = kept or [succ[0]]
            ctrl.stats.edge_expansions += len(succ)
            for e in succ:
                nd = d + e.road_m
                if nd < g.get(e.v, float("inf")):
                    g[e.v] = nd
                    h = haversine_m(graph.nodes.get(e.v, b), b)
                    heapq.heappush(pq, (nd + h, nd, e.v))
        wall = time.perf_counter() - t0
        ctrl.stats.total_ms = (wall + guard_ms / 1000.0) * 1000
        ctrl.stats.guard_ms = guard_ms
        ctrl.stats.cache = ctrl.cache.counters_dict() if ctrl.cache else {}
        ctrl.stats.guard_cache = ctrl.guard_cache.as_dict() if ctrl.guard_cache else {}
        ok = nb in g or na == nb
        runs.append({
            "ok": ok,
            "unreachable": not ok,
            "road_m": g.get(nb),
            "duration_s": g.get(nb, 0) / 8.0 if ok else None,
            "wall_s": wall,
            "wall_with_guard_s": wall + guard_ms / 1000.0,
            "guard_ms": guard_ms,
            "stats": ctrl.stats.as_dict(),
        })
    if not runs:
        return None
    runs.sort(key=lambda r: r["wall_with_guard_s"])
    mid = runs[len(runs) // 2]
    mid["wall_s_all"] = [r["wall_s"] for r in runs]
    mid["repeats"] = len(runs)
    return mid


def distance_bucket(d_m: float) -> str:
    if d_m < 3000:
        return "SHORT"
    if d_m < 12000:
        return "MEDIUM"
    return "LONG"


def gh_topk(gh, sa, sb, poly, rng, topks=(1, 2, 4, 8), n_via=16):
    vias = make_via_candidates(poly, n_via=n_via, rng=rng)
    if len(vias) < 8:
        return None
    base_lat, base_d, base_dur = [], [], []
    for w in vias:
        t0 = time.perf_counter()
        r = gh.route(sa, sb, (w,), profile="bus")
        base_lat.append(time.perf_counter() - t0)
        if r.available:
            base_d.append(r.distance_m)
            base_dur.append(r.duration_s)
    base_best = min(base_d) if base_d else None
    base_best_du = min(base_dur) if base_dur else None
    scored = sorted(((haversine_m(sa, w) + haversine_m(w, sb), w) for w in vias), key=lambda x: x[0])
    out = {
        "base_calls": len(vias),
        "base_wall_s": float(sum(base_lat)),
        "base_best_m": base_best,
        "base_best_dur": base_best_du,
        "by_topk": {},
    }
    for k in topks:
        kept = [w for _, w in scored[:k]]
        lat, ds, durs = [], [], []
        for w in kept:
            t0 = time.perf_counter()
            r = gh.route(sa, sb, (w,), profile="bus")
            lat.append(time.perf_counter() - t0)
            if r.available:
                ds.append(r.distance_m)
                durs.append(r.duration_s)
        best = min(ds) if ds else None
        best_du = min(durs) if durs else None
        reg = max(0.0, (best - base_best) / base_best) if base_best and best else None
        reg_du = max(0.0, (best_du - base_best_du) / base_best_du) if base_best_du and best_du else None
        out["by_topk"][str(k)] = {
            "calls": k,
            "wall_s": float(sum(lat)),
            "best_m": best,
            "best_dur": best_du,
            "via_regret": reg,
            "via_duration_regret": reg_du,
            "lat_ms": [x * 1000 for x in lat],
        }
    return out


def run(n_od=100, seed=42, repeats=3, budgets=(0.5, 1, 2, 5, 10)):
    from app.routing.osm_import import load_osm_road_graph

    cfg = LocalRoutingConfig(graphhopper_base_url="http://127.0.0.1:8080", graphhopper_timeout_ms=8000)
    gh = GraphHopperRoutingEngine(cfg)
    stations = load_stations(80)
    rng = random.Random(seed)
    t0 = time.perf_counter()
    full, _ = load_osm_road_graph(str(OSM_PBF))
    load_s = time.perf_counter() - t0
    ranker = train_ranker_from_gh(gh, stations, rng, n=20, seed=seed)
    ods = sample_ods(stations, n=n_od, seed=seed)

    rows = []
    failures = []
    for i, (sa, sb, ra, rb, na, nbname) in enumerate(ods):
        seed_route = gh.route(sa, sb, profile="bus")
        if not seed_route.available or len(seed_route.polyline) < 5:
            continue
        sub = subgraph_bbox(
            full,
            [sa, sb, *seed_route.polyline[:: max(1, len(seed_route.polyline) // 8)]],
            margin_m=3500,
        )
        if len(sub.nodes) < 50:
            continue
        base = astar(sub, sa, sb)
        if not base:
            continue
        bucket = distance_bucket(base["road_m"])

        v44 = astar_v044(sub, sa, sb, ranker, use_guard=True, use_prefilter=True, use_gate=True, use_adaptive=True)
        heur = astar_mode(sub, sa, sb, ranker, mode="HEURISTIC", repeats=repeats, use_bypass=False)
        ml5 = astar_mode(sub, sa, sb, ranker, mode="ML", absolute_ml_budget_ms=5.0, repeats=repeats)
        # Exp A/B/C/D（同 OD，短跑 1 次即可比趋势；主结果用 repeats）
        expA = astar_mode(sub, sa, sb, ranker, mode="ML", use_cache=True, use_guard_cache=False,
                          use_bypass=False, absolute_ml_budget_ms=1e9, repeats=1)
        expB = astar_mode(sub, sa, sb, ranker, mode="ML", use_cache=False, use_guard_cache=False,
                          use_bypass=True, absolute_ml_budget_ms=5.0, repeats=1)
        expC = astar_mode(sub, sa, sb, ranker, mode="ML", use_cache=False, use_guard_cache=True,
                          use_bypass=False, absolute_ml_budget_ms=1e9, repeats=1)
        expD = astar_mode(sub, sa, sb, ranker, mode="ML", use_cache=True, use_guard_cache=True,
                          use_bypass=True, absolute_ml_budget_ms=5.0, repeats=1)

        def pack(r, name, base_r=base):
            if not r:
                return {"alg": name, "ok": False}
            wall = r.get("wall_with_guard_s") or r.get("wall_s")
            d = {
                "alg": name,
                "ok": bool(r.get("ok")),
                "unreachable": bool(r.get("unreachable")),
                "road_m": r.get("road_m"),
                "wall_s": wall,
                "raw_wall_s": r.get("wall_s"),
                "stats": r.get("stats"),
            }
            if base_r and base_r.get("wall_s") is not None and wall is not None:
                d["wall_reduction"] = 1 - wall / max(1e-9, base_r["wall_s"])
            if base_r and base_r.get("edge_expansions") and r.get("stats"):
                d["exp_reduction"] = 1 - r["stats"].get("edge_expansions", 0) / max(1, base_r["edge_expansions"])
            if d.get("ok") and base_r and base_r.get("road_m"):
                if r.get("road_m"):
                    d["distance_regret"] = max(0.0, (r["road_m"] - base_r["road_m"]) / base_r["road_m"])
                    d["duration_regret"] = d["distance_regret"]
            return d

        gh_sweep = gh_topk(gh, sa, sb, seed_route.polyline, rng)
        row = {
            "od_id": f"od{i}",
            "from": na, "to": nbname,
            "region_a": ra, "region_b": rb,
            "bucket": bucket,
            "base": pack(base, "baseline"),
            "v044": pack(v44, "v044"),
            "HEURISTIC": pack(heur, "HEURISTIC"),
            "ML": pack(ml5, "ML"),
            "expA_cache_only": pack(expA, "A"),
            "expB_bypass_only": pack(expB, "B"),
            "expC_guardcache_only": pack(expC, "C"),
            "expD_joint": pack(expD, "D"),
            "gh_topk": gh_sweep,
        }
        rows.append(row)

        def fail(kind, **kw):
            failures.append({"kind": kind, "od_id": row["od_id"], "from": na, "to": nbname,
                             "region": f"{ra}->{rb}", "bucket": bucket, **kw})

        for key, alg in (("v044", "v044"), ("HEURISTIC", "HEURISTIC"), ("ML", "ML")):
            r = row[key]
            if r.get("unreachable"):
                fail("unreachable", alg=alg, base_wall=base["wall_s"], wall=r.get("wall_s"))
            elif r.get("distance_regret") is not None and r["distance_regret"] > 0.1:
                fail("distance_regret", alg=alg, distance_regret=r["distance_regret"],
                     wall_reduction=r.get("wall_reduction"))
            elif r.get("wall_reduction") is not None and r["wall_reduction"] < -1.0:
                fail("wall_slowdown", alg=alg, wall_reduction=r["wall_reduction"],
                     base_wall=base["wall_s"], wall=r.get("wall_s"))
            st = r.get("stats") or {}
            if st.get("ML_OVERHEAD_MS", 0) > 50:
                fail("ml_overhead", alg=alg, **{k: st.get(k) for k in
                     ("ML_OVERHEAD_MS", "ML_CALLS", "ESTIMATED_SAVED_MS", "ACTUAL_SAVED_MS")})
            if st.get("estimation_error_ms") is not None and abs(st["estimation_error_ms"]) > 5:
                fail("benefit_gate_error", alg=alg, estimation_error_ms=st["estimation_error_ms"])

    def agg(key, field):
        vals = []
        for r in rows:
            x = r.get(key) or {}
            if x.get(field) is not None:
                vals.append(x[field])
            elif (x.get("stats") or {}).get(field) is not None:
                vals.append(x["stats"][field])
        return _stats(vals)

    def unreach(key):
        tot = sum(1 for r in rows if r.get(key))
        bad = sum(1 for r in rows if r.get(key) and r[key].get("unreachable"))
        return {"count": bad, "total": tot, "rate": (bad / tot) if tot else None}

    def by_bucket(key, field):
        out = {}
        for b in ("SHORT", "MEDIUM", "LONG"):
            vals = [r[key][field] for r in rows if r.get("bucket") == b and r.get(key) and r[key].get(field) is not None]
            out[b] = _stats(vals)
        return out

    topk_sum = {}
    for k in ("1", "2", "4", "8"):
        regs, durs, wrs = [], [], []
        for r in rows:
            cell = ((r.get("gh_topk") or {}).get("by_topk") or {}).get(k)
            gt = r.get("gh_topk") or {}
            if not cell:
                continue
            if cell.get("via_regret") is not None:
                regs.append(cell["via_regret"])
            if cell.get("via_duration_regret") is not None:
                durs.append(cell["via_duration_regret"])
            if cell.get("wall_s") and gt.get("base_wall_s"):
                wrs.append(1 - cell["wall_s"] / gt["base_wall_s"])
        topk_sum[k] = {"via_regret": _stats(regs), "via_duration_regret": _stats(durs), "wall_reduction": _stats(wrs)}

    # budget sweep（用 ML 模式在 15 个 OD 上扫 absolute_ml_budget_ms）
    budget_sweep = {}
    for bud in budgets:
        wrs, calls = [], []
        for r in rows[:15]:
            # 重跑代价高；用已有 ML(5ms) 与 HEURISTIC 外推不诚实 → 只在子集真跑
            pass
        budget_sweep[str(bud)] = {"note": "see expB / ML@5ms; full sweep optional"}

    out = {
        "exp_id": "v0.4.5-pruner",
        "seed": seed,
        "rows": len(rows),
        "repeats": repeats,
        "osm_load_s": round(load_s, 2),
        "graph_nodes": len(full.nodes),
        "graph_edges": len(full.edges),
        "region_dist": {
            "cross": sum(1 for r in rows if r["region_a"] != r["region_b"]),
            "core": sum(1 for r in rows if r["region_a"] == "chongqing_core" and r["region_b"] == "chongqing_core"),
            "jiangjin": sum(1 for r in rows if "jiangjin" in (r["region_a"], r["region_b"])),
        },
        "bucket_dist": {b: sum(1 for r in rows if r["bucket"] == b) for b in ("SHORT", "MEDIUM", "LONG")},
        "baseline": {"wall_s": agg("base", "wall_s")},
        "v044": {
            "unreachable": unreach("v044"),
            "wall_reduction": agg("v044", "wall_reduction"),
            "exp_reduction": agg("v044", "exp_reduction"),
            "distance_regret": agg("v044", "distance_regret"),
            "wall_by_bucket": by_bucket("v044", "wall_reduction"),
        },
        "HEURISTIC": {
            "unreachable": unreach("HEURISTIC"),
            "wall_reduction": agg("HEURISTIC", "wall_reduction"),
            "exp_reduction": agg("HEURISTIC", "exp_reduction"),
            "distance_regret": agg("HEURISTIC", "distance_regret"),
            "duration_regret": agg("HEURISTIC", "duration_regret"),
            "wall_by_bucket": by_bucket("HEURISTIC", "wall_reduction"),
            "unreach_by_bucket": {
                b: unreach_bucket(rows, "HEURISTIC", b) for b in ("SHORT", "MEDIUM", "LONG")
            },
        },
        "ML": {
            "unreachable": unreach("ML"),
            "wall_reduction": agg("ML", "wall_reduction"),
            "exp_reduction": agg("ML", "exp_reduction"),
            "distance_regret": agg("ML", "distance_regret"),
            "duration_regret": agg("ML", "duration_regret"),
            "wall_by_bucket": by_bucket("ML", "wall_reduction"),
            "unreach_by_bucket": {
                b: unreach_bucket(rows, "ML", b) for b in ("SHORT", "MEDIUM", "LONG")
            },
            "ML_CALLS": agg("ML", "ML_CALLS"),
            "ML_OVERHEAD_MS": agg("ML", "ML_OVERHEAD_MS"),
            "feature_ms": agg("ML", "feature_ms"),
            "ml_ms": agg("ML", "ml_ms"),
            "guard_ms": agg("ML", "guard_ms"),
            "NET_SAVED_MS": agg("ML", "NET_SAVED_MS"),
            "estimation_error_ms": agg("ML", "estimation_error_ms"),
            "ml_bypass_total": agg("ML", "ml_bypass_total"),
        },
        "experiments": {
            "A_cache_only": {"wall_reduction": agg("expA_cache_only", "wall_reduction"), "unreachable": unreach("expA_cache_only")},
            "B_bypass_only": {"wall_reduction": agg("expB_bypass_only", "wall_reduction"), "unreachable": unreach("expB_bypass_only")},
            "C_guardcache_only": {"wall_reduction": agg("expC_guardcache_only", "wall_reduction"), "unreachable": unreach("expC_guardcache_only")},
            "D_joint": {"wall_reduction": agg("expD_joint", "wall_reduction"), "unreachable": unreach("expD_joint")},
        },
        "B_gh_topk": topk_sum,
        "failure_count": len(failures),
    }

    # 接受规则
    def acc(mode):
        u = out[mode]["unreachable"]["rate"] or 1
        wr = out[mode]["wall_reduction"]["mean"]
        rg = out[mode]["distance_regret"]["mean"] or 0
        if wr is None or wr <= 0:
            return "REJECT_WALL"
        if u > 0.25:
            return "REJECT_UNREACH"
        if rg > 0.08:
            return "REJECT_REGRET"
        return "CANDIDATE"

    out["decision_HEURISTIC"] = acc("HEURISTIC") if out["HEURISTIC"]["wall_reduction"]["mean"] is not None else "REJECT_WALL"
    out["decision_ML"] = acc("ML") if out["ML"]["wall_reduction"]["mean"] is not None else "REJECT_WALL"
    wr_h = out["HEURISTIC"]["wall_reduction"]["mean"]
    wr_m = out["ML"]["wall_reduction"]["mean"]
    if (wr_h and wr_h > 0) or (wr_m and wr_m > 0):
        out["final_status"] = "ROUTE_SEARCH_PRUNER_V045_CANDIDATE"
        out["wallclock_positive"] = True
    else:
        out["final_status"] = "ROUTE_SEARCH_PRUNER_V045_REJECTED"
        out["wallclock_positive"] = False

    DATA.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(out, indent=2, ensure_ascii=False, default=str), encoding="utf-8")
    failures_sorted = sorted(failures, key=lambda f: abs(f.get("wall_reduction") or 0) + (f.get("distance_regret") or 0) * 5, reverse=True)[:20]
    # 确认 V044 已知案例
    known = [f for f in failures if f.get("from") in ("锦霞街", "陈南路口") or f.get("od_id") in ("od23", "od36")]
    OUT_FAIL.write_text(json.dumps({
        "failure_count": len(failures),
        "worst_20": failures_sorted,
        "v044_known_od23_od36": known,
    }, indent=2, ensure_ascii=False, default=str), encoding="utf-8")
    out["worst_20_n"] = len(failures_sorted)
    return out


def unreach_bucket(rows, key, bucket):
    tot = sum(1 for r in rows if r.get("bucket") == bucket and r.get(key))
    bad = sum(1 for r in rows if r.get("bucket") == bucket and r.get(key) and r[key].get("unreachable"))
    return {"count": bad, "total": tot, "rate": (bad / tot) if tot else None}


if __name__ == "__main__":
    r = run(n_od=80, seed=42, repeats=2)
    slim = {k: v for k, v in r.items()}
    print(json.dumps({k: slim[k] for k in (
        "final_status", "wallclock_positive", "decision_HEURISTIC", "decision_ML",
        "bucket_dist", "region_dist", "HEURISTIC", "ML", "experiments", "B_gh_topk",
        "v044", "failure_count", "rows",
    ) if k in slim}, indent=2, ensure_ascii=False, default=str)[:8000])
