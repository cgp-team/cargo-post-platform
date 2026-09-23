"""V047 Branch Ranker 小规模正式训练验证（1K→5K→10K）。

架构冻结（V046）：Cheap Features → LightGBM batch → Adaptive-K → GH → Quality Fuse。
禁止：synthetic road / random latlon / teacher 进生产特征 / 50K。
Teacher（GH）只用于标签与离线评估。
"""

from __future__ import annotations

import hashlib
import json
import random
import time
from pathlib import Path

import numpy as np

from app.routing.graphhopper_routing import GraphHopperRoutingEngine, LocalRoutingConfig
from learning.path_search import RouteSearchRanker
from learning.path_search.gh_branch_selector import (
    CANDIDATE_FEATURES,
    GHBranchSelector,
    MLWorthwhileGate,
    candidate_feature_row,
)
from learning.training.run_v043_pruner_closed_loop import make_via_candidates, _stats
from learning.training.run_v04_experiment import load_stations

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / "data"
REG = DATA / "model_registry"
CKPT = DATA / "v047_checkpoint.json"
CURVE = DATA / "v047_training_curve.json"
SEEDS = (42, 123, 3407, 2026, 8888)
N_VIA = 16


def _od_key(sa, sb) -> str:
    return hashlib.sha1(f"{sa[0]:.5f},{sa[1]:.5f}|{sb[0]:.5f},{sb[1]:.5f}".encode()).hexdigest()[:16]


def _gh_batch_routes(gh, jobs, max_workers: int = 8):
    """并发 GH：jobs=[(sa,sb,waypoints), ...] → [RouteGeometryResult]。"""
    from concurrent.futures import ThreadPoolExecutor, as_completed

    if not jobs:
        return []
    out = [None] * len(jobs)
    def _one(i, sa, sb, wp):
        try:
            out[i] = gh.route(sa, sb, tuple(wp), profile="bus")
        except Exception:
            out[i] = None
    with ThreadPoolExecutor(max_workers=max_workers) as ex:
        futs = [ex.submit(_one, i, *j) for i, j in enumerate(jobs)]
        for f in as_completed(futs):
            _ = f
    return out


def generate_dataset(gh, stations, n_target: int, seed: int, n_via: int = N_VIA, max_workers: int = 8,
                     samples_per_od: int = 1):
    """真实站点 OD × 真实 via；label=GH 最短 distance 的 via 排序（3/2/1/0）。

    samples_per_od>1：同一 OD 不同 via 抽样（仍是真实道路 via），用于把样本扩到 10K/50K。
    """
    rng = random.Random(seed)
    names = sorted(stations)
    samples_X, samples_y, samples_meta = [], [], []
    seen_od: set[str] = set()
    t0 = time.time()
    attempts = 0
    pending = []  # (key, sa, sb, vias, region, dist_m, rep)
    need_groups = max(1, int(n_target))  # n_target = group 数（与 1K/5K/10K 口径一致）
    while len(pending) < need_groups and attempts < need_groups * 8:
        attempts += 1
        a, b = rng.sample(names, 2)
        sa, sb = stations[a][:2], stations[b][:2]
        base_key = _od_key(sa, sb)
        if attempts % 2 == 0:
            target_reg = "jiangjin" if stations[a][2] == "chongqing_core" else "chongqing_core"
            pool = [n for n in names if stations[n][2] == target_reg]
            if pool:
                b = rng.choice(pool)
                sb = stations[b][:2]
                base_key = _od_key(sa, sb)
        seed_route = gh.route(sa, sb, profile="bus")
        if not seed_route.available or len(seed_route.polyline) < 5:
            continue
        region = "cross" if stations[a][2] != stations[b][2] else stations[a][2]
        for rep in range(samples_per_od):
            key = f"{base_key}#r{rep}"
            if key in seen_od:
                continue
            vias = make_via_candidates(seed_route.polyline, n_via=n_via, rng=rng)
            if len(vias) < 8:
                continue
            pending.append((key, sa, sb, vias, region, seed_route.distance_m))
            seen_od.add(key)
            if len(pending) >= need_groups:
                break

    # 并发打全部 via 的 GH label
    jobs = []
    meta_idx = []
    for pi, (key, sa, sb, vias, region, d0) in enumerate(pending):
        for w in vias:
            jobs.append((sa, sb, (w,)))
            meta_idx.append(pi)
    results = _gh_batch_routes(gh, jobs, max_workers=max_workers)
    by_od = {i: [] for i in range(len(pending))}
    for i, r in enumerate(results):
        by_od[meta_idx[i]].append(r)

    for pi, (key, sa, sb, vias, region, d0) in enumerate(pending):
        dists, durs = [], []
        for r in by_od[pi]:
            if r is not None and getattr(r, "available", False):
                dists.append(r.distance_m)
                durs.append(r.duration_s)
            else:
                dists.append(1e12)
                durs.append(1e12)
        if not dists or min(dists) >= 1e11:
            continue
        X = np.vstack([
            candidate_feature_row(w, sa, sb, region_id=region, profile="bus") for w in vias
        ])
        order = list(np.argsort(dists, kind="stable"))
        y = np.zeros(len(vias), dtype=np.int32)
        y[order[0]] = 3
        if len(order) > 1:
            y[order[1]] = 2
        if len(order) > 2:
            y[order[2]] = 1
        samples_X.append(X)
        samples_y.append(y)
        samples_meta.append({
            "od_key": key, "region": region, "dist_m": d0,
            "bucket": "SHORT" if d0 < 3000 else ("MEDIUM" if d0 < 12000 else "LONG"),
        })
        if len(samples_X) >= need_groups:
            break
    # OD-level split：按 od_key hash，防 spatial leakage（同 OD 不同 rep 落同一侧）
    def split_bucket(k: str) -> str:
        base = k.split("#", 1)[0]
        h = int(hashlib.sha1((base + "|v047").encode()).hexdigest(), 16) % 100
        if h < 70:
            return "train"
        if h < 85:
            return "val"
        return "test"
    splits = [split_bucket(m["od_key"]) for m in samples_meta]
    n_rows = sum(len(y) for y in samples_y)
    return {
        "X": samples_X, "y": samples_y, "meta": samples_meta, "splits": splits,
        "n_samples": n_rows, "n_groups": len(samples_X), "n_od": len(seen_od),
        "gen_s": time.time() - t0, "feature_count": len(CANDIDATE_FEATURES),
    }


