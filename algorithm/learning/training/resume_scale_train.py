# -*- coding: utf-8 -*-
"""从 checkpoint 续跑前缀学习曲线（不再重新生成）。

稳定判据：至少到 MIN_SCEN，且连续 STABLE_STEPS 档 top1/top3/ndcg 波动 < EPS。
"""
from __future__ import annotations

import json
import pickle
import sys
import time
from pathlib import Path

ROOT = Path(r"C:\Users\袁\Desktop\测试\cargo-post-platform\algorithm")
STATUS = ROOT / "learning" / "training" / "training_status.json"
CKPT = ROOT / "learning" / "training" / "scale_dataset_ckpt.pkl"
OUT = ROOT / "data" / "model_registry" / "insertion_ranker_osm_v2"
sys.path.insert(0, str(ROOT))

EPS = 0.02
STABLE_STEPS = 3
MIN_SCEN = 1200
LADDER = [800, 1000, 1200, 1500, 2000, 2500, 3000]
T0 = time.time()


def snap(**kw):
    d = {}
    if STATUS.exists():
        try:
            d = json.loads(STATUS.read_text(encoding="utf-8"))
        except Exception:
            d = {}
    d.update(kw)
    d["elapsed_seconds"] = round(time.time() - T0, 1)
    d["updated_at"] = time.strftime("%Y-%m-%dT%H:%M:%S")
    STATUS.write_text(json.dumps(d, ensure_ascii=False, indent=2), encoding="utf-8")


def slice_prefix(GX, GY, GR, GS, n):
    idx = [i for i, s in enumerate(GS) if s < n]
    return [GX[i] for i in idx], [GY[i] for i in idx], [GR[i] for i in idx]


def stable_enough(history):
    if len(history) < STABLE_STEPS:
        return False
    if history[-1]["scenarios"] < MIN_SCEN:
        return False
    for a, b in zip(history[-STABLE_STEPS:-1], history[-STABLE_STEPS + 1:]):
        if abs(a["top1_hit"] - b["top1_hit"]) >= EPS:
            return False
        if abs(a["top3_hit"] - b["top3_hit"]) >= EPS:
            return False
        if abs(a["ndcg_5"] - b["ndcg_5"]) >= EPS:
            return False
    return True


def main():
    from learning.training.train_insertion_ranker_osm import train_eval

    snap(status="RUNNING", stage="RESUME_SLICE", last_error=None, note="resume from ckpt, stricter stability")
    blob = pickle.loads(CKPT.read_bytes())
    GX, GY, GR, GS = blob["GX"], blob["GY"], blob["GR"], blob["GS"]
    road_stats = {"road_calls": blob.get("road", None) and blob["road"].stats().get("road_calls")}
    try:
        road_stats = blob["road"].stats() if blob.get("road") is not None else {}
    except Exception:
        road_stats = {}
    print(f"loaded groups={len(GX)} rows={int(sum(map(len, GY)))}", flush=True)
    history = list(json.loads(STATUS.read_text(encoding="utf-8")).get("history") or [])
    # 只保留已到 MIN_SCEN 的历史，避免旧 STABLE 干扰
    history = [h for h in history if h.get("scenarios", 0) >= MIN_SCEN] or history[:1]

    for n in LADDER:
        gx, gy, gr = slice_prefix(GX, GY, GR, GS, n)
        if len(gx) < 12:
            continue
        rows = int(sum(map(len, gy)))
        snap(status="RUNNING", stage="TRAINING", target_scenarios=n, history=history,
             groups=len(gx), rows=rows, dataset_size=len(gx))
        print(f"==== TRAIN prefix n={n} groups={len(gx)} rows={rows} ====", flush=True)
        bst, m = train_eval(gx, gy, gr, tune=True)
        m["scenarios"] = n
        m["road_stats"] = road_stats
        m["curve"] = f"prefix<{n} of 3000"
        OUT.mkdir(parents=True, exist_ok=True)
        (OUT / "route_search_ranker.model.txt").write_text(bst.model_to_string(), encoding="utf-8")
        (OUT / "manifest.json").write_text(json.dumps({
            "name": "insertion_ranker_osm_v2", "dataset_size": len(gx), "n_samples": rows,
            "scenarios": n, "metrics": m,
            "label": "hybrid pax->detour_bucket+block_penalty(1-step)->dist->dur",
            "curve": "prefix slice of 3000 one-shot generate",
            "created_at": time.time(),
        }, ensure_ascii=False, indent=2), encoding="utf-8")
        rec = {
            "scenarios": n, "groups": len(gx), "rows": rows,
            "top1_hit": m["top1_hit"], "top3_hit": m["top3_hit"], "ndcg_5": m["ndcg_5"],
            "can_rank": m["can_rank"],
            "tuned_params": m.get("tuned_params"),
        }
        history.append(rec)
        print("METRICS", rec, flush=True)
        snap(status="RUNNING", stage="CHECKPOINT", history=history,
             ranking_metrics={k: m[k] for k in ("top1_hit", "top3_hit", "ndcg_5", "can_rank")})
        if stable_enough(history):
            snap(status="DONE", stage="STABLE", history=history)
            print("STABLE_STRICT", history[-STABLE_STEPS:], flush=True)
            return
    snap(status="DONE", stage="LADDER_FINISHED", history=history)
    print("LADDER_FINISHED", history, flush=True)


if __name__ == "__main__":
    main()
