"""RESEARCH_ONLY — 禁止进入生产 routing path（V045 已证 Python A* Pruner 无法墙钟转正）。
V0.45 Search Pruner：目标=墙钟转正，不追求更高 Expansion Reduction。

模式：
  BASELINE   纯 A*（由 runner 实现）
  HEURISTIC  cheap prefilter + Guard + adaptive keep，无 LightGBM
  ML         HEURISTIC + static cache + batch LGB + absolute bypass + benefit gate
"""

from __future__ import annotations

import time
from collections import deque
from dataclasses import dataclass, field

import numpy as np

from app.routing.local_routing import RoadEdge, RoadGraph, haversine_m
from learning.path_search.branch_feature_cache import (
    DYNAMIC_KEYS,
    STATIC_KEYS,
    BranchFeatureCache,
    GuardCache,
    edge_key,
    static_vector,
)
from learning.path_search.pruner_v044 import (
    CheapStaticPrefilter,
    MLBeneiftGate,
    TargetReachabilityGuard,
    adaptive_keep_ratio,
)

# EDGE_FEATURES 完整顺序（与 path_search 一致）
FULL_FEATURE_ORDER = STATIC_KEYS + DYNAMIC_KEYS


@dataclass
class V045Stats:
    # 分项计时
    feature_ms: float = 0.0
    prefilter_ms: float = 0.0
    guard_ms: float = 0.0
    ml_ms: float = 0.0
    prune_ms: float = 0.0
    search_ms: float = 0.0
    cache_ms: float = 0.0
    total_ms: float = 0.0
    # 计数
    branch_points: int = 0
    degree2_points: int = 0
    edge_expansions: int = 0
    node_expansions: int = 0
    pruned_away: int = 0
    ML_CANDIDATE_BRANCHES: int = 0
    ML_CALLS: int = 0
    ML_SKIPPED: int = 0
    ML_OVERHEAD_MS: float = 0.0
    ESTIMATED_SAVED_MS: float = 0.0
    ACTUAL_SAVED_MS: float = 0.0
    estimated_saved_ms_hist: list = field(default_factory=list)
    actual_saved_ms_hist: list = field(default_factory=list)
    ml_bypass_short_od: int = 0
    ml_bypass_low_benefit: int = 0
    ml_bypass_total: int = 0
    batch_sizes: list = field(default_factory=list)
    degree_hist: dict = field(default_factory=dict)
    fallback_count: int = 0
    fallback_reasons: dict = field(default_factory=dict)
    unreachable_cands: int = 0
    protected: int = 0
    prefilter_input: int = 0
    prefilter_removed: int = 0
    cheap_prefilter_input: int = 0
    cheap_prefilter_removed: int = 0
    cache: dict = field(default_factory=dict)
    guard_cache: dict = field(default_factory=dict)
    guard_ms_build: float = 0.0

    def as_dict(self) -> dict:
        net = self.ESTIMATED_SAVED_MS - self.ML_OVERHEAD_MS
        est = float(np.mean(self.estimated_saved_ms_hist)) if self.estimated_saved_ms_hist else None
        act = float(np.mean(self.actual_saved_ms_hist)) if self.actual_saved_ms_hist else None
        err = (act - est) if (est is not None and act is not None) else None
        return {
            "feature_ms": round(self.feature_ms, 3),
            "prefilter_ms": round(self.prefilter_ms, 3),
            "guard_ms": round(self.guard_ms, 3),
            "ml_ms": round(self.ml_ms, 3),
            "prune_ms": round(self.prune_ms, 3),
            "search_ms": round(self.search_ms, 3),
            "cache_ms": round(self.cache_ms, 3),
            "total_ms": round(self.total_ms, 3),
            "branch_points": self.branch_points,
            "degree2_points": self.degree2_points,
            "edge_expansions": self.edge_expansions,
            "node_expansions": self.node_expansions,
            "pruned_away": self.pruned_away,
            "ML_CANDIDATE_BRANCHES": self.ML_CANDIDATE_BRANCHES,
            "ML_CALLS": self.ML_CALLS,
            "ML_SKIPPED": self.ML_SKIPPED,
            "ML_OVERHEAD_MS": round(self.ML_OVERHEAD_MS, 3),
            "ESTIMATED_SAVED_MS": round(self.ESTIMATED_SAVED_MS, 3),
            "ACTUAL_SAVED_MS": round(self.ACTUAL_SAVED_MS, 3),
            "NET_SAVED_MS": round(net, 3),
            "estimated_saved_ms_mean": est,
            "actual_saved_ms_mean": act,
            "estimation_error_ms": err,
            "ml_bypass_short_od": self.ml_bypass_short_od,
            "ml_bypass_low_benefit": self.ml_bypass_low_benefit,
            "ml_bypass_total": self.ml_bypass_total,
            "batch_size_mean": float(np.mean(self.batch_sizes)) if self.batch_sizes else None,
            "batch_size_p95": float(np.percentile(self.batch_sizes, 95)) if self.batch_sizes else None,
            "degree_hist": dict(sorted(self.degree_hist.items())),
            "fallback_count": self.fallback_count,
            "fallback_reasons": dict(self.fallback_reasons),
            "prefilter_input": self.prefilter_input or self.cheap_prefilter_input,
            "prefilter_removed": self.prefilter_removed or self.cheap_prefilter_removed,
            "prefilter_keep_ratio": (
                1 - (self.prefilter_removed or self.cheap_prefilter_removed) / (self.prefilter_input or self.cheap_prefilter_input)
                if (self.prefilter_input or self.cheap_prefilter_input) else None
            ),
            "protected": self.protected,
            **(self.cache or {}),
            **(self.guard_cache or {}),
            "guard_ms_build": round(self.guard_ms_build, 3),
        }


