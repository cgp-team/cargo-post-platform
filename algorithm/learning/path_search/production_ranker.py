"""生产默认 Branch Ranker 加载入口（V047.5K）。

排序/削减能力解耦（RankerCapability）：
- can_rank：允许 ML 重排探索顺序（recall >= 0.80 即可，有 Quality Fuse 兜底）
- can_prune：允许硬砍候选（recall 必须 >= 0.99 安全门）
训练不完整（如 smoke-1k recall=0.73）时：排序也关闭，走业务序启发式；不误用半成品模型硬砍。
"""

from __future__ import annotations

from pathlib import Path

from learning.path_search import RouteSearchRanker

try:
    from learning.path_search.gh_branch_selector import RankerCapability
except Exception:  # pragma: no cover
    RankerCapability = None

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


def load_production_branch_ranker(metrics: dict | None = None):
    """加载生产 ranker；metrics 用于 RankerCapability 解耦门控。

    metrics 缺省时从 REG 上一级的 models/model_metadata.json 读取；
    读不到则视为无能力（走业务序启发式），不误用半成品模型。
    """
    for d in DEFAULT_MODEL_DIRS:
        if d.exists():
            r = RouteSearchRanker.load(d)
            if not r.fallback:
                if RankerCapability is not None:
                    r.capability = RankerCapability.from_metrics(
                        metrics if metrics is not None else _load_metrics()
                    )
                return r
    return RouteSearchRanker()


def _load_metrics():
    import json

    meta = REG.parent.parent / "models" / "model_metadata.json"
    try:
        data = json.loads(meta.read_text(encoding="utf-8"))
        # 取 ranking_metrics 顶层（含 best_candidate_recall / overall_feasible_candidate_recall）
        return data.get("ranking_metrics") or data
    except Exception:
        return None
