"""HACO 插入候选 ← Branch Ranker（LightGBM）桥接。

模型特征 13 维（data/model_registry/branch_ranker_*）：
candidate_distance, candidate_duration_estimate, origin_distance, destination_distance,
via_distance, heading_difference, road_class, road_level, intersection_degree,
detour_estimate, region_code, profile_code, historical_success_rate

默认只 **重排** 不硬砍（Quality Fuse）；RankerCapability.can_prune=False 时绝不丢弃候选。
"""
from __future__ import annotations

from functools import lru_cache
from pathlib import Path
from typing import Any, Mapping, Sequence

import numpy as np

from .insertion_features import FEATURES as FEATURE_NAMES
from .insertion_features import compute_features

MODEL_DIRS = (
    # 优先：真实公交线站序骨架 + 真实站点产品订单
    Path(__file__).resolve().parents[2] / "data" / "model_registry" / "insertion_ranker_osm_v2",
    # OSM 真实站点/道路 + 产品目标标签（top3=0.87，过 rank 门）
    Path(__file__).resolve().parents[2] / "data" / "model_registry" / "insertion_ranker_osm_v1",
    Path(__file__).resolve().parents[2] / "data" / "model_registry" / "branch_ranker_10k_seed3407",
    Path(__file__).resolve().parents[2] / "data" / "model_registry" / "branch_ranker_10k_seed42",
    Path(__file__).resolve().parents[2] / "data" / "model_registry" / "branch_ranker_5k_seed3407",
    Path(__file__).resolve().parents[2] / "data" / "model_registry" / "branch_ranker_5k_seed42",
)

FEATURE_NAMES = (
    "delta_distance", "delta_duration", "passenger_impact", "cargo_detour", "heuristic_score",
    "is_passenger", "is_shipment", "is_delivery", "is_pickup",
    "task_size", "task_weight", "task_volume", "economic_value",
    "pickup_rel", "delivery_rel", "span_stops", "n_events",
    "n_placed", "detour_budget_ratio", "pax_onboard",
    "road_km_pu_de", "straight_km_pu_de", "road_straight_ratio", "span_km",
)


