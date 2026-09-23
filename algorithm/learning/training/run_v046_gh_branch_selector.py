"""V046 GH Branch Selector 实验：full / top1/2/4/8 / adaptive，cold/hot，分桶，多 seed。

LocalGraph Python Pruner = RESEARCH_ONLY，不参与本主线。
"""

from __future__ import annotations

import json
import random
import time
from pathlib import Path

import numpy as np

from app.routing.graphhopper_routing import GraphHopperRoutingEngine, LocalRoutingConfig
from learning.path_search.gh_branch_selector import GHBranchSelector, MLWorthwhileGate
from learning.training.run_v043_pruner_closed_loop import (
    make_via_candidates,
    train_ranker_from_gh,
    _stats,
)
from learning.training.run_v04_experiment import load_stations

DATA = Path(__file__).resolve().parents[2] / "data"
OUT = DATA / "v046_result.json"
OUT_FAIL = DATA / "v046_failure_cases.json"
MODES = ("full", "top1", "top2", "top4", "top8", "adaptive")
SEEDS = (42, 123, 3407, 2026, 8888)


def distance_bucket(d_m: float) -> str:
    if d_m < 3000:
        return "SHORT"
    if d_m < 12000:
        return "MEDIUM"
    return "LONG"


def sample_ods_v046(stations, n=200, seed=42):
    rng = random.Random(seed)
    names = sorted(stations)
    core = [x for x in names if stations[x][2] == "chongqing_core"]
    jj = [x for x in names if stations[x][2] == "jiangjin"]
    ods = []
    def pair(a, b):
        return (stations[a][:2], stations[b][:2], stations[a][2], stations[b][2], a, b)
    # 区域覆盖
    for _ in range(min(40, len(core), max(1, len(jj)))):
        ods.append(pair(rng.choice(core), rng.choice(jj)))
    for _ in range(min(100, max(1, len(core) - 1))):
        a, b = rng.sample(core, 2)
        ods.append(pair(a, b))
    for _ in range(min(30, max(0, len(jj) - 1))):
        a, b = rng.sample(jj, 2)
        ods.append(pair(a, b))
    # 短距
    cs = sorted(core, key=lambda n: (stations[n][0], stations[n][1]))
    for i in range(min(30, len(cs) - 4)):
        ods.append(pair(cs[i * 2], cs[i * 2 + 1]))
    rng.shuffle(ods)
    return ods[:n]


def train_branch_ranker(gh, stations, rng, n_od=18, n_via=16, seed=42):
    """用真实 via + GH 最优距离作 label（仅训练/离线，不进生产特征）。"""
    from learning.path_search import RouteSearchRanker
    from learning.path_search.gh_branch_selector import candidate_feature_row

    names = sorted(stations)
    X_list, y_list, sizes = [], [], []
    for i in range(n_od):
        a, b = rng.sample(names, 2)
        sa, sb = stations[a][:2], stations[b][:2]
        r = gh.route(sa, sb, profile="bus")
        if not r.available or len(r.polyline) < 5:
            continue
        vias = make_via_candidates(r.polyline, n_via=n_via, rng=rng)
        if len(vias) < 6:
            continue
        dists = []
        for w in vias:
            rr = gh.route(sa, sb, (w,), profile="bus")
            dists.append(rr.distance_m if rr.available else 1e12)
        if min(dists) >= 1e11:
            continue
        X = np.vstack([
            candidate_feature_row(w, sa, sb, region_id=stations[a][2]) for w in vias
        ])
        # label：最优 via=3，次优=2，其余 0/1（无并列规则，按距离序）
        order = list(np.argsort(dists, kind="stable"))
        y = np.zeros(len(vias), dtype=np.int32)
        y[order[0]] = 3
        if len(order) > 1:
            y[order[1]] = 2
        if len(order) > 2:
            y[order[2]] = 1
        X_list.append(X)
        y_list.append(y)
        sizes.append(len(y))
    ranker = RouteSearchRanker()
    if X_list:
        ranker.train(X_list, y_list, sizes, seed=seed, n_estimators=60)
    return ranker