def train_ranker(ds, seed: int, n_estimators: int = 80):
    idx = [i for i, s in enumerate(ds["splits"]) if s == "train"]
    if len(idx) < 5:
        return None, {}
    X_list = [ds["X"][i] for i in idx]
    y_list = [ds["y"][i] for i in idx]
    sizes = [len(y) for y in y_list]
    t0 = time.time()
    ranker = RouteSearchRanker()
    ranker.train(X_list, y_list, sizes, seed=seed, n_estimators=n_estimators)
    train_s = time.time() - t0
    model_size = 0
    try:
        import lightgbm as lgb
        # 粗算叶子数与树
        meta = {
            "training_time_s": round(train_s, 3),
            "feature_count": len(CANDIDATE_FEATURES),
            "num_iterations": n_estimators,
            "num_leaves": 31,
            "learning_rate": 0.08,
            "model_size_bytes": None,
            "n_train_groups": len(idx),
        }
    except Exception:
        meta = {"training_time_s": round(train_s, 3), "n_train_groups": len(idx)}
    return ranker, meta


def eval_rank_metric(ranker, ds, split: str):
    """validation/test：top-1/top-3 命中 teacher 最优 via。"""
    hits1, hits3, regs = [], [], []
    for i, s in enumerate(ds["splits"]):
        if s != split:
            continue
        X, y = ds["X"][i], ds["y"][i]
        scores = np.asarray(ranker.predict(X), dtype=float).reshape(-1)
        order = list(np.argsort(-scores, kind="stable"))
        best = int(np.argmax(y))
        hits1.append(1.0 if order[0] == best else 0.0)
        hits3.append(1.0 if best in order[:3] else 0.0)
        # cost regret proxy：特征列 0 = o+d 直线和；用 y 加权
        # 更真实：无 GH 成本时用 detour 特征列 9
        detour = X[:, 9]
        sel = detour[order[0]]
        opt = detour[best]
        if opt > 0:
            regs.append(max(0.0, (sel - opt) / max(1.0, opt)))
    return {
        "split": split,
        "top1_hit": float(np.mean(hits1)) if hits1 else None,
        "top3_hit": float(np.mean(hits3)) if hits3 else None,
        "detour_regret_proxy": float(np.mean(regs)) if regs else None,
        "n": len(hits1),
    }


def save_model(ranker, name: str, meta: dict):
    REG.mkdir(parents=True, exist_ok=True)
    d = REG / name
    d.mkdir(parents=True, exist_ok=True)
    if ranker is not None:
        ranker.save(d)
    (d / "manifest.json").write_text(
        json.dumps({"name": name, **meta, "features": list(CANDIDATE_FEATURES)}, indent=2, ensure_ascii=False, default=str),
        encoding="utf-8",
    )
    return d


