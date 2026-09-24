# -*- coding: utf-8 -*-
"""真实公交线路站序当骨架 + 真实站点坐标产品订单 → 插入排序 LambdaRank。

数据：
- 线路/站序：osm_routes_full.json（OSM route=bus 真实站序）
- 道路：chongqing-260921.osm.pbf LocalRoutingEngine
- 订单：客运/快递进村/农产品出山/返程揽派/联运，起讫落在真实站点坐标
- 标签：产品优先级最优插入 pax→detour→dist→dur
"""
from __future__ import annotations

import json
import pickle
import random
import sys
import time
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))

from app.haco.construction import generate_insertion_candidates
from app.haco.encoding import TaskBlock, TaskType
from app.haco.feasibility_engine import FeasibilityEngine
from app.haco.insertion_features import FEATURES, compute_features
from app.haco.route_genome import RouteGenome
from app.models import Station
from app.routing.local_routing import LocalRoutingEngine

SNAPSHOT = ROOT / "data" / "transit" / "snapshots" / "osm_routes_full.json"
CACHE = ROOT / "data" / "road_graph_cq.pkl"
OUT = ROOT / "data" / "model_registry" / "insertion_ranker_osm_v2"


class M(dict):
    def get(self, a, b=None):
        if b is None:
            return dict.get(self, a)
        return dict.get(self, (min(a, b), max(a, b)), (1.0, 40.0))

    def __getitem__(self, k):
        if isinstance(k, tuple):
            return self.get(*k)
        return super().__getitem__(k)


def hav(a, b):
    lat1, lon1 = a
    lat2, lon2 = b
    p1, p2 = np.radians(lat1), np.radians(lat2)
    dp, dl = np.radians(lat2 - lat1), np.radians(lon2 - lon1)
    h = np.sin(dp / 2) ** 2 + np.cos(p1) * np.cos(p2) * np.sin(dl / 2) ** 2
    return 6371.0 * 2 * np.arcsin(np.sqrt(h))


def load_real_routes(min_stops=5, max_stops=14):
    """真实 route=bus 站序；过长线路截取连续窗口当骨架。"""
    data = json.loads(SNAPSHOT.read_text(encoding="utf-8"))
    st = {s["station_id"]: s for s in data["stations"]}
    routes = []
    for r in data["routes"]:
        stops = list(r.get("stop_ids") or [])
        if len(stops) < min_stops:
            continue
        # 只保留能在站点表解析出的站
        stops = [sid for sid in stops if sid in st]
        if len(stops) < min_stops:
            continue
        routes.append({
            "route_id": r["route_id"],
            "name": r.get("name") or r["route_id"],
            "stops": stops,
        })
    return st, routes


def pick_window(stops, rng, min_stops=4, max_stops=16):
    """从真实站序截取连续窗口当骨架；偶发整条线，长度随机。"""
    n = len(stops)
    if n <= max_stops or rng.random() < 0.15:
        return list(stops)
    win = rng.randint(min_stops, max_stops)
    start = rng.randint(0, n - win)
    return stops[start : start + win]


def nearby_stations(st, core_ids, rng, k=24):
    """真实站点坐标：骨架邻域 + 可变半径，作订单 OD 池。"""
    core = [st[sid] for sid in core_ids if sid in st]
    if not core:
        return []
    lat = sum(s["latitude"] for s in core) / len(core)
    lon = sum(s["longitude"] for s in core) / len(core)
    radius = rng.choice([5.0, 8.0, 12.0, 18.0, 25.0])
    k = rng.choice([16, 24, 32, 48])
    scored = []
    for s in st.values():
        d = hav((lat, lon), (s["latitude"], s["longitude"]))
        if d <= radius:
            scored.append((d, s))
    scored.sort(key=lambda x: x[0])
    near = [s for _, s in scored[: max(k, len(core_ids))]]
    seen, pool = set(), []
    for sid in core_ids:
        s = st.get(sid)
        if s and s["station_id"] not in seen:
            seen.add(s["station_id"])
            pool.append(s)
    for s in near:
        if s["station_id"] not in seen:
            seen.add(s["station_id"])
            pool.append(s)
    rng.shuffle(pool)
    return pool[: max(30, k * 2)]


