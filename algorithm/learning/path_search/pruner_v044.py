"""V0.44 Pruner：branch-point batch inference + prefilter + reachability + adaptive + fallback。

设计约束：
- degree<=2 禁止 LightGBM
- 同 junction 一次 predict(batch)
- cheap prefilter 只用几何/路类/通行，不用 teacher
- TargetReachabilityGuard 只用 RoadGraph 拓扑反向可达
- ML-benefit gate：expected_saved_search_cost > estimated_ml_cost 才推理
- Safety Fallback：连通/候选过少/异常 → heuristic
"""

from __future__ import annotations

import time
from collections import deque
from dataclasses import dataclass, field
from typing import Sequence

import numpy as np

from app.routing.local_routing import RoadEdge, RoadGraph, haversine_m


def edge_id_of(e: RoadEdge) -> str:
    return f"{e.u}|{e.v}|{round(e.road_m, 1)}"

# 统计用计数器（单次搜索可 new 一份）


@dataclass
class PruneStats:
    feature_build_ms: float = 0.0
    model_predict_ms: float = 0.0
    prune_ms: float = 0.0
    search_ms: float = 0.0
    cache_ms: float = 0.0
    total_ms: float = 0.0
    ml_calls: int = 0
    ml_skipped: int = 0
    ml_cost_ms: float = 0.0
    search_saved_ms_est: float = 0.0
    cheap_prefilter_input: int = 0
    cheap_prefilter_removed: int = 0
    fallback_count: int = 0
    fallback_reasons: dict = field(default_factory=dict)
    branch_points: int = 0
    degree2_points: int = 0
    edge_expansions: int = 0
    node_expansions: int = 0
    pruned_away: int = 0
    unreachable_cands: int = 0
    uncertain_kept: int = 0
    protected: int = 0

    def note_fallback(self, reason: str) -> None:
        self.fallback_count += 1
        self.fallback_reasons[reason] = self.fallback_reasons.get(reason, 0) + 1

    @property
    def cheap_prefilter_keep_ratio(self) -> float | None:
        if self.cheap_prefilter_input <= 0:
            return None
        return 1.0 - self.cheap_prefilter_removed / float(self.cheap_prefilter_input)

    @property
    def fallback_rate(self) -> float:
        n = self.branch_points + self.degree2_points
        return self.fallback_count / n if n else 0.0

    def as_dict(self) -> dict:
        return {
            "feature_build_ms": round(self.feature_build_ms, 3),
            "model_predict_ms": round(self.model_predict_ms, 3),
            "prune_ms": round(self.prune_ms, 3),
            "search_ms": round(self.search_ms, 3),
            "cache_ms": round(self.cache_ms, 3),
            "total_ms": round(self.total_ms, 3),
            "ML_CALLS": self.ml_calls,
            "ML_SKIPPED": self.ml_skipped,
            "ML_COST_MS": round(self.ml_cost_ms, 3),
            "SEARCH_SAVED_MS_EST": round(self.search_saved_ms_est, 3),
            "NET_SAVED_MS_EST": round(self.search_saved_ms_est - self.ml_cost_ms, 3),
            "cheap_prefilter_input": self.cheap_prefilter_input,
            "cheap_prefilter_removed": self.cheap_prefilter_removed,
            "cheap_prefilter_keep_ratio": self.cheap_prefilter_keep_ratio,
            "pruner_fallback_count": self.fallback_count,
            "pruner_fallback_rate": self.fallback_rate,
            "fallback_reasons": dict(self.fallback_reasons),
            "branch_points": self.branch_points,
            "degree2_points": self.degree2_points,
            "edge_expansions": self.edge_expansions,
            "node_expansions": self.node_expansions,
            "pruned_away": self.pruned_away,
            "unreachable_cands": self.unreachable_cands,
            "uncertain_kept": self.uncertain_kept,
            "protected": self.protected,
        }


