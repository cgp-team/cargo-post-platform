# -*- coding: utf-8 -*-
"""插入候选 24 维特征：训练与推理共用，禁止两套口径。"""
from __future__ import annotations

from typing import Any, Mapping, Sequence

import numpy as np

FEATURES = (
    "delta_distance", "delta_duration", "passenger_impact", "cargo_detour", "heuristic_score",
    "is_passenger", "is_shipment", "is_delivery", "is_pickup",
    "task_size", "task_weight", "task_volume", "economic_value",
    "pickup_rel", "delivery_rel", "span_stops", "n_events",
    "n_placed", "detour_budget_ratio", "pax_onboard",
    "road_km_pu_de", "straight_km_pu_de", "road_straight_ratio",
    "span_km",
    # 在线可算的全局压力（blocked 只进标签，不进特征，避免 train/serve 偏斜）
    "n_unplaced", "n_gaps",
)


def _hav(a, b) -> float:
    lat1, lon1 = a
    lat2, lon2 = b
    p1, p2 = np.radians(lat1), np.radians(lat2)
    dp, dl = np.radians(lat2 - lat1), np.radians(lon2 - lon1)
    h = np.sin(dp / 2) ** 2 + np.cos(p1) * np.cos(p2) * np.sin(dl / 2) ** 2
    return float(6371.0 * 2 * np.arcsin(np.sqrt(h)))


def _pair_km(matrix: Any, pu: str, de: str) -> float | None:
    """兼容 DistanceMatrix=dict[(from,to)] 与训练 M 的 (min,max) 键。

    只用显式 pair-key，禁止 M.get(a,b) 的伪造默认值，避免训练/推理口径漂移。
    """
    if matrix is None or not pu or not de:
        return None
    get = getattr(matrix, "get", None)
    # 优先 dict.get(key)（绕过 M.get 两参覆盖），再试 __getitem__
    for key in ((pu, de), (de, pu), (min(pu, de), max(pu, de))):
        v = None
        if isinstance(matrix, dict):
            v = dict.get(matrix, key)  # type: ignore[arg-type]
        elif callable(get):
            v = get(key)
        if v is None:
            try:
                v = matrix[key]  # type: ignore[index]
            except Exception:
                v = None
        if isinstance(v, (tuple, list)) and v and isinstance(v[0], (int, float)):
            return float(v[0])
        if isinstance(v, (int, float)):
            return float(v)
    return None


def product_label_key(c: Any, blocked: int = 0) -> tuple:
    """全局对齐混合：pax → detour桶+堵死重罚 → dist → dur。"""
    det = round(float(getattr(c, "cargo_detour", 0.0) or 0.0), 1) + 10.0 * int(blocked)
    return (
        round(float(getattr(c, "passenger_impact", 0.0) or 0.0), 3),
        det,
        round(float(getattr(c, "delta_distance", 0.0) or 0.0), 2),
        round(float(getattr(c, "delta_duration", 0.0) or 0.0), 2),
    )


def compute_features(
    c: Any,
    task: Any,
    route: Any,
    coord: Mapping[str, tuple[float, float]],
    matrix: Any,
    dep: tuple[float, float],
    n_unplaced: float = 0.0,
    n_gaps: float | None = None,
    blocked: float = 0.0,  # 仅标签用；特征不包含，避免偏斜
) -> np.ndarray:
    """InsertionCandidate + 上下文 → 26 维。训练/推理必须走这里。"""
    n = max(1, len(getattr(route, "events", []) or []))
    pu, de = task.pickup_station, task.delivery_station
    if pu == de:
        rp, sk = 0.0, 1e-6
    else:
        rp = _pair_km(matrix, pu, de)
        if rp is None:
            raise ValueError(f"real road matrix missing for {pu}->{de}; haversine fallback is forbidden in training")
        a, b = coord.get(pu, dep), coord.get(de, pu if pu in coord else dep)
        sk = float(_hav(a, b)) or 1e-6
    tt = getattr(task, "task_type", None)
    tt_name = getattr(tt, "name", str(tt or ""))
    if n_gaps is None:
        try:
            n_gaps = float(len(route.get_gap_ranges()))
        except Exception:
            n_gaps = 0.0
    return np.asarray([
        float(c.delta_distance), float(c.delta_duration), float(c.passenger_impact),
        float(c.cargo_detour), float(c.heuristic_score),
        1.0 if tt_name == "PASSENGER" else 0.0,
        1.0 if tt_name == "SHIPMENT" else 0.0,
        1.0 if tt_name == "DELIVERY" else 0.0,
        1.0 if tt_name == "PICKUP" else 0.0,
        float(getattr(task, "size", 1) or 1),
        float(getattr(task, "weight_kg", 0) or 0),
        float(getattr(task, "volume_m3", 0) or 0),
        float(getattr(task, "economic_value", 0) or 0),
        float(c.pickup_index or 0) / n,
        float((c.delivery_index if c.delivery_index is not None else n)) / n,
        abs(float((c.delivery_index if c.delivery_index is not None else n)) - float(c.pickup_index or 0)) / n,
        float(n),
        float(len(getattr(route, "placements", {}) or {})),
        min(1.0, float(c.cargo_detour) / 2.0),
        0.0,
        rp, sk, rp / sk,
        float(_hav(dep, coord.get(pu, dep))),
        float(n_unplaced), float(n_gaps or 0.0),
    ], dtype=np.float64)


def features_from_candidates(
    candidates: Sequence,
    task: Any,
    route: Any,
    coord: Mapping[str, tuple[float, float]],
    matrix: Any,
    dep: tuple[float, float],
) -> np.ndarray:
    if not candidates:
        return np.zeros((0, len(FEATURES)))
    return np.vstack([
        compute_features(c, task, route, coord, matrix, dep) for c in candidates
    ])