def make_tasks(rng, pool_ids, skel_ids):
    """抗过拟合订单生成：真实站坐标 + 高随机配比/规模/OD 跨度/异常单。"""
    n = rng.choice(
        [2, 3, 4, 5, 6, 7, 8, 9, 10, 12, 14]
        + [5, 6, 7, 8] * 2  # 主体仍落在中等密度
    )
    # 每场景重抽类型权重（偶发单一类型刷屏）
    mode = rng.random()
    if mode < 0.08:
        kinds = [rng.choice(list(TaskType))] * n  # 单类型难例
    else:
        w_pax = rng.uniform(0.1, 0.6)
        w_ship = rng.uniform(0.05, 0.4)
        w_del = rng.uniform(0.05, 0.4)
        w_pick = rng.uniform(0.05, 0.35)
        kinds_pool, weights = [], []
        for tt, w in (
            (TaskType.PASSENGER, w_pax),
            (TaskType.SHIPMENT, w_ship),
            (TaskType.DELIVERY, w_del),
            (TaskType.PICKUP, w_pick),
        ):
            kinds_pool.append(tt)
            weights.append(w)

        def _pick_type():
            x, acc = rng.random() * sum(weights), 0.0
            for tt, w in zip(kinds_pool, weights):
                acc += w
                if x <= acc:
                    return tt
            return kinds_pool[-1]

        kinds = [_pick_type() for _ in range(n)]

    out = []
    for i in range(n):
        tt = kinds[i]
        # 极端属性：15% 离群大件/高价/超小
        outlier = rng.random() < 0.15
        if tt == TaskType.PASSENGER and len(skel_ids) >= 2:
            # 客运：骨架站上下；80% 顺向，20% 逆向（制造时间/顺序压力）
            o, d = rng.sample(skel_ids, 2)
            if rng.random() < 0.8 and skel_ids.index(o) > skel_ids.index(d):
                o, d = d, o
            size = 1 if rng.random() < 0.85 else rng.randint(2, 4)
            ev = rng.uniform(3, 200) if outlier else rng.uniform(5, 40)
            out.append(TaskBlock(
                task_id=f"T{i:03d}", task_type=tt, pickup_station=o, delivery_station=d,
                size=size, order_ids=[f"O{i:03d}"], economic_value=round(ev, 2),
            ))
        elif tt == TaskType.SHIPMENT and len(pool_ids) >= 2:
            # 联运：OD 跨度随机（短途↔长距）
            if rng.random() < 0.25:
                # 长距：按几何距离抽最远/随机远的两点
                a = rng.choice(pool_ids)
                rest = sorted(
                    pool_ids,
                    key=lambda s: -hav(
                        (0, 0), (0, 0)
                    ),
                )
                # 简化：随机两个不同点即可，跨度由池半径决定
                o, d = a, rng.choice([s for s in pool_ids if s != a])
            else:
                o, d = rng.sample(pool_ids, 2)
            if outlier:
                ev, wt, vol, sz = (
                    rng.uniform(80, 500), rng.uniform(40, 120),
                    round(rng.uniform(0.2, 0.8), 3), rng.randint(4, 12),
                )
            else:
                ev, wt, vol, sz = (
                    rng.uniform(5, 120), rng.uniform(1, 50),
                    round(rng.uniform(0.01, 0.4), 3), rng.randint(1, 5),
                )
            out.append(TaskBlock(
                task_id=f"T{i:03d}", task_type=tt, pickup_station=o, delivery_station=d,
                size=sz, order_ids=[f"O{i:03d}"],
                economic_value=round(ev, 2), weight_kg=round(wt, 2), volume_m3=vol,
            ))
        else:
            # 单点：派送/揽收；偶发贴近骨架站 vs 池边缘
            if pool_ids and rng.random() < 0.3 and skel_ids:
                d = rng.choice(skel_ids)
            else:
                d = rng.choice(pool_ids)
            if outlier:
                ev, wt, vol, sz = (
                    rng.uniform(50, 300), rng.uniform(30, 90),
                    round(rng.uniform(0.15, 0.6), 3), rng.randint(3, 10),
                )
            else:
                ev, wt, vol, sz = (
                    rng.uniform(3, 80), rng.uniform(0.5, 30),
                    round(rng.uniform(0.005, 0.25), 3), rng.randint(1, 6),
                )
            out.append(TaskBlock(
                task_id=f"T{i:03d}", task_type=tt,
                pickup_station=d, delivery_station=d,
                size=sz, order_ids=[f"O{i:03d}"],
                economic_value=round(ev, 2), weight_kg=round(wt, 2), volume_m3=vol,
            ))
    return out