def dynamic_row(e: RoadEdge, origin, dest, cum_m: float) -> np.ndarray:
    mid = e.polyline[len(e.polyline) // 2] if e.polyline else dest
    o = haversine_m(mid, origin)
    d = haversine_m(mid, dest)
    return np.asarray([
        float(o), float(d), float(d),
        float(cum_m), float(cum_m / 8.0),
        float(o / (o + d + 1.0)),
        0.0, 0.0, 0.0, 0.0, 1800.0, 3.0, 0.0, 0.0,
    ], dtype=np.float64)


class V045SearchController:
    """A* 扩展控制器：HEURISTIC / ML。"""

    def __init__(
        self,
        ranker=None,
        *,
        mode: str = "ML",
        graph_version: str = "osm-1",
        profile: str = "bus",
        absolute_ml_budget_ms: float = 5.0,
        use_cache: bool = True,
        use_guard_cache: bool = True,
        use_prefilter: bool = True,
        use_bypass: bool = True,
        target_region_id: str = "corridor",
    ):
        self.ranker = ranker
        self.mode = mode  # HEURISTIC | ML
        self.graph_version = graph_version
        self.profile = profile
        self.absolute_ml_budget_ms = absolute_ml_budget_ms
        self.use_cache = use_cache
        self.use_guard_cache = use_guard_cache
        self.use_prefilter = use_prefilter
        self.use_bypass = use_bypass
        self.target_region_id = target_region_id
        self.cache = BranchFeatureCache(graph_version, profile) if use_cache else None
        self.guard_cache = GuardCache() if use_guard_cache else None
        self._radj_cache: dict[int, dict] = {}
        self.prefilter = CheapStaticPrefilter()
        self.gate = MLBeneiftGate()
        self.stats = V045Stats()

    def build_guard(self, graph: RoadGraph, target_xy) -> TargetReachabilityGuard | None:
        nt, _ = graph.nearest_node(target_xy)
        if nt is None:
            return None
        t0 = time.perf_counter()
        # 同一子图共享反向邻接（避免每次 BFS 重建 O(E)）
        radj = self._radj_cache.get(id(graph))
        if radj is None:
            radj = {}
            for u, es in graph.adjacency("bus").items():
                for e in es:
                    radj.setdefault(e.v, []).append(u)
            self._radj_cache[id(graph)] = radj
        def builder():
            return TargetReachabilityGuard(graph, target_xy, reverse_adj=radj)
        if self.guard_cache is not None:
            g = self.guard_cache.get(self.graph_version, nt, self.profile, builder)
        else:
            g = builder()
        self.stats.guard_ms += (time.perf_counter() - t0) * 1000
        self.stats.guard_ms_build += (time.perf_counter() - t0) * 1000
        return g

    def on_expand(self, succ, *, u, u_xy, dest_xy, origin_xy, cum_m, guard, est_remaining_search_ms: float):
        """返回 kept edges。est_remaining_search_ms 供 absolute bypass。"""
        st = self.stats
        if not succ:
            return list(succ)
        deg = len(succ)
        st.degree_hist[str(deg)] = st.degree_hist.get(str(deg), 0) + 1

        # degree 1/2：零 ML
        if deg <= 2:
            st.degree2_points += 1
            kept = list(succ)
            if guard is not None and deg == 2:
                cls = [guard.classify(u, e.v) for e in succ]
                if cls[0] == "unreachable" and cls[1] != "unreachable":
                    kept = [succ[1]]
                elif cls[1] == "unreachable" and cls[0] != "unreachable":
                    kept = [succ[0]]
            return kept

        st.branch_points += 1

        # Guard 分类 + 必留
        t_g = time.perf_counter()
        must, pool = [], []
        for e in succ:
            if guard is not None and guard.must_keep(u, e):
                must.append(e)
                st.protected += 1
                continue
            cls = guard.classify(u, e.v) if guard is not None else "uncertain"
            if cls == "unreachable":
                st.unreachable_cands += 1
                continue
            pool.append(e)
        st.guard_ms += (time.perf_counter() - t_g) * 1000
        if not pool and not must:
            st.fallback_count += 1
            st.fallback_reasons["reachability_empty"] = st.fallback_reasons.get("reachability_empty", 0) + 1
            return list(succ)

        # Prefilter（字段写入 self.stats，兼容 CheapStaticPrefilter）
        t_p = time.perf_counter()
        if self.use_prefilter:
            filtered = self.prefilter.filter(pool, u_xy, dest_xy, cum_m, st)
            st.prefilter_input = st.cheap_prefilter_input
            st.prefilter_removed = st.cheap_prefilter_removed
            if not filtered:
                filtered = list(pool)
        else:
            filtered = list(pool)
        st.prefilter_ms += (time.perf_counter() - t_p) * 1000

        # adaptive keep
        rel = sum(1 for e in filtered if guard is None or guard.classify(u, e.v) != "unreachable")
        reach_ratio = rel / max(1, len(filtered))
        keep_ratio = adaptive_keep_ratio(deg, reach_ratio, len({(e.road_class or "") for e in filtered}))
        est_keep = max(2, int(round(len(filtered) * keep_ratio)))
        est_keep = min(est_keep, len(filtered))

        # HEURISTIC 模式：永远不 ML
        if self.mode != "ML":
            st.ML_SKIPPED += 1
            return self._heuristic_keep(filtered, dest_xy, est_keep) + must

        # absolute bypass：剩余搜索都不够付 ML
        if self.use_bypass and est_remaining_search_ms < self.absolute_ml_budget_ms:
            st.ml_bypass_short_od += 1
            st.ml_bypass_total += 1
            st.ML_SKIPPED += 1
            return self._heuristic_keep(filtered, dest_xy, est_keep) + must

        st.ML_CANDIDATE_BRANCHES += 1
        use, est_saved, est_ml = self.gate.should_call(len(filtered), est_keep)
        if not use:
            st.ml_bypass_low_benefit += 1
            st.ml_bypass_total += 1
            st.ML_SKIPPED += 1
            return self._heuristic_keep(filtered, dest_xy, est_keep) + must

        if self.ranker is None or getattr(self.ranker, "fallback", True):
            st.fallback_count += 1
            st.ML_SKIPPED += 1
            return self._heuristic_keep(filtered, dest_xy, est_keep) + must

        # batch static cache + dynamic + one predict
        try:
            t0 = time.perf_counter()
            if self.cache is not None:
                ids, static_m = self.cache.branch_batch(u, self.target_region_id, list(filtered))
            else:
                static_m = np.vstack([static_vector(e) for e in filtered]) if filtered else np.zeros((0, len(STATIC_KEYS)))
            dyn = np.vstack([dynamic_row(e, origin_xy, dest_xy, cum_m) for e in filtered]) if filtered else np.zeros((0, len(DYNAMIC_KEYS)))
            X = np.hstack([static_m, dyn]) if len(static_m) else np.zeros((0, len(FULL_FEATURE_ORDER)))
            st.feature_ms += (time.perf_counter() - t0) * 1000
            st.batch_sizes.append(len(filtered))

            t1 = time.perf_counter()
            scores = np.asarray(self.ranker.predict(X), dtype=float).reshape(-1)
            t2 = time.perf_counter()
            overhead = (t2 - t0) * 1000
            st.ml_ms += (t2 - t1) * 1000
            st.ML_OVERHEAD_MS += overhead
            st.ML_CALLS += 1
            st.ESTIMATED_SAVED_MS += est_saved
            st.estimated_saved_ms_hist.append(est_saved)
            self.gate.update(overhead, len(filtered), 0)

            order = list(np.argsort(-scores, kind="stable"))
            chosen = [filtered[i] for i in order[:est_keep]]
            # 保护启发式最优
            def _h(e: RoadEdge) -> float:
                mid = e.polyline[len(e.polyline) // 2] if e.polyline else dest_xy
                return haversine_m(mid, dest_xy)
            best_h = min(filtered, key=_h)
            if edge_key(best_h) not in {edge_key(e) for e in chosen} and est_keep < len(filtered):
                chosen = chosen[:-1] + [best_h]
            t3 = time.perf_counter()
            st.prune_ms += (t3 - t2) * 1000
            kept = chosen + must
            # actual_saved 用启发式反事实：未保留边的代价估计（子树粗估）
            dropped = [e for e in filtered if e not in chosen]
            actual_saved = 0.0
            for e in dropped:
                mid = e.polyline[len(e.polyline) // 2] if e.polyline else dest_xy
                # 越偏的边预估省越多
                bias = haversine_m(mid, dest_xy) - haversine_m(u_xy, dest_xy)
                actual_saved += max(0.05, 0.002 * max(0.0, bias) + 0.15)
            st.ACTUAL_SAVED_MS += actual_saved
            st.actual_saved_ms_hist.append(actual_saved)
            st.pruned_away += len(dropped)
            return kept
        except Exception as ex:  # noqa: BLE001
            st.fallback_count += 1
            st.fallback_reasons[type(ex).__name__] = st.fallback_reasons.get(type(ex).__name__, 0) + 1
            return self._heuristic_keep(filtered, dest_xy, est_keep) + must

    @staticmethod
    def _heuristic_keep(edges, dest_xy, keep_n: int):
        if not edges:
            return []
        if keep_n >= len(edges):
            return list(edges)
        def h(e: RoadEdge) -> float:
            mid = e.polyline[len(e.polyline) // 2] if e.polyline else dest_xy
            return haversine_m(mid, dest_xy)
        return sorted(edges, key=h)[: max(1, keep_n)]
