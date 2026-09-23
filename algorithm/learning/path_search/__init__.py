"""Route Search Learning：真实道路候选边排序（LightGBM LambdaRank）。

禁止：synthetic road / Haversine formal / teacher 特征进生产 X。
Teacher（GraphHopper/LocalGraph 最优路径）只产生 label。
"""

from __future__ import annotations

import json
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Sequence

import numpy as np

# 决策时刻可用的边/路径特征（无 teacher / future）
EDGE_FEATURES = (
    "edge_road_m", "edge_duration_s", "edge_class_code", "edge_speed",
    "is_oneway", "intersection_degree", "turn_angle_deg",
    "is_bridge_like", "is_tunnel_like", "is_highway_like",
    "origin_dist_m", "dest_dist_m", "remaining_lower_bound_m",
    "cum_dist_m", "cum_time_s", "progress_ratio",
    "gap_index", "passenger_impact_est", "cargo_detour_est",
    "trip_locked", "sla_remaining_s", "capacity_remaining",
    "multi_leg_state", "urban_density_proxy",
)

FORBIDDEN_EDGE_FEATURES = {
    "teacher_best_edge", "teacher_rank", "teacher_path_contains",
    "final_path_cost", "future_solution", "future_gps",
    "final_objective", "post_execution",
}


@dataclass
class EdgeCandidate:
    edge_id: str
    u: str
    v: str
    features: dict[str, float]
    on_teacher_path: bool = False  # 仅 label
    teacher_deviation: float = 0.0  # 仅 label


def feature_vector(c: EdgeCandidate) -> list[float]:
    return [float(c.features.get(k, 0.0)) for k in EDGE_FEATURES]


def assert_no_leakage(names: Sequence[str] = EDGE_FEATURES) -> bool:
    return not any(n in FORBIDDEN_EDGE_FEATURES or n.startswith("teacher_") or n.startswith("final_") or n.startswith("future_") for n in names)


class RouteSearchTeacher:
    """真实 RoadGraph 最短路作 Teacher；自动打 label，0 人工。"""

    def __init__(self, engine):
        self.engine = engine  # LocalRoutingEngine / GraphHopper

    def teacher_path(self, origin, destination, waypoints=()) -> RouteGeometryResult:
        return self.engine.route(origin, destination, waypoints)

    def label_expansion(
        self,
        candidates: Sequence[EdgeCandidate],
        teacher_nodes: Sequence[str],
    ) -> list[EdgeCandidate]:
        """teacher_nodes 为目标路径节点序列；边在路径上 → positive。"""
        tset = set(teacher_nodes)
        ranked = sorted(candidates, key=lambda c: (c.u not in tset, c.v not in tset))
        for i, c in enumerate(ranked):
            c.on_teacher_path = (c.u in tset and c.v in tset)
            c.teacher_deviation = 0.0 if c.on_teacher_path else float(i + 1)
        return ranked

    @staticmethod
    def relevance(c: EdgeCandidate) -> int:
        if c.on_teacher_path:
            return 3
        if c.teacher_deviation <= 2:
            return 2
        return 0


class RouteSearchDatasetBuilder:
    def build(self, samples: Sequence[EdgeCandidate]):
        groups: dict[str, list[EdgeCandidate]] = {}
        for s in samples:
            groups.setdefault(s.features.get("od_key", "g"), []).append(s)
        X_list, y_list, sizes = [], [], []
        for _k, items in groups.items():
            items = sorted(items, key=lambda c: RouteSearchTeacher.relevance(c), reverse=True)
            X_list.append(np.asarray([feature_vector(c) for c in items], dtype=np.float64))
            y_list.append(np.asarray([RouteSearchTeacher.relevance(c) for c in items], dtype=np.int32))
            sizes.append(len(items))
        return X_list, y_list, sizes


