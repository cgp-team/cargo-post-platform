"""Hard Negative Mining：model-disagreement 与 boundary 两类，训练后生成。"""

from __future__ import annotations

import hashlib
import json
from collections import defaultdict
from dataclasses import dataclass, field
from pathlib import Path
from typing import Sequence

import numpy as np

from .search_trace_recorder import SearchTraceSample


def _fp(s: SearchTraceSample) -> str:
    raw = f"{s.search_state_id}|{s.candidate_id}|{s.candidate_type}|{s.candidate_operation}"
    return hashlib.sha1(raw.encode()).hexdigest()[:16]


@dataclass
class HardNegativePool:
    max_pool_size: int = 50000
    items: dict[str, SearchTraceSample] = field(default_factory=dict)

    def add(self, s: SearchTraceSample) -> None:
        key = _fp(s)
        if key in self.items or len(self.items) >= self.max_pool_size:
            if key not in self.items and self.items:
                self.items.pop(next(iter(self.items)))
            else:
                return
        self.items[key] = s

    def extend(self, samples: Sequence[SearchTraceSample]) -> None:
        for s in samples:
            self.add(s)


class HardNegativeMiner:
    """必须在模型预测之后调用 mine_model_disagreement。"""

    def __init__(self, max_pool_size: int = 50000, max_hard_negative_ratio: float = 0.30):
        self.pool = HardNegativePool(max_pool_size=max_pool_size)
        self.max_hard_negative_ratio = max_hard_negative_ratio
        self.backlog: list[SearchTraceSample] = []
        self.last_model_disagreement: list[SearchTraceSample] = []
        self.last_boundary: list[SearchTraceSample] = []

    def mine_model_disagreement(
        self,
        samples: Sequence[SearchTraceSample],
        model_scores: np.ndarray,
    ) -> list[SearchTraceSample]:
        """类型 A：Teacher vs Model 排序严重分歧。必须有 model_scores。"""
        assert model_scores is not None and len(model_scores) == len(samples)
        found: list[SearchTraceSample] = []
        by_state: dict[str, list[tuple[SearchTraceSample, float]]] = defaultdict(list)
        for s, sc in zip(samples, model_scores):
            by_state[s.search_state_id].append((s, float(sc)))

        for _sid, items in by_state.items():
            teacher_ranked = sorted(items, key=lambda x: (
                x[0].infeasibility, x[0].vehicle_count, x[0].passenger_impact,
                x[0].cargo_detour, x[0].total_distance, x[0].total_duration,
            ))
            model_ranked = sorted(items, key=lambda x: -x[1])
            if not teacher_ranked or not model_ranked:
                continue
            teacher_top = teacher_ranked[0][0]
            model_top = model_ranked[0][0]
            # Teacher Top 被模型严重漏掉
            model_pos = {id(s): i for i, (s, _) in enumerate(model_ranked)}
            if model_pos[id(teacher_top)] >= max(1, len(items) // 2):
                found.append(teacher_top)
            # 模型预测高但 Teacher 很差
            if model_top.objective_rank > max(2, len(items) // 2):
                found.append(model_top)
            # 排名差异大
            teacher_pos = {id(s): i for i, (s, _) in enumerate(teacher_ranked)}
            for s, _ in items:
                if abs(teacher_pos[id(s)] - model_pos[id(s)]) >= max(2, len(items) // 3):
                    found.append(s)
        self.last_model_disagreement = list({id(x): x for x in found}.values())
        self.pool.extend(self.last_model_disagreement)
        return self.last_model_disagreement

    def mine(
        self,
        samples: Sequence[SearchTraceSample],
        model_scores: np.ndarray | None = None,
    ) -> list[SearchTraceSample]:
        """入口：无 model_scores 时只做 boundary；有则合并 disagreement。"""
        found = list(self.mine_boundary(samples))
        if model_scores is not None:
            found.extend(self.mine_model_disagreement(samples, model_scores))
        uniq = {id(x): x for x in found}
        self.pool.extend(list(uniq.values()))
        return list(uniq.values())

    def mine_boundary(self, samples: Sequence[SearchTraceSample]) -> list[SearchTraceSample]:
        """类型 B：阈值/易混淆边界（非模型错误）。"""
        found: list[SearchTraceSample] = []
        for s in samples:
            ratio = s.features.get("roadDetourRatio", 0.0)
            if abs(ratio - 1.0) < 0.1:
                found.append(s)
            if s.candidate_type in ("MULTI_LEG", "DIRECT", "CURRENT_TRIP", "NEXT_TRIP"):
                found.append(s)
            if 40 < s.features.get("passengerImpact", 0.0) < 80:
                found.append(s)
            if not s.feasible and s.infeasibility < 2:
                found.append(s)
        self.last_boundary = list({id(x): x for x in found}.values())
        self.pool.extend(self.last_boundary)
        return self.last_boundary

    def combined_dataset(self, base: Sequence[SearchTraceSample]) -> list[SearchTraceSample]:
        """测试/训练兼容入口：与 sample_for_training 相同语义。"""
        return self.sample_for_training(base)

    def sample_for_training(self, base: Sequence[SearchTraceSample]) -> list[SearchTraceSample]:
        cap = int(len(base) * self.max_hard_negative_ratio)
        ranked = sorted(
            self.pool.items.values(),
            key=lambda s: (s.objective_rank or 99, 0 if s.became_best else 1, s.candidate_id),
        )
        use = ranked[:cap]
        self.backlog.extend(ranked[cap:])
        seen = {f"{s.search_state_id}|{s.candidate_id}" for s in base}
        out = list(base)
        for s in use:
            k = f"{s.search_state_id}|{s.candidate_id}"
            if k not in seen:
                out.append(s)
                seen.add(k)
        return out

    def save_backlog(self, path: str | Path) -> None:
        p = Path(path)
        p.parent.mkdir(parents=True, exist_ok=True)
        with p.open("w", encoding="utf-8") as f:
            for s in self.backlog:
                f.write(json.dumps(s.__dict__, ensure_ascii=False, default=str) + "\n")
