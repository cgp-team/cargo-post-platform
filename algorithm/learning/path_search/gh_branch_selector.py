"""GHBranchSelector：ML 只做 GraphHopper 多分支预选，不进 A* 扩展。

生产链：
  Real Via Candidates → Cheap Features → LightGBM batch rank → Adaptive-K
  → GraphHopper exact route → Quality Fuse → (fallback expand K) → result
LocalGraph Python Pruner = RESEARCH_ONLY（见 pruner_v045.py）。
"""

from __future__ import annotations

import time
from dataclasses import dataclass, field

import numpy as np

from app.routing.local_routing import haversine_m

# 廉价 O(1) 特征（无 teacher）
CANDIDATE_FEATURES = (
    "candidate_distance",
    "candidate_duration_estimate",
    "origin_distance",
    "destination_distance",
    "via_distance",
    "heading_difference",
    "road_class",
    "road_level",
    "intersection_degree",
    "detour_estimate",
    "region_code",
    "profile_code",
    "historical_success_rate",
)

REGION_CODES = {"chongqing_core": 0.0, "jiangjin": 1.0, "cross": 2.0}
PROFILE_CODES = {"bus": 0.0, "car": 1.0}


def candidate_feature_row(
    via: tuple[float, float],
    origin: tuple[float, float],
    dest: tuple[float, float],
    *,
    region_id: str = "chongqing_core",
    profile: str = "bus",
    road_class: float = 0.0,
    road_level: float = 0.0,
    intersection_degree: float = 0.0,
    historical_success_rate: float = 0.5,
) -> np.ndarray:
    o = haversine_m(origin, via)
    d = haversine_m(via, dest)
    od = haversine_m(origin, dest)
    detour = max(0.0, o + d - od)
    # heading difference：O→D 与 O→via
    th = (dest[0] - origin[0], dest[1] - origin[1])
    vh = (via[0] - origin[0], via[1] - origin[1])
    tn = (th[0] ** 2 + th[1] ** 2) ** 0.5 or 1.0
    vn = (vh[0] ** 2 + vh[1] ** 2) ** 0.5 or 1.0
    cosang = max(-1.0, min(1.0, (th[0] * vh[0] + th[1] * vh[1]) / (tn * vn)))
    heading = float(np.degrees(np.arccos(cosang)))
    return np.asarray([
        float(o + d),          # candidate_distance
        float((o + d) / 8.0),  # duration estimate @8m/s
        float(o),
        float(d),
        float(haversine_m(via, (0.5 * (origin[0] + dest[0]), 0.5 * (origin[1] + dest[1])))),
        heading,
        road_class,
        road_level,
        intersection_degree,
        detour,
        REGION_CODES.get(region_id, 0.0),
        PROFILE_CODES.get(profile, 0.0),
        historical_success_rate,
    ], dtype=np.float64)


@dataclass
class BranchCandidate:
    via: tuple[float, float]
    features: np.ndarray
    score: float = 0.0
    gh_distance_m: float | None = None
    gh_duration_s: float | None = None
    gh_wall_ms: float | None = None
    feasible: bool = False


@dataclass
class SelectorStats:
    feature_ms: float = 0.0
    ml_ms: float = 0.0
    cache_hit: int = 0
    cache_miss: int = 0
    ml_bypass_count: int = 0
    ml_bypass_rate: float = 0.0
    fallback_count: int = 0
    fallback_reasons: dict = field(default_factory=dict)
    gh_calls: int = 0
    k_used: int = 0
    adaptive_k_trace: list = field(default_factory=list)
    batch_size: int = 0

    def as_dict(self) -> dict:
        return {
            "feature_ms": round(self.feature_ms, 3),
            "ml_inference_ms": round(self.ml_ms, 3),
            "cache_hit": self.cache_hit,
            "cache_miss": self.cache_miss,
            "cache_hit_rate": (self.cache_hit / (self.cache_hit + self.cache_miss)
                               if (self.cache_hit + self.cache_miss) else None),
            "ml_bypass_count": self.ml_bypass_count,
            "ml_bypass_rate": self.ml_bypass_rate,
            "fallback_count": self.fallback_count,
            "fallback_reasons": dict(self.fallback_reasons),
            "gh_calls": self.gh_calls,
            "k_used": self.k_used,
            "batch_size": self.batch_size,
        }