class InsertionMLRanker:
    """插入候选 ML 重排；无模型/门禁关闭时零影响。"""

    def __init__(self) -> None:
        self.model: Any = None
        self.enabled = False
        self.version = "none"
        self.recall: float | None = None
        self.can_rank = False
        self.can_prune = False
        self.rerank_calls = 0

    def load(self) -> "InsertionMLRanker":
        import lightgbm as lgb

        for d in MODEL_DIRS:
            txt = d / "route_search_ranker.model.txt"
            binf = d / "route_search_ranker.model"
            if not txt.exists() and not binf.exists():
                # 同目录任意 .model.txt
                cands = list(d.glob("*.model.txt"))
                if cands:
                    txt = cands[0]
            try:
                if txt.exists():
                    self.model = lgb.Booster(model_str=txt.read_text(encoding="utf-8"))
                elif binf.exists():
                    self.model = lgb.Booster(model_file=str(binf))
                else:
                    continue
                self.version = d.name
                self.enabled = True
                break
            except Exception:
                self.model = None
        if self.enabled:
            # registry smoke-1k feasible_recall=0.73 → 默认只排序也偏保守；
            # 强制实验用 enable(..., force=True)
            self._apply_gate_from_registry()
        return self

    def _apply_gate_from_registry(self) -> None:
        try:
            import json

            # 优先读 insertion_ranker_osm_v2（真实公交线骨架）manifest
            best = None
            for name in ("insertion_ranker_osm_v2", "insertion_ranker_osm_v1"):
                meta = (
                    Path(__file__).resolve().parents[2]
                    / "data" / "model_registry" / name / "manifest.json"
                )
                if meta.exists():
                    m = json.loads(meta.read_text(encoding="utf-8")).get("metrics") or {}
                    best = m.get("rank_recall_proxy") or m.get("top3_hit")
                    if best is not None:
                        break
            if best is None:
                reg = Path(__file__).resolve().parents[2] / "models" / "registry.json"
                data = json.loads(reg.read_text(encoding="utf-8")) if reg.exists() else {}
                for _name, entry in data.items():
                    m = (entry or {}).get("metrics") or {}
                    rec = m.get("feasible_candidate_recall")
                    if rec is not None:
                        best = rec if best is None else max(best, float(rec))
            self.recall = float(best) if best is not None else None
            self.can_rank = (self.recall or 0) >= 0.80
            self.can_prune = (self.recall or 0) >= 0.99
        except Exception:
            self.can_rank = False
            self.can_prune = False

    def enable_force_rank(self) -> None:
        """A/B 实验：无视安全门，只重排不硬砍。"""
        self.can_rank = True
        self.can_prune = False

    @staticmethod
    def features_of(
        c,
        task=None,
        route=None,
        coord=None,
        matrix=None,
        dep=None,
        n_unplaced=0.0,
        n_gaps=None,
        blocked=0.0,
    ) -> np.ndarray:
        """InsertionCandidate → 27 维；与训练共用 compute_features。"""
        if task is not None and route is not None and coord is not None:
            if dep is None:
                dep = coord.get(getattr(route, "depot_station", ""), next(iter(coord.values()), (0.0, 0.0)))
            return compute_features(
                c, task, route, coord, matrix, dep,
                n_unplaced=n_unplaced, n_gaps=n_gaps, blocked=blocked,
            )
        d = max(0.0, float(getattr(c, "delta_distance", 0.0)))
        t = max(0.0, float(getattr(c, "delta_duration", 0.0)))
        det = max(0.0, float(getattr(c, "cargo_detour", 0.0)))
        pax = max(0.0, float(getattr(c, "passenger_impact", 0.0)))
        pi = float(getattr(c, "pickup_index", 0) or 0)
        di = float(getattr(c, "delivery_index", 0) or 0)
        hs = float(getattr(c, "heuristic_score", 0.0))
        return np.asarray([
            d, t, pax, det, hs,
            0.0, 0.0, 0.0, 0.0,
            1.0, 0.0, 0.0, 0.0,
            0.0, 0.0, 0.0,
            1.0, 0.0, min(1.0, det / 2.0), 0.0,
            0.0, 0.0, 0.0, 0.0,
            float(n_unplaced), 0.0,
        ], dtype=np.float64)

    def scores(
        self,
        candidates: Sequence,
        task=None,
        route=None,
        coord: Mapping | None = None,
        matrix=None,
        dep=None,
        routes_by_vi: Mapping | None = None,
        n_unplaced: float = 0.0,
        n_gaps=None,
        blocked_map: Mapping | None = None,
    ) -> np.ndarray:
        if not self.enabled or self.model is None or not candidates:
            return np.zeros(len(candidates))
        rows = []
        for c in candidates:
            rt = route
            if routes_by_vi:
                rt = routes_by_vi.get(getattr(c, "vehicle_index", 0), route)
            blocked = 0.0
            if blocked_map:
                blocked = float(blocked_map.get(id(c), 0))
            rows.append(
                self.features_of(
                    c, task=task, route=rt, coord=coord, matrix=matrix, dep=dep,
                    n_unplaced=n_unplaced, n_gaps=n_gaps, blocked=blocked,
                )
            )
        X = np.vstack(rows)
        try:
            return np.asarray(self.model.predict(X), dtype=float)
        except Exception:
            return np.zeros(len(candidates))

    def rerank(
        self,
        candidates: Sequence,
        *,
        blend: float = 0.75,
        task=None,
        route=None,
        coord: Mapping | None = None,
        matrix=None,
        dep=None,
        routes_by_vi: Mapping | None = None,
        n_unplaced: float = 0.0,
        n_gaps=None,
        blocked_map: Mapping | None = None,
    ) -> list:
        """Quality Fuse：ML 分与启发式分归一后加权；只重排，不丢候选。"""
        cands = list(candidates)
        if not self.enabled or not self.can_rank or self.model is None or len(cands) < 2:
            return sorted(cands, key=lambda x: x.heuristic_score)
        self.rerank_calls += 1
        ml = self.scores(
            cands,
            task=task,
            route=route,
            coord=coord,
            matrix=matrix,
            dep=dep,
            routes_by_vi=routes_by_vi,
            n_unplaced=n_unplaced,
            n_gaps=n_gaps,
            blocked_map=blocked_map,
        )
        hs = np.asarray([c.heuristic_score for c in cands], dtype=float)

        def _norm(a: np.ndarray) -> np.ndarray:
            lo, hi = float(np.min(a)), float(np.max(a))
            if hi - lo < 1e-12:
                return np.zeros_like(a)
            return (a - lo) / (hi - lo)

        # 启发式越低越好；ML 越高越好
        fused = (1.0 - blend) * _norm(hs) + blend * (1.0 - _norm(ml))
        order = list(np.argsort(fused, kind="stable"))
        return [cands[i] for i in order]


@lru_cache(maxsize=1)
def get_ranker() -> InsertionMLRanker:
    return InsertionMLRanker().load()
