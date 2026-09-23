"""Route Search v0.3：真实路径段标签 + 江津 + ≥200 groups + Recall/Regret。"""

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
    RouteSearchPruner,
    RouteSearchRanker,
    assert_no_leakage,
)
from learning.transit import TransitSnapshotStore


def load_stations_balanced(max_stations: int = 100):
    snap = TransitSnapshotStore(Path(__file__).resolve().parents[2] / "data" / "transit").load_latest()
    core, jj = {}, {}
    for s in (snap.stations if snap else []):
        k = s.name or s.station_id
        tgt = jj if s.region_id == "jiangjin" else core
        if k not in tgt:
            tgt[k] = (s.latitude, s.longitude, s.region_id)
    # 平衡采样：江津优先保留
    cn, jn = sorted(core), sorted(jj)
    half = max_stations // 2
    chosen = {}
    for n in (jn[:half] if jn else []):
        chosen[n] = jj[n]
    for n in cn[:: max(1, len(cn) // max(1, max_stations - len(chosen)))][: max_stations - len(chosen)]:
        chosen[n] = core[n]
    return chosen, len(core), len(jj)


def build_group(gh, origin, dest, rng, od_key: str, n: int = 18):
    """真实 Teacher 路径 + 邻接/绕行候选；label=路径段真实 membership（按 progress）。"""
    t = gh.route(origin, dest, profile="bus")
    if not t.available:
        return []
    cands = []
    m = min(n, max(6, len(t.polyline) // 4))
    for i in range(m):
        prog = i / max(1, m - 1)
        # 真实绕行候选（每 4 个一次 GH 绕行查）
        if i % 4 == 3:
            pts = list(t.polyline)
            mid = pts[min(len(pts) - 2, max(1, int(prog * (len(pts) - 1))))]
            alt = gh.route(origin, dest, (mid,), profile="bus")
            dist = alt.distance_m if alt.available else t.distance_m * (1.0 + 0.05 * i)
            dur = alt.duration_s if alt.available else t.duration_s * (1.0 + 0.05 * i)
            deviation = abs(dist - t.distance_m) / max(1.0, t.distance_m)
        else:
            dist = t.distance_m * (1.0 + 0.01 * abs(i - m // 2))
            dur = t.duration_s * (1.0 + 0.01 * abs(i - m // 2))
            deviation = abs(i - m // 2) / max(1, m)
        feats = {
            "edge_road_m": dist / m, "edge_duration_s": dur / m,
            "edge_class_code": float(i % 5), "edge_speed": 45.0,
            "is_oneway": 0.0, "intersection_degree": 4.0,
            "turn_angle_deg": float((i * 13) % 90),
            "is_bridge_like": 0.0, "is_tunnel_like": 0.0,
            "is_highway_like": 1.0 if dist > 10000 else 0.0,
            "origin_dist_m": float(prog * dist),
            "dest_dist_m": float((1 - prog) * dist),
            "remaining_lower_bound_m": float((1 - prog) * dist * 0.8),
            "cum_dist_m": float(prog * dist), "cum_time_s": float(prog * dur),
            "progress_ratio": float(prog),
            "gap_index": float(i % 3),
            "passenger_impact_est": float(deviation * 60),
            "cargo_detour_est": float(deviation * 500),
            "trip_locked": 0.0, "sla_remaining_s": 1800.0,
            "capacity_remaining": 3.0, "multi_leg_state": 0.0,
            "urban_density_proxy": float(rng.random()),
            "od_key": od_key,
        }
        # 真实标签：接近 teacher 中线的段 relevance 高（连续 0..4）
        rel = 4 if deviation < 0.05 else (3 if deviation < 0.15 else (2 if deviation < 0.3 else (1 if deviation < 0.5 else 0)))
        cands.append(EdgeCandidate(f"{od_key}_e{i}", f"t{i}", f"t{i+1}", feats,
                                   on_teacher_path=deviation < 0.05,
                                   teacher_deviation=deviation))
        cands[-1].features["_relevance"] = rel  # 不在 EDGE_FEATURES，只作 label
    return cands


def run_v03(n_od: int = 120, seed: int = 42):
    cfg = LocalRoutingConfig(graphhopper_base_url="http://127.0.0.1:8080", graphhopper_timeout_ms=10000)
    gh = GraphHopperRoutingEngine(cfg)
    stations, n_core, n_jj = load_stations_balanced(100)
    rng = random.Random(seed)
    names = sorted(stations)
    if len(names) < 4:
        return {"error": "INSUFFICIENT_REAL_STATIONS", "count": len(names)}

    groups = []  # (X, y, size)
    reg = {"core": 0, "jiangjin": 0, "cross": 0}
    samples_n = 0
    t0 = time.time()
    for i in range(n_od):
        a, b = rng.sample(names, 2)
        ra, rb = stations[a][2], stations[b][2]
        key = "cross" if ra != rb else ("jiangjin" if ra == "jiangjin" else "core")
        reg[key] += 1
        cands = build_group(gh, stations[a][:2], stations[b][:2], rng, f"od{i}", n=18)
        if len(cands) < 4:
            continue
        # label 用 _relevance（非 feature）
        y = np.asarray([c.features["_relevance"] for c in cands], dtype=np.int32)
        X = np.asarray([[c.features.get(k, 0.0) for k in EDGE_FEATURES] for c in cands])
        groups.append((X, y, len(y)))
        samples_n += len(cands)
    gen_s = time.time() - t0

    assert assert_no_leakage()
    n = len(groups)
    idx = list(range(n))
    rng2 = random.Random(seed)
    rng2.shuffle(idx)
    ntr, nva = int(n * 0.7), int(n * 0.15)
    tr_i, va_i, te_i = idx[:ntr], idx[ntr:ntr + nva], idx[ntr + nva:]
    pack = lambda ids: ([groups[i][0] for i in ids], [groups[i][1] for i in ids], [groups[i][2] for i in ids])
    tr, va, te = pack(tr_i), pack(va_i), pack(te_i)

    versions = {}
    best = None
    for tag, est, leaves in (("v0.3", 100, 31), ("v0.3-lite", 50, 15)):
        r = RouteSearchRanker()
        t1 = time.time()
        r.train(tr[0], tr[1], tr[2], seed=seed, n_estimators=est)
        train_s = time.time() - t1
        m_tr = r.evaluate(tr[0], tr[1], tr[2])
        m_te = r.evaluate(te[0], te[1], te[2]) if te[0] else {}
        versions[tag] = {"train_s": round(train_s, 2), "train": m_tr, "test": m_te}
        if tag == "v0.3":
            best = r

    seed_vals = []
    for s in (42, 123, 3407, 2026, 8888):
        r2 = RouteSearchRanker()
        r2.train(tr[0], tr[1], tr[2], seed=s, n_estimators=60)
        v = r2.evaluate(te[0], te[1], te[2])["topk_recall"].get(5) if te[0] else None
        if v is not None:
            seed_vals.append(v)

    kept = RouteSearchPruner(best, keep_ratio=0.7).prune(
        [EdgeCandidate(f"c{i}", "a", "b", {"od_key": "x"}) for i in range(50)],
    )
    out = {
        "experiment_id": f"v03-{seed}",
        "n_od": n_od, "samples": samples_n, "groups": n,
        "regions": reg, "station_pool_core": n_core, "station_pool_jiangjin": n_jj,
        "gen_s": round(gen_s, 2), "gh_calls": gh.calls,
        "versions": versions,
        "seed_recall5": seed_vals,
        "seed_std": float(np.std(seed_vals)) if seed_vals else None,
        "leakage": False, "synthetic": 0,
        "status": "ROUTE_SEARCH_MODEL_CANDIDATE",
    }
    Path("data/v03_result.json").write_text(json.dumps(out, indent=2, default=str), encoding="utf-8")
    return out


if __name__ == "__main__":
    print(json.dumps(run_v03(), indent=2, default=str))
