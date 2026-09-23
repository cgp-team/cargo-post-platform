"""Ranking Label：ObjectiveVector 排序 → group-size relevance；hash split 防泄漏。"""

from __future__ import annotations

import hashlib
from collections import defaultdict
from dataclasses import dataclass, field
from typing import Sequence

import numpy as np

from .search_trace_recorder import FEATURE_NAMES, SearchTraceSample


@dataclass
class RankingGroup:
    group_id: str
    candidate_ids: list[str] = field(default_factory=list)
    X: np.ndarray | None = None
    y: np.ndarray | None = None
    scenario_id: str = ""
    route_pattern: str = ""
    random_seed: int = 0
    teacher_order: list[str] = field(default_factory=list)  # 仅 offline eval 用


def objective_sort_key(s: SearchTraceSample):
    return (
        s.infeasibility, s.vehicle_count,
        round(s.passenger_impact, 3), round(s.cargo_detour, 3),
        round(s.total_distance, 3), round(s.total_duration, 1),
    )


def _group_hash_key(g: RankingGroup, dataset_seed: str) -> str:
    raw = f"{g.scenario_id}|{g.route_pattern}|{g.random_seed}|{dataset_seed}"
    return hashlib.sha1(raw.encode()).hexdigest()


class RankingDatasetBuilder:
    """SearchTrace → (X, y, group)。y 保留真实 lexicographic 序。"""

    def __init__(self, feature_names: Sequence[str] = FEATURE_NAMES):
        self.feature_names = list(feature_names)

    def build(self, samples: Sequence[SearchTraceSample]) -> list[RankingGroup]:
        by_state: dict[str, list[SearchTraceSample]] = defaultdict(list)
        for s in samples:
            by_state[s.search_state_id].append(s)

        groups: list[RankingGroup] = []
        for sid, items in by_state.items():
            items = sorted(items, key=objective_sort_key)
            n = len(items)
            if n < 2:
                continue
            X = np.asarray(
                [[float(s.features.get(f, 0.0)) for f in self.feature_names] for s in items],
                dtype=np.float64,
            )
            # rank 1 → n, rank n → 1；不可行 0；并列 objective 同 relevance
            y = np.zeros(n, dtype=np.int32)
            prev_key = None
            prev_rel = 0
            for i, s in enumerate(items):
                key = objective_sort_key(s)
                if s.infeasibility > 0 or not s.feasible:
                    y[i] = 0
                    prev_key = key
                    prev_rel = 0
                    continue
                if key == prev_key and prev_rel:
                    y[i] = prev_rel
                else:
                    y[i] = n - i
                    prev_rel = y[i]
                prev_key = key
            groups.append(RankingGroup(
                group_id=sid,
                candidate_ids=[s.candidate_id for s in items],
                X=X, y=y,
                scenario_id=items[0].scenario_id,
                route_pattern=items[0].trip_id,
                random_seed=items[0].random_seed,
                teacher_order=[s.candidate_id for s in items],
            ))
        return groups

    def split_by_group(
        self,
        groups: Sequence[RankingGroup],
        train: float = 0.70,
        val: float = 0.15,
        test: float = 0.15,
        dataset_seed: str = "lsr20",
    ) -> tuple[list[RankingGroup], list[RankingGroup], list[RankingGroup]]:
        """稳定 hash shuffle 分组切分，禁止 candidate 级随机。"""
        keyed = sorted(
            ((_group_hash_key(g, dataset_seed), g) for g in groups),
            key=lambda x: x[0],
        )
        n = len(keyed)
        n_train = int(n * train)
        n_val = int(n * val)
        tr = [g for _, g in keyed[:n_train]]
        va = [g for _, g in keyed[n_train:n_train + n_val]]
        te = [g for _, g in keyed[n_train + n_val:]]
        return tr, va, te

    @staticmethod
    def to_lgb_dataset(groups: Sequence[RankingGroup]):
        import lightgbm as lgb

        X = np.vstack([g.X for g in groups])
        y = np.concatenate([g.y for g in groups])
        group = [len(g.y) for g in groups]
        return lgb.Dataset(X, label=y, group=group, free_raw_data=False)
