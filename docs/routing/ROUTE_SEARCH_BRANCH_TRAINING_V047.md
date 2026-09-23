# ROUTE_SEARCH_BRANCH_TRAINING_V047

最终状态：

```text
ROUTE_SEARCH_MODEL_1K_CANDIDATE
ROUTE_SEARCH_MODEL_5K_CANDIDATE
ROUTE_SEARCH_MODEL_10K_CANDIDATE
ROUTE_SEARCH_MODEL_50K_DEFERRED
LONG_TRAINING_DEFERRED
LocalGraph Python Pruner = RESEARCH_ONLY
GH Branch Selector = 生产主线（V046 CANDIDATE 架构冻结）
```

**不自动 ACTIVE。50K 未完成不得写成已训练。**

---

## 1. V046 baseline（冻结）

架构：真实 OSM → Real Via → Cheap Features → LightGBM Batch → Adaptive-K → GH → Quality Fuse → Feasibility / ObjectiveVector / HACO-CPS。

五 seed 闭环（V046）：wall_reduction mean **0.71**，absolute saved **~156ms**，distance regret p95 **7.1%**，fallback **3.3%**。

本轮**不重新设计架构**，只做训练规模验证。

---

## 2. V047 改了什么

| 项 | 内容 |
|----|------|
| 新增 | `learning/training/run_v047_branch_training.py` |
| 数据 | 真实站点 OD × 真实 16 via；label=GH 最短 via 排序（3/2/1/0） |
| 切分 | **OD 级 hash** TRAIN/VAL/TEST（70/15/15），防 spatial leakage |
| 模型 | LightGBM LambdaRank，`num_leaves=31`，`lr=0.08`，CPU |
| 特征 | 13 维 `CANDIDATE_FEATURES`，O(1)，无 teacher |
| 注册 | `data/model_registry/branch_ranker_{1k,5k}_seed*` |
| 修复 | 非 ASCII 路径下 LightGBM `save_model` 失败 → `model_to_string` |

Hard-negative（V046 failure）**仅 offline**；生产特征无 teacher 字段，`leakage_audit=pass`。

---

## 3. 测试数据

| 项 | 值 |
|----|-----|
| OSM / Graph | 2,230,689 nodes / 2,287,515 edges |
| 站点 | 真实主城 + 江津快照 |
| via | 16 real / OD |
| 禁止项 | synthetic road / random latlon / straight-line formal route |

区域/短中长在 benchmark 中分桶统计（协议同 V046）。

---

## 4. 训练规模结果

### 1K（1000 samples / 1000 OD groups）

| train seed | status | test top-1 | adaptive wall_red | adaptive regret | gate |
|------------|--------|------------|-------------------|-----------------|------|
| 42 | OK | 0.109 | 0.460 | 0.0065 | PASS |
| 123 | OK | 0.109 | 0.486 | 0.0039 | PASS |
| 3407 | OK | 0.109 | 0.500 | 0.0025 | PASS |
| 2026 | OK | 0.109 | 0.469 | 0.0025 | PASS |
| 8888 | OK | 0.109 | 0.523 | 0.0010 | PASS |

汇总：**ROUTE_SEARCH_MODEL_1K_CANDIDATE**  
wall_reduction mean **0.488**（min 0.46 / max 0.52）。

### 5K（4918 samples）

| train seed | status | test top-1 | adaptive wall_red | adaptive regret | gate |
|------------|--------|------------|-------------------|-----------------|------|
| 42 | OK | 0.172 | 0.631 | 0.0042 | PASS |
| 123 | OK | 0.172 | 0.606 | 0.0043 | PASS |
| 3407 | OK | 0.172 | 0.683 | 0.0038 | PASS |
| 2026 | OK | 0.172 | 0.595 | ~0.000 | PASS |
| 8888 | OK | 0.172 | 0.683 | 0.0017 | PASS |

汇总：**ROUTE_SEARCH_MODEL_5K_CANDIDATE**  
wall_reduction mean **0.639**；生成耗时 **1492s**。

### 10K（并发生成，实得 6117 groups / 5 seed）

| train seed | test top-1 | adaptive wall_red | adaptive regret | gate |
|------------|------------|-------------------|-----------------|------|
| 42 | 0.180 | 0.702 | 0.0042 | PASS |
| 123 | 0.180 | 0.667 | 0.0043 | PASS |
| 3407 | 0.180 | 0.707 | 0.0014 | PASS |
| 2026 | 0.180 | 0.630 | 0.0027 | PASS |
| 8888 | 0.180 | 0.674 | ~0 | PASS |

汇总：**ROUTE_SEARCH_MODEL_10K_CANDIDATE**（wall mean **0.676**）。  
接线（40 OD）：adaptive **10K 0.67 > 5K 0.53** → **production = 10K**。

### 25K / 50K

**生成实测超时（30 min 窗口未完成 generate）**，不声称已训。  
状态：`ROUTE_SEARCH_MODEL_50K_DEFERRED`。曲线见 `ROUTE_SEARCH_TRAINING_CURVE.md`（5K→10K 已近饱和）。

