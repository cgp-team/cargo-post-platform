"""生产默认 Branch Ranker 加载入口（V047.5K）。"""

from __future__ import annotations

from pathlib import Path

from learning.path_search import RouteSearchRanker

REG = Path(__file__).resolve().parents[2] / "data" / "model_registry"

# Regression Gate + wire 对照后的生产选择（见 run_v047_1_wire_5k.py）
# 10K 五 seed Gate 通过后优先 10K；再 5K / 1K
DEFAULT_MODEL_DIRS = (
    REG / "branch_ranker_10k_seed3407",
    REG / "branch_ranker_10k_seed42",
    REG / "branch_ranker_5k_seed3407",
    REG / "branch_ranker_5k_seed42",
    REG / "branch_ranker_1k_seed42",
)


def load_production_branch_ranker() -> RouteSearchRanker:
    for d in DEFAULT_MODEL_DIRS:
        if d.exists():
            r = RouteSearchRanker.load(d)
            if not r.fallback:
                return r
    return RouteSearchRanker()
