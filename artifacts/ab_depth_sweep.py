# -*- coding: utf-8 -*-
"""搜索深度对照：固定足够大的墙钟预算，只扫 max_iterations。

回答：更深搜索是否总能降低目标？看目标是否随迭代饱和。
"""
from __future__ import annotations

import json
import statistics
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "algorithm"))
sys.path.insert(0, str(ROOT / "artifacts"))

import ab_large_pool as ab
from app.haco import construction as C
from app.models import AlgorithmConfig, AlgorithmMode
from app.solver import solve


def run_one(seed: int, max_iters: int, mode: str = "off"):
    req, line_name, n_tasks, n_skel = ab.build_request(mode, 22, 64, seed=seed)
    # 放开墙钟，只让迭代数控制深度
    req.algorithmConfig = AlgorithmConfig(
        algorithmMode=AlgorithmMode.HACO,
        randomSeed=seed,
        max_iterations=max_iters,
        ant_count=8,
        maxDetourDistanceKm=3.5,
        use_branch_ranker=mode,
        candidate_size=32,
        # 钉死/关闭其它阶段，只让 ACO max_iterations 变化
        alns_iterations=-1,  # <0 关闭 ALNS，纯 ACO 深度
        local_search_rounds=0,
        convergence_threshold=100,
        haco_time_limit=60.0,
        overall_time_limit=60.0,
        greedy_seed_time_limit=5.0,
    )
    C.generate_insertion_candidates.last_stats = {}
    t0 = time.perf_counter()
    out = solve(req)
    elapsed = time.perf_counter() - t0
    warn = [w for w in (out.warnings or []) if "ITERATION" in w or "TIME_LIMIT" in w or "SEED_" in w]
    return {
        "seed": seed,
        "max_iters": max_iters,
        "mode": mode,
        "line": line_name[:32],
        "status": out.status,
        "dist": round(out.total_distance, 4),
        "stops": sum(len(p.stops) for p in out.vehicle_plans),
        "unassigned": len(out.unassigned_order_ids or []),
        "elapsed_s": round(elapsed, 3),
        "warn": warn[:6],
    }


def main():
    seeds = [11, 88]
    depths = [2, 8, 20]
    mode = sys.argv[1] if len(sys.argv) > 1 else "off"
    print("=" * 64)
    print(f"搜索深度扫描 · mode={mode} · 墙钟 120s · 只变 max_iterations")
    print(f"seeds={seeds}  depths={depths}")
    print("=" * 64)
    rows = []
    for d in depths:
        for s in seeds:
            r = run_one(s, d, mode=mode)
            rows.append(r)
            print(f"  iters={d:2d} seed={s:4d}  dist={r['dist']:.4f}  stops={r['stops']}  "
                  f"t={r['elapsed_s']}s  unassigned={r['unassigned']}  {r['line']}  {r['warn']}")
        vals = [x["dist"] for x in rows if x["max_iters"] == d and x["status"] == "feasible"]
        if vals:
            print(f"  >> iters={d} mean_dist={statistics.mean(vals):.4f}  median={statistics.median(vals):.4f}")

    print("\n" + "=" * 64)
    print("按深度汇总（feasible only）")
    summary = {}
    for d in depths:
        vals = [x["dist"] for x in rows if x["max_iters"] == d and x["status"] == "feasible"]
        ts = [x["elapsed_s"] for x in rows if x["max_iters"] == d]
        if vals:
            summary[d] = {
                "mean_dist": statistics.mean(vals),
                "median_dist": statistics.median(vals),
                "min_dist": min(vals),
                "mean_time_s": statistics.mean(ts),
            }
            print(f"  iters={d:2d}  mean={summary[d]['mean_dist']:.4f}  "
                  f"median={summary[d]['median_dist']:.4f}  min={summary[d]['min_dist']:.4f}  "
                  f"time={summary[d]['mean_time_s']:.2f}s")
    # 相对 iters=2 的改进
    if 2 in summary:
        base = summary[2]["mean_dist"]
        print("\n相对 iters=2 的均值改进：")
        for d, s in summary.items():
            gain = (base - s["mean_dist"]) / base * 100 if base else 0
            print(f"  iters={d:2d}  {gain:+.1f}%")

    out = ROOT / "artifacts" / f"depth_sweep_{mode}.json"
    out.write_text(json.dumps({"rows": rows, "summary": {str(k): v for k, v in summary.items()}},
                              ensure_ascii=False, indent=2), encoding="utf-8")
    print("saved", out)


if __name__ == "__main__":
    main()
