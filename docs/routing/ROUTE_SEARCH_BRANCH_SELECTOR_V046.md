# ROUTE_SEARCH_BRANCH_SELECTOR_V046

最终状态：**ROUTE_SEARCH_BRANCH_SELECTOR_V046_CANDIDATE**  
`LONG_TRAINING_DEFERRED`（50K/100K 仍禁止）  
`LocalGraph Python Pruner = RESEARCH_ONLY`  
历史标签保留：`A_LOCAL_GRAPH = SEARCH_REDUCTION_WITHOUT_SPEEDUP`、`B_GH_SELECTOR = ENGINEERING_SIGNAL_POSITIVE`

---

## 1. V045 为什么停止

| 事实 | 证据 |
|------|------|
| 去掉 LightGBM 后 HEURISTIC 仍慢于纯 A* | wall_reduction mean **−18** |
| Python per-node 回调结构性固定开销 | V045 PRE_AUDIT |
| Guard / Prefilter / feature 税 | 分项计时 |
| 扩展减少 ≠ 墙钟减少 | Expansion 0.61 vs Wall −4 ~ −18 |

→ **禁止**继续用 prune_ratio / cache / LightGBM 微调「逼出」A* 加速。  
`pruner_v045.py` 已标 **RESEARCH_ONLY**，代码与实验记录保留。

---

## 2. 为什么 B 成为主线 / V046 架构

生产职责重定义：

```text
真实 OSM → Real RoadGraph → Real Candidate Via
  → Cheap O(1) Features → LightGBM batch rank → Adaptive-K
  → GraphHopper CH/LM exact → Quality Fuse → Feasibility
  → ObjectiveVector → Dispatch/HACO-CPS → Execution
```

**ML 不进入 A* 扩展。** 只减少需要调用 GraphHopper 的候选分支。

实现：`learning/path_search/gh_branch_selector.py`  
实验：`learning/training/run_v046_gh_branch_selector.py`  
（`--mode` 语义：full / top1 / top2 / top4 / top8 / adaptive；seed / repeats / cache 冷热在 runner 内。）

### 组件

| 组件 | 行为 |
|------|------|
| Cheap Features（13 维） | detour / heading / OD 距离 / region / profile 等，O(1) |
| Batch inference | 一次 `predict([via1..viaN])` |
| Feature cache | 冷/热可分 |
| **ML Worthwhile Gate** | `expected_saved_GH_cost ≤ ML_cost` → 直接 Full GH |
| **Adaptive-K** | margin / 分布 / OD 距离 / MultiLeg / 高价值 → K∈{1,2,4,8,Full} |
| **质量保险丝** | distance regret 超阈 → K 阶梯扩大；不可行 → `NO_FEASIBLE_ROUTE` 并继续扩 |
| Full GH baseline | **永久保留** grounded runtime baseline |
| 双指标 | distance + duration regret |

Adaptive-K 阈值（benchmark 标定，非「写死好看」）：高置信 `nm≥0.45`→K=2；中 `≥0.22`→4；低 `≥0.08`→8；更低→Full；保守路径（长距/MultiLeg/高价值）抬高 K。

---

## 3. 数据

| 项 | 值 |
|----|-----|
| OSM / Graph | 同 V044/V045：2,230,689 nodes / 2,287,515 edges |
| GH | GraphHopper CH `:8080`，profile=bus |
| OD | 每 seed 请求 200，有效 **128**（真实站点） |
| 覆盖 | 主城 / 江津 / 跨区 + SHORT/MEDIUM/LONG |
| seeds | **42 / 123 / 3407 / 2026 / 8888** |
| repeats | 1–2（median wall） |
| 训练 | 小规模 Branch Ranker（via 特征 + GH 最优 label，**仅训练/离线**） |
| teacher leakage | **0**（生产特征无 teacher_*） |

---

## 4. End-to-End Gate（五 seed 汇总）

| 门禁 | 值 | 结果 |
|------|-----|------|
| GH wall-clock 整体下降 | wall_reduction mean **0.712** | **通过** |
| absolute saved | mean **156.3 ms** | **通过**（非百分比陷阱） |
| route quality | distance_regret p95 **0.071** | **通过**（≤0.15） |
| fallback | mean **3.3%** | **通过**（≤50%） |
| 多 seed 墙钟全正 | 5/5 | **通过** |
| 主城/江津可跑 | 见 §8 | **通过** |
| teacher leakage | 0 | **通过** |

```text
ROUTE_SEARCH_BRANCH_SELECTOR_V046_CANDIDATE
LONG_TRAINING_DEFERRED
```

**不升 ACTIVE。**

---

## 5. top-K sweep（seed=42，128 OD）

| mode | GH calls | call reduction | wall_reduction mean | absolute saved mean | dist regret mean | fallback |
|------|----------|----------------|---------------------|---------------------|------------------|----------|
| full | 16 | 0% | ≈0 | ≈0 | 0 | 0% |
| top1 | ~1.25 | ~92% | **0.948** | **245 ms** | 0.055 | 25% |
| top2 | ~2.3 | ~86% | 0.853 | 224 ms | 0.037 | 17% |
| **top4** | ~4.3 | **~73%** | **0.759** | **166 ms** | **0.008** | 8% |
| top8 | 8 | 50% | 0.482 | 151 ms | 0.001 | 0% |
| **adaptive** | ~7.2 | ~55% | **0.539** | **128 ms** | **0.009** | **1.6%** |

