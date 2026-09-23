"""ML Experiment Watchdog：泄漏审计 / 过欠拟合 / 稳健性 / 异常完美检测。

禁止固定数值目标驱动调参。状态只输出：
HEALTHY / CAUTION / OVERFIT_RISK / UNDERFIT_RISK / LEAKAGE_RISK /
ROBUSTNESS_RISK / DATA_LIMITED / STATISTICALLY_WEAK / NOT_READY / ACTIVE / FAILED
"""

from __future__ import annotations

import json
import math
import time
import uuid
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Sequence

import numpy as np

# 决策时刻不可用 → 禁止进 X
FORBIDDEN_FEATURES = {
    "teacher_rank", "teacher_top", "final_objective", "future_objective",
    "future_execution", "became_best", "accepted", "actual_gps_after_decision",
    "future_amap_result", "post_route_outcome", "final_solution",
}


@dataclass
class FeatureLeakageRow:
    feature: str
    source: str
    available_at_decision_time: bool
    allowed: bool


class FeatureLeakageAudit:
    def audit(self, feature_names: Sequence[str]) -> list[FeatureLeakageRow]:
        rows = []
        for f in feature_names:
            banned = f.lower() in FORBIDDEN_FEATURES or any(
                b in f.lower() for b in ("future_", "final_", "teacher_rank", "teacher_top")
            )
            rows.append(FeatureLeakageRow(
                feature=f,
                source="decision_time" if not banned else "FORBIDDEN",
                available_at_decision_time=not banned,
                allowed=not banned,
            ))
        return rows

    def has_leakage(self, feature_names: Sequence[str]) -> bool:
        return any(not r.allowed for r in self.audit(feature_names))


@dataclass
class PerfectScoreAudit:
    test_groups: int
    avg_group_size: float
    leakage: bool
    overlap: bool
    duplicated_candidates: bool
    suspicious: bool
    reasons: list[str] = field(default_factory=list)


class PerfectScoreAuditor:
    """指标异常完美时启动，禁止直接判 EXCELLENT。"""

    def audit(
        self,
        *,
        metric_value: float | None,
        test_groups: int,
        avg_group_size: float,
        feature_names: Sequence[str],
        train_keys: set,
        test_keys: set,
        candidate_ids: Sequence[str] | None = None,
    ) -> PerfectScoreAudit:
        reasons: list[str] = []
        if metric_value is not None and metric_value >= 0.999:
            reasons.append("near_perfect_metric")
        if test_groups < 30:
            reasons.append(f"test_groups_low:{test_groups}")
        if avg_group_size < 15:
            reasons.append(f"group_size_small:{avg_group_size:.1f}")
        leakage = FeatureLeakageAudit().has_leakage(feature_names)
        if leakage:
            reasons.append("feature_leakage")
        overlap = bool(train_keys & test_keys)
        if overlap:
            reasons.append("train_test_overlap")
        dup = False
        if candidate_ids is not None:
            dup = len(candidate_ids) != len(set(candidate_ids))
            if dup:
                reasons.append("candidate_duplication")
        suspicious = bool(reasons)
        return PerfectScoreAudit(
            test_groups=test_groups,
            avg_group_size=avg_group_size,
            leakage=leakage,
            overlap=overlap,
            duplicated_candidates=dup,
            suspicious=suspicious,
            reasons=reasons,
        )


@dataclass
class HealthReport:
    experiment_id: str
    overall_status: str
    data_quality: dict = field(default_factory=dict)
    leakage_status: str = "OK"
    train_validation_gap: float = 0.0
    test_generalization: dict = field(default_factory=dict)
    seed_stability: dict = field(default_factory=dict)
    scenario_stability: dict = field(default_factory=dict)
    region_stability: dict = field(default_factory=dict)
    feature_stability: dict = field(default_factory=dict)
    robustness_status: dict = field(default_factory=dict)
    reduction_safety: dict = field(default_factory=dict)
    objective_status: dict = field(default_factory=dict)
    feasibility_status: dict = field(default_factory=dict)
    runtime_status: dict = field(default_factory=dict)
    model_complexity: dict = field(default_factory=dict)
    notes: list[str] = field(default_factory=list)

    def conclusion(self) -> str:
        return self.overall_status