def label_key(c, blocked=0):
    """全局对齐混合：pax → detour桶+堵死重罚 → dist → dur。

    blocked=0 时比里程（吃全局收益）；blocked>0 时重罚（保后续可放）。
    """
    det = round(float(c.cargo_detour), 1) + 10.0 * int(blocked)
    return (
        round(float(c.passenger_impact), 3),
        det,
        round(float(c.delta_distance), 2),
        round(float(c.delta_duration), 2),
    )


def count_blocked(route, cand, task, remaining_tasks, tmap, fe, station_map, matrix, sample=2, steps=1):
    """1-step 前瞻（标签用）：试插入后抽查剩余任务是否 0 候选。

    注意：2-step 曾让离线 top1 略升，但线上 A/B 明显变差（迁移到贪心+ML 路径失败），
    维持 1-step。steps 参数保留供实验，默认 1。
    """
    trial = route.copy()
    try:
        trial.insert_task(task, cand.pickup_index, cand.delivery_index)
    except Exception:
        return 9
    rest = list(remaining_tasks)
    if steps >= 2 and rest:
        scored = []
        for other in rest:
            try:
                cs = generate_insertion_candidates(
                    other, [trial], tmap, fe, station_map, matrix,
                    {0: 16}, {0: 36}, {0: 0}, {0: 0},
                    candidate_size=8,
                )
            except Exception:
                cs = []
            scored.append((len(cs), other, cs))
        scored.sort(key=lambda x: x[0])
        n0, other, cs = scored[0]
        if cs:
            try:
                b = min(cs, key=lambda c: label_key(c, 0))
                trial.insert_task(other, b.pickup_index, b.delivery_index)
                rest = [t for t in rest if t.task_id != other.task_id]
            except Exception:
                pass
        elif n0 == 0:
            return 9
    blocked = 0
    for other in rest[:sample]:
        try:
            cs = generate_insertion_candidates(
                other, [trial], tmap, fe, station_map, matrix,
                {0: 16}, {0: 36}, {0: 0}, {0: 0},
                candidate_size=4,
            )
            if not cs:
                blocked += 1
        except Exception:
            blocked += 1
    return blocked


def feat(c, task, route, coord, matrix, dep, n_unplaced=0.0, blocked=0.0):
    return compute_features(
        c, task, route, coord, matrix, dep,
        n_unplaced=n_unplaced, blocked=blocked,
    )


class RoadMatrix:
    """真实道路 OD 矩阵：只接受 LocalRoutingEngine 结果，禁止直线×系数。"""

    def __init__(self, eng):
        self.eng = eng
        self.cache: dict[tuple, tuple[float, float]] = {}
        self.calls = 0
        self.hits = 0
        self.zeros = 0
        self.errors = 0
        self._first_err: str | None = None

    @staticmethod
    def _key(a, b):
        return (round(a[0], 5), round(a[1], 5), round(b[0], 5), round(b[1], 5))

    def pair(self, a, b):
        """返回 (km, seconds)，必须是真实道路；失败则抛错，不回落直线。"""
        if a == b:
            return 0.0, 0.0
        k = self._key(a, b)
        if k in self.cache:
            self.hits += 1
            return self.cache[k]
        self.calls += 1
        r = self.eng.route(a, b)
        if r is None:
            self.errors += 1
            self._first_err = self._first_err or "route returned None"
            raise RuntimeError(f"real road failed for {a}->{b}")
        km = float(getattr(r, "distance_m", 0.0) or 0.0) / 1000.0
        sec = float(getattr(r, "duration_s", 0.0) or 0.0)
        if km <= 0.0:
            self.zeros += 1
        self.cache[k] = (max(km, 1e-6), max(sec, 1e-6))
        return self.cache[k]

    def fill(self, coords: dict[str, tuple], matrix: M) -> None:
        keys = list(coords.keys())
        for i in range(len(keys)):
            for j in range(i + 1, len(keys)):
                a, b = keys[i], keys[j]
                km, sec = self.pair(coords[a], coords[b])
                matrix[(a, b)] = (max(km, 0.01), sec if sec > 0 else km / 0.025)

    def stats(self) -> dict:
        return {
            "road_calls": self.calls,
            "road_cache_hits": self.hits,
            "road_zero_m": self.zeros,
            "road_errors": self.errors,
            "road_cached_pairs": len(self.cache),
            "first_error": self._first_err,
        }


