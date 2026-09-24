# -*- coding: utf-8 -*-
"""多种子 × 多真实线路大池 A/B，汇总 force/off 里程比与搜索耗时。

说明：PlanResult.total_distance 单位是 **degree**（欧氏路径口径），比值可比，绝对值不是公里。
"""
from __future__ import annotations

import json
import statistics
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "algorithm"))
sys.path.insert(0, str(ROOT / "artifacts"))

import ab_large_pool as ab


def run_pair(seed: int, n_orders: int = 22, candidate_size: int = 64):
    rows = {}
    for mode in ("off", "force"):
        rows[mode] = ab.run_once(mode, n_orders, candidate_size) if False else None
    # run_once 固定 seed=20260318，这里直接 build_request 注入 seed
    out = {}
    for mode in ("off", "force"):
        req, line_name, n_tasks, n_skel = ab.build_request(mode, n_orders, candidate_size, seed=seed)
        from app.haco import construction as C
        from app.solver import solve
        import time
        C.generate_insertion_candidates.last_stats = {}
        # 累计 rerank
        from app.haco.ml_ranker import get_ranker
        rk = get_ranker()
        rk.rerank_calls = 0
        t0 = time.perf_counter()
        res = solve(req)
        elapsed = time.perf_counter() - t0
        stops = sum(len(p.stops) for p in res.vehicle_plans)
        out[mode] = {
            "mode": mode,
            "seed": seed,
            "line": line_name,
            "status": res.status,
            "elapsed_s": round(elapsed, 3),
            "vehicles": len(res.vehicle_plans),
            "distance_degree": round(res.total_distance, 4),
            "stops": stops,
            "unassigned": len(res.unassigned_order_ids or []),
            "rerank_calls": rk.rerank_calls,
            "warnings": [w for w in (res.warnings or []) if any(k in w for k in ("SEED_CANDIDATES", "TOTAL_SEARCH", "CANDIDATE_MS", "ITERATION"))],
        }
    return out


def main():
    seeds = [11, 42, 88, 2026, 3407]
    print("=" * 64)
    print(f"多线路多种子大池 A/B · {len(seeds)} seeds · 22 单 · pool=64 · 单位=degree")
    print("=" * 64)
    pairs = []
    for seed in seeds:
        p = run_pair(seed)
        pairs.append(p)
        a, b = p["off"], p["force"]
        ratio = (b["distance_degree"] / a["distance_degree"]) if a["distance_degree"] else None
        print(f"\nseed={seed}  {a['line'][:28]}")
        print(f"  off  dist={a['distance_degree']} stops={a['stops']} t={a['elapsed_s']}s unassigned={a['unassigned']}")
        print(f"  force dist={b['distance_degree']} stops={b['stops']} t={b['elapsed_s']}s unassigned={b['unassigned']} rerank={b['rerank_calls']}")
        print(f"  ratio force/off = {ratio:.3f}" if ratio else "  ratio n/a")

    offs = [p["off"]["distance_degree"] for p in pairs]
    fors = [p["force"]["distance_degree"] for p in pairs]
    ratios = [f / o for f, o in zip(fors, offs) if o]
    wins = sum(1 for f, o in zip(fors, offs) if f < o - 1e-9)
    print("\n" + "=" * 64)
    print("汇总")
    print(f"  off   dist mean={statistics.mean(offs):.4f}  median={statistics.median(offs):.4f}")
    print(f"  force dist mean={statistics.mean(fors):.4f}  median={statistics.median(fors):.4f}")
    print(f"  ratio force/off mean={statistics.mean(ratios):.3f}  median={statistics.median(ratios):.3f}")
    print(f"  force 更优场次: {wins}/{len(pairs)}")
    print(f"  未分配 off/force: {sum(p['off']['unassigned'] for p in pairs)}/{sum(p['force']['unassigned'] for p in pairs)}")
    out = ROOT / "artifacts" / "ab_large_pool_multi.json"
    out.write_text(json.dumps({"pairs": pairs, "summary": {
        "ratio_mean": statistics.mean(ratios),
        "ratio_median": statistics.median(ratios),
        "force_wins": wins,
        "n": len(pairs),
    }}, ensure_ascii=False, indent=2), encoding="utf-8")
    print("saved", out)


if __name__ == "__main__":
    main()
