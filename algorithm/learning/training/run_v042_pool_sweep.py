"""v0.4.2 候选池扫描：N ∈ {16,32,64,128,256}。

目标（非追求漂亮 Recall）：
  找「保留关键真实道路候选 + 降低搜索量/运行时间」的有效工作点。
指标：
  Path-Edge Recall@keep = |kept ∩ teacher_path_edges| / |teacher_path_edges|
  Teacher Retention     = |kept ∩ T| / |T|          （T=池内正样本）
  Search Reduction      = 1 - kept/N                 （相对全池扩展）
  Runtime               = pool_gen / train / infer
禁止：改评估公式、合成路网、Haversine 正式路线、teacher 特征泄漏。
"""

from __future__ import annotations

import json
import random
import statistics as st
import time
from pathlib import Path

import numpy as np

from app.routing.graphhopper_routing import GraphHopperRoutingEngine, LocalRoutingConfig
from learning.path_search import EDGE_FEATURES, RouteSearchRanker, assert_no_leakage
from learning.training.run_v04_experiment import (
    DATA_DIR,
    _edge_features,
    load_stations,
)

SWEEP_PATH = DATA_DIR / "v042_pool_sweep.json"
POOL_SIZES = (16, 32, 64, 128, 256)
# keep 预算：按绝对条数与比例各一组，观察 reduction–质量曲线
KEEP_COUNTS = (2, 4, 8, 16, 32, 64)
KEEP_RATIOS = (0.125, 0.25, 0.5)


def _stats(vals):
    if not vals:
        return {"n": 0, "mean": None, "median": None, "std": None, "p90": None, "min": None, "max": None}
    return {
        "n": len(vals),
        "mean": float(st.mean(vals)),
        "median": float(st.median(vals)),
        "std": float(st.pstdev(vals)) if len(vals) > 1 else 0.0,
        "p90": float(sorted(vals)[min(len(vals) - 1, int(round(0.9 * (len(vals) - 1))))]),
        "min": float(min(vals)),
        "max": float(max(vals)),
    }


def build_master_pool(gh, origin, dest, rng, od_key, need_pos, need_neg, max_alts=12):
    """收集真实边主池：teacher 路径边 + 绕行负样本，不合成。"""
    t = gh.route(origin, dest, profile="bus")
    if not t.available or not t.edge_segments:
        return None
    teacher_ids = {e.edge_id for e in t.edge_segments}
    by_id = {e.edge_id: e for e in t.edge_segments}
    pos_ids = list(teacher_ids)
    rng.shuffle(pos_ids)

    neg_ids: list[int] = []
    seen = set(teacher_ids)
    pts = list(t.polyline)
    for i in range(max_alts):
        if len(neg_ids) >= need_neg and len(pos_ids) >= min(need_pos, len(teacher_ids)):
            break
        if len(pts) < 3:
            break
        mid = pts[1 + ((i * 5) % max(1, len(pts) - 2))]
        lat, lon = mid
        jitter = 0.015 * (1 + i % 4)
        wp = (lat + jitter * (1 if i % 2 == 0 else -1), lon + jitter * (1 if i % 3 else -1))
        alt = gh.route(origin, dest, (wp,), profile="bus")
        if not alt.available:
            continue
        for e in alt.edge_segments:
            if e.edge_id in seen:
                continue
            seen.add(e.edge_id)
            by_id[e.edge_id] = e
            neg_ids.append(e.edge_id)

    take_pos = pos_ids[: min(need_pos, len(pos_ids))]
    take_neg = neg_ids[:need_neg]
    return {
        "od_key": od_key,
        "origin": origin,
        "dest": dest,
        "teacher_ids": teacher_ids,
        "by_id": by_id,
        "pos_ids": take_pos,
        "neg_ids": take_neg,
        "n_path": len(teacher_ids),
        "teacher_d": t.distance_m,
        "teacher_dur": t.duration_s,
    }