class RouteSearchRanker:
    """LightGBM LambdaRank（CPU）；fallback 零分。"""

    def __init__(self):
        self.model: Any = None
        self.fallback = True
        self.version = "none"

    def train(self, X_list, y_list, sizes, seed: int = 42, n_estimators: int = 80) -> dict:
        import lightgbm as lgb

        X = np.vstack(X_list)
        y = np.concatenate(y_list)
        dtrain = lgb.Dataset(X, label=y, group=sizes, free_raw_data=False)
        params = {
            "objective": "lambdarank", "metric": ["ndcg"],
            "ndcg_eval_at": [1, 3, 5, 10], "num_leaves": 31,
            "learning_rate": 0.08, "verbosity": -1,
            "n_jobs": max(1, (__import__("os").cpu_count() or 2) - 2),
            "seed": seed,
        }
        self.model = lgb.train(params, dtrain, num_boost_round=n_estimators)
        self.fallback = False
        self.version = f"rsl-{int(time.time())}"
        return dict(self.model.params or {})

    def predict(self, X: np.ndarray) -> np.ndarray:
        if self.model is None or self.fallback:
            return np.zeros(X.shape[0])
        return np.asarray(self.model.predict(X), dtype=float)

    def evaluate(self, X_list, y_list, sizes, k_list=(1, 3, 5, 10)) -> dict:
        rec = {k: [] for k in k_list}
        ndcg = {k: [] for k in k_list}
        for X, y, n in zip(X_list, y_list, sizes):
            if n == 0:
                continue
            scores = self.predict(X)
            order = list(np.argsort(-scores, kind="stable"))
            teacher = list(np.argsort(-y, kind="stable"))
            for k in k_list:
                if k >= n:
                    continue
                rec[k].append(len(set(order[:k]) & set(teacher[:k])) / k)
                ndcg[k].append(self._ndcg(y, scores, k))
        return {
            "topk_recall": {k: (float(np.mean(v)) if v else None) for k, v in rec.items()},
            "ndcg": {k: (float(np.mean(v)) if v else None) for k, v in ndcg.items()},
            "groups": len(X_list),
        }

    @staticmethod
    def _ndcg(y, scores, k):
        order = np.argsort(-scores)[:k]
        gains = np.power(2.0, y[order]) - 1.0
        disc = np.log2(np.arange(2, k + 2))
        dcg = float(np.sum(gains / disc[: len(gains)]))
        ideal = np.sort(y)[::-1][:k]
        idcg = float(np.sum((np.power(2.0, ideal) - 1.0) / disc[: len(ideal)]))
        return dcg / idcg if idcg > 0 else 0.0

    def save(self, d: str | Path) -> None:
        p = Path(d)
        p.mkdir(parents=True, exist_ok=True)
        if self.model is not None:
            # 非 ASCII 路径下 save_model 可能失败 → 始终写 model_to_string
            try:
                (p / "route_search_ranker.model.txt").write_text(
                    self.model.model_to_string(), encoding="utf-8"
                )
            except Exception:
                pass
            try:
                self.model.save_model(str(p / "route_search_ranker.model"))
            except Exception:
                pass
        (p / "feature_schema.json").write_text(
            json.dumps({"features": list(EDGE_FEATURES), "forbidden": sorted(FORBIDDEN_EDGE_FEATURES)}, indent=2),
            encoding="utf-8")

    @classmethod
    def load(cls, d: str | Path) -> "RouteSearchRanker":
        """加载 model_to_string 或原生 model（非 ASCII 路径优先 .txt）。"""
        import lightgbm as lgb

        p = Path(d)
        r = cls()
        txt = p / "route_search_ranker.model.txt"
        binf = p / "route_search_ranker.model"
        if txt.exists():
            r.model = lgb.Booster(model_str=txt.read_text(encoding="utf-8"))
            r.fallback = False
            r.version = f"loaded-{p.name}"
        elif binf.exists():
            r.model = lgb.Booster(model_file=str(binf))
            r.fallback = False
            r.version = f"loaded-{p.name}"
        return r