def build(engine, st, routes, n_scen, rng, road=None):
    GX, GY, GR, GS = [], [], [], []  # GS: 该 group 来自第几个场景（前缀切片用）
    road = RoadMatrix(engine) if road is None else road
    used_routes = 0
    for si in range(n_scen):
        # 轮转覆盖全网 454 条线（防抽样遗漏），窗口/订单仍随机
        rt_meta = routes[si % len(routes)]
        skel_ids = pick_window(rt_meta["stops"], rng)
        pool = nearby_stations(st, skel_ids, rng)
        if len(pool) < 6:
            continue
        pool_ids = [s["station_id"] for s in pool]
        names = {sid: f"S{i:02d}" for i, sid in enumerate(pool_ids)}
        inv = {v: k for k, v in names.items()}
        coord = {names[sid]: (st[sid]["latitude"], st[sid]["longitude"]) for sid in pool_ids}
        skel = [names[sid] for sid in skel_ids if sid in names]
        if len(skel) < 5:
            continue
        used_routes += 1
        # ── 业务参数全面随机（线路/站点/道路里程保持真实）──
        depot_i = rng.randint(0, min(3, len(skel) - 1))
        coord["DEPOT"] = coord[skel[depot_i]]
        matrix = M()
        road.fill(coord, matrix)
        # 时长抖动：里程仍用真实道路，时长乘 0.75–1.35 防背死 ETA
        if rng.random() < 0.7:
            jit = rng.uniform(0.75, 1.35)
            for key in list(matrix.keys()):
                km, sec = matrix[key]
                matrix[key] = (km, max(1.0, sec * jit))

        station_map = {
            names[sid]: Station(stationId=names[sid], longitude=st[sid]["longitude"], latitude=st[sid]["latitude"])
            for sid in pool_ids
        }
        station_map["DEPOT"] = Station(
            stationId="DEPOT",
            longitude=st[inv[skel[depot_i]]]["longitude"],
            latitude=st[inv[skel[depot_i]]]["latitude"],
        )
        route = RouteGenome(0, 1, "DEPOT", skeleton=skel)
        max_detour = rng.choice([1.5, 2.0, 2.5, 3.0, 4.0, 5.0])
        fe = FeasibilityEngine(station_map=station_map, matrix=matrix, max_detour_km=max_detour)
        tasks = make_tasks(rng, [names[s] for s in pool_ids], skel)
        rng.shuffle(tasks)  # 插入顺序随机，防背死顺序
        for i, t in enumerate(tasks):
            t.task_id = f"T{i:03d}"
            t.order_ids = [f"O{i:03d}"]
        tmap = {t.task_id: t for t in tasks}
        dep = coord[skel[depot_i]]
        pax_cap = rng.randint(8, 28)
        cargo_cap = rng.randint(12, 48)
        init_pax = rng.randint(0, max(0, pax_cap // 4))
        init_cargo = rng.randint(0, max(0, cargo_cap // 3))
        cand_size = rng.choice([16, 24, 32, 48])
        for task in tasks:
            try:
                cands = generate_insertion_candidates(
                    task, [route], tmap, fe, station_map, matrix,
                    {0: pax_cap}, {0: cargo_cap}, {0: init_pax}, {0: init_cargo},
                    candidate_size=cand_size,
                )
            except Exception:
                continue
            if len(cands) < 4:
                continue
            rest = [t for t in tasks if t.task_id != task.task_id]
            n_unplaced = float(len(tasks) - len(route.placements))
            # 全局标签：1-step 前瞻 blocked + 粗 detour 桶
            blocked_of = {}
            def _key(i, _cands=cands, _rest=rest):
                b = count_blocked(route, _cands[i], task, _rest, tmap, fe, station_map, matrix)
                blocked_of[i] = b
                return label_key(_cands[i], b)

            order = sorted(range(len(cands)), key=_key)
            rank = [0] * len(cands)
            for r, i in enumerate(order):
                rank[i] = 3 if r == 0 else (2 if r < 3 else (1 if r < max(4, len(order) // 2) else 0))
            GX.append(np.asarray([
                feat(c, task, route, coord, matrix, dep, n_unplaced=n_unplaced, blocked=blocked_of.get(i, 0))
                for i, c in enumerate(cands)
            ], dtype=np.float64))
            GY.append(np.asarray(rank, dtype=np.int32))
            GR.append(rt_meta["route_id"])
            GS.append(si)
            try:
                b = cands[order[0]]
                route.insert_task(task, b.pickup_index, b.delivery_index)
            except Exception:
                pass
        if (si + 1) % 20 == 0 or si == n_scen - 1:
            print(f"  [{si+1}/{n_scen}] groups={len(GX)} road={road.stats()} routes={used_routes} last={rt_meta['name'][:24]}", flush=True)
    return GX, GY, GR, road.stats(), GS


def train_eval(GX, GY, GR=None, seed=3407, tune=True):
    """按**线路**留出 20% 验证；可选小幅 LightGBM 调参（防过拟合 + 自适应）。"""
    import lightgbm as lgb
    idx = list(range(len(GX)))
    if GR is not None and len(set(GR)) >= 5:
        # route-level holdout
        routes = sorted(set(GR))
        random.Random(seed).shuffle(routes)
        n_val_r = max(1, len(routes) // 5)
        val_routes = set(routes[:n_val_r])
        val = [i for i in idx if GR[i] in val_routes]
        tr = [i for i in idx if GR[i] not in val_routes]
        split_mode = "route_holdout"
    else:
        random.Random(seed).shuffle(idx)
        n_val = max(1, len(idx) // 5)
        val, tr = idx[:n_val], idx[n_val:]
        split_mode = "group_random"
    X = np.vstack([GX[i] for i in tr])
    y = np.concatenate([GY[i] for i in tr])
    g = [len(GX[i]) for i in tr]
    base_params = {
        "objective": "lambdarank", "metric": ["ndcg"], "ndcg_eval_at": [1, 3, 5, 10],
        "num_leaves": 31, "learning_rate": 0.06, "verbosity": -1, "seed": seed,
        "n_jobs": max(1, (__import__("os").cpu_count() or 2) - 2),
    }
    grid = [base_params]
    if tune and len(tr) >= 80:
        grid += [
            {**base_params, "num_leaves": 21, "learning_rate": 0.05, "min_child_samples": 20},
            {**base_params, "num_leaves": 41, "learning_rate": 0.04, "min_child_samples": 40},
            {**base_params, "num_leaves": 63, "learning_rate": 0.03, "min_child_samples": 30, "lambda_l2": 1.0},
        ]

    def _eval_model(bst):
        h1, h3, nd = [], [], []
        for i in val:
            Xv, yv = GX[i], GY[i]
            if len(yv) < 2:
                continue
            sc = bst.predict(Xv)
            order = list(np.argsort(-sc, kind="stable"))
            best = int(np.argmax(yv))
            h1.append(float(order[0] == best))
            h3.append(float(best in order[:3]))
            kk = min(5, len(yv))
            gains = np.power(2.0, yv[order[:kk]]) - 1.0
            disc = np.log2(np.arange(2, 2 + kk))
            ideal = np.sort(yv)[::-1][:kk]
            idcg = float(np.sum((np.power(2.0, ideal) - 1.0) / disc[: len(ideal)]))
            nd.append((float(np.sum(gains / disc)) / idcg) if idcg > 0 else 0.0)
        m = {
            "top1_hit": float(np.mean(h1)) if h1 else 0.0,
            "top3_hit": float(np.mean(h3)) if h3 else 0.0,
            "ndcg_5": float(np.mean(nd)) if nd else 0.0,
            "val_groups": len(h1), "train_groups": len(tr),
            "split_mode": split_mode,
            "val_routes": len(set(GR[i] for i in val)) if GR is not None else None,
            "train_routes": len(set(GR[i] for i in tr)) if GR is not None else None,
            "features": list(FEATURES),
        }
        m["rank_recall_proxy"] = m["top3_hit"]
        m["can_rank"] = m["top3_hit"] >= 0.80
        m["can_prune"] = m["top3_hit"] >= 0.99
        return m

    best_bst, best_m, best_score = None, None, -1.0
    for params in grid:
        bst = lgb.train(
            params,
            lgb.Dataset(X, label=y, group=g, free_raw_data=False, feature_name=list(FEATURES)),
            num_boost_round=120,
        )
        m = _eval_model(bst)
        score = m["top3_hit"] + 0.25 * m["ndcg_5"]
        if score > best_score:
            best_bst, best_m, best_score = bst, m, score
    best_m["tuned_params"] = {k: best_bst.params.get(k) for k in ("num_leaves", "learning_rate", "min_child_samples", "lambda_l2") if k in (best_bst.params or {})}
    best_m["param_grid_size"] = len(grid)
    return best_bst, best_m


def main():
    rng = random.Random(20260324)
    st, routes = load_real_routes()
    print("real bus routes:", len(routes), "stations:", len(st), flush=True)
    if len(routes) < 5:
        raise SystemExit("too few real routes")
    graph = pickle.loads(CACHE.read_bytes())
    print("road nodes", len(graph.nodes), flush=True)
    eng = LocalRoutingEngine(graph, speed_m_s=8.0)
    t = time.perf_counter()
    eng._adj()
    print("adj cached", time.perf_counter() - t, flush=True)
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 80
    t = time.perf_counter()
    GX, GY, GR, road_stats, _GS = build(eng, st, routes, n, rng)
    print(f"dataset groups={len(GX)} rows={sum(map(len, GY))} unique_routes={len(set(GR))} {time.perf_counter()-t:.1f}s", flush=True)
    print("road_stats", road_stats, flush=True)
    if len(GX) < 12:
        raise SystemExit("too few groups")
    bst, m = train_eval(GX, GY, GR)
    m["real_stations"] = len(st)
    m["real_routes"] = len(routes)
    m["road_stats"] = road_stats
    m["road_source"] = "LocalRoutingEngine real road only (no haversine fallback)"
    m["skeleton_source"] = "osm_routes_full.json route=bus stop sequences"
    m["order_source"] = "real station coords product mix pax/ship/del/pick"
    print(json.dumps(m, indent=2, ensure_ascii=False))
    OUT.mkdir(parents=True, exist_ok=True)
    model_txt = OUT / "route_search_ranker.model.txt"
    model_txt.write_text(bst.model_to_string(), encoding="utf-8")
    try:
        bst.save_model(str(OUT / "route_search_ranker.model"))
    except Exception as e:
        print("binary save skipped:", e, flush=True)
    (OUT / "feature_schema.json").write_text(json.dumps({"features": list(FEATURES)}, indent=2), encoding="utf-8")
    (OUT / "manifest.json").write_text(json.dumps({
        "name": "insertion_ranker_osm_v2",
        "dataset_size": len(GX), "n_samples": int(sum(map(len, GY))),
        "stations_source": "OSM osm_routes_full.json real bus stops",
        "routes_source": "OSM route=bus real stop sequences as skeleton",
        "road_source": "chongqing-260921.osm.pbf LocalRoutingEngine real road only",
        "order_source": "product mix on real station coords with high variance (pax/ship/del/pick/outliers)",
        "robustness": "real routes/stations/road-km only; random type mix n=2-14, outliers 15%, window 4-16, radius 5-25km, max_detour 1.5-5, depot, task order, capacity 8-48, init load, candidate_size 16-48, duration jitter 0.75-1.35",
        "metrics": m,
        "label": "hybrid pax→detour_bucket+block_penalty(1-step)→dist→dur",
        "created_at": time.time(),
    }, indent=2, ensure_ascii=False), encoding="utf-8")
    print("saved", OUT, flush=True)


if __name__ == "__main__":
    main()
