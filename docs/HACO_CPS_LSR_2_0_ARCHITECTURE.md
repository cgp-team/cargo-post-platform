# HACO_CPS_LSR_2_0_ARCHITECTURE

搜索轨迹自动学习 → Search Space Reduction（不改 HACO-CPS 业务规则）

## 架构

```
OSM → RoadGraph → Local Routing（真实道路）
  → Baseline HACO-CPS Teacher
  → SearchTrace（0 人工标注）
  → Automatic Ranking Labels（ObjectiveVector.key()）
  → LightGBM LambdaRank
  → CandidateRanker
  → DynamicSearchSpaceReducer（20–30%，安全门）
  → HACO / ALNS / FeasibilityEngine
  → Top-K → AMap 抽样校验 → DispatchPlan
```

## 组件

| 模块 | 职责 |
|------|------|
| `search_trace_recorder.py` | Teacher 轨迹 + 特征 schema（无未来泄漏） |
| `generate_training_samples.py` | 22 类 Scenario 自动生成 |
| `build_ranking_dataset.py` | ObjectiveVector → relevance 0–4；按 scenario/seed 切分 |
| `train_candidate_ranker.py` | LightGBM lambdarank / rank_xendcg；fallback |
| `search_space_reducer.py` | 20–30% 削减 + 保护候选 + Recall 安全门 |
| `hard_negative_mining.py` | 自动 hard negative + dedup |
| `amap_quota.py` | 日配额 5000/预留 500/目标 4000 + 分层抽样 |
| `model_registry.py` | CANDIDATE→VALIDATED→ACTIVE |
| `data_quality.py` | 坏样本 quarantine |
| `run_auto_training.py` | 无人值守：checkpoint/status/smoke |

## 安全门

- Best Candidate Recall ≥ 99%
- Feasible Candidate Recall ≥ 99%
- 不达标 → 自动降 reduction → Disabled
- 无模型/加载失败 → fallback baseline，不删候选

## 冷启动

模型缺失/损坏/ schema 不兼容 → `fallback_mode=True` → 原 HACO-CPS。
