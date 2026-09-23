"""DynamicSearchSpaceReducer：生产路径禁止 Teacher 信息。

production reduce() 只能用决策时刻已知信息：
candidate_ids / X / scores / protected_ids（业务规则）/ policy_context。

teacher_top_id / feasible_ids 只允许出现在 offline evaluator。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Sequence

import numpy as np

from .train_candidate_ranker import CandidateRanker


@dataclass
class ReductionResult:
    kept_ids: list[str]
    dropped_ids: list[str]
    reduction_ratio: float
    protected_ids: list[str] = field(default_factory=list)
    low_confidence_disabled: bool = False


@dataclass
class OfflineReductionMetrics:
    """仅 offline evaluation：用 Teacher GT 评估 reduced set 质量。"""

    reduction_recall: dict[int, float] = field(default_factory=dict)  # K -> recall
    best_candidate_recall: dict[int, float] = field(default_factory=dict)
    best_feasible_candidate_recall: dict[int, float] = field(default_factory=dict)
    overall_feasible_candidate_recall: float = 0.0
    total_candidates: int = 0
    total_kept: int = 0
    total_dropped: int = 0
    weighted_reduction: float = 0.0
    mean_reduction: float = 0.0
    median_reduction: float = 0.0
    p90_reduction: float = 0.0


class DynamicSearchSpaceReducer:
    """20–30% 削减；安全门在 offline evaluator，不在 production path。"""

    def __init__(
        self,
        ranker: CandidateRanker | None = None,
        *,
        target_ratio: float = 0.25,
        min_ratio: float = 0.15,
        max_ratio: float = 0.30,
    ):
        self.ranker = ranker
        self.target_ratio = target_ratio
        self.min_ratio = min_ratio
        self.max_ratio = max_ratio

    def reduce(
        self,
        candidate_ids: Sequence[str],
        X: np.ndarray,
        *,
        scores: np.ndarray | None = None,
        protected_ids: Sequence[str] | None = None,
        policy_context: dict | None = None,
    ) -> ReductionResult:
        """生产接口：禁止 teacher_top_id / feasible_ids。

        protected_ids 只能来自当前已知业务信息：
        highValue / urgent / uniqueFeasibleByHardPrecheck / multiLeg /
        lowConfidence / modelDisagreement / newRoute / newStation。
        """
        n = len(candidate_ids)
        if n == 0:
            return ReductionResult([], [], 0.0)
        # group 太小不 reduction
        if n < 12:
            return ReductionResult(list(candidate_ids), [], 0.0, sorted(protected_ids or []))

        protected: set[str] = set(protected_ids or [])
        policy_context = policy_context or {}
        for key in (
            "highValueIds", "urgentIds", "uniqueFeasibleIds", "multiLegIds",
            "lowConfidenceIds", "modelDisagreementIds", "newRouteIds", "newStationIds",
        ):
            protected.update(policy_context.get(key) or [])

        if self.ranker is None or self.ranker.fallback_mode:
            return ReductionResult(list(candidate_ids), [], 0.0, sorted(protected))

        if scores is None:
            scores = self.ranker.predict(X)

        # 低置信度保护：分数过近或 top-2 差距过小
        if n > 2:
            std = float(np.std(scores))
            top2_gap = float(np.sort(scores)[-1] - np.sort(scores)[-2]) if n >= 2 else 0.0
            if std < 1e-6 or abs(top2_gap) < 1e-6:
                return ReductionResult(
                    list(candidate_ids), [], 0.0, sorted(protected),
                    low_confidence_disabled=True,
                )

        # per-call effective ratio（不修改全局 target_ratio，保证可复现）
        ratio = min(self.max_ratio, max(self.min_ratio, self.target_ratio))
        order = list(np.argsort(-scores, kind="stable"))
        n_drop = int(round(n * ratio))
        keep_count = n - n_drop

        kept_set: set[str] = set(protected)
        for idx in order:
            if len(kept_set) >= keep_count:
                break
            kept_set.add(candidate_ids[idx])

        kept_ids = [cid for cid in candidate_ids if cid in kept_set]
        dropped_ids = [cid for cid in candidate_ids if cid not in kept_set]
        return ReductionResult(
            kept_ids, dropped_ids,
            len(dropped_ids) / n,
            sorted(protected),
        )


def offline_reduction_metrics(
    groups: Sequence[tuple[list[str], np.ndarray | None, list[str], list[str], dict[str, int]]],
    reducer: DynamicSearchSpaceReducer,
    k_list: Sequence[int] = (1, 3, 5, 10),
) -> OfflineReductionMetrics:
    """offline：对每个 test group 跑 reducer，再用 Teacher GT 算 Reduction Recall。

    groups 元素：(candidate_ids, X, teacher_top_k_ids_by_rank, feasible_ids, rank_by_id)
    teacher_top_k_ids_by_rank：按 ObjectiveVector 排序的候选 id 列表
    """
    out = OfflineReductionMetrics()
    reductions: list[float] = []
    rec_sum = {k: 0.0 for k in k_list}
    best_sum = {k: 0.0 for k in k_list}
    best_feas_sum = {k: 0.0 for k in k_list}
    n_used = {k: 0 for k in k_list}
    n_best = {k: 0 for k in k_list}
    n_best_feas = {k: 0 for k in k_list}
    feas_num = feas_den = 0

    for cids, X, teacher_order, feas_ids, _ranks in groups:
        n = len(cids)
        if n == 0:
            continue
        result = reducer.reduce(cids, X)
        kept = set(result.kept_ids)
        out.total_candidates += n
        out.total_kept += len(kept)
        out.total_dropped += len(result.dropped_ids)
        reductions.append(result.reduction_ratio)

        for k in k_list:
            if k >= n:
                continue  # N/A
            teacher_topk = teacher_order[:k]
            inter = sum(1 for t in teacher_topk if t in kept)
            rec_sum[k] += inter / k
            n_used[k] += 1
            # Best Candidate Recall@K
            n_best[k] += 1
            if teacher_order and teacher_order[0] in kept:
                best_sum[k] += 1.0
            # Best Feasible：排除无可行解 group
            if feas_ids:
                n_best_feas[k] += 1
                if feas_ids[0] in kept:
                    best_feas_sum[k] += 1.0

        if feas_ids:
            feas_den += 1
            if any(f in kept for f in feas_ids):
                feas_num += 1

    out.weighted_reduction = (
        out.total_dropped / out.total_candidates if out.total_candidates else 0.0
    )
    if reductions:
        out.mean_reduction = float(np.mean(reductions))
        out.median_reduction = float(np.median(reductions))
        out.p90_reduction = float(np.percentile(reductions, 90))
    out.reduction_recall = {
        k: (rec_sum[k] / n_used[k] if n_used[k] else None) for k in k_list
    }
    out.best_candidate_recall = {
        k: (best_sum[k] / n_best[k] if n_best[k] else None) for k in k_list
    }
    out.best_feasible_candidate_recall = {
        k: (best_feas_sum[k] / n_best_feas[k] if n_best_feas[k] else None) for k in k_list
    }
    out.overall_feasible_candidate_recall = (
        feas_num / feas_den if feas_den else 0.0
    )
    return out