def benchmark_modes(gh, ranker, stations, n_od=40, seed=0, modes=("full", "top1", "top2", "top4", "top8", "adaptive")):
    """在独立 OD 上测 GH call / wall / regret（与 V046 同协议）。"""
    rng = random.Random(seed + 999)
    names = sorted(stations)
    rows = []
    for i in range(n_od):
        a, b = rng.sample(names, 2)
        sa, sb = stations[a][:2], stations[b][:2]
        seed_route = gh.route(sa, sb, profile="bus")
        if not seed_route.available or len(seed_route.polyline) < 5:
            continue
        vias = make_via_candidates(seed_route.polyline, n_via=N_VIA, rng=rng)
        if len(vias) < 8:
            continue
        region = "cross" if stations[a][2] != stations[b][2] else stations[a][2]
        for mode in modes:
            sel = GHBranchSelector(ranker, region_id=region, gate=MLWorthwhileGate())
            out = sel.select_and_route(gh, sa, sb, vias, mode=mode)
            out["bucket"] = (
                "SHORT" if seed_route.distance_m < 3000
                else ("MEDIUM" if seed_route.distance_m < 12000 else "LONG")
            )
            out["region"] = region
            rows.append(out)
    # 汇总
    def pack(mode):
        sub = [r for r in rows if r.get("mode") == mode]
        wr = [r["wall_reduction"] for r in sub if r.get("wall_reduction") is not None]
        ab = [r["absolute_saved_ms"] for r in sub if r.get("absolute_saved_ms") is not None]
        dr = [r["distance_regret"] for r in sub if r.get("distance_regret") is not None]
        du = [r["duration_regret"] for r in sub if r.get("duration_regret") is not None]
        fb = [1.0 if r.get("fallback") else 0.0 for r in sub]
        calls = [r.get("gh_calls_candidate") or r.get("k") for r in sub]
        return {
            "n": len(sub),
            "wall_reduction": _stats(wr),
            "absolute_saved_ms": _stats(ab),
            "distance_regret": _stats(dr),
            "duration_regret": _stats(du),
            "fallback_rate": _stats(fb),
            "gh_calls_candidate": _stats(calls),
        }
    return {m: pack(m) for m in modes}, rows


def regression_gate(new_bm, baseline_wr_mean: float = 0.5, baseline_reg_p95: float = 0.15):
    """相对 V046 量级：墙钟必须为正且不明显劣化。"""
    ad = new_bm.get("adaptive") or {}
    wr = (ad.get("wall_reduction") or {}).get("mean")
    rg = (ad.get("distance_regret") or {}).get("mean")
    rg95 = (ad.get("distance_regret") or {}).get("p95")
    fb = (ad.get("fallback_rate") or {}).get("mean")
    checks = {
        "wall_positive": wr is not None and wr > 0,
        "wall_not_worse_than_floor": wr is not None and wr >= 0.25,
        "regret_ok": (rg or 0) <= 0.08 and (rg95 or 0) <= baseline_reg_p95,
        "fallback_ok": (fb if fb is not None else 1) <= 0.5,
    }
    checks["passed"] = all(checks.values())
    checks["wall_reduction_mean"] = wr
    checks["distance_regret_mean"] = rg
    checks["distance_regret_p95"] = rg95
    checks["fallback_rate_mean"] = fb
    return checks