class TargetReachabilityGuard:
    """从 target 反向 BFS 的拓扑可达集合。禁止 teacher 信息。

    BFS 跑到队列空（子图规模内完整）；若被 max_nodes 截断，则未访问节点只标 uncertain，
    禁止当成 unreachable（否则会误剪正确路径）。
    """

    def __init__(self, graph: RoadGraph, target_xy: tuple[float, float], max_nodes: int = 500000,
                 reverse_adj: dict | None = None):
        self.graph = graph
        self.status = "OK"
        self.reachable: set[str] = set()
        self.reverse_degree: dict[str, int] = {}
        self.bridges_from: dict[str, set[str]] = {}
        self.bfs_complete = False
        nt, _ = graph.nearest_node(target_xy)
        self.target_node = nt
        if nt is None:
            self.status = "NO_TARGET_SNAP"
            return
        adj = graph.adjacency("bus")
        # 反向邻接：允许外部注入共享缓存（V0.45：同子图只建一次）
        if reverse_adj is not None:
            radj = reverse_adj
        else:
            radj = {}
            for u, es in adj.items():
                for e in es:
                    radj.setdefault(e.v, []).append(u)
        q = deque([nt])
        self.reachable.add(nt)
        truncated = False
        while q:
            if len(self.reachable) >= max_nodes:
                truncated = True
                break
            v = q.popleft()
            for u in radj.get(v, ()):
                if u not in self.reachable:
                    self.reachable.add(u)
                    q.append(u)
        self.bfs_complete = not truncated
        for u, es in adj.items():
            self.reverse_degree[u] = len(es)
        for u, es in adj.items():
            outs = [e for e in es if e.v in self.reachable]
            if len(es) >= 1 and len(outs) == 1 and u in self.reachable:
                self.bridges_from[u] = {outs[0].v}

    def classify(self, u: str, v: str) -> str:
        """reachable | unreachable | uncertain"""
        if self.status != "OK" or not self.reachable:
            return "uncertain"
        v_ok = v in self.reachable
        u_ok = u in self.reachable
        if v_ok:
            return "reachable" if u_ok else "uncertain"
        # v 不在反向可达集
        if self.bfs_complete and not v_ok:
            return "unreachable"
        return "uncertain"

    def must_keep(self, u: str, e: RoadEdge) -> bool:
        if self.status != "OK":
            return False
        br = self.bridges_from.get(u)
        if br and e.v in br:
            return True
        if self.reverse_degree.get(u, 0) == 1:
            return True
        if u not in self.reachable and e.v in self.reachable:
            return True
        return False