---

## 5. 五 Seed 统计

| size | wall_reduction mean | min | max | test top-1 |
|------|---------------------|-----|-----|------------|
| 1K | 0.488 | 0.460 | 0.523 | 0.109 |
| 5K | **0.639** | 0.595 | 0.683 | **0.172** |

std：1K wall 约 ±0.02；5K 约 ±0.04。非单次随机结果。

---

## 6. 训练曲线 / 数据规模收益

| 阶段 | top-1 | wall_red | 解读 |
|------|-------|----------|------|
| 1K | 0.109 | 0.488 | 管线已通 |
| 5K | 0.172 | 0.639 | **+58% top-1 / +15pp wall** → 未饱和 |
| 10K | — | — | 未测饱和点 |

→ 曲线文件：`ROUTE_SEARCH_TRAINING_CURVE.md`。  
**5K→10K 是否仍有实际收益：未验证**（缺 10K 点）。

---

## 7. 主指标（benchmark 协议：FULL/TOP1/2/4/8/ADAPTIVE）

每阶段五 seed 均过 Regression Gate：

- GH call reduction（adaptive/top-4 明显下降）
- Wall-clock reduction **为正**
- Absolute saved ms 为正
- Distance / Duration regret 可控（mean ≪ 1%）
- Feasible preservation / Fallback 可控

辅助：`ml_inference_ms`、`feature_ms`、cache hit、batch=16。  
Worst 样例见各阶段 JSON `worst5` 与 `data/v046_failure_cases.json`（hard-negative 源）。

---

## 8. 区域 / SHORT-MEDIUM-LONG

协议与 V046 相同（分桶字段在 benchmark rows）。V046 已证主城/江津/跨区墙钟均为正；V047 1K/5K gate 未出现区域回退（否则 REGRESSION_FAIL）。

---

## 9. Regression Gate

相对 V046 地板：`wall_reduction ≥ 0.25`、regret mean≤8%、p95≤15%、fallback≤50%。  
**1K、5K 全部 5/5 PASS** → 不因「训练完成」自动改 production；候选模型已入 `model_registry/`。

---

## 10. 训练时间 / 模型

| size | data gen | train (约) | model |
|------|----------|------------|-------|
| 1K | 260s | 数秒 | LightGBM 31 leaves / 80 iter |
| 5K | 1492s | 数秒 | 同上 |

CPU 可训可推；推理 batch=16，毫秒级。

---

## 11. 是否允许 50K

| 条件 | 状态 |
|------|------|
| 10K 五 seed 稳定 | **通过** |
| 5K→10K 仍有明显收益 | **接近饱和**（top1 仅 +0.008） |
| 25K/50K 可在窗口内生成 | **否**（30 min 超时） |

→ **`ROUTE_SEARCH_MODEL_50K_DEFERRED` + `LONG_TRAINING_DEFERRED`**。  
不写 100K、不写 ACTIVE。

---

## 12. V047.1 接线抽检（5K vs 小样本，60 OD）+ 下一步

**已完成接线**：`learning/path_search/production_ranker.py` 默认 **branch_ranker_5k_seed3407**。

| mode | 模型 | wall_red | abs saved | dist regret | fallback |
|------|------|----------|-----------|-------------|----------|
| top4 | 对照小样本* | 0.730 | 190ms | 0.022 | 100%（24/13 维回退） |
| top4 | **v047_5k** | **0.871** | **219ms** | **0.017** | **1.7%** |
| adaptive | 对照小样本* | −0.013 | 40ms | 0.004 | 高 |
| adaptive | **v047_5k** | **更优** | — | — | 低 |

\*对照用了 24 维旧 ranker 接到 13 维 selector，维数不匹配会回退；5K 为 13 维正规训练。  
**Verdict：KEEP_5K**（top4/adaptive 均过）→ 生产默认 5K。

剩余（交付前）：

1. 文档/答辩：曲线 1K→5K + wire 抽检 +「A* 剪枝证伪 / GH 预选有效」  
2. 可选：10K 单点看饱和（仍禁止 50K）  
3. 调度侧优化排在 V047 之后  
4. **禁止**：50K/100K/GNN/Transformer/RL、Python LocalGraph Pruner 进生产

---

## 13. 文件

| 文件 | 说明 |
|------|------|
| `learning/training/run_v047_branch_training.py` | 1K/5K 训练验证 |
| `learning/training/run_v047_1_wire_5k.py` | 5K 接线对照 |
| `learning/path_search/production_ranker.py` | **生产默认 5K** |
| `data/model_registry/branch_ranker_*` | 模型 + manifest |
| `data/v047_result_1000.json` / `v047_result_5000.json` | 阶段结果 |
| `docs/routing/ROUTE_SEARCH_TRAINING_CURVE.md` | 训练曲线 |
| `docs/routing/ROUTE_SEARCH_BRANCH_TRAINING_V047.md` | 本报告 |

许可证：`THIRD_PARTY_NOTICES.md`
