"""v0.4：真实 GH edge_id membership 标签 + Shuffle 金标准 + Distance/Duration Regret。

修复 v0.3 评估失效：
- label = 边是否在 teacher 最优路径 edge_id 集合（0/1，无并列、无位置规则）
- Recall@K = |model_topK ∩ teacher_cands| / |teacher_cands|
- 打乱训练标签后，相对真实测试 label 必须接近随机基线（评估有效性金标准）
- Distance/Duration Regret：top-|T| 选中边成本 vs teacher 边成本
"""

from __future__ import annotations

import json
import random
import time
from pathlib import Path

import numpy as np

from app.routing.graphhopper_routing import (
    ROAD_CLASS_CODES,
    GraphHopperRoutingEngine,
    LocalRoutingConfig,
    haversine_m,
)
from app.routing.models import EdgeSegment
from learning.path_search import EDGE_FEATURES, assert_no_leakage

DATA_DIR = Path(__file__).resolve().parents[2] / "data"
RESULT_PATH = DATA_DIR / "v04_result.json"


def load_stations(max_stations: int = 80):
    """分层抽样：主城 + 江津 都必须进入 OD 工作集。"""
    from learning.transit import TransitSnapshotStore

    snap = TransitSnapshotStore(DATA_DIR / "transit").load_latest()
    by_region: dict[str, dict[str, tuple[float, float, str]]] = {}
    for s in (snap.stations if snap else []):
        k = s.name or s.station_id
        reg = s.region_id or "chongqing_core"
        by_region.setdefault(reg, {})
        if k not in by_region[reg]:
            by_region[reg][k] = (s.latitude, s.longitude, reg)

    core = by_region.get("chongqing_core", {})
    jj = by_region.get("jiangjin", {})
    # 江津全量或上限 25，主城补足剩余名额
    jj_names = sorted(jj)
    n_jj = min(len(jj_names), max(10, max_stations // 4), 25)
    core_names = sorted(core)
    n_core = max(10, max_stations - n_jj)
    picked: dict[str, tuple[float, float, str]] = {}
    for n in jj_names[:: max(1, len(jj_names) // max(1, n_jj))][:n_jj]:
        picked[n] = jj[n]
    for n in core_names[:: max(1, len(core_names) // max(1, n_core))][:n_core]:
        picked[n] = core[n]
    return picked


def _road_class_code(name: str) -> float:
    return float(ROAD_CLASS_CODES.get(str(name).lower(), 0.0))


def _edge_features(
    e: EdgeSegment,
    origin: tuple[float, float],
    dest: tuple[float, float],
) -> dict[str, float]:
    """决策时刻可用特征：边属性 + 相对 OD 几何。禁止 teacher / 路径序号泄漏。"""
    o = haversine_m((e.mid_lat, e.mid_lon), origin)
    d = haversine_m((e.mid_lat, e.mid_lon), dest)
    denom = o + d + 1.0
    speed = max(1.0, e.speed_kmh)
    dist = max(0.1, e.distance_m)
    hw = 1.0 if _road_class_code(e.road_class) >= 3.0 else 0.0
    return {
        "edge_road_m": dist,
        "edge_duration_s": dist / (speed / 3.6),
        "edge_class_code": _road_class_code(e.road_class),
        "edge_speed": speed,
        "is_oneway": 0.0,
        "intersection_degree": 0.0,
        "turn_angle_deg": 0.0,
        "is_bridge_like": 0.0,
        "is_tunnel_like": 0.0,
        "is_highway_like": hw,
        "origin_dist_m": float(o),
        "dest_dist_m": float(d),
        "remaining_lower_bound_m": float(d),
        "cum_dist_m": float(o),
        "cum_time_s": float(o / (speed / 3.6)),
        "progress_ratio": float(o / denom),
        "gap_index": 0.0,
        "passenger_impact_est": 0.0,
        "cargo_detour_est": 0.0,
        "trip_locked": 0.0,
        "sla_remaining_s": 1800.0,
        "capacity_remaining": 3.0,
        "multi_leg_state": 0.0,
        "urban_density_proxy": 0.0,
    }


def build_group_v04(
    gh: GraphHopperRoutingEngine,
    origin: tuple[float, float],
    dest: tuple[float, float],
    rng: random.Random,
    od_key: str,
    n: int = 16,
    n_alts: int = 4,
):
    """候选=teacher 真实边 + 绕行路径真实边；label=edge_id ∈ teacher 集合。"""
    t = gh.route(origin, dest, profile="bus")
    if not t.available or not t.edge_segments:
        return None
    teacher_ids = {e.edge_id for e in t.edge_segments}
    by_id: dict[int, EdgeSegment] = {e.edge_id: e for e in t.edge_segments}

    # 真实绕行：途经 teacher 折线中点附近，产生 off-path 真实边
    pts = list(t.polyline)
    alt_neg_ids: list[int] = []
    seen_alt: set[int] = set()
    for i in range(n_alts):
        if len(pts) < 3:
            break
        mid = pts[1 + ((i * 3) % max(1, len(pts) - 2))]
        # 侧向扰动，强制绕行（度级，非合成路网）
        lat, lon = mid
        jitter = 0.02 * (1 + i % 3)
        wp = (lat + jitter * (1 if i % 2 == 0 else -1), lon + jitter * (1 if i % 3 else -1))
        alt = gh.route(origin, dest, (wp,), profile="bus")
        if not alt.available:
            continue
        for e in alt.edge_segments:
            if e.edge_id in teacher_ids or e.edge_id in seen_alt:
                continue
            seen_alt.add(e.edge_id)
            by_id[e.edge_id] = e
            alt_neg_ids.append(e.edge_id)

    pos_ids = list(teacher_ids)
    rng.shuffle(pos_ids)
    rng.shuffle(alt_neg_ids)
    half = max(1, n // 2)
    take_pos = pos_ids[: min(half, len(pos_ids))]
    take_neg = alt_neg_ids[: max(1, n - len(take_pos))]
    if len(take_neg) < max(1, n - len(take_pos)):
        return None  # 真实负样本不足：丢弃，禁止合成
    cand_ids = take_pos + take_neg
    rng.shuffle(cand_ids)  # 打乱位置，杜绝 index 捷径

    teacher_d = t.distance_m
    teacher_dur = t.duration_s
    teacher_cost_m = sum(by_id[i].distance_m for i in take_pos) or 1.0
    teacher_cost_s = sum(
        by_id[i].distance_m / max(1.0, by_id[i].speed_kmh / 3.6) for i in take_pos
    ) or 1.0

    cands = []
    for eid in cand_ids:
        e = by_id[eid]
        feats = _edge_features(e, origin, dest)
        on_teacher = eid in teacher_ids
        cands.append({
            "edge_id": eid,
            "feats": feats,
            "label": 1 if on_teacher else 0,
            "cost_m": e.distance_m,
            "cost_s": e.distance_m / max(1.0, e.speed_kmh / 3.6),
        })
    meta = {
        "od_key": od_key,
        "teacher_path_edges": len(teacher_ids),
        "teacher_in_cands": len(take_pos),
        "n_cands": len(cands),
        "teacher_d": teacher_d,
        "teacher_dur": teacher_dur,
        "teacher_cost_m": teacher_cost_m,
        "teacher_cost_s": teacher_cost_s,
    }
    return cands, meta


def recall_at_k(y_true, scores, k):
    """|model_topK ∩ teacher_cands| / |teacher_cands|。"""
    teacher = [i for i, v in enumerate(y_true) if v == 1]
    if not teacher:
        return None
    order = list(np.argsort(-scores, kind="stable"))[:k]
    return len(set(order) & set(teacher)) / len(teacher)


def path_edge_recall_at_k(labels, scores, k, n_path_edges):
    """|model_topK ∩ teacher_path| / |teacher_path|（候选覆盖子集时的上限=|T∩cands|/|path|）。"""
    teacher = [i for i, v in enumerate(labels) if v == 1]
    if not n_path_edges:
        return None
    order = list(np.argsort(-scores, kind="stable"))[:k]
    return len(set(order) & set(teacher)) / float(n_path_edges)


def eval_ranker(X_list, y_list, cost_m_list, cost_s_list, n_path_list, ranker):
    rec = {k: [] for k in (1, 3, 5, 10)}
    path_rec = {k: [] for k in (1, 3, 5, 10)}
    d_regrets, t_regrets = [], []
    for X, y, cm, cs, npath in zip(X_list, y_list, cost_m_list, cost_s_list, n_path_list):
        s = ranker.predict(X)
        teacher_idx = [i for i, v in enumerate(y) if v == 1]
        if not teacher_idx:
            continue
        for k in (1, 3, 5, 10):
            r = recall_at_k(y, s, k)
            if r is not None:
                rec[k].append(r)
            pr = path_edge_recall_at_k(y, s, k, npath)
            if pr is not None:
                path_rec[k].append(pr)
        order = list(np.argsort(-s, kind="stable"))[: len(teacher_idx)]
        t_m = sum(cm[i] for i in teacher_idx)
        m_m = sum(cm[i] for i in order)
        t_s = sum(cs[i] for i in teacher_idx)
        m_s = sum(cs[i] for i in order)
        if t_m > 0:
            d_regrets.append(max(0.0, (m_m - t_m) / t_m))
        if t_s > 0:
            t_regrets.append(max(0.0, (m_s - t_s) / t_s))
    def _mean(v):
        return float(np.mean(v)) if v else None
    return {
        "recall": {k: _mean(v) for k, v in rec.items()},
        "path_edge_recall": {k: _mean(v) for k, v in path_rec.items()},
        "distance_regret_mean": _mean(d_regrets),
        "duration_regret_mean": _mean(t_regrets),
        "n_groups_scored": len(d_regrets),
    }


def random_baseline(y_list, k=5):
    """E[Recall@K] = K / n_cands（与 |T| 无关）。"""
    vals = []
    for y in y_list:
        n = len(y)
        if n:
            vals.append(min(k, n) / float(n))
    return float(np.mean(vals)) if vals else None


def run_v04(n_od: int = 250, seed: int = 42, n_cands: int = 16, n_alts: int = 4):
    assert assert_no_leakage(EDGE_FEATURES)
    cfg = LocalRoutingConfig(
        graphhopper_base_url="http://127.0.0.1:8080",
        graphhopper_timeout_ms=8000,
    )
    gh = GraphHopperRoutingEngine(cfg)
    stations = load_stations(80)
    rng = random.Random(seed)
    names = sorted(stations)

    groups = []  # each: dict X,y,cost_m,cost_s,n_path,meta
    t0 = time.time()
    for i in range(n_od):
        if len(names) < 2:
            break
        a, b = rng.sample(names, 2)
        sa, sb = stations[a], stations[b]
        cross = False
        if i % 2 == 0:
            # 强制跨 region（主城↔江津）
            target_reg = "jiangjin" if sa[2] == "chongqing_core" else "chongqing_core"
            pool = [n for n in names if stations[n][2] == target_reg]
            if pool:
                b = rng.choice(pool)
                sb = stations[b]
                cross = sa[2] != sb[2]
        g = build_group_v04(gh, sa[:2], sb[:2], rng, f"od{i}", n=n_cands, n_alts=n_alts)
        if not g:
            continue
        cands, meta = g
        meta["region_a"] = sa[2]
        meta["region_b"] = sb[2]
        meta["cross_region"] = bool(cross or sa[2] != sb[2])
        y = np.array([c["label"] for c in cands], dtype=np.int32)
        X = np.array([[c["feats"].get(k, 0.0) for k in EDGE_FEATURES] for c in cands])
        groups.append({
            "X": X,
            "y": y,
            "cost_m": [c["cost_m"] for c in cands],
            "cost_s": [c["cost_s"] for c in cands],
            "n_path": meta["teacher_path_edges"],
            "meta": meta,
        })
    gen_s = time.time() - t0

    n = len(groups)
    if n < 20:
        out = {
            "experiment_id": f"v04-{seed}",
            "status": "INSUFFICIENT_GROUPS",
            "groups": n,
            "note": "真实边/绕行不足，禁止合成填充",
        }
        DATA_DIR.mkdir(parents=True, exist_ok=True)
        RESULT_PATH.write_text(json.dumps(out, indent=2, default=str), encoding="utf-8")
        return out

    idx = list(range(n))
    random.Random(seed).shuffle(idx)
    n_tr = int(n * 0.75)
    tr_i, te_i = idx[:n_tr], idx[n_tr:]

    def pack(ids):
        return (
            [groups[i]["X"] for i in ids],
            [groups[i]["y"] for i in ids],
            [groups[i]["cost_m"] for i in ids],
            [groups[i]["cost_s"] for i in ids],
            [groups[i]["n_path"] for i in ids],
        )

    tr, te = pack(tr_i), pack(te_i)

    from learning.path_search import RouteSearchRanker

    ranker = RouteSearchRanker()
    t1 = time.time()
    ranker.train(tr[0], tr[1], [len(y) for y in tr[1]], seed=seed, n_estimators=80)
    train_s = time.time() - t1
    real_metrics = eval_ranker(*te, ranker)

    # 金标准：仅打乱训练 label，评估仍用真实测试 label → 必须≈随机
    rng2 = np.random.RandomState(0)
    y_shuf = [rng2.permutation(y) for y in tr[1]]
    r_shuf = RouteSearchRanker()
    r_shuf.train(tr[0], y_shuf, [len(y) for y in y_shuf], seed=seed, n_estimators=80)
    shuf_metrics = eval_ranker(*te, r_shuf)

    rand5 = random_baseline(te[1], k=5)
    rand1 = random_baseline(te[1], k=1)
    shuf5 = (shuf_metrics.get("recall") or {}).get(5)
    shuf1 = (shuf_metrics.get("recall") or {}).get(1)
    # |shuffled - random| 允许抽样波动，但不得系统性接近 real
    def _near(a, b, tol=0.12):
        if a is None or b is None:
            return False
        return abs(a - b) <= tol

    shuffle_ok = _near(shuf5, rand5) and _near(shuf1, rand1, tol=0.15)
    real5 = (real_metrics.get("recall") or {}).get(5)

    out = {
        "experiment_id": f"v04-{seed}",
        "status": "OK" if shuffle_ok else "EVAL_INVALID",
        "n_od_requested": n_od,
        "groups": n,
        "train_groups": len(tr_i),
        "test_groups": len(te_i),
        "gen_s": round(gen_s, 2),
        "train_s": round(train_s, 2),
        "gh_calls": gh.calls,
        "real_model": real_metrics,
        "shuffled_label_control": shuf_metrics,
        "random_baseline": {"recall@1": rand1, "recall@5": rand5},
        "shuffle_golden_ok": shuffle_ok,
        "label_balance": float(np.mean([float(np.mean(g["y"])) for g in groups])),
        "teacher_path_edges_mean": float(np.mean([g["n_path"] for g in groups])),
        "cross_region_groups": sum(1 for g in groups if g["meta"].get("cross_region")),
        "jiangjin_touch_groups": sum(
            1 for g in groups
            if "jiangjin" in (g["meta"].get("region_a", ""), g["meta"].get("region_b", ""))
        ),
        "leakage": False,
        "synthetic": 0,
        "notes": [
            "label=真实 GH edge_id ∈ teacher 路径集合",
            "Recall@K=|topK∩teacher_cands|/|teacher_cands|",
            "shuffle 控制：训练 label 置乱，测试 label 保持真实",
            "E[Recall@K]_random = K/n_cands",
        ],
    }
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    RESULT_PATH.write_text(json.dumps(out, indent=2, default=str), encoding="utf-8")
    return out


def run_v04_multi(seeds=(42, 123, 3407, 2026, 8888), n_od: int = 250):
    results = []
    for s in seeds:
        print(f"=== v0.4 seed={s} ===")
        r = run_v04(n_od=n_od, seed=s)
        results.append(r)
        print(json.dumps({
            "seed": s,
            "status": r.get("status"),
            "groups": r.get("groups"),
            "recall": (r.get("real_model") or {}).get("recall"),
            "shuf_recall": (r.get("shuffled_label_control") or {}).get("recall"),
            "random": r.get("random_baseline"),
            "shuffle_ok": r.get("shuffle_golden_ok"),
            "d_regret": (r.get("real_model") or {}).get("distance_regret_mean"),
        }, indent=2, default=str))
    summary = {
        "experiment_id": "v0.4-multi",
        "seeds": list(seeds),
        "n_od": n_od,
        "runs": [
            {
                "seed": r.get("experiment_id"),
                "status": r.get("status"),
                "groups": r.get("groups"),
                "recall": (r.get("real_model") or {}).get("recall"),
                "shuf_recall": (r.get("shuffled_label_control") or {}).get("recall"),
                "random": r.get("random_baseline"),
                "shuffle_golden_ok": r.get("shuffle_golden_ok"),
                "distance_regret_mean": (r.get("real_model") or {}).get("distance_regret_mean"),
                "duration_regret_mean": (r.get("real_model") or {}).get("duration_regret_mean"),
            }
            for r in results
        ],
        "all_shuffle_ok": all(r.get("shuffle_golden_ok") for r in results),
        "model_trusted": all(r.get("status") == "OK" for r in results)
        and all(
            ((r.get("real_model") or {}).get("recall") or {}).get(5) is not None
            and (
                ((r.get("real_model") or {}).get("recall") or {}).get(5) or 0
            )
            > ((r.get("random_baseline") or {}).get("recall@5") or 0) + 0.05
            for r in results
        ),
    }
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    (DATA_DIR / "v04_multi_result.json").write_text(
        json.dumps(summary, indent=2, default=str), encoding="utf-8"
    )
    return summary


if __name__ == "__main__":
    print(json.dumps(run_v04_multi(), indent=2, default=str))
