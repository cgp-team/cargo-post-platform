"""CandidateRanker：LightGBM LambdaRank；动态 K；四类 Recall 严格分离。"""

from __future__ import annotations

import json
import os
import platform
import time
from pathlib import Path
from typing import Any, Sequence

import numpy as np

from .build_ranking_dataset import RankingDatasetBuilder, RankingGroup
from .search_trace_recorder import FEATURE_NAMES

K_LIST_DEFAULT = (1, 3, 5, 10, 20, 50)


class CandidateRanker:
    def __init__(self, feature_names: Sequence[str] = FEATURE_NAMES):
        self.feature_names = list(feature_names)
        self.model: Any = None
        self.fallback_mode = True
        self.model_version = "none"
        self.metadata: dict[str, Any] = {}

    def train(
        self,
        train_groups: Sequence[RankingGroup],
        val_groups: Sequence[RankingGroup] | None = None,
        *,
        objective: str = "lambdarank",
        n_estimators: int = 200,
        learning_rate: float = 0.05,
        num_leaves: int = 31,
        max_depth: int = 8,
        min_data_in_leaf: int = 20,
        early_stopping_rounds: int = 20,
        seed: int = 20260921,
    ) -> dict[str, Any]:
        import lightgbm as lgb

        builder = RankingDatasetBuilder(self.feature_names)
        dtrain = builder.to_lgb_dataset(train_groups)
        params = {
            "objective": objective,
            "metric": ["ndcg", "map"],
            "ndcg_eval_at": [1, 3, 5, 10],
            "num_leaves": num_leaves,
            "max_depth": max_depth,
            "min_data_in_leaf": min_data_in_leaf,
            "learning_rate": learning_rate,
            "verbosity": -1,
            "n_jobs": max(1, (os.cpu_count() or 2) - 2),
            "seed": seed,
        }
        valid_sets = [dtrain]
        valid_names = ["train"]
        callbacks = []
        if val_groups:
            valid_sets.append(builder.to_lgb_dataset(val_groups))
            valid_names.append("val")
            callbacks.append(lgb.early_stopping(early_stopping_rounds, verbose=False))
        self.model = lgb.train(
            params, dtrain, num_boost_round=n_estimators,
            valid_sets=valid_sets, valid_names=valid_names,
            callbacks=callbacks or None,
        )
        self.fallback_mode = False
        self.model_version = f"lgb-{objective}-{int(time.time())}"
        self.metadata = {
            "model_version": self.model_version,
            "objective": objective,
            "training_time": time.strftime("%Y-%m-%dT%H:%M:%S"),
            "training_cpu": platform.processor(),
            "cpu_count": os.cpu_count(),
            "feature_schema_version": "1",
        }
        return dict(self.model.params or {})

    def predict(self, X: np.ndarray) -> np.ndarray:
        if self.model is None or self.fallback_mode:
            return np.zeros(X.shape[0], dtype=float)
        return np.asarray(self.model.predict(X), dtype=float)

    def evaluate(
        self,
        groups: Sequence[RankingGroup],
        k_list: Sequence[int] = K_LIST_DEFAULT,
    ) -> dict[str, Any]:
        """四类指标严格分离；k >= group_size → None（N/A）。

        1. Ranking Recall@K = |model_topK ∩ teacher_topK| / K
        2. Best Candidate Recall@K
        3. Best Feasible Candidate Recall@K（无可行解 group 排除）
        4. Overall Feasible Candidate Recall（monitoring）
        """
        rank_sum = {k: 0.0 for k in k_list}
        rank_n = {k: 0 for k in k_list}
        best_sum = {k: 0.0 for k in k_list}
        best_n = {k: 0 for k in k_list}
        bfr_sum = {k: 0.0 for k in k_list}
        bfr_n = {k: 0 for k in k_list}
        ndcg_sum = {k: 0.0 for k in k_list}
        ndcg_n = {k: 0 for k in k_list}
        feas_num = feas_den = 0
        n = 0

        for g in groups:
            if g.X is None or len(g.y) == 0:
                continue
            n += 1
            size = len(g.y)
            scores = self.predict(g.X)
            model_order = list(np.argsort(-scores, kind="stable"))
            # Teacher order by objective rank = by y desc (stable)
            teacher_order = list(np.argsort(-g.y, kind="stable"))
            feas_idx = [i for i, y in enumerate(g.y) if y > 0]

            for k in k_list:
                if k >= size:
                    continue  # N/A
                mk = set(model_order[:k])
                tk = teacher_order[:k]
                rank_sum[k] += len(mk & set(tk)) / k
                rank_n[k] += 1
                ndcg_sum[k] += self._ndcg(g.y, scores, k)
                ndcg_n[k] += 1
                best_n[k] += 1
                if teacher_order[0] in mk:
                    best_sum[k] += 1.0
                if feas_idx:
                    bfr_n[k] += 1
                    best_feas = max(feas_idx, key=lambda i: g.y[i])
                    if best_feas in mk:
                        bfr_sum[k] += 1.0

            feas_den += 1
            kept20 = set(model_order[: min(20, size)])
            if any(i in kept20 for i in feas_idx):
                feas_num += 1

        def _avg(s, c):
            return {k: (s[k] / c[k] if c[k] else None) for k in k_list}

        return {
            "groups": n,
            "ranking_recall": _avg(rank_sum, rank_n),
            "best_candidate_recall": _avg(best_sum, best_n),
            "best_feasible_candidate_recall": _avg(bfr_sum, bfr_n),
            "ndcg": _avg(ndcg_sum, ndcg_n),
            "overall_feasible_candidate_recall": (feas_num / feas_den if feas_den else 0.0),
        }

    @staticmethod
    def _ndcg(y_true: np.ndarray, scores: np.ndarray, k: int) -> float:
        order = np.argsort(-scores, kind="stable")[:k]
        gains = np.power(2.0, y_true[order]) - 1.0
        discounts = np.log2(np.arange(2, k + 2))
        dcg = float(np.sum(gains / discounts[: len(gains)]))
        ideal = np.sort(y_true)[::-1][:k]
        igains = np.power(2.0, ideal) - 1.0
        idcg = float(np.sum(igains / discounts[: len(ideal)]))
        return dcg / idcg if idcg > 0 else 0.0

    def save(self, model_dir: str | Path, metadata: dict[str, Any] | None = None) -> None:
        d = Path(model_dir).resolve()
        d.mkdir(parents=True, exist_ok=True)
        if self.model is not None:
            model_path = d / "candidate_ranker.model"
            try:
                self.model.save_model(str(model_path))
            except Exception:
                model_path.write_text(self.model.model_to_string(), encoding="utf-8")
        (d / "feature_schema.json").write_text(
            json.dumps({"feature_names": self.feature_names, "version": "1"}, indent=2),
            encoding="utf-8",
        )
        meta = {**self.metadata, **(metadata or {})}
        (d / "model_metadata.json").write_text(json.dumps(meta, indent=2, default=str), encoding="utf-8")

    @classmethod
    def load(cls, model_dir: str | Path) -> "CandidateRanker":
        d = Path(model_dir)
        schema = json.loads((d / "feature_schema.json").read_text(encoding="utf-8"))
        ranker = cls(schema["feature_names"])
        model_path = d / "candidate_ranker.model"
        if model_path.exists():
            try:
                import lightgbm as lgb

                ranker.model = lgb.Booster(model_file=str(model_path))
                ranker.fallback_mode = False
                ranker.model_version = "loaded"
            except Exception:
                ranker.fallback_mode = True
        return ranker