class OverfittingDetector:
    def check(self, train_metric: float, val_metric: float, history: Sequence[tuple[float, float]] | None = None) -> str:
        gap = train_metric - val_metric
        degrading = False
        if history and len(history) >= 2:
            degrading = history[-1][1] < history[-2][1] - 1e-3 and history[-1][0] > history[-2][0]
        if degrading or gap > 0.15:
            return "OVERFIT_RISK"
        return "OK"


class UnderfittingDetector:
    def check(self, train_metric: float, val_metric: float) -> str:
        if train_metric < 0.55 and val_metric < 0.55:
            return "UNDERFIT_RISK"
        return "OK"


class ComplexityAnalyzer:
    def analyze(self, *, num_trees: int, leaves: int, depth: int, train_metric: float,
                val_metric: float, prev_val: float | None = None) -> dict:
        over = False
        if prev_val is not None and val_metric <= prev_val + 1e-4 and num_trees > 200:
            over = True
        return {
            "num_trees": num_trees,
            "leaves": leaves,
            "depth": depth,
            "train_metric": train_metric,
            "val_metric": val_metric,
            "over_complex": over or (train_metric - val_metric > 0.2),
        }


class ExperimentController:
    """有限实验循环：Baseline → 小改 → 评估 → 接受/拒绝；连续无改善 EARLY_STOP。"""

    def __init__(self, health_dir: str | Path = "algorithm/learning/training/health"):
        self.health_dir = Path(health_dir)
        self.health_dir.mkdir(parents=True, exist_ok=True)
        self.experiment_id = f"exp-{time.strftime('%Y%m%d-%H%M%S')}-{uuid.uuid4().hex[:6]}"
        self.no_improve_streak = 0

    def record(self, report: HealthReport) -> Path:
        p = self.health_dir / f"experiment_{self.experiment_id}.json"
        p.write_text(json.dumps(asdict(report), indent=2, default=str), encoding="utf-8")
        md = self.health_dir / f"experiment_{self.experiment_id}.md"
        md.write_text(self._to_md(report), encoding="utf-8")
        return p

    def _to_md(self, r: HealthReport) -> str:
        return (
            f"# Experiment {r.experiment_id}\n\n"
            f"**Overall: {r.overall_status}**\n\n"
            f"- leakage: {r.leakage_status}\n"
            f"- train/val gap: {r.train_validation_gap:.4f}\n"
            f"- seed_stability: {r.seed_stability}\n"
            f"- scenario_stability: {r.scenario_stability}\n"
            f"- reduction_safety: {r.reduction_safety}\n"
            f"- notes: {r.notes}\n"
        )

    def accept_or_reject(self, val_metric: float, best_val: float) -> bool:
        if val_metric > best_val + 1e-4:
            self.no_improve_streak = 0
            return True
        self.no_improve_streak += 1
        return False

    def should_early_stop(self, max_streak: int = 3) -> bool:
        return self.no_improve_streak >= max_streak


def seed_stability(values: Sequence[float]) -> dict:
    if not values:
        return {"mean": None, "std": None, "min": None, "max": None, "sensitive": False}
    arr = np.asarray(values, dtype=float)
    std = float(np.std(arr))
    return {
        "mean": float(np.mean(arr)),
        "std": std,
        "min": float(np.min(arr)),
        "max": float(np.max(arr)),
        "sensitive": std > 0.08 or (float(np.max(arr)) - float(np.min(arr))) > 0.15,
    }


def classify_health(
    *,
    leakage: bool,
    overfit: str,
    underfit: str,
    test_groups: int,
    seed_sens: bool,
    perfect_suspicious: bool,
    reduction_safe: bool,
) -> str:
    if leakage:
        return "LEAKAGE_RISK"
    if perfect_suspicious:
        return "STATISTICALLY_WEAK"
    if test_groups < 20:
        return "DATA_LIMITED"
    if overfit == "OVERFIT_RISK":
        return "OVERFIT_RISK"
    if underfit == "UNDERFIT_RISK":
        return "UNDERFIT_RISK"
    if seed_sens:
        return "ROBUSTNESS_RISK"
    if not reduction_safe:
        return "CAUTION"
    return "HEALTHY"