class MLWorthwhileGate:
    """expected_saved_GH_cost <= ML_cost → 直接 Full GH，不调 ML。

    规模自适应（针对县域单批 ≤3 车/≤25 单）：
    候选数 <= SMALL_POOL_THRESHOLD 时 ML 排序收益为负（特征+推理开销 > 省下的 GH 调用），
    直接走廉价业务序启发式，纯启发式几秒就能搜完。
    """

    # 县域规模下 ML 无收益的候选池阈值（benchmark 标定：12 个 via 以内纯启发式已足够）
    SMALL_POOL_THRESHOLD = 12

    def __init__(self, ml_cost_ms: float = 0.3, gh_cost_ms_per_call: float = 15.0):
        self.ml_cost_ms = ml_cost_ms
        self.gh_cost_ms_per_call = gh_cost_ms_per_call

    def worth(self, n_cands: int, expected_keep: int) -> tuple[bool, float, float]:
        # 小池直接不调 ML：分支数撑不起排序收益（规模不匹配）
        if n_cands <= self.SMALL_POOL_THRESHOLD:
            est_ml = self.ml_cost_ms + 0.02 * n_cands
            est_saved = self.gh_cost_ms_per_call * max(0, n_cands - expected_keep)
            return False, est_saved, est_ml
        # 一次 batch ML vs 预计省下的 (n-keep) 次 GH
        est_ml = self.ml_cost_ms + 0.02 * n_cands
        est_saved = self.gh_cost_ms_per_call * max(0, n_cands - expected_keep)
        return est_saved > est_ml, est_saved, est_ml


class RankerCapability:
    """排序 / 削减能力解耦（训练不完整时只降级削减，不废掉排序）。

    - ranking：只重排探索顺序，有 Quality Fuse + expand-K 兜底，recall 要求可放宽
    - pruning：硬砍候选，必须过 0.99 安全门，否则保持关闭
    """

    RANK_MIN_RECALL = 0.80
    PRUNE_MIN_RECALL = 0.99

    @classmethod
    def from_metrics(cls, metrics: dict | None) -> "RankerCapability":
        m = metrics or {}
        best_rec = (
            m.get("best_candidate_recall")
            or m.get("best_candidate_recall_20")
            or m.get("ranking_recall", {}).get("20")
        )
        feas_rec = m.get("feasible_candidate_recall") or m.get("overall_feasible_candidate_recall")
        # 取两者较小值作为能力下限
        rec = None
        if best_rec is not None and feas_rec is not None:
            rec = min(float(best_rec), float(feas_rec))
        elif best_rec is not None:
            rec = float(best_rec)
        elif feas_rec is not None:
            rec = float(feas_rec)
        if rec is None:
            return cls(False, False, rec)
        return cls(rec >= cls.RANK_MIN_RECALL, rec >= cls.PRUNE_MIN_RECALL, rec)

    def __init__(self, can_rank: bool, can_prune: bool, recall: float | None):
        self.can_rank = can_rank
        self.can_prune = can_prune
        self.recall = recall

    def as_dict(self) -> dict:
        return {"can_rank": self.can_rank, "can_prune": self.can_prune, "recall": self.recall}


def business_heuristic_scores(X: np.ndarray) -> np.ndarray:
    """无 ML 时的业务序启发式评分（越高越好）。

    对齐 ObjectiveVector 业务优先级（lexicographic）：
    绕行/里程小 > 顺路（heading 小）> 时长短。保证纯启发式搜索顺序也贴合
    「少绕行、少耽误乘客」的客货邮业务目标，而不是只看 detour 一维。
    """
    if X.size == 0:
        return np.zeros(0)
    # 特征列：0=candidate_distance, 5=heading_difference, 9=detour_estimate
    detour = X[:, 9]
    dist = X[:, 0]
    heading = X[:, 5]
    # 归一化后加权：绕行最重（业务硬约束），其次总里程，再次顺路程度
    def _norm(v):
        rng = float(v.max() - v.min())
        return (v - v.min()) / rng if rng > 1e-9 else np.zeros_like(v)
    score = 1.0 * (1.0 - _norm(detour)) + 0.5 * (1.0 - _norm(dist)) + 0.3 * (1.0 - _norm(heading))
    return score


