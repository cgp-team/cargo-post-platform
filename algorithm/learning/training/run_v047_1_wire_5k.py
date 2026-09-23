"""V047.1：5K 模型接入 GHBranchSelector + 与 V046 小样本模型同 OD 对照。"""

from __future__ import annotations

import json
import random
import time
from pathlib import Path

from app.routing.graphhopper_routing import GraphHopperRoutingEngine, LocalRoutingConfig
from learning.path_search import RouteSearchRanker
from learning.path_search.gh_branch_selector import GHBranchSelector, MLWorthwhileGate
from learning.training.run_v043_pruner_closed_loop import make_via_candidates, train_ranker_from_gh, _stats
from learning.training.run_v04_experiment import load_stations

DATA = Path(__file__).resolve().parents[2] / "data"
REG = DATA / "model_registry"
OUT = DATA / "v047_1_wire_5k_result.json"
N_VIA = 16


def load_best_5k(seed: int = 3407) -> RouteSearchRanker:
    """默认取 5K；路径不存在则回退 1K。"""
    for tag, s in (("5k", seed), ("5k", 42), ("1k", 42)):
        d = REG / f"branch_ranker_{tag}_seed{s}"
        if d.exists() and ((d / "route_search_ranker.model.txt").exists() or (d / "route_search_ranker.model").exists()):
            r = RouteSearchRanker.load(d)
            if not r.fallback:
                return r
    # 训练失败回退：现场小样本（V046 协议）
    return RouteSearchRanker()


def bench_pair(gh, stations, ranker_a, ranker_b, n_od=80, seed=42, modes=("top4", "adaptive")):
    rng = random.Random(seed)
    names = sorted(stations)
    rows = []
    for i in range(n_od):
        a, b = rng.sample(names, 2)
        sa, sb = stations[a][:2], stations[b][:2]
        sr = gh.route(sa, sb, profile="bus")
        if not sr.available or len(sr.polyline) < 5:
            continue
        vias = make_via_candidates(sr.polyline, n_via=N_VIA, rng=rng)
        if len(vias) < 8:
            continue
        region = "cross" if stations[a][2] != stations[b][2] else stations[a][2]
        bucket = "SHORT" if sr.distance_m < 3000 else ("MEDIUM" if sr.distance_m < 12000 else "LONG")
        for mode in modes:
            outs = {}
            for name, rk in (("v046_small", ranker_a), ("v047_5k", ranker_b)):
                sel = GHBranchSelector(rk, region_id=region, gate=MLWorthwhileGate())
                o = sel.select_and_route(gh, sa, sb, vias, mode=mode)
                o["bucket"] = bucket
                o["region"] = region
                outs[name] = o
            rows.append({"od": i, "mode": mode, **outs})
    return rows


def summarize(rows, key, mode):
    sub = [r for r in rows if r["mode"] == mode]
    def g(field):
        return _stats([r[key].get(field) for r in sub if r[key].get(field) is not None])
    return {
        "n": len(sub),
        "wall_reduction": g("wall_reduction"),
        "absolute_saved_ms": g("absolute_saved_ms"),
        "distance_regret": g("distance_regret"),
        "duration_regret": g("duration_regret"),
        "fallback_rate": _stats([1.0 if r[key].get("fallback") else 0.0 for r in sub]),
        "gh_calls_candidate": g("gh_calls_candidate"),
    }


def main(n_od=80, seed=42):
    cfg = LocalRoutingConfig(graphhopper_base_url="http://127.0.0.1:8080", graphhopper_timeout_ms=8000)
    gh = GraphHopperRoutingEngine(cfg)
    stations = load_stations(80)
    rng = random.Random(seed)

    # A：V046 小样本协议 ranker（现场训练，对照）
    small = train_ranker_from_gh(gh, stations, rng, n=18, seed=seed)
    # B：V047 5K 注册模型
    big = load_best_5k(seed=3407)
    print("loaded 5k fallback=", big.fallback, "version=", big.version)

    rows = bench_pair(gh, stations, small, big, n_od=n_od, seed=seed)
    out = {
        "experiment_id": "v047.1-wire-5k",
        "seed": seed,
        "rows": len(rows),
        "model_5k_version": big.version,
        "modes": {
            m: {
                "v046_small": summarize(rows, "v046_small", m),
                "v047_5k": summarize(rows, "v047_5k", m),
            }
            for m in ("top4", "adaptive")
        },
    }
    # 回归判定：5k 不得明显差于 small
    for m in ("top4", "adaptive"):
        w_s = (out["modes"][m]["v046_small"]["wall_reduction"] or {}).get("mean") or 0
        w_b = (out["modes"][m]["v047_5k"]["wall_reduction"] or {}).get("mean") or 0
        r_b = (out["modes"][m]["v047_5k"]["distance_regret"] or {}).get("mean") or 0
        out["modes"][m]["verdict"] = "KEEP_5K" if (w_b >= w_s - 0.05 and r_b <= 0.08) else "KEEP_V046_SMALL"
    out["production_ranker"] = (
        "v047_5k" if out["modes"]["adaptive"]["verdict"] == "KEEP_5K" else "v046_small_or_retrain"
    )
    DATA.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(out, indent=2, ensure_ascii=False, default=str), encoding="utf-8")
    print(json.dumps({k: out[k] for k in ("production_ranker", "modes", "model_5k_version")}, indent=2, default=str)[:4000])
    return out


if __name__ == "__main__":
    main()
