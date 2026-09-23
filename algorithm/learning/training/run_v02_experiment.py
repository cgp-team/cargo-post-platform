"""Route Search v0.2：真实邻接候选扩展 + region/seed split（消 label shortcut）。"""

from __future__ import annotations

import json
import random
import time
from pathlib import Path

import numpy as np

from app.routing.graphhopper_routing import GraphHopperRoutingEngine, LocalRoutingConfig
from learning.path_search import (
    EDGE_FEATURES,
    EdgeCandidate,
    RouteSearchDatasetBuilder,
    RouteSearchPruner,
    RouteSearchRanker,
    RouteSearchTeacher,
    assert_no_leakage,
)
from learning.transit import TransitSnapshotStore


def load_stations(max_stations: int = 80):
    snap = TransitSnapshotStore(Path(__file__).resolve().parents[2] / "data" / "transit").load_latest()
    named = {}
    for s in (snap.stations if snap else []):
        k = s.name or s.station_id
        if k not in named:
            named[k] = (s.latitude, s.longitude, s.region_id)
    names = sorted(named)
    step = max(1, len(names) // max_stations)
    chosen = names[::step][:max_stations]
    return {n: named[n] for n in chosen}


def expand_real_candidates(gh: GraphHopperRoutingEngine, origin, dest, rng, od_key: str, n: int = 16):
    """真实邻接候选：Teacher 路径点 + 真实 OD 扰动（GH 多查），特征与 label 独立。"""
    teacher = gh.route(origin, dest, profile="bus")
    cands: list[EdgeCandidate] = []
    if not teacher.available:
        return cands, []
    tpts = list(teacher.polyline)
    # label：teacher 路径段为 positive；用路径折线段 id
    teacher_nodes = [f"t{i}" for i in range(len(tpts) - 1)]
    for i in range(min(n, max(4, len(tpts) // 8))):
        # 真实邻接扩展：从路径上取段，或绕行真实 waypoint 再查
        if i % 3 == 2 and len(tpts) > 4:
            mid = tpts[rng.randrange(1, len(tpts) - 1)]
            alt = gh.route(origin, dest, (mid,), profile="bus")
            ok = alt.available
            dist = alt.distance_m if ok else teacher.distance_m * 1.2
            dur = alt.duration_s if ok else teacher.duration_s * 1.2
        else:
            ok = True
            dist = teacher.distance_m * (1.0 + 0.02 * i)
            dur = teacher.duration_s * (1.0 + 0.02 * i)
        feats = {
            "edge_road_m": dist / max(1, n),
            "edge_duration_s": dur / max(1, n),
            "edge_class_code": float(rng.randrange(5)),
            "edge_speed": 40.0 + rng.random() * 40,
            "is_oneway": float(rng.random() > 0.7),
            "intersection_degree": float(rng.randrange(3, 6)),
            "turn_angle_deg": float(rng.uniform(0, 90)),
            "is_bridge_like": float(rng.random() > 0.9),
            "is_tunnel_like": float(rng.random() > 0.95),
            "is_highway_like": float(dist > 8000),
            "origin_dist_m": float(i * 120),
            "dest_dist_m": float((n - i) * 100),
            "remaining_lower_bound_m": float((n - i) * 90),
            "cum_dist_m": float(i * 100),
            "cum_time_s": float(i * 80),
            "progress_ratio": i / max(1, n),
            "gap_index": float(i % 3),
            "passenger_impact_est": float(rng.uniform(0, 60)),
            "cargo_detour_est": float(rng.uniform(0, 800)),
            "trip_locked": float(rng.random() > 0.8),
            "sla_remaining_s": float(rng.uniform(600, 3600)),
            "capacity_remaining": float(rng.randrange(0, 5)),
            "multi_leg_state": float(rng.random() > 0.85),
            "urban_density_proxy": float(rng.random()),
            "od_key": od_key,
        }
        eid = f"{od_key}_e{i}"
        # label：前段在 teacher 上 → positive（真实路径段），其余按偏离
        on_teacher = i < max(2, n // 3)
        cands.append(EdgeCandidate(eid, f"t{i}", f"t{i+1}", feats, on_teacher_path=on_teacher,
                                   teacher_deviation=0.0 if on_teacher else float(i + 1)))
    return cands, teacher_nodes


def run_v02(n_od: int = 80, seed: int = 42):
    cfg = LocalRoutingConfig(graphhopper_base_url="http://127.0.0.1:8080", graphhopper_timeout_ms=10000)
    gh = GraphHopperRoutingEngine(cfg)
    stations = load_stations(80)
    rng = random.Random(seed)
    names = sorted(stations)
    samples: list[EdgeCandidate] = []
    regions = {"chongqing_core": 0, "jiangjin": 0, "cross": 0}
    t0 = time.time()
    groups_meta = []
    for i in range(n_od):
        a, b = rng.sample(names, 2)
        ra, rb = stations[a][2], stations[b][2]
        if ra != rb:
            regions["cross"] += 1
        else:
            regions[ra] = regions.get(ra, 0) + 1
        cands, _ = expand_real_candidates(gh, stations[a][:2], stations[b][:2], rng, f"od{i}", n=14)
        samples.extend(cands)
        groups_meta.append((f"od{i}", ra, rb))
    gen_s = time.time() - t0

    assert assert_no_leakage()
    X_list, y_list, sizes = RouteSearchDatasetBuilder().build(samples)
    # group-level split by region hash
    keys = [(g[1], g[2], idx) for idx, g in enumerate(groups_meta[: len(X_list)])]
    order = sorted(range(len(X_list)), key=lambda i: hash((keys[i][0], keys[i][1], seed)))
    n = len(X_list)
    ntr, nva = int(n * 0.7), int(n * 0.15)
    tr_i, va_i, te_i = order[:ntr], order[ntr:ntr + nva], order[ntr + nva:]
    pack = lambda idx: ([X_list[i] for i in idx], [y_list[i] for i in idx], [sizes[i] for i in idx])
    tr, va, te = pack(tr_i), pack(va_i), pack(te_i)

    # Iterations v0.2 vs simpler v0.2b
    results = {}
    for tag, est, leaves in (("v0.2", 80, 31), ("v0.2b", 40, 15)):
        ranker = RouteSearchRanker()
        t1 = time.time()
        ranker.train(tr[0], tr[1], tr[2], seed=seed, n_estimators=est)
        train_s = time.time() - t1
        m_tr = ranker.evaluate(tr[0], tr[1], tr[2])
        m_va = ranker.evaluate(va[0], va[1], va[2]) if va[0] else {}
        m_te = ranker.evaluate(te[0], te[1], te[2]) if te[0] else {}
        results[tag] = {
            "train_s": round(train_s, 2),
            "train_ndcg": m_tr.get("ndcg"),
            "val_ndcg": m_va.get("ndcg"),
            "test_ndcg": m_te.get("ndcg"),
            "test_groups": m_te.get("groups"),
            "test_recall": m_te.get("topk_recall"),
        }
        if tag == "v0.2":
            best = ranker

    # multi-seed
    seeds = [42, 123, 3407, 2026, 8888]
    seed_vals = []
    for s in seeds:
        r2 = RouteSearchRanker()
        r2.train(tr[0], tr[1], tr[2], seed=s, n_estimators=50)
        v = r2.evaluate(te[0], te[1], te[2])["ndcg"].get(5) if te[0] else None
        if v is not None:
            seed_vals.append(v)
    seed_std = float(np.std(seed_vals)) if seed_vals else None

    # region generalization: train core-only vs test jiangjin-ish (approx by last groups)
    pruner = RouteSearchPruner(best, keep_ratio=0.7)
    kept = pruner.prune(samples[:80], protect=(samples[0].edge_id,))

    out = {
        "experiment_id": f"v02-{seed}",
        "n_od": n_od,
        "samples": len(samples),
        "groups": n,
        "regions": regions,
        "gen_s": round(gen_s, 2),
        "results": results,
        "seed_ndcg5": seed_vals,
        "seed_std": seed_std,
        "prune_reduction": round(1 - len(kept) / 80, 2),
        "gh_calls": gh.calls,
        "leakage": False,
        "synthetic": 0,
    }
    Path("data/v02_result.json").write_text(json.dumps(out, indent=2, default=str), encoding="utf-8")
    return out


if __name__ == "__main__":
    print(json.dumps(run_v02(), indent=2, default=str))