class AdaptiveKPolicy:
    """按 score margin / 分布 / OD 距离决定 K；阈值由 benchmark 标定，见 V046 报告。"""

    def __init__(self, candidates=(1, 2, 4, 8), full_k: int | None = None):
        self.candidates = candidates
        self.full_k = full_k

    def pick(self, scores: np.ndarray, n_cands: int, od_distance_m: float,
             *, multi_leg: bool = False, high_value: bool = False) -> tuple[int, str]:
        if n_cands <= 0:
            return 0, "empty"
        s = np.sort(np.asarray(scores, dtype=float))[::-1]
        # margin：top1-top2
        margin = float(s[0] - s[1]) if len(s) >= 2 else float("inf")
        gap2 = float(s[0] - s[min(3, len(s) - 1)]) if len(s) >= 2 else float("inf")
        # 归一化 margin
        rng = float(s[0] - s[-1]) if len(s) >= 2 and s[0] != s[-1] else 1.0
        nm = margin / (rng + 1e-9)

        # 业务加权：关键路径更保守
        conservative = multi_leg or high_value or od_distance_m > 15000

        if n_cands <= 2:
            return n_cands, "small_pool"
        if conservative and nm < 0.55:
            k = 8 if n_cands >= 8 else n_cands
            return k, "conservative_low_margin"
        if nm >= 0.45 and gap2 >= 0.25:
            k = 2 if n_cands >= 2 else n_cands
            return k, "high_confidence"
        if nm >= 0.22:
            k = 4 if n_cands >= 4 else n_cands
            return k, "mid_confidence"
        if nm >= 0.08:
            k = 8 if n_cands >= 8 else n_cands
            return k, "low_confidence"
        return n_cands, "fallback_full"