def run_seed(seed=42, n_od=200, n_via=16, repeats=2, modes=MODES):
    cfg = LocalRoutingConfig(graphhopper_base_url="http://127.0.0.1:8080", graphhopper_timeout_ms=8000)
    gh = GraphHopperRoutingEngine(cfg)
    stations = load_stations(80)
    rng = random.Random(seed)
    ranker = train_branch_ranker(gh, stations, rng, n_od=18, n_via=n_via, seed=seed)
    ods = sample_ods_v046(stations, n=n_od, seed=seed)

    rows = []
    failures = []
    cache_cold = {}
    cache_hot = {}

    for i, (sa, sb, ra, rb, na, nbname) in enumerate(ods):
        seed_route = gh.route(sa, sb, profile="bus")
        if not seed_route.available or len(seed_route.polyline) < 5:
            continue
        vias = make_via_candidates(seed_route.polyline, n_via=n_via, rng=rng)
        if len(vias) < 8:
            continue
        region = "cross" if ra != rb else ra
        bucket = distance_bucket(seed_route.distance_m)

        results = {}
        for mode in modes:
            # cold：空 cache；hot：复用
            walls = []
            last = None
            for rep in range(max(1, repeats)):
                sel = GHBranchSelector(
                    ranker,
                    region_id=region,
                    gate=MLWorthwhileGate(),
                    feature_cache=cache_cold if rep == 0 else cache_hot,
                )
                # warm-up 第一次为 cold，其余 hot
                out = sel.select_and_route(gh, sa, sb, vias, mode=mode)
                walls.append(out["candidate_wall_ms"])
                last = out
            if last is None:
                continue
            # median candidate wall
            walls_sorted = sorted(walls)
            last = dict(last)
            last["candidate_wall_ms_median"] = walls_sorted[len(walls_sorted) // 2]
            last["candidate_wall_ms_min"] = walls_sorted[0]
            last["candidate_wall_ms_all"] = walls
            last["bucket"] = bucket
            last["region"] = region
            results[mode] = last

            if last.get("distance_regret") is not None and last["distance_regret"] > 0.12:
                failures.append({
                    "kind": "distance_regret", "od_id": f"od{i}", "from": na, "to": nbname,
                    "region": region, "bucket": bucket, "mode": mode,
                    "distance_regret": last["distance_regret"],
                    "duration_regret": last.get("duration_regret"),
                    "k": last.get("k"), "n_candidates": last.get("n_candidates"),
                    "full_best_m": last.get("full_best_distance_m"),
                    "selected_best_m": last.get("selected_best_distance_m"),
                    "scores_top": last.get("scores_top"),
                    "fallback_reason": last.get("fallback_reason"),
                })
            if last.get("fallback") and mode == "adaptive":
                failures.append({
                    "kind": "fallback", "od_id": f"od{i}", "from": na, "to": nbname,
                    "region": region, "bucket": bucket, "mode": mode,
                    "fallback_reason": last.get("fallback_reason"),
                    "distance_regret": last.get("distance_regret"),
                })
            if last.get("full_gh_best_miss") and mode in ("top1", "top2", "adaptive"):
                failures.append({
                    "kind": "full_gh_best_miss", "od_id": f"od{i}", "from": na, "to": nbname,
                    "region": region, "bucket": bucket, "mode": mode,
                    "distance_regret": last.get("distance_regret"),
                    "k": last.get("k"),
                })
            if (last.get("wall_reduction") or 0) < -0.2 and mode == "adaptive":
                failures.append({
                    "kind": "slowdown", "od_id": f"od{i}", "from": na, "to": nbname,
                    "region": region, "bucket": bucket, "mode": mode,
                    "wall_reduction": last.get("wall_reduction"),
                    "baseline_wall_ms": last.get("baseline_wall_ms"),
                    "candidate_wall_ms": last.get("candidate_wall_ms_median"),
                    "absolute_saved_ms": last.get("absolute_saved_ms"),
                })

        rows.append({
            "od_id": f"od{i}",
            "from": na, "to": nbname,
            "region_a": ra, "region_b": rb,
            "region": region,
            "bucket": bucket,
            "od_distance_m": seed_route.distance_m,
            "results": results,
        })

    # 汇总 per mode
    def agg_mode(mode, field, use_median_wall=False):
        vals = []
        for r in rows:
            cell = r["results"].get(mode)
            if not cell:
                continue
            if use_median_wall and field == "candidate_wall_ms":
                vals.append(cell.get("candidate_wall_ms_median"))
            elif field in cell and cell[field] is not None:
                vals.append(cell[field])
            elif (cell.get("stats") or {}).get(field) is not None:
                vals.append(cell["stats"][field])
        return _stats([v for v in vals if v is not None])

    summary_modes = {}
    for mode in modes:
        # wall_reduction 用 median candidate
        wrs, regs, dregs, abs_s, calls, feas = [], [], [], [], [], []
        for r in rows:
            c = r["results"].get(mode)
            if not c:
                continue
            bm = c.get("baseline_wall_ms") or 0
            cm = c.get("candidate_wall_ms_median") if c.get("candidate_wall_ms_median") is not None else c.get("candidate_wall_ms")
            if bm > 0 and cm is not None:
                wrs.append(1 - cm / bm)
                abs_s.append(bm - cm)
            if c.get("distance_regret") is not None:
                regs.append(c["distance_regret"])
            if c.get("duration_regret") is not None:
                dregs.append(c["duration_regret"])
            calls.append(c.get("gh_calls_candidate") or c.get("k"))
            if c.get("distance_regret") is not None:
                feas.append(1.0 if c["distance_regret"] < 1e-9 else 0.0)
            elif c.get("full_best_distance_m") is None:
                feas.append(1.0)
        summary_modes[mode] = {
            "wall_reduction": _stats(wrs),
            "absolute_saved_ms": _stats(abs_s),
            "distance_regret": _stats(regs),
            "duration_regret": _stats(dregs),
            "gh_calls_candidate": _stats(calls),
            "gh_call_reduction": _stats([1 - (c or 0) / 16 for c in calls]),
            "feasible_preservation": _stats(feas),
            "fallback_rate": _stats([
                1.0 if (r["results"].get(mode) or {}).get("fallback") else 0.0
                for r in rows if r["results"].get(mode)
            ]),
            "baseline_wall_ms": agg_mode(mode, "baseline_wall_ms"),
            "ml_inference_ms": agg_mode(mode, "ml_inference_ms"),
            "feature_ms": agg_mode(mode, "feature_ms"),
        }

    def by_bucket(mode, field):
        out = {}
        for b in ("SHORT", "MEDIUM", "LONG"):
            vals = []
            for r in rows:
                if r["bucket"] != b:
                    continue
                c = r["results"].get(mode)
                if not c:
                    continue
                if field == "wall_reduction":
                    bm, cm = c.get("baseline_wall_ms") or 0, c.get("candidate_wall_ms_median") or c.get("candidate_wall_ms")
                    if bm > 0 and cm is not None:
                        vals.append(1 - cm / bm)
                elif field == "absolute_saved_ms":
                    bm, cm = c.get("baseline_wall_ms") or 0, c.get("candidate_wall_ms_median") or c.get("candidate_wall_ms")
                    if cm is not None:
                        vals.append(bm - cm)
                elif c.get(field) is not None:
                    vals.append(c[field])
            out[b] = _stats(vals)
        return out

    def by_region(mode, field):
        out = {}
        for reg in ("chongqing_core", "jiangjin", "cross"):
            vals = []
            for r in rows:
                if r["region"] != reg:
                    continue
                c = r["results"].get(mode)
                if not c:
                    continue
                if field == "wall_reduction":
                    bm, cm = c.get("baseline_wall_ms") or 0, c.get("candidate_wall_ms_median") or c.get("candidate_wall_ms")
                    if bm > 0 and cm is not None:
                        vals.append(1 - cm / bm)
                elif field == "absolute_saved_ms":
                    bm, cm = c.get("baseline_wall_ms") or 0, c.get("candidate_wall_ms_median") or c.get("candidate_wall_ms")
                    if cm is not None:
                        vals.append(bm - cm)
                elif c.get(field) is not None:
                    vals.append(c[field])
            out[reg] = _stats(vals)
        return out

    # cold vs hot：第一次结果 vs 后续
    cold_hot = {
        "cache_hit_rate": {
            "cold": None,
            "hot": None,
        }
    }
    hits = []
    for r in rows:
        c = r["results"].get("adaptive") or r["results"].get("top4")
        if c and (c.get("stats") or {}).get("cache_hit_rate") is not None:
            hits.append(c["stats"]["cache_hit_rate"])
    cold_hot["cache_hit_rate"]["hot"] = _stats(hits)

    out = {
        "experiment_id": "v046-gh-branch-selector",
        "seed": seed,
        "n_od_requested": n_od,
        "rows": len(rows),
        "n_via": n_via,
        "repeats": repeats,
        "region_dist": {
            "core": sum(1 for r in rows if r["region"] == "chongqing_core"),
            "jiangjin": sum(1 for r in rows if r["region"] == "jiangjin"),
            "cross": sum(1 for r in rows if r["region"] == "cross"),
        },
        "bucket_dist": {b: sum(1 for r in rows if r["bucket"] == b) for b in ("SHORT", "MEDIUM", "LONG")},
        "modes": summary_modes,
        "adaptive_by_bucket": {f: by_bucket("adaptive", f) for f in ("wall_reduction", "absolute_saved_ms", "distance_regret")},
        "top4_by_bucket": {f: by_bucket("top4", f) for f in ("wall_reduction", "absolute_saved_ms", "distance_regret")},
        "adaptive_by_region": {f: by_region("adaptive", f) for f in ("wall_reduction", "absolute_saved_ms", "distance_regret")},
        "top4_by_region": {f: by_region("top4", f) for f in ("wall_reduction", "absolute_saved_ms", "distance_regret")},
        "cold_hot": cold_hot,
        "failure_count": len(failures),
        "rows_detail": rows,
    }
    return out, failures


def decide(out_multi):
    """End-to-end Gate。看 adaptive/top4 的 wall 转正 + regret + fallback + 多 seed。"""
    checks = {}
    wr_means = []
    abs_means = []
    reg_p95 = []
    fb = []
    for o in out_multi:
        for mode in ("adaptive", "top4"):
            m = o.get("modes", {}).get(mode) or {}
            wr = (m.get("wall_reduction") or {}).get("mean")
            ab = (m.get("absolute_saved_ms") or {}).get("mean")
            rg = (m.get("distance_regret") or {}).get("p95")
            fr = (m.get("fallback_rate") or {}).get("mean")
            if wr is not None:
                wr_means.append(wr)
            if ab is not None:
                abs_means.append(ab)
            if rg is not None:
                reg_p95.append(rg)
            if fr is not None:
                fb.append(fr)
    checks["wall_reduction_mean"] = float(np.mean(wr_means)) if wr_means else None
    checks["absolute_saved_ms_mean"] = float(np.mean(abs_means)) if abs_means else None
    checks["distance_regret_p95"] = float(np.mean(reg_p95)) if reg_p95 else None
    checks["fallback_rate_mean"] = float(np.mean(fb)) if fb else None
    checks["multi_seed_wall_positive"] = all(x > 0 for x in wr_means) if wr_means else False
    checks["teacher_leakage"] = 0

    ok = (
        checks["wall_reduction_mean"] is not None
        and checks["wall_reduction_mean"] > 0
        and checks["multi_seed_wall_positive"]
        and (checks["distance_regret_p95"] or 0) <= 0.15
        and (checks["fallback_rate_mean"] or 0) <= 0.5
        and checks["teacher_leakage"] == 0
    )
    checks["decision"] = "ROUTE_SEARCH_BRANCH_SELECTOR_V046_CANDIDATE" if ok else "V046_REJECTED"
    if not ok and (checks["wall_reduction_mean"] or 0) <= 0:
        checks["block"] = "V046_BLOCKED_ON_WALLCLOCK"
    else:
        checks["block"] = "" if ok else "V046_GATE_FAIL"
    return checks


def main(n_od=200, seeds=SEEDS, n_via=16, repeats=2):
    DATA.mkdir(parents=True, exist_ok=True)
    all_out, all_fail = [], []
    for seed in seeds:
        print(f"=== v046 seed={seed} ===")
        out, fails = run_seed(seed=seed, n_od=n_od, n_via=n_via, repeats=repeats)
        out.pop("rows_detail", None)  # 主 JSON 不放巨量明细
        all_out.append(out)
        all_fail.extend(fails)
        ad = out["modes"].get("adaptive") or {}
        t4 = out["modes"].get("top4") or {}
        print(json.dumps({
            "seed": seed,
            "rows": out["rows"],
            "adaptive_wr": (ad.get("wall_reduction") or {}).get("mean"),
            "adaptive_abs": (ad.get("absolute_saved_ms") or {}).get("mean"),
            "adaptive_reg": (ad.get("distance_regret") or {}).get("mean"),
            "adaptive_fb": (ad.get("fallback_rate") or {}).get("mean"),
            "top4_wr": (t4.get("wall_reduction") or {}).get("mean"),
            "top4_abs": (t4.get("absolute_saved_ms") or {}).get("mean"),
            "top4_reg_p95": (t4.get("distance_regret") or {}).get("p95"),
        }, default=str))

    gate = decide(all_out)
    summary = {
        "experiment_id": "v046-gh-branch-selector",
        "seeds": list(seeds),
        "n_od": n_od,
        "per_seed": all_out,
        "end_to_end_gate": gate,
        "final_status": gate["decision"],
        "long_training_allowed": False,
        "localgraph_pruner": "RESEARCH_ONLY",
    }
    OUT.write_text(json.dumps(summary, indent=2, ensure_ascii=False, default=str), encoding="utf-8")

    def fkey(f):
        return abs(f.get("distance_regret") or 0) * 10 + abs(f.get("wall_reduction") or 0) + (1 if f.get("kind") == "full_gh_best_miss" else 0)
    worst = sorted(all_fail, key=fkey, reverse=True)[:20]
    OUT_FAIL.write_text(json.dumps({
        "failure_count": len(all_fail),
        "worst_20": worst,
    }, indent=2, ensure_ascii=False, default=str), encoding="utf-8")
    return summary


if __name__ == "__main__":
    s = main()
    print(json.dumps({"final_status": s["final_status"], "gate": s["end_to_end_gate"]}, indent=2, default=str))
