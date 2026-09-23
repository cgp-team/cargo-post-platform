"""TrainingDataQualityChecker：real_route_required + leakage + FeatureQualityReport。"""

from __future__ import annotations

import json
import math
from dataclasses import dataclass, field
from pathlib import Path
from typing import Sequence

import numpy as np

from .search_trace_recorder import FEATURE_NAMES, SearchTraceSample

FORMAL_PROVIDERS = {"local_graph", "graphhopper", "amap", "cached_real"}
LEAKAGE_KEYS = {
    "final_objective", "future_accepted", "future_became_best",
    "future_execution", "future_gps", "future_amap_result",
}


@dataclass
class QualityReport:
    total: int = 0
    ok: int = 0
    bad: int = 0
    estimated_rejected: int = 0
    uncertain_rejected: int = 0
    duplicates: int = 0
    leakage_rejected: int = 0
    graph_version_mismatch: int = 0
    reasons: dict[str, int] = field(default_factory=dict)


@dataclass
class FeatureQualityReport:
    feature: str
    non_null_rate: float
    non_zero_rate: float
    variance: float
    unique_count: int
    source: str
    uninformative: bool


class TrainingDataQualityChecker:
    def check_one(self, s: SearchTraceSample) -> str | None:
        if not s.features:
            return "missing_features"
        for k in LEAKAGE_KEYS:
            if k in s.features:
                return "feature_leakage"
        for _k, v in s.features.items():
            if v is None:
                return "null_feature"
            if isinstance(v, float) and (math.isnan(v) or math.isinf(v)):
                return "nan_or_inf"
        if s.total_distance <= 0 and s.feasible:
            return "nonpositive_distance"
        if s.total_duration < 0:
            return "negative_metric"
        if s.infeasibility < 0:
            return "invalid_objective"
        if not s.graph_version or not s.route_version:
            return "missing_version"
        # real route required
        if s.route_provider not in FORMAL_PROVIDERS:
            return "estimated_or_uncertain_route"
        return None

    def filter(
        self,
        samples: Sequence[SearchTraceSample],
        quarantine_dir: str | Path,
        *,
        expected_graph_version: str | None = None,
    ) -> tuple[list[SearchTraceSample], QualityReport]:
        qdir = Path(quarantine_dir)
        qdir.mkdir(parents=True, exist_ok=True)
        good: list[SearchTraceSample] = []
        bad: list[SearchTraceSample] = []
        rep = QualityReport(total=len(samples))
        seen: set[str] = set()
        for s in samples:
            reason = self.check_one(s)
            if reason is None and expected_graph_version and s.graph_version != expected_graph_version:
                reason = "graph_version_mismatch"
                rep.graph_version_mismatch += 1
            key = f"{s.search_state_id}|{s.candidate_id}"
            if reason is None and key in seen:
                reason = "duplicate"
                rep.duplicates += 1
            if reason is None:
                seen.add(key)
                good.append(s)
                rep.ok += 1
            else:
                bad.append(s)
                rep.bad += 1
                rep.reasons[reason] = rep.reasons.get(reason, 0) + 1
                if reason == "estimated_or_uncertain_route":
                    rep.uncertain_rejected += 1
        if bad:
            self.dump_quarantine(bad, qdir / "quarantine.jsonl")
        return good, rep

    def feature_quality(self, samples: Sequence[SearchTraceSample]) -> list[FeatureQualityReport]:
        out: list[FeatureQualityReport] = []
        n = max(1, len(samples))
        for f in FEATURE_NAMES:
            vals = np.array([s.features.get(f, 0.0) for s in samples], dtype=float)
            non_null = float(np.mean(~np.isnan(vals)))
            non_zero = float(np.mean(np.abs(vals) > 1e-12))
            var = float(np.var(vals))
            uniq = len(set(vals.tolist()))
            uninf = non_zero < 0.01 or uniq <= 1 or var < 1e-12
            out.append(FeatureQualityReport(
                feature=f, non_null_rate=non_null, non_zero_rate=non_zero,
                variance=var, unique_count=uniq, source="decision_time",
                uninformative=uninf,
            ))
        return out

    @staticmethod
    def dump_quarantine(bad: Sequence[SearchTraceSample], path: str | Path) -> None:
        p = Path(path)
        p.parent.mkdir(parents=True, exist_ok=True)
        with p.open("w", encoding="utf-8") as f:
            for s in bad:
                f.write(json.dumps(s.__dict__, ensure_ascii=False, default=str) + "\n")