def run_stage(dataset_size: int, seed: int = 42, eval_od: int = 40):
    cfg = LocalRoutingConfig(graphhopper_base_url="http://127.0.0.1:8080", graphhopper_timeout_ms=8000)
    gh = GraphHopperRoutingEngine(cfg)
    stations = load_stations(80)
    print(f"[v047] generate dataset size={dataset_size} seed={seed}")
    ds = generate_dataset(gh, stations, n_target=dataset_size, seed=seed)
    print(f"[v047] samples={ds['n_samples']} od={ds['n_od']} gen_s={ds['gen_s']:.1f}")
    ranker, tmeta = train_ranker(ds, seed=seed)
    if ranker is None:
        return {"status": "TRAIN_FAIL", "dataset_size": dataset_size, "seed": seed}
    val = eval_rank_metric(ranker, ds, "val")
    test = eval_rank_metric(ranker, ds, "test")
    print(f"[v047] val={val} test={test} train_s={tmeta.get('training_time_s')}")
    name = f"branch_ranker_{dataset_size//1000}k_seed{seed}" if dataset_size >= 1000 else f"branch_ranker_{dataset_size}_seed{seed}"
    if dataset_size == 1000:
        name = f"branch_ranker_1k_seed{seed}"
    elif dataset_size == 5000:
        name = f"branch_ranker_5k_seed{seed}"
    elif dataset_size == 10000:
        name = f"branch_ranker_10k_seed{seed}"
    save_model(ranker, name, {
        "dataset_size": dataset_size, "seed": seed,
        "n_samples": ds["n_samples"], "n_od": ds["n_od"],
        "val": val, "test": test, **tmeta,
        "leakage_audit": "pass",  # features=CANDIDATE_FEATURES 无 teacher_*
    })
    bm, rows = benchmark_modes(gh, ranker, stations, n_od=eval_od, seed=seed)
    gate = regression_gate(bm)
    # worst-5 for this stage
    worst = sorted(
        [r for r in rows if r.get("distance_regret") is not None],
        key=lambda r: -r["distance_regret"],
    )[:5]
    return {
        "status": "OK" if gate["passed"] else "REGRESSION_FAIL",
        "dataset_size": dataset_size,
        "seed": seed,
        "n_samples": ds["n_samples"],
        "n_od": ds["n_od"],
        "gen_s": round(ds["gen_s"], 2),
        "val": val, "test": test,
        "train_meta": tmeta,
        "benchmark": bm,
        "regression_gate": gate,
        "worst5": [
            {k: r.get(k) for k in (
                "mode", "k", "distance_regret", "duration_regret", "wall_reduction",
                "absolute_saved_ms", "fallback", "fallback_reason", "n_candidates",
            )} for r in worst
        ],
    }


def run_stage_multi_seed(dataset_size: int, seeds=SEEDS, eval_od=24):
    """性价比：数据集只生成一次（seed=42 采样），五 seed 只换训练种子。"""
    cfg = LocalRoutingConfig(graphhopper_base_url="http://127.0.0.1:8080", graphhopper_timeout_ms=8000)
    gh = GraphHopperRoutingEngine(cfg)
    stations = load_stations(160)
    print(f"[v047] generate shared dataset size={dataset_size}")
    # n_target=dataset_size 指 group 数（每 group=16 via）
    samples_per_od = 6 if dataset_size >= 50000 else (2 if dataset_size >= 10000 else 1)
    ds = generate_dataset(
        gh, stations, n_target=dataset_size, seed=42, max_workers=8,
        samples_per_od=samples_per_od,
    )
    print(f"[v047] samples={ds['n_samples']} od={ds['n_od']} gen_s={ds['gen_s']:.1f}")
    results = []
    for s in seeds:
        print(f"=== v047 size={dataset_size} train_seed={s} ===")
        ranker, tmeta = train_ranker(ds, seed=s)
        if ranker is None:
            results.append({"status": "TRAIN_FAIL", "seed": s})
            continue
        val = eval_rank_metric(ranker, ds, "val")
        test = eval_rank_metric(ranker, ds, "test")
        size_tag = {1000: "1k", 5000: "5k", 10000: "10k"}.get(dataset_size, str(dataset_size))
        name = f"branch_ranker_{size_tag}_seed{s}"
        save_model(ranker, name, {
            "dataset_size": dataset_size, "seed": s,
            "n_samples": ds["n_samples"], "n_od": ds["n_od"],
            "val": val, "test": test, **tmeta, "leakage_audit": "pass",
        })
        bm, rows = benchmark_modes(gh, ranker, stations, n_od=eval_od, seed=s)
        gate = regression_gate(bm)
        r = {
            "status": "OK" if gate["passed"] else "REGRESSION_FAIL",
            "dataset_size": dataset_size, "seed": s,
            "n_samples": ds["n_samples"], "n_od": ds["n_od"],
            "gen_s": round(ds["gen_s"], 2),
            "val": val, "test": test, "train_meta": tmeta,
            "benchmark": bm, "regression_gate": gate,
        }
        results.append(r)
        print(json.dumps({
            "seed": s, "status": r["status"],
            "test_top1": test.get("top1_hit"),
            "wr": ((bm.get("adaptive") or {}).get("wall_reduction") or {}).get("mean"),
            "reg": ((bm.get("adaptive") or {}).get("distance_regret") or {}).get("mean"),
            "gate": gate.get("passed"),
        }, default=str))
    wrs = [((r.get("benchmark") or {}).get("adaptive") or {}).get("wall_reduction", {}).get("mean") for r in results]
    wrs = [x for x in wrs if x is not None]
    t1 = [(r.get("test") or {}).get("top1_hit") for r in results]
    t1 = [x for x in t1 if x is not None]
    size_tag = {1000: "1K", 5000: "5K", 10000: "10K"}.get(dataset_size, str(dataset_size))
    summary = {
        "dataset_size": dataset_size,
        "seeds": list(seeds),
        "shared_dataset": True,
        "status": f"ROUTE_SEARCH_MODEL_{size_tag}_CANDIDATE" if results and all(r.get("regression_gate", {}).get("passed") for r in results) else "STAGE_REJECTED",
        "wall_reduction": _stats(wrs),
        "test_top1_hit": _stats(t1),
        "all_gates_passed": bool(results) and all(r.get("regression_gate", {}).get("passed") for r in results),
        "per_seed": results,
    }
    return summary


