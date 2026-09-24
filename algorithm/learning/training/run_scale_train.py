# -*- coding: utf-8 -*-
"""学习曲线：一次生成到最大 n，再按场景前缀切片训练。

- 前缀嵌套：800 ⊂ 1000 ⊂ …，指标随数据增长可直接比
- 只算一遍路网/样本；route_holdout 评估
- 连续两档 top1/top3/ndcg 波动 < STABLE_EPS 则停
- 真实：线路/站点/道路里程；其余维度已高随机
"""
from __future__ import annotations

import json
import pickle
import random
import sys
import time
from pathlib import Path

ROOT = Path(r"C:\Users\袁\Desktop\测试\cargo-post-platform\algorithm")
STATUS = ROOT / "learning" / "training" / "training_status.json"
OUT = ROOT / "data" / "model_registry" / "insertion_ranker_osm_v2"
sys.path.insert(0, str(ROOT))

STABLE_EPS = 0.02
# 前缀档位（场景数）；生成到 LADDER[-1]
LADDER = [400, 600, 800, 1000, 1200, 1500, 2000, 2500, 3000]
SEED = 20260324
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
    return d


def stable(history: list[dict]) -> bool:
    if len(history) < 2:
        return False
    a, b = history[-2], history[-1]
    return (
        abs(a["top3_hit"] - b["top3_hit"]) < STABLE_EPS
        and abs(a["ndcg_5"] - b["ndcg_5"]) < STABLE_EPS
        and abs(a["top1_hit"] - b["top1_hit"]) < STABLE_EPS
    )


def slice_prefix(GX, GY, GR, GS, n_scen: int):
    """保留场景下标 < n_scen 的 group。"""
    idx = [i for i, s in enumerate(GS) if s < n_scen]
    return [GX[i] for i in idx], [GY[i] for i in idx], [GR[i] for i in idx]