class GHBranchSelector:
    """真实 via 候选 → 廉价特征 → batch ML → Adaptive-K → GH → 质量保险丝。"""

    def __init__(
        self,
        ranker=None,
        *,
        profile: str = "bus",
        region_id: str = "chongqing_core",
        gate: MLWorthwhileGate | None = None,
        policy: AdaptiveKPolicy | None = None,
        feature_cache: dict | None = None,
    ):
        self.ranker = ranker
        self.profile = profile
        self.region_id = region_id
        self.gate = gate or MLWorthwhileGate()
        self.policy = policy or AdaptiveKPolicy()
        self.feature_cache = feature_cache if feature_cache is not None else {}
        self.stats = SelectorStats()

    def build_features(self, vias, origin, dest) -> np.ndarray:
        t0 = time.perf_counter()
        rows = []
        for w in vias:
            key = (round(w[0], 5), round(w[1], 5), round(origin[0], 5), round(origin[1], 5),
                   round(dest[0], 5), round(dest[1], 5), self.region_id, self.profile)
            hit = self.feature_cache.get(key)
            if hit is not None:
                self.stats.cache_hit += 1
                rows.append(hit)
            else:
                self.stats.cache_miss += 1
                row = candidate_feature_row(w, origin, dest, region_id=self.region_id, profile=self.profile)
                self.feature_cache[key] = row
                rows.append(row)
        self.stats.feature_ms += (time.perf_counter() - t0) * 1000
        self.stats.batch_size = len(rows)
        return np.vstack(rows) if rows else np.zeros((0, len(CANDIDATE_FEATURES)))

    def rank_batch(self, X: np.ndarray, bypass: bool = False) -> np.ndarray:
        n = X.shape[0]
        if bypass or self.ranker is None or getattr(self.ranker, "fallback", True):
            self.stats.ml_bypass_count += 1
            # 无模型时走业务序启发式（绕行/里程/顺路），而非单维 detour
            return business_heuristic_scores(X)
        # 排序/削减解耦：只允许 ranking 的模型不做硬砍，但仍可重排
        cap = getattr(self.ranker, "capability", None)
        if cap is not None and not getattr(cap, "can_rank", True):
            self.stats.ml_bypass_count += 1
            self.stats.fallback_reasons["rank_gate_blocked"] = self.stats.fallback_reasons.get("rank_gate_blocked", 0) + 1
            return business_heuristic_scores(X)
        t0 = time.perf_counter()
        try:
            scores = np.asarray(self.ranker.predict(X), dtype=float).reshape(-1)
        except Exception:
            self.stats.fallback_count += 1
            self.stats.fallback_reasons["predict_error"] = self.stats.fallback_reasons.get("predict_error", 0) + 1
            scores = business_heuristic_scores(X)
        self.stats.ml_ms += (time.perf_counter() - t0) * 1000
        return scores

    def select_and_route(
        self,
        gh,
        origin,
        dest,
        vias: list,
        *,
        mode: str = "adaptive",  # full|top1|top2|top4|top8|adaptive
        multi_leg: bool = False,
        high_value: bool = False,
        quality_margin: float = 0.12,
    ) -> dict:
        n = len(vias)
        self.stats.ml_bypass_rate = 0.0
        base_wall_ms = 0.0
        t_all = time.perf_counter()

        # Full GH baseline（永远测，Grounded Runtime Baseline）
        full_cands: list[BranchCandidate] = []
        full_wall = 0.0
        for w in vias:
            t0 = time.perf_counter()
            r = gh.route(origin, dest, (w,), profile=self.profile)
            dt = (time.perf_counter() - t0) * 1000
            full_wall += dt
            self.stats.gh_calls += 1
            c = BranchCandidate(via=w, features=np.zeros(len(CANDIDATE_FEATURES)))
            if r.available:
                c.feasible = True
                c.gh_distance_m = r.distance_m
                c.gh_duration_s = r.duration_s
            c.gh_wall_ms = dt
            full_cands.append(c)
        base_wall_ms = full_wall
        full_feasible = [c for c in full_cands if c.feasible]
        full_best_dist = min((c.gh_distance_m for c in full_feasible), default=None)
        full_best_dur = min((c.gh_duration_s for c in full_feasible), default=None)

        # 候选路径选择
        X = self.build_features(vias, origin, dest)
        od_dist = haversine_m(origin, dest)
        # worthwhile gate
        expect_keep = {"top1": 1, "top2": 2, "top4": 4, "top8": 8, "full": n, "adaptive": 4}.get(mode, 4)
        worth, est_saved, est_ml = self.gate.worth(n, expect_keep)
        if mode == "full":
            k, k_reason = n, "mode_full"
            scores = self.rank_batch(X, bypass=True)
            bypass = True
        else:
            bypass = (not worth) or mode in ("top1", "top2", "top4", "top8")
            # fixed modes still rank (cheap) then cut; bypass only when gate says full
            if mode == "adaptive" and not worth:
                self.stats.ml_bypass_count += 1
                scores = self.rank_batch(X, bypass=True)
                k, k_reason = n, "worthwhile_gate_full"
                bypass = True
            else:
                scores = self.rank_batch(X, bypass=False)
                if mode == "adaptive":
                    k, k_reason = self.policy.pick(scores, n, od_dist, multi_leg=multi_leg, high_value=high_value)
                elif mode == "top1":
                    k, k_reason = 1, "fixed_top1"
                elif mode == "top2":
                    k, k_reason = 2, "fixed_top2"
                elif mode == "top4":
                    k, k_reason = 4, "fixed_top4"
                elif mode == "top8":
                    k, k_reason = 8, "fixed_top8"
                else:
                    k, k_reason = n, "fallback_full_mode"
                k = max(1, min(k, n))
                bypass = False
        self.stats.ml_bypass_rate = self.stats.ml_bypass_count / 1.0

        order = list(np.argsort(-scores, kind="stable"))
        chosen_idx = order[:k]
        # 质量保险丝预检：margin 太小 → 扩 K
        if mode == "adaptive" and not bypass and len(order) >= 2:
            s_sorted = scores[order]
            margin = float(s_sorted[0] - s_sorted[min(1, len(s_sorted) - 1)])
            if margin < 0.02 and k < n:
                k = min(n, max(k, 8))
                chosen_idx = order[:k]
                k_reason = k_reason + "+margin_expand"
                self.stats.fallback_count += 1
                self.stats.fallback_reasons["margin_expand"] = self.stats.fallback_reasons.get("margin_expand", 0) + 1

        # 对 top-k 调 GH（若 full 模式则直接用 full_cands）
        sel_wall = 0.0
        selected: list[BranchCandidate] = []
        if mode == "full" or (bypass and k >= n):
            selected = full_cands
            sel_wall = full_wall
        else:
            for i in chosen_idx:
                w = vias[i]
                # 复用 full 结果（同 via 已测过）— 真实生产会重打 GH；为公平计时我们重打并计入
                t0 = time.perf_counter()
                r = gh.route(origin, dest, (w,), profile=self.profile)
                dt = (time.perf_counter() - t0) * 1000
                sel_wall += dt
                self.stats.gh_calls += 1
                c = BranchCandidate(via=w, features=X[i], score=float(scores[i]))
                if r.available:
                    c.feasible = True
                    c.gh_distance_m = r.distance_m
                    c.gh_duration_s = r.duration_s
                c.gh_wall_ms = dt
                selected.append(c)

        self.stats.k_used = k
        sel_feasible = [c for c in selected if c.feasible]
        sel_best_dist = min((c.gh_distance_m for c in sel_feasible), default=None)
        sel_best_dur = min((c.gh_duration_s for c in sel_feasible), default=None)

        # 质量保险丝：与 full-GH 最优比，异常则扩 K
        fused = False
        fuse_reason = None
        if mode != "full" and full_best_dist and sel_best_dist:
            reg = (sel_best_dist - full_best_dist) / full_best_dist
            if reg > quality_margin and k < n:
                fused = True
                fuse_reason = "distance_regret_expand"
                # 扩到 next ladder
                ladder = [x for x in (1, 2, 4, 8, n) if x > k]
                new_k = ladder[0] if ladder else n
                for i in order[k:new_k]:
                    w = vias[i]
                    t0 = time.perf_counter()
                    r = gh.route(origin, dest, (w,), profile=self.profile)
                    dt = (time.perf_counter() - t0) * 1000
                    sel_wall += dt
                    self.stats.gh_calls += 1
                    c = BranchCandidate(via=w, features=X[i], score=float(scores[i]))
                    if r.available:
                        c.feasible = True
                        c.gh_distance_m = r.distance_m
                        c.gh_duration_s = r.duration_s
                    c.gh_wall_ms = dt
                    selected.append(c)
                k = new_k
                sel_feasible = [c for c in selected if c.feasible]
                sel_best_dist = min((c.gh_distance_m for c in sel_feasible), default=None)
                sel_best_dur = min((c.gh_duration_s for c in sel_feasible), default=None)
                self.stats.fallback_count += 1
                self.stats.fallback_reasons[fuse_reason] = self.stats.fallback_reasons.get(fuse_reason, 0) + 1
        if not sel_feasible:
            self.stats.fallback_count += 1
            self.stats.fallback_reasons["NO_FEASIBLE_ROUTE"] = self.stats.fallback_reasons.get("NO_FEASIBLE_ROUTE", 0) + 1
            fuse_reason = fuse_reason or "NO_FEASIBLE_ROUTE"

        total_wall = time.perf_counter() - t_all
        dist_reg = None
        dur_reg = None
        if full_best_dist and sel_best_dist:
            dist_reg = max(0.0, (sel_best_dist - full_best_dist) / full_best_dist)
        if full_best_dur and sel_best_dur:
            dur_reg = max(0.0, (sel_best_dur - full_best_dur) / full_best_dur)

        return {
            "mode": mode,
            "n_candidates": n,
            "k": k,
            "k_reason": k_reason,
            "gh_calls_baseline": n,
            "gh_calls_candidate": len(selected) if mode != "full" else n,
            "gh_calls_total_incl_baseline": self.stats.gh_calls,
            "baseline_wall_ms": base_wall_ms,
            "candidate_wall_ms": sel_wall + self.stats.feature_ms + self.stats.ml_ms,
            "candidate_gh_wall_ms": sel_wall,
            "absolute_saved_ms": (base_wall_ms) - (sel_wall + self.stats.feature_ms + self.stats.ml_ms),
            "wall_reduction": (
                1 - (sel_wall + self.stats.feature_ms + self.stats.ml_ms) / base_wall_ms
                if base_wall_ms > 0 else None
            ),
            "full_best_distance_m": full_best_dist,
            "full_best_duration_s": full_best_dur,
            "selected_best_distance_m": sel_best_dist,
            "selected_best_duration_s": sel_best_dur,
            "distance_regret": dist_reg,
            "duration_regret": dur_reg,
            "feasible_preservation": (
                1.0 if (full_best_dist and sel_best_dist and abs(sel_best_dist - full_best_dist) < 1e-6)
                or (full_best_dist is None and sel_best_dist is None)
                else (1.0 if sel_best_dist is not None and full_best_dist is not None and dist_reg is not None and dist_reg < 1e-9 else 0.0)
            ),
            "full_gh_best_miss": bool(full_best_dist and sel_best_dist and dist_reg is not None and dist_reg > 1e-9),
            "fallback": fused or self.stats.fallback_count > 0,
            "fallback_reason": fuse_reason,
            "scores_top": [float(scores[i]) for i in order[: min(8, len(order))]],
            "selected_vias_rank": chosen_idx[:8],
            "stats": self.stats.as_dict(),
            "total_wall_ms": total_wall * 1000,
            "ml_worthwhile": worth,
            "est_saved_ms": est_saved,
            "est_ml_ms": est_ml,
        }