def slice_pool(master, n, rng):
    """从主池切出 N 候选（正负尽量对半；不足则用尽真实样本）。"""
    half = max(1, n // 2)
    pos = master["pos_ids"][: min(half, len(master["pos_ids"]))]
    neg = master["neg_ids"][: min(n - len(pos), len(master["neg_ids"]))]
    ids = pos + neg
    if len(ids) < 4:
        return None
    rng.shuffle(ids)
    teacher_set = master["teacher_ids"]
    by_id = master["by_id"]
    origin, dest = master["origin"], master["dest"]
    X_rows, y, costs_m, edge_ids = [], [], [], []
    for eid in ids:
        e = by_id[eid]
        feats = _edge_features(e, origin, dest)
        X_rows.append([feats.get(k, 0.0) for k in EDGE_FEATURES])
        y.append(1 if eid in teacher_set else 0)
        costs_m.append(e.distance_m)
        edge_ids.append(eid)
    return {
        "X": np.array(X_rows, dtype=np.float64),
        "y": np.array(y, dtype=np.int32),
        "cost_m": costs_m,
        "edge_ids": edge_ids,
        "n_path": master["n_path"],
        "teacher_set": teacher_set,
    }


def eval_keep(y, scores, edge_ids, teacher_path_ids, keep):
    """kept=top-keep；Path-Edge Recall / Teacher Retention / Search Reduction。"""
    n = len(y)
    keep = max(1, min(keep, n))
    order = list(np.argsort(-scores, kind="stable"))[:keep]
    kept_edges = {edge_ids[i] for i in order}
    t_idx = [i for i, v in enumerate(y) if int(v) == 1]
    t_set = {edge_ids[i] for i in t_idx}
    n_path = max(1, len(teacher_path_ids))
    inter_path = len(kept_edges & teacher_path_ids)
    inter_t = len(kept_edges & t_set)
    return {
        "keep": keep,
        "path_edge_recall": inter_path / float(n_path),
        "teacher_retention": (inter_t / len(t_set)) if t_set else None,
        "search_reduction": 1.0 - keep / float(n),
        "kept_path_edges": inter_path,
        "kept_teacher_cands": inter_t,
        "teacher_cands": len(t_set),
    }


def run_sweep(n_od=80, seed=42):
    assert assert_no_leakage(EDGE_FEATURES)
    cfg = LocalRoutingConfig(graphhopper_base_url="http://127.0.0.1:8080", graphhopper_timeout_ms=8000)
    gh = GraphHopperRoutingEngine(cfg)
    stations = load_stations(80)
    rng = random.Random(seed)
    names = sorted(stations)

    # 1) 先建主池（按最大 N 需求收集真实边）
    masters = []
    t0 = time.time()
    for i in range(n_od):
        a, b = rng.sample(names, 2)
        sa, sb = stations[a], stations[b]
        if i % 2 == 0:
            target = "jiangjin" if sa[2] == "chongqing_core" else "chongqing_core"
            pool = [n for n in names if stations[n][2] == target]
            if pool:
                b = rng.choice(pool)
                sb = stations[b]
        m = build_master_pool(
            gh, sa[:2], sb[:2], rng, f"od{i}",
            need_pos=128, need_neg=128, max_alts=12,
        )
        if m:
            masters.append(m)
    gen_s = time.time() - t0

    if len(masters) < 15:
        return {"status": "INSUFFICIENT_GROUPS", "masters": len(masters)}

    idx = list(range(len(masters)))
    random.Random(seed).shuffle(idx)
    n_tr = int(len(masters) * 0.75)
    tr_i, te_i = idx[:n_tr], idx[n_tr:]

    results_by_n = {}
    for n in POOL_SIZES:
        rng_n = random.Random(seed + n)
        tr_groups = [slice_pool(masters[i], n, rng_n) for i in tr_i]
        te_groups = [slice_pool(masters[i], n, rng_n) for i in te_i]
        tr_groups = [g for g in tr_groups if g]
        te_groups = [g for g in te_groups if g]
        if len(tr_groups) < 8 or len(te_groups) < 4:
            results_by_n[str(n)] = {"status": "INSUFFICIENT", "train": len(tr_groups), "test": len(te_groups)}
            continue

        ranker = RouteSearchRanker()
        t1 = time.time()
        ranker.train(
            [g["X"] for g in tr_groups],
            [g["y"] for g in tr_groups],
            [len(g["y"]) for g in tr_groups],
            seed=seed, n_estimators=80,
        )
        train_s = time.time() - t1

        # 打乱 label 对照（同一 pool）
        rng2 = np.random.RandomState(0)
        y_shuf = [rng2.permutation(g["y"]) for g in tr_groups]
        r_shuf = RouteSearchRanker()
        r_shuf.train([g["X"] for g in tr_groups], y_shuf, [len(y) for y in y_shuf], seed=seed, n_estimators=80)

        infer_s = 0.0
        by_keep = {}
        shuf_by_keep = {}
        rand_by_keep = {}
        t_counts = []
        path_counts = []
        for g in te_groups:
            t0i = time.perf_counter()
            s = ranker.predict(g["X"])
            infer_s += time.perf_counter() - t0i
            ss = r_shuf.predict(g["X"])
            t_counts.append(int(np.sum(g["y"])))
            path_counts.append(g["n_path"])
            keeps = sorted(set(
                [k for k in KEEP_COUNTS if k <= len(g["y"])] +
                [max(1, int(round(r * len(g["y"])))) for r in KEEP_RATIOS]
            ))
            for k in keeps:
                # teacher_path 分母 = 全路径；T_pool = 池内正样本
                m = eval_keep(g["y"], s, g["edge_ids"], g["teacher_set"], k)
                by_keep.setdefault(k, []).append(m)

                ms = eval_keep(g["y"], ss, g["edge_ids"], g["teacher_set"], k)
                shuf_by_keep.setdefault(k, []).append(ms)

                # 随机排序基线（8 次置换近似期望）
                rr = []
                for _ in range(8):
                    rp = np.random.permutation(len(g["y"])).astype(float)
                    rr.append(eval_keep(g["y"], rp, g["edge_ids"], g["teacher_set"], k))
                rand_by_keep.setdefault(k, []).append({
                    "path_edge_recall": float(np.mean([x["path_edge_recall"] for x in rr])),
                    "teacher_retention": float(np.mean([x["teacher_retention"] or 0 for x in rr])),
                    "search_reduction": rr[0]["search_reduction"],
                })

        def agg(d):
            out = {}
            for k, rows in sorted(d.items()):
                out[str(k)] = {
                    "keep": k,
                    "search_reduction": rows[0]["search_reduction"] if rows else None,
                    "path_edge_recall": _stats([r["path_edge_recall"] for r in rows]),
                    "teacher_retention": _stats([r["teacher_retention"] for r in rows if r["teacher_retention"] is not None]),
                }
            return out

        results_by_n[str(n)] = {
            "status": "OK",
            "pool_size": n,
            "train_groups": len(tr_groups),
            "test_groups": len(te_groups),
            "pos_mean": float(np.mean([float(np.mean(g["y"])) for g in te_groups])),
            "teacher_cands_mean": float(np.mean(t_counts)),
            "teacher_path_mean": float(np.mean(path_counts)),
            "train_s": round(train_s, 3),
            "infer_s_total": round(infer_s, 4),
            "infer_ms_per_group": round(1000 * infer_s / max(1, len(te_groups)), 3),
            "real": agg(by_keep),
            "shuffled": agg(shuf_by_keep),
            "random": agg(rand_by_keep),
        }
        r5 = results_by_n[str(n)]
        print(json.dumps({
            "N": n,
            "train_s": r5["train_s"],
            "infer_ms": r5["infer_ms_per_group"],
            "teacher_cands": r5["teacher_cands_mean"],
            "path_mean": r5["teacher_path_mean"],
            "keep8_path_recall": ((r5["real"].get("8") or {}).get("path_edge_recall") or {}).get("mean"),
            "keep8_retention": ((r5["real"].get("8") or {}).get("teacher_retention") or {}).get("mean"),
            "keep8_reduction": (r5["real"].get("8") or {}).get("search_reduction"),
            "keep8_shuf_path": ((r5["shuffled"].get("8") or {}).get("path_edge_recall") or {}).get("mean"),
        }, default=str))

    out = {
        "sweep_id": "v0.4.2-pool-sweep",
        "seed": seed,
        "n_od": n_od,
        "masters": len(masters),
        "gen_s": round(gen_s, 2),
        "gh_calls": gh.calls,
        "pool_sizes": list(POOL_SIZES),
        "results_by_n": results_by_n,
        "definitions": {
            "path_edge_recall": "|kept ∩ teacher_path_edges| / |teacher_path_edges|",
            "teacher_retention": "|kept ∩ T_pool| / |T_pool|",
            "search_reduction": "1 - keep/N",
            "note": "分母 teacher_path 为全路径真实边数；池内只含其中一部分 → 上限=|T_pool|/|path|",
        },
    }
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    SWEEP_PATH.write_text(json.dumps(out, indent=2, ensure_ascii=False, default=str), encoding="utf-8")
    return out


def pick_working_point(out):
    """有效工作点：Path-Edge Recall 尽量高 + reduction 高 + runtime 低；输出 Pareto，不强行单一最优。"""
    rows = []
    for n_str, r in (out.get("results_by_n") or {}).items():
        if r.get("status") != "OK":
            continue
        n = int(n_str)
        for k_str, cell in (r.get("real") or {}).items():
            k = int(k_str)
            pr = (cell.get("path_edge_recall") or {}).get("mean")
            tr = (cell.get("teacher_retention") or {}).get("mean")
            if pr is None:
                continue
            rows.append({
                "N": n, "keep": k,
                "path_edge_recall": pr,
                "teacher_retention": tr,
                "search_reduction": cell.get("search_reduction"),
                "infer_ms": r.get("infer_ms_per_group"),
                "train_s": r.get("train_s"),
            })
    # 粗筛：retention ≥ 0.7 且 reduction ≥ 0.5 的点里选 path_edge_recall 最高；并列取更小 N / 更小 keep
    feas = [x for x in rows if (x["teacher_retention"] or 0) >= 0.7 and (x["search_reduction"] or 0) >= 0.5]
    feas.sort(key=lambda x: (-x["path_edge_recall"], x["N"], x["keep"]))
    return {"pareto_candidates": rows[:30], "feasible_sorted": feas[:10], "chosen": feas[0] if feas else None}


if __name__ == "__main__":
    out = run_sweep()
    wp = pick_working_point(out)
    out["working_point"] = wp
    SWEEP_PATH.write_text(json.dumps(out, indent=2, ensure_ascii=False, default=str), encoding="utf-8")
    print(json.dumps(wp, indent=2, default=str))