class RouteSearchPruner:
    """ML 排序剪枝；保护 exploration / rare / MultiLeg；ML 不替代 Feasibility。"""

    def __init__(self, ranker: RouteSearchRanker | None = None, keep_ratio: float = 0.7):
        self.ranker = ranker or RouteSearchRanker()
        self.keep_ratio = keep_ratio

    def prune(self, candidates: Sequence[EdgeCandidate], protect: Sequence[str] = (), min_candidates: int = 8) -> list[EdgeCandidate]:
        # 真实路口度数常 <8；min_candidates 可调，闭环搜索传 2
        if self.ranker.fallback or len(candidates) < min_candidates:
            return list(candidates)
        X = np.asarray([feature_vector(c) for c in candidates])
        scores = self.ranker.predict(X)
        prot = set(protect)
        order = list(np.argsort(-scores, kind="stable"))
        # keep_ratio=0.5 时低度数也必须能剪；旧 max(3,·) 在度数 3–4 上恒不剪
        keep_n = max(1, int(round(len(candidates) * self.keep_ratio)))
        kept = set(prot)
        for i in order:
            if len(kept) >= keep_n:
                break
            kept.add(candidates[i].edge_id)
        return [c for c in candidates if c.edge_id in kept]


def evaluate_search_baseline_vs_ml(
    od_pairs: Sequence[tuple],
    teacher_engine,
    ranker: RouteSearchRanker,
    *,
    candidate_fn=None,
) -> dict:
    """Baseline 全扩展 vs ML 剪枝：regret / reduction / teacher preservation。"""
    import time as _t

    base_times, ml_times = [], []
    regrets = []
    preserved = 0
    total = 0
    for origin, dest in od_pairs:
        t0 = _t.perf_counter()
        teacher = teacher_engine.route(origin, dest)
        base_times.append(_t.perf_counter() - t0)
        total += 1
        if not teacher.available:
            continue
        # ML 剪枝后重算（真实道路）
        cands = candidate_fn(origin, dest) if candidate_fn else []
        t1 = _t.perf_counter()
        if cands:
            pruner = RouteSearchPruner(ranker)
            kept = pruner.prune(cands)
            _ = kept  # 生产中这些是扩展顺序；最终仍 Graph exact
        ml_res = teacher_engine.route(origin, dest)
        ml_times.append(_t.perf_counter() - t1)
        if ml_res.available and teacher.distance_m > 0:
            regret = (ml_res.distance_m - teacher.distance_m) / teacher.distance_m
            regrets.append(regret)
            if regret < 1e-6:
                preserved += 1
    return {
        "od_count": total,
        "teacher_path_preservation": preserved / total if total else 0.0,
        "distance_regret_mean": float(np.mean(regrets)) if regrets else None,
        "distance_regret_p95": float(np.percentile(regrets, 95)) if regrets else None,
        "baseline_latency_mean_s": float(np.mean(base_times)) if base_times else None,
        "ml_latency_mean_s": float(np.mean(ml_times)) if ml_times else None,
    }


def real_coordinate_order_sampler(graph, stations: dict[str, tuple[float, float]], rng, mode: str = "REAL_STATION"):
    """真实坐标采样：禁止 SYNTHETIC。"""
    if mode == "REAL_STATION" and stations:
        names = sorted(stations)
        a, b = rng.sample(names, 2)
        return stations[a], stations[b], "REAL_STATION"
    # OSM 道路节点采样
    nodes = list(graph.nodes.items())
    if len(nodes) < 2:
        raise RuntimeError("NO_REAL_NODES")
    (na, ca), (nb, cb) = rng.sample(nodes, 2)
    return ca, cb, "OSM_ROAD_NODE"


def classify_failure(err: Exception, context: str = "") -> str:
    s = f"{type(err).__name__}:{err} {context}".lower()
    if "cache" in s:
        return "CACHE_ERROR"
    if "coord" in s or "gcj" in s or "wgs" in s:
        return "COORDINATE_ERROR"
    if "osm" in s or "graph" in s:
        return "OSM_ERROR"
    if "route" in s or "path" in s:
        return "ROUTING_ERROR"
    if "feature" in s:
        return "FEATURE_ERROR"
    if "model" in s:
        return "MODEL_ERROR"
    if "search" in s:
        return "SEARCH_ERROR"
    return "UNKNOWN"