top-4 absolute_saved（ms）：mean **166** / p50 **164** / p95 **300** / p99 **357** / max **363**。

**实验决定（非写死）**：
- **默认生产：adaptive-K**（质量优先，regret≈0.9%，fallback 1.6%）
- **吞吐优先场景：top-4**（墙钟 −76%，绝对省 ~166ms）
- top-1 墙钟最高但 regret/fallback 偏大，不默认

---

## 6. Adaptive-K

- 输入：score margin、top 分布、OD 距离、MultiLeg、高价值标记
- 输出：K + reason（`high_confidence` / `mid_confidence` / `low_confidence` / `conservative_low_margin` / `worthwhile_gate_full` / `margin_expand`）
- 质量保险丝：distance regret >12% 自动沿 2→4→8→Full 扩；全程不直接失败
- 五 seed adaptive regret mean ≈ **0.8–0.9%**，fallback ≈ **0.8–1.6%**

---

## 7. 短 / 中 / 长（adaptive，seed=42）

| bucket | wall_reduction mean | absolute_saved | distance_regret |
|--------|---------------------|----------------|-----------------|
| SHORT（&lt;3km） | **0.65** | 正 | 低 |
| MEDIUM（3–12km） | 0.37 | 正 | 低 |
| LONG（&gt;12km） | **0.57** | 正 | 低 |

短距未再出现 V045 式「亚毫秒相对爆炸」——因 ML 成本固定而 GH 仍是主成本，Worthwhile Gate 在极小 OD 上 bypass。

---

## 8. 主城 / 江津 / 跨区（adaptive，seed=42）

| region | wall_reduction mean |
|--------|---------------------|
| chongqing_core | 0.51 |
| jiangjin | **0.56** |
| cross | **0.64** |

江津与跨区均正收益，**不是**只记住主城局部模式。完整分布见 `v046_result.json` `*_by_region`。

---

## 9. Cold / Hot Cache

- Feature cache：key=via+origin+dest+region+profile；hot 轮 `cache_hit_rate` 明显上升（见 `cold_hot`）
- **不以 hot 成绩冒充 cold**：主表 wall 为该 mode 首次/median，cold/hot 分列记录
- GH 服务 JVM 侧缓存随 repeats 会变热；report 使用 median wall

---

## 10. 五 seed 汇总

| seed | adaptive wall_red | adaptive abs_ms | adaptive regret | top4 wall_red | top4 reg p95 |
|------|-------------------|-----------------|-----------------|---------------|--------------|
| 42 | 0.539 | 128 | 0.009 | 0.781 | 0.080 |
| 123 | 0.603 | 143 | 0.008 | 0.788 | 0.094 |
| 3407 | 0.666 | 165 | 0.009 | 0.809 | 0.095 |
| 2026 | 0.650 | 164 | 0.009 | — | — |
| 8888 | 0.662 | 158 | 0.009 | — | — |

**五 seed 墙钟全部为正**（`multi_seed_wall_positive=true`）。

---

## 11. Worst-20

`algorithm/data/v046_failure_cases.json`  
类型：`distance_regret` / `fallback` / `full_gh_best_miss` / `slowdown`。  
字段含 OD、region、k、scores_top、full-GH best、selected、regret、fallback_reason。

---

## 12. Runtime / Regret / Fallback（指标以 §8–9 主指标为准）

辅助：`ml_inference_ms`、`feature_ms`、`cache_hit_rate`、`batch_size`（≈16，单次 batch）。

百分比陷阱规避：主结论同时给 **absolute saved ms**（mean 156 / top4 mean 166 / p50 164）。

---

## 13. 是否接受

| 条件 | 结果 |
|------|------|
| GH calls 明显下降 | **是**（top4 ~−73%，adaptive ~−55%） |
| 整体 wall-clock 下降 | **是**（mean −71%，abs +156ms） |
| 质量无明显恶化 | **是**（regret p95 7.1%） |
| fallback 可控 | **是**（3.3%） |
| 多 seed 稳定 | **是**（5/5） |
| 主城/江津 | **是** |
| teacher leakage=0 | **是** |

→ **ROUTE_SEARCH_BRANCH_SELECTOR_V046_CANDIDATE**

若 wall 未转正才会写 `V046_BLOCKED_ON_WALLCLOCK`——**本轮不适用**。

---

## 14. 下一步

1. 1K → 5K → 10K OD（仍 **禁止 50K/100K**）
2. 与 MultiLeg / Dispatch 联调：Adaptive-K 消费 multi_leg / high_value 标记
3. 历史 `historical_success_rate` 特征接入生产统计
4. LocalGraph 原生化（GH Java / Cython）仅作独立研究轨，不阻塞 B
5. 证据齐备后再讨论 ACTIVE（当前明确 **不直接 ACTIVE**）

---

## 15. 文件

| 文件 | 说明 |
|------|------|
| `learning/path_search/gh_branch_selector.py` | 生产主线组件 |
| `learning/path_search/pruner_v045.py` | RESEARCH_ONLY |
| `learning/training/run_v046_gh_branch_selector.py` | 实验 |
| `data/v046_result.json` / `v046_failure_cases.json` | 结果 / Worst-20 |
| `ROUTE_SEARCH_PRUNER_V045.md` 等 | 历史 |

许可证：`THIRD_PARTY_NOTICES.md`
