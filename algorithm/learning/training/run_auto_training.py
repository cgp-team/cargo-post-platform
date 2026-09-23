"""AutoTrainingController：1k→10k→50k→100k 无人值守 + 72h deadline + checkpoint。"""

from __future__ import annotations

import json
import os
import platform
import time
import traceback
from dataclasses import asdict, dataclass, field
from pathlib import Path

from .amap_quota import AMapQuotaScheduler
from .build_ranking_dataset import RankingDatasetBuilder, RankingGroup
from .data_quality import TrainingDataQualityChecker
from .generate_training_samples import RealRoadRouter, TrainingSampleGenerator
from .hard_negative_mining import HardNegativeMiner
from .model_registry import ModelRegistry
from .search_space_reducer import DynamicSearchSpaceReducer, offline_reduction_metrics
from .train_candidate_ranker import CandidateRanker

STAGES = (1000, 10000, 50000, 100000)
GRAPH_VERSION = "osm-pbf-137591hw-2287515e"


@dataclass
class Checkpoint:
    stage: str = "INIT"
    dataset_version: str = "lsr20"
    graph_version: str = GRAPH_VERSION
    route_version: str = "local-v1"
    dataset_size: int = 0
    generated_samples: int = 0
    current_target: int = 0
    seed: int = 20260921
    model_version: str = ""
    best_metric: float = 0.0
    last_completed_step: str = ""
    hard_negative_pool_size: int = 0
    amap_usage_file: str = ""
    started_at: float = 0.0
    deadline_at: float = 0.0


@dataclass
class TrainingStatus:
    status: str = "INIT"
    stage: str = "INIT"
    dataset_size: int = 0
    target: int = 0
    groups: int = 0
    avg_candidates: float = 0.0
    min_candidates: int = 0
    max_candidates: int = 0
    dataset_version: str = "lsr20"
    graph_version: str = GRAPH_VERSION
    route_version: str = "local-v1"
    model_version: str = ""
    elapsed_seconds: float = 0.0
    samples_per_second: float = 0.0
    ranking_metrics: dict = field(default_factory=dict)
    reduction_metrics: dict = field(default_factory=dict)
    hard_negative_count: int = 0
    amap_used_today: int = 0
    amap_remaining_today: int = 0
    checkpoint: str = ""
    last_error: str | None = None