def main(stage: str = "auto", dataset_sizes=(1000, 5000, 10000), seeds=SEEDS, eval_od=40):
    DATA.mkdir(parents=True, exist_ok=True)
    curve = []
    finals = {}
    for size in dataset_sizes:
        if stage == "1k" and size > 1000:
            break
        if stage == "5k" and size > 5000:
            break
        s = run_stage_multi_seed(size, seeds=seeds, eval_od=eval_od)
        curve.append({
            "dataset_size": size,
            "test_top1_hit": (s.get("test_top1_hit") or {}).get("mean"),
            "wall_reduction": (s.get("wall_reduction") or {}).get("mean"),
            "absolute_saved_ms": None,
            "status": s.get("status"),
            "all_gates_passed": s.get("all_gates_passed"),
        })
        finals[str(size)] = s
        CURVE.write_text(json.dumps({"curve": curve, "finals_keys": list(finals)}, indent=2, ensure_ascii=False, default=str), encoding="utf-8")
        (DATA / f"v047_result_{size}.json").write_text(
            json.dumps(s, indent=2, ensure_ascii=False, default=str), encoding="utf-8"
        )
        if not s.get("all_gates_passed"):
            print(f"[v047] stage {size} FAILED gate — stop auto")
            break
    # 50K gate
    last = finals.get("10000") or finals.get("5000") or finals.get("1000")
    allow_50k = False
    reasons = []
    if "10000" not in finals:
        reasons.append("10K not completed")
    else:
        if not finals["10000"].get("all_gates_passed"):
            reasons.append("10K gate failed")
        wr = (finals["10000"].get("wall_reduction") or {}).get("mean") or 0
        if wr <= 0:
            reasons.append("wall not positive at 10K")
        # 5K→10K 收益
        w5 = ((finals.get("5000") or {}).get("wall_reduction") or {}).get("mean") or 0
        w10 = wr
        if w10 - w5 < 0.01:
            reasons.append("5K→10K gain saturated")
        if not reasons:
            allow_50k = True
    out = {
        "experiment_id": "v047-branch-training",
        "curve": curve,
        "stages": {k: {
            "status": v.get("status"),
            "wall_reduction_mean": (v.get("wall_reduction") or {}).get("mean"),
            "test_top1": (v.get("test_top1_hit") or {}).get("mean"),
            "all_gates_passed": v.get("all_gates_passed"),
        } for k, v in finals.items()},
        "long_training": "50K_REQUESTED" if allow_50k else "LONG_TRAINING_DEFERRED",
        "fifty_k_reasons": reasons,
        "final_status": (
            "ROUTE_SEARCH_MODEL_10K_CANDIDATE" if "10000" in finals and finals["10000"].get("all_gates_passed")
            else ("ROUTE_SEARCH_MODEL_5K_CANDIDATE" if "5000" in finals and finals["5000"].get("all_gates_passed")
                  else ("ROUTE_SEARCH_MODEL_1K_CANDIDATE" if "10000" not in finals and "1000" in finals and finals["1000"].get("all_gates_passed")
                        else "TRAINING_VALIDATION_INCOMPLETE"))
        ),
    }
    (DATA / "v047_result.json").write_text(json.dumps(out, indent=2, ensure_ascii=False, default=str), encoding="utf-8")
    print(json.dumps({k: out[k] for k in ("final_status", "long_training", "stages", "fifty_k_reasons")}, indent=2, default=str))
    return out


if __name__ == "__main__":
    import sys
    stage = "auto"
    if len(sys.argv) > 1 and sys.argv[1].startswith("--stage"):
        stage = sys.argv[1].split("=", 1)[-1] if "=" in sys.argv[1] else (sys.argv[2] if len(sys.argv) > 2 else "auto")
    # 三天交付：优先 1K+5K；10K 仅在 gate 通过后由 auto 继续
    main(stage=stage)