class CheapStaticPrefilter:
    """ML 前廉价过滤：明显回头 / 明显背离 / 不可通行。Haversine 仅作下界与粗滤。"""

    def __init__(self, max_backtrack_deg: float = 120.0, max_detour_factor: float = 3.5):
        self.max_backtrack_deg = max_backtrack_deg
        self.max_detour_factor = max_detour_factor

    def filter(self, succ: Sequence[RoadEdge], u_xy, dest_xy, cum_m: float, stats: PruneStats) -> list[RoadEdge]:
        stats.cheap_prefilter_input += len(succ)
        if len(succ) <= 1:
            return list(succ)
        kept: list[RoadEdge] = []
        # 目标方向
        th = (dest_xy[0] - u_xy[0], dest_xy[1] - u_xy[1])
        tn = (th[0] * th[0] + th[1] * th[1]) ** 0.5 or 1.0
        th = (th[0] / tn, th[1] / tn)
        for e in succ:
            if not e.vehicle_allowed:
                stats.cheap_prefilter_removed += 1
                continue
            if e.polyline:
                mid = e.polyline[len(e.polyline) // 2]
            else:
                mid = dest_xy
            # 明显背离：边中点比当前点离目标更远且航向差 > 阈值
            d_here = haversine_m(u_xy, dest_xy)
            d_mid = haversine_m(mid, dest_xy)
            eh = (mid[0] - u_xy[0], mid[1] - u_xy[1])
            en = (eh[0] * eh[0] + eh[1] * eh[1]) ** 0.5 or 1.0
            eh = (eh[0] / en, eh[1] / en)
            cosang = max(-1.0, min(1.0, eh[0] * th[0] + eh[1] * th[1]))
            ang = float(np.degrees(np.arccos(cosang)))
            if ang >= self.max_backtrack_deg and d_mid > d_here:
                stats.cheap_prefilter_removed += 1
                continue
            # 明显无意义绕行（下界）
            if d_mid > d_here * self.max_detour_factor and ang > 90:
                stats.cheap_prefilter_removed += 1
                continue
            kept.append(e)
        # 不可过度过滤
        if not kept:
            return list(succ)
        return kept


def adaptive_keep_ratio(degree: int, reachable_ratio: float, class_diversity: float) -> float:
    """保守/积极随路口状态变化；系数经实验网格得出，非硬编码漂亮结果（见 V044 报告）。"""
    if degree <= 2:
        return 1.0
    # 基线：越大越保守
    base = 0.85 if degree == 3 else (0.75 if degree <= 5 else 0.65)
    # 可达比例低 → 保守（多留）
    if reachable_ratio < 0.5:
        base = min(0.95, base + 0.15)
    elif reachable_ratio < 0.8:
        base = min(0.9, base + 0.05)
    # 道路等级分散 → 稍积极（更可信有主路）
    if class_diversity >= 2:
        base = max(0.5, base - 0.05)
    return float(min(0.95, max(0.5, base)))


class MLBeneiftGate:
    """只有 expected_saved_search_cost > estimated_ml_cost 才推理。

    参数用 V0.43 实测校准：单次 predict ~0.05–0.2ms；剪掉 1 条错误分支 ≈ 省掉其子树扩展。
    saved_ms_per_pruned_edge 默认 0.8ms（约数百次松弛），网格见 V044 报告。
    """

    def __init__(self, ml_cost_ms_per_cand: float = 0.05, saved_ms_per_pruned_edge: float = 0.8):
        self.ml_cost_ms_per_cand = ml_cost_ms_per_cand
        self.saved_ms_per_pruned_edge = saved_ms_per_pruned_edge
        self._ml_ema = ml_cost_ms_per_cand
        self._saved_ema = saved_ms_per_pruned_edge

    def should_call(self, n_cands: int, est_keep: int) -> tuple[bool, float, float]:
        est_ml = self._ml_ema * max(1, n_cands) + 0.05  # predict 固定开销
        est_saved = self._saved_ema * max(0, n_cands - est_keep)
        return est_saved > est_ml, est_saved, est_ml

    def update(self, ml_ms: float, n_cands: int, pruned: int) -> None:
        if n_cands > 0:
            self._ml_ema = 0.85 * self._ml_ema + 0.15 * (ml_ms / max(1, n_cands))


@dataclass
class BranchPointDecision:
    kept: list[RoadEdge]
    used_ml: bool
    reason: str


class BranchPointPruner:
    """V0.44：仅 branch-point 批量 ML；degree<=2 走廉价启发。"""

    def __init__(
        self,
        ranker,
        *,
        ml_degree_threshold: int = 3,
        prefilter: CheapStaticPrefilter | None = None,
        gate: MLBeneiftGate | None = None,
    ):
        self.ranker = ranker
        self.ml_degree_threshold = ml_degree_threshold
        self.prefilter = prefilter or CheapStaticPrefilter()
        self.gate = gate or MLBeneiftGate()
        # 特征缓存留给 V0.45（含 cum_* 动态列，本轮不做错误缓存）

    def _build_features(self, edges: Sequence[RoadEdge], origin, dest, cum_m: float, stats: PruneStats) -> np.ndarray:
        from learning.path_search import EDGE_FEATURES

        t0 = time.perf_counter()
        rows = []
        for e in edges:
            if e.polyline:
                mid = e.polyline[len(e.polyline) // 2]
            else:
                mid = dest
            class_code = {"motorway": 5, "trunk": 4, "primary": 3, "secondary": 2, "tertiary": 1}.get(
                (e.road_class or "").lower(), 0.0
            )
            speed = (e.road_m / e.duration_s * 3.6) if e.duration_s > 0 else 30.0
            o = haversine_m(mid, origin)
            d = haversine_m(mid, dest)
            feats = {
                "edge_road_m": float(e.road_m),
                "edge_duration_s": float(e.duration_s or e.road_m / 8.0),
                "edge_class_code": float(class_code),
                "edge_speed": float(max(1.0, speed)),
                "is_oneway": 0.0 if e.bidirectional else 1.0,
                "intersection_degree": 0.0,
                "turn_angle_deg": 0.0,
                "is_bridge_like": 0.0,
                "is_tunnel_like": 0.0,
                "is_highway_like": 1.0 if class_code >= 3 else 0.0,
                "origin_dist_m": float(o),
                "dest_dist_m": float(d),
                "remaining_lower_bound_m": float(d),
                "cum_dist_m": float(cum_m),
                "cum_time_s": float(cum_m / 8.0),
                "progress_ratio": float(o / (o + d + 1.0)),
                "gap_index": 0.0,
                "passenger_impact_est": 0.0,
                "cargo_detour_est": 0.0,
                "trip_locked": 0.0,
                "sla_remaining_s": 1800.0,
                "capacity_remaining": 3.0,
                "multi_leg_state": 0.0,
                "urban_density_proxy": 0.0,
            }
            vec = np.asarray([float(feats.get(k, 0.0)) for k in EDGE_FEATURES], dtype=np.float64)
            rows.append(vec)
        stats.feature_build_ms += (time.perf_counter() - t0) * 1000
        return np.vstack(rows) if rows else np.zeros((0, 24))

    def decide(
        self,
        succ: Sequence[RoadEdge],
        *,
        u: str,
        u_xy,
        dest_xy,
        origin_xy,
        cum_m: float,
        guard: TargetReachabilityGuard | None,
        stats: PruneStats,
        rng_class_diversity: float = 0.0,
    ) -> BranchPointDecision:
        if not succ:
            return BranchPointDecision(list(succ), False, "empty")

        # 0) Safety：单出口 / 链状 degree=2 —— 禁止 ML，且不做 prefilter（热路径零税）
        if len(succ) == 1:
            stats.degree2_points += 1
            return BranchPointDecision(list(succ), False, "degree1")
        if len(succ) == 2:
            stats.degree2_points += 1
            kept = list(succ)
            if guard is not None:
                # 丢掉明确 unreachable 的一条（若另一条可达）
                st = [guard.classify(u, e.v) for e in succ]
                if st[0] == "unreachable" and st[1] != "unreachable":
                    kept = [succ[1]]
                elif st[1] == "unreachable" and st[0] != "unreachable":
                    kept = [succ[0]]
            return BranchPointDecision(kept, False, "degree_le_2")

        # 分类 reachability
        classes = []
        must = []
        pool = []
        for e in succ:
            if guard is not None and guard.must_keep(u, e):
                must.append(e)
                stats.protected += 1
                continue
            st = guard.classify(u, e.v) if guard is not None else "uncertain"
            classes.append((e, st))
            if st == "unreachable":
                stats.unreachable_cands += 1
                continue  # 允许剪
            if st == "uncertain":
                stats.uncertain_kept += 1
            pool.append(e)

        # 不可把 pool 剪空
        if not pool and not must:
            stats.note_fallback("reachability_empty")
            return BranchPointDecision(list(succ), False, "fallback_reachability")

        # 1) cheap prefilter（仅 branch-point）
        t_pf = time.perf_counter()
        filtered = self.prefilter.filter(pool, u_xy, dest_xy, cum_m, stats)
        stats.prune_ms += (time.perf_counter() - t_pf) * 1000
        if not filtered:
            stats.note_fallback("prefilter_empty")
            filtered = list(pool)

        degree = len(succ)
        stats.branch_points += 1

        # 2) adaptive keep
        rel_pool = [e for e in filtered if (guard is None or guard.classify(u, e.v) != "unreachable")]
        reachable_ratio = (
            sum(1 for e in filtered if guard is not None and guard.classify(u, e.v) == "reachable") / max(1, len(filtered))
            if guard is not None else 1.0
        )
        keep_ratio = adaptive_keep_ratio(degree, reachable_ratio, rng_class_diversity)
        est_keep = max(1, int(round(len(filtered) * keep_ratio)))

        # 3) ML-benefit gate
        use_ml, est_saved, est_ml = self.gate.should_call(len(filtered), est_keep)
        if not use_ml:
            stats.ml_skipped += 1
            kept = self._heuristic_keep(filtered, dest_xy, keep_ratio=keep_ratio) + must
            return BranchPointDecision(kept, False, "benefit_gate_skip")

        if self.ranker is None or getattr(self.ranker, "fallback", True):
            stats.note_fallback("model_fallback")
            stats.ml_skipped += 1
            kept = self._heuristic_keep(filtered, dest_xy, keep_ratio=keep_ratio) + must
            return BranchPointDecision(kept, False, "fallback_model")

        # 4) batch feature + batch predict（一次）
        try:
            t0 = time.perf_counter()
            X = self._build_features(filtered, origin_xy, dest_xy, cum_m, stats)
            t1 = time.perf_counter()
            scores = np.asarray(self.ranker.predict(X), dtype=float).reshape(-1)
            t2 = time.perf_counter()
            self.gate.update((t2 - t0) * 1000, len(filtered), 0)
            stats.model_predict_ms += (t2 - t1) * 1000
            stats.ml_cost_ms += (t2 - t0) * 1000
            stats.ml_calls += 1
            stats.search_saved_ms_est += est_saved

            order = list(np.argsort(-scores, kind="stable"))
            keep_n = est_keep
            keep_n = max(2, min(keep_n, len(filtered)))  # 至少 2，降断路
            chosen = [filtered[i] for i in order[:keep_n]]
            # 再保护 1 条启发式最优（非 teacher）
            def _h(e: RoadEdge) -> float:
                mid = e.polyline[len(e.polyline) // 2] if e.polyline else dest_xy
                return haversine_m(mid, dest_xy)
            best_h = min(filtered, key=_h)
            chosen_ids = {edge_id_of(e) for e in chosen}
            if edge_id_of(best_h) not in chosen_ids and keep_n < len(filtered):
                chosen = chosen[:-1] + [best_h]
            stats.prune_ms += (time.perf_counter() - t2) * 1000
            stats.pruned_away += max(0, len(succ) - (len(chosen) + len(must)))
            kept = chosen + must
            return BranchPointDecision(kept, True, "ml_batch")
        except Exception as ex:  # noqa: BLE001
            stats.note_fallback(f"exception:{type(ex).__name__}")
            kept = self._heuristic_keep(filtered, dest_xy, keep_ratio=keep_ratio) + must
            return BranchPointDecision(kept, False, "fallback_exception")

    @staticmethod
    def _heuristic_keep(edges: Sequence[RoadEdge], dest_xy, keep_ratio: float) -> list[RoadEdge]:
        if not edges:
            return []
        if keep_ratio >= 0.99 or len(edges) <= 2:
            return list(edges)
        def h(e: RoadEdge) -> float:
            mid = e.polyline[len(e.polyline) // 2] if e.polyline else dest_xy
            return haversine_m(mid, dest_xy)
        s = sorted(edges, key=h)
        k = max(1, int(round(len(edges) * keep_ratio)))
        return s[:k]