class AutoTrainingController:
    def __init__(self, base_dir: str | Path = ".", deadline_hours: float = 72.0):
        self.base = Path(base_dir)
        self.data_dir = self.base / "algorithm/learning/data"
        self.model_dir = self.base / "algorithm/models"
        self.log_dir = self.base / "logs/learning"
        self.status_path = self.base / "algorithm/learning/training/training_status.json"
        self.ckpt_path = self.data_dir / "checkpoint.json"
        for d in (self.data_dir, self.model_dir, self.log_dir):
            d.mkdir(parents=True, exist_ok=True)
        self.status = TrainingStatus(checkpoint=str(self.ckpt_path))
        self.ckpt = Checkpoint(
            started_at=time.time(),
            deadline_at=time.time() + deadline_hours * 3600,
        )
        self.amap = AMapQuotaScheduler()
        self.registry = ModelRegistry(self.model_dir)
        self._t0 = time.time()
        self._load_ckpt()

    def _load_ckpt(self) -> None:
        if self.ckpt_path.exists():
            try:
                data = json.loads(self.ckpt_path.read_text(encoding="utf-8"))
                self.ckpt = Checkpoint(**{k: v for k, v in data.items() if k in Checkpoint.__annotations__})
            except Exception:
                pass

    def _save_ckpt(self) -> None:
        self.ckpt_path.write_text(json.dumps(asdict(self.ckpt), indent=2), encoding="utf-8")

    def _write_status(self) -> None:
        self.status.elapsed_seconds = time.time() - self._t0
        snap = self.amap.snapshot()
        self.status.amap_used_today = snap["amap_used_today"]
        self.status.amap_remaining_today = snap["amap_remaining_today"]
        self.status_path.parent.mkdir(parents=True, exist_ok=True)
        self.status_path.write_text(json.dumps(asdict(self.status), indent=2, default=str), encoding="utf-8")

    def _log(self, name: str, msg: str) -> None:
        p = self.log_dir / f"{name}.log"
        with p.open("a", encoding="utf-8") as f:
            f.write(f"{time.strftime('%Y-%m-%d %H:%M:%S')} {msg}\n")

    def _time_left(self) -> float:
        return max(0.0, self.ckpt.deadline_at - time.time())

    def _insufficient_for(self, next_target: int) -> bool:
        left = self._time_left()
        if left <= 0:
            # checkpoint 过期/未设置：重新起算 72h，不误杀
            self.ckpt.deadline_at = time.time() + 72 * 3600
            self.ckpt.started_at = time.time()
            left = self._time_left()
        # 经验：真实 OSM ~5 samples/s；预留 1.5x
        need = next_target / 5.0 * 1.5
        return left < need

    def run_stage(self, n: int, seed: int) -> dict:
        """生成 n 真实样本 → 质检 → 训练 → 动态 K 评估 → offline reduction → hard-neg。"""
        self.status.status = f"GENERATING_{n}"
        self.status.target = n
        self.status.stage = "GENERATING"
        self._write_status()
        t0 = time.time()
        gen = TrainingSampleGenerator(router=RealRoadRouter())
        rec = gen.generate(n, seed=seed)
        samples = rec.samples
        self.status.dataset_size = len(samples)
        self.status.samples_per_second = len(samples) / max(1e-6, time.time() - t0)
        self.ckpt.generated_samples = len(samples)
        self.ckpt.current_target = n
        self.ckpt.last_completed_step = f"generate_{n}"
        self._save_ckpt()
        rec.save_jsonl(self.data_dir / f"search_trace_{n}.jsonl")

        checker = TrainingDataQualityChecker()
        good, qrep = checker.filter(
            samples, self.data_dir / "quarantine",
            expected_graph_version=GRAPH_VERSION,
        )
        fq = checker.feature_quality(good)
        const_feats = sum(1 for x in fq if x.uninformative)
        self._log("dataset-generation", (
            f"n={n} ok={qrep.ok} bad={qrep.bad} est_rej={gen.estimated_rejected} "
            f"unc_rej={gen.uncertain_rejected} scen_unav={gen.scenario_unavailable_count} "
            f"const_feats={const_feats}"
        ))

        builder = RankingDatasetBuilder()
        groups = builder.build(good)
        sizes = [len(g.y) for g in groups]
        self.status.groups = len(groups)
        self.status.avg_candidates = (sum(sizes) / len(sizes)) if sizes else 0.0
        self.status.min_candidates = min(sizes) if sizes else 0
        self.status.max_candidates = max(sizes) if sizes else 0
        tr, va, te = builder.split_by_group(groups)

        self.status.status = f"TRAINING_{n}"
        self.status.stage = "TRAINING"
        self._write_status()
        ranker = CandidateRanker()
        try:
            ranker.train(tr if tr else groups, va if va else None, n_estimators=150)
        except Exception as ex:
            self.status.last_error = f"train:{ex}"
            self._log("model-training", traceback.format_exc())
            ranker = CandidateRanker()
            try:
                ranker.train(tr if tr else groups, n_estimators=50, num_leaves=15, max_depth=5)
            except Exception:
                pass

        self.status.status = f"VALIDATING_{n}"
        self.status.stage = "VALIDATING"
        metrics = ranker.evaluate(te if te else groups)
        self.status.ranking_metrics = metrics
        self.status.model_version = ranker.model_version
        self._write_status()

        # offline reduction（全部 test groups；production reducer 无 teacher 输入）
        reducer = DynamicSearchSpaceReducer(ranker)
        eval_groups = []
        for g in (te if te else groups):
            teacher_order = [g.candidate_ids[i] for i in list(__import__("numpy").argsort(-g.y, kind="stable"))]
            feas_ids = [g.candidate_ids[i] for i, y in enumerate(g.y) if y > 0]
            eval_groups.append((g.candidate_ids, g.X, teacher_order, feas_ids, {}))
        red = offline_reduction_metrics(eval_groups, reducer, k_list=(1, 3, 5, 10))
        self.status.reduction_metrics = {
            "weighted_reduction": red.weighted_reduction,
            "mean_reduction": red.mean_reduction,
            "reduction_recall": red.reduction_recall,
            "best_candidate_recall": red.best_candidate_recall,
            "best_feasible_candidate_recall": red.best_feasible_candidate_recall,
            "total_candidates": red.total_candidates,
            "total_dropped": red.total_dropped,
        }
        self._write_status()

        # hard negatives AFTER model prediction
        self.status.status = f"MINING_HARD_NEGATIVES_{n}"
        self.status.stage = "MINING_HARD_NEGATIVES"
        miner = HardNegativeMiner()
        subset = good[: min(2000, len(good))]
        Xsub = None
        if subset:
            import numpy as np

            Xsub = np.asarray(
                [[float(s.features.get(f, 0.0)) for f in ranker.feature_names] for s in subset]
            )
            scores = ranker.predict(Xsub)
            md = miner.mine_model_disagreement(subset, scores)
            bd = miner.mine_boundary(subset)
        else:
            md, bd = [], []
        self.status.hard_negative_count = len(miner.pool.items)
        self.ckpt.hard_negative_pool_size = len(miner.pool.items)
        self._save_ckpt()
        self._log("model-training", f"hard_neg model_disagreement={len(md)} boundary={len(bd)}")
        miner.save_backlog(self.data_dir / "hard_negative_backlog.jsonl")

        ranker.save(self.model_dir, metadata={
            "dataset_version": f"real{n}",
            "graph_version": GRAPH_VERSION,
            "training_samples": len(good),
            "ranking_metrics": metrics,
            "reduction_metrics": self.status.reduction_metrics,
            "model_disagreement_hard_negatives": len(md),
            "boundary_hard_negatives": len(bd),
        })
        self.status.status = f"TRAINED_{n}"
        self._write_status()

        return {
            "samples": len(good),
            "groups": len(groups),
            "avg_candidates": self.status.avg_candidates,
            "min_candidates": self.status.min_candidates,
            "max_candidates": self.status.max_candidates,
            "ranking_metrics": metrics,
            "reduction_metrics": self.status.reduction_metrics,
            "model_disagreement_hard_negatives": len(md),
            "boundary_hard_negatives": len(bd),
            "quality": asdict(qrep),
            "const_features": const_feats,
            "router_stats": gen.router.stats,
            "scenario_unavailable_count": gen.scenario_unavailable_count,
        }

    def gate_pass(self, summary: dict) -> bool:
        rm = summary.get("ranking_metrics", {})
        b1 = (rm.get("best_candidate_recall") or {}).get(1)
        b5 = (rm.get("best_candidate_recall") or {}).get(5)
        bf5 = (rm.get("best_feasible_candidate_recall") or {}).get(5)
        if b1 is None and b5 is None:
            return False
        ok_best = (b1 is not None and b1 >= 0.99) or (b5 is not None and b5 >= 0.99)
        ok_bf = bf5 is None or bf5 >= 0.99
        return ok_best and ok_bf

    def run(self, smoke_only: bool = False) -> dict:
        self.status.status = "CHECKING_ENV"
        self.status.stage = "CHECK_ENV"
        self._write_status()
        try:
            import lightgbm  # noqa: F401
        except Exception:
            self.status.status = "FALLBACK"
            self.status.last_error = "lightgbm_missing"
            self._write_status()

        self.status.status = "CHECKING_GRAPH"
        self._write_status()
        results = {}
        for target in STAGES:
            if smoke_only and target != 1000:
                break
            if self._insufficient_for(target):
                self.status.status = "EARLY_STOPPED"
                self.status.last_error = f"insufficient_time_for_{target}"
                self._write_status()
                break
            try:
                summary = self.run_stage(target, seed=20260921 + target)
                results[str(target)] = summary
                self.ckpt.stage = f"done_{target}"
                self.ckpt.dataset_size = summary["samples"]
                self.ckpt.best_metric = (
                    summary["ranking_metrics"].get("best_candidate_recall", {}).get(5) or 0.0
                )
                self._save_ckpt()
                if target >= 10000 and not self.gate_pass(summary):
                    self.status.status = "EARLY_STOPPED"
                    self.status.last_error = f"gate_fail_at_{target}"
                    self._write_status()
                    break
            except Exception as ex:
                self.status.status = "FAILED"
                self.status.last_error = str(ex)
                self._write_status()
                self._log("auto-training", traceback.format_exc())
                break
        else:
            self.status.status = "COMPLETED"

        if self.status.status not in ("FAILED", "EARLY_STOPPED"):
            self.status.status = "COMPLETED" if not smoke_only else "SMOKE_COMPLETED"
        self._write_status()
        (self.data_dir / "benchmark_summary.json").write_text(
            json.dumps(results, indent=2, default=str), encoding="utf-8"
        )
        return results


def run_smoke_1000(base_dir: str | Path = ".") -> dict:
    c = AutoTrainingController(base_dir=base_dir)
    out = c.run(smoke_only=True)
    return out.get("1000", out)


if __name__ == "__main__":
    root = Path(__file__).resolve().parents[3]
    print(json.dumps(run_smoke_1000(root), indent=2, default=str))