def save_model(bst, m, n_scen, groups, rows):
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "route_search_ranker.model.txt").write_text(bst.model_to_string(), encoding="utf-8")
    (OUT / "feature_schema.json").write_text(
        json.dumps({"features": list(m.get("features", []))}, indent=2), encoding="utf-8"
    )
    (OUT / "manifest.json").write_text(
        json.dumps(
            {
                "name": "insertion_ranker_osm_v2",
                "dataset_size": groups,
                "n_samples": rows,
                "scenarios": n_scen,
                "stations_source": "OSM real bus stops",
                "routes_source": "OSM route=bus skeleton",
                "road_source": "LocalRoutingEngine real road only",
                "order_source": "high-variance product mix on real coords",
                "metrics": m,
                "label": "hybrid pax->detour_bucket+block_penalty(1-step)->dist->dur",
                "curve": "one-shot generate then scenario-prefix slices",
                "created_at": time.time(),
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )


def main():
    from app.routing.local_routing import LocalRoutingEngine
    from learning.training.train_insertion_ranker_osm import build, load_real_routes, train_eval

    max_n = LADDER[-1]
    snap(
        status="RUNNING",
        stage="INIT",
        target_scenarios=max_n,
        ladder=LADDER,
        history=[],
        last_error=None,
        note="one-shot generate + prefix slice until stable",
    )
    st, routes = load_real_routes()
    graph = pickle.loads((ROOT / "data" / "road_graph_cq.pkl").read_bytes())
    eng = LocalRoutingEngine(graph, speed_m_s=8.0)
    eng._adj()
    snap(stage="GENERATING", real_stations=len(st), real_routes=len(routes), target_scenarios=max_n)

    print(f"==== GENERATE ONCE n={max_n} ====", flush=True)
    rng = random.Random(SEED)
    t1 = time.time()
    ckpt = ROOT / "learning" / "training" / "scale_dataset_ckpt.pkl"
    GX, GY, GR, GS = [], [], [], []
    road_stats = {}
    CHUNK = 200
    try:
        from learning.training.train_insertion_ranker_osm import RoadMatrix

        road = RoadMatrix(eng)
        base = 0
        # 断点续跑
        if ckpt.exists():
            try:
                blob = pickle.loads(ckpt.read_bytes())
                if blob.get("seed") == SEED and blob.get("max_n") == max_n:
                    GX, GY, GR, GS = blob["GX"], blob["GY"], blob["GR"], blob["GS"]
                    road = blob.get("road") or road
                    base = blob.get("base", 0)
                    print(f"resume ckpt base={base} groups={len(GX)}", flush=True)
            except Exception as e:
                print("ckpt load failed", e, flush=True)
        while base < max_n:
            chunk = min(CHUNK, max_n - base)
            gx, gy, gr, rs, gs = build(eng, st, routes, chunk, rng, road=road)
            gs = [s + base for s in gs]
            GX.extend(gx)
            GY.extend(gy)
            GR.extend(gr)
            GS.extend(gs)
            base += chunk
            road_stats = rs
            ckpt.write_bytes(
                pickle.dumps(
                    {"seed": SEED, "max_n": max_n, "base": base, "GX": GX, "GY": GY, "GR": GR, "GS": GS, "road": road},
                    protocol=4,
                )
            )
            snap(
                stage="GENERATING",
                generate_base=base,
                target_scenarios=max_n,
                groups_all=len(GX),
                rows_all=int(sum(map(len, GY))),
                road_stats=road_stats,
            )
            print(f"chunk done base={base}/{max_n} groups={len(GX)}", flush=True)
    except Exception as e:
        snap(status="ERROR", stage="GENERATING", last_error=repr(e), generate_base=globals().get("base", 0))
        raise
    snap(
        stage="SLICE_TRAIN",
        groups_all=len(GX),
        rows_all=int(sum(map(len, GY))),
        road_stats=road_stats,
        generate_seconds=round(time.time() - t1, 1),
    )
    print(f"generated groups={len(GX)} rows={int(sum(map(len, GY)))} in {time.time()-t1:.1f}s", flush=True)

    history: list[dict] = []
    for n in LADDER:
        gx, gy, gr = slice_prefix(GX, GY, GR, GS, n)
        rows = int(sum(map(len, gy)))
        if len(gx) < 12:
            print(f"skip n={n}: groups={len(gx)}", flush=True)
            continue
        snap(status="RUNNING", stage="TRAINING", target_scenarios=n, history=history,
             groups=len(gx), rows=rows, dataset_size=len(gx))
        print(f"==== TRAIN prefix n={n} groups={len(gx)} rows={rows} ====", flush=True)
        try:
            bst, m = train_eval(gx, gy, gr, tune=True)
        except Exception as e:
            snap(status="ERROR", stage="TRAINING", last_error=repr(e), history=history)
            raise
        m["real_stations"] = len(st)
        m["real_routes"] = len(routes)
        m["road_stats"] = road_stats
        m["scenarios"] = n
        m["curve"] = f"prefix<{n} of {max_n}"
        save_model(bst, m, n, len(gx), rows)
        rec = {
            "scenarios": n,
            "groups": len(gx),
            "rows": rows,
            "top1_hit": m["top1_hit"],
            "top3_hit": m["top3_hit"],
            "ndcg_5": m["ndcg_5"],
            "can_rank": m["can_rank"],
            "road_calls": road_stats.get("road_calls"),
            "road_errors": road_stats.get("road_errors"),
        }
        history.append(rec)
        print("METRICS", rec, flush=True)
        snap(
            status="RUNNING",
            stage="CHECKPOINT",
            history=history,
            ranking_metrics={k: m[k] for k in ("top1_hit", "top3_hit", "ndcg_5", "can_rank")},
            last_error=None,
        )
        if stable(history):
            snap(status="DONE", stage="STABLE", history=history)
            print("STABLE", history[-2:], flush=True)
            return
    snap(status="DONE", stage="LADDER_FINISHED", history=history)
    print("LADDER_FINISHED", history, flush=True)


if __name__ == "__main__":
    main()
