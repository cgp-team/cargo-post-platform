# ROUTE_SEARCH_METRIC_AUDIT_V041

状态：**METRIC_AUDIT_PASSED**

审计对象：`ROUTE_SEARCH_LEARNING_REPORT` v0.4 指标定义与 5×250 groups 重跑结果  
约束：**不修改评估公式**；**不调参抬高指标**；审计通过前禁止 50K/100K 长训  
数据：`algorithm/data/v041_metric_audit.json`、`algorithm/data/v041_metric_audit_rows.jsonl`（逐 group 审计行）  
代码：`algorithm/learning/training/run_v041_metric_audit.py`

---

## 1. 对「Recall@5=0.527 > 0.3125」疑问的结论

**不是公式越界，是字段名歧义。**

| 字段 | 实测 | 含义 |
|------|------|------|
| `candidate_count` | **16.0**（min=max=16） | 候选池大小 n |
| `teacher_candidate_count` | **8.0**（min=max=8，seed123 均值 7.98） | \|T\| = label==1 的候选数 = **Recall 分母** |
| `teacher_path_edge_count` | 均值 ≈ 134 | teacher 全路径真实边数（Path-Edge Recall 分母） |
| `actual_topK_count` @5 | **5.0**（min=max=5） | 从不 expand，从不超过 K |

Evaluator 公式（未改）：

```text
recall = |topK ∩ T| / |T|
       = recall_numerator / recall_denominator
       = intersection_count / teacher_candidate_count
```

因此：

| 量 | 值 | 说明 |
|----|----|------|
| Recall@5 理论上限 | **min(1, 5/\|T\|) = 5/8 = 0.625** | 分母是 \|T\|=8 |
| 若误用 n=16 当分母 | 5/16 = 0.3125 | **这不是本 evaluator 的定义** |
| random E[Recall@5] | **5/16 = 0.3125** | E[\|topK∩T\|/\|T\|] = K/n |
| 实测 macro Recall@5 | **0.524–0.537** | 落在 (0.3125, 0.625] 内 |
| 实测 group 最大 Recall@5 | **0.625** | 恰达上限，硬断言通过 |

**用户推论中的「teacher_cands 真的是 16」不成立**：16 是 candidate_count（池），不是 teacher_candidate_count（\|T\|）。报告原文写 `/ |teacher_cands|` 而未写清是 \|label==1\|，造成误读。**公式与 evaluator 一致，未改公式。**

硬断言（全部通过）：

```text
intersection_count <= actual_topK_count <= requested_K
recall <= min(1, K / teacher_candidate_count)
```

---

## 2. 十项检查

| # | 检查项 | 结果 | 证据 |
|---|--------|------|------|
| 1 | Recall 是 group-level 后平均还是 micro | **两者都报** | macro = mean(group recall)；micro = Σ\|topK∩T\| / Σ\|T\|。本实验 \|T\| 几乎恒为 8，macro≈micro |
| 2 | random baseline 是否同聚合 | **是** | macro: mean(K/n_i)；micro: Σ(K·\|T_i\|/n_i)/Σ\|T_i\|。已修正 micro 公式笔误（曾误写成 K/Σn） |
| 3 | Shuffle 是否同一 candidate pool | **是** | 同一 test `X`/`edge_ids`/`y`；仅训练 label 置乱 |
| 4 | teacher_cands 是否真的为 16 | **否，为 8** | `teacher_candidate_count_distribution.mean = 8.0` |
| 5 | model_topK 是否真的最多 5 | **是** | `topK_actual_count@5` min=max=5 |
| 6 | 是否存在重复 edge_id | **否** | `duplicate_edge_id_groups = 0`（5 seed 全 0） |
| 7 | candidate pool 去重问题 | **无** | 池内 edge_id 唯一；正负样本集合不相交 |
| 8 | 是否存在 topK expand | **无** | actual_topK_count == requested_K 恒成立 |
| 9 | 不同 K 指标是否混用 | **否** | 逐 K 分行；Recall / Path-Edge Recall 字段分离 |
| 10 | 报告字段与 evaluator 是否一致 | **公式一致，措辞有歧义** | 分母始终是 \|T\|；报告未写清 \|T\|≠n，已在本审计更正表述 |

---

## 3. 逐 group 审计表（字段）

完整 5×63×4 = **1260** 行见 `v041_metric_audit_rows.jsonl`。字段：

```text
group_id
candidate_count
teacher_candidate_count
teacher_path_edge_count
requested_K
actual_topK_count
intersection_count
recall_numerator
recall_denominator
recall
random_expected_recall
theoretical_max_recall
```

示例（seed=42, od16, K=1）：

| 字段 | 值 |
|------|-----|
| candidate_count | 16 |
| teacher_candidate_count | 8 |
| teacher_path_edge_count | 115 |
| requested_K | 1 |
| actual_topK_count | 1 |
| intersection_count | 1 |
| recall_numerator / denominator | 1 / 8 |
| recall | 0.125 |
| random_expected_recall | 0.0625 |
| theoretical_max_recall | 0.125 |

---

## 4. 五 seed 重跑（250 OD / seed，n_cands=16）

### Recall macro（mean / median / std / p90）

| seed | Recall@1 | Recall@5 | Shuffle@5 | Path-Edge@5 | Random@5 | D-Regret |
|------|----------|----------|-----------|-------------|----------|----------|
| 42 | 0.113 | 0.524 | 0.313 | 0.036 | 0.3125 | 0.203 |
| 123 | 0.121 | 0.537 | 0.289 | 0.042 | 0.3125 | 0.133 |
| 3407 | 0.111 | 0.526 | 0.331 | 0.037 | 0.3125 | 0.123 |
| 2026 | 0.123 | 0.536 | 0.351 | 0.040 | 0.3125 | 0.202 |
| 8888 | 0.115 | 0.524 | 0.298 | 0.036 | 0.3125 | 0.101 |

（seed=42 详细分布，完整表在 JSON）

| 指标 | mean | median | std | p90 | min | max |
|------|------|--------|-----|-----|-----|-----|
| Recall@1 | 0.113 | 0.125 | 0.037 | 0.125 | 0.000 | 0.125 |
| Recall@5 | 0.524 | 0.500 | 0.109 | 0.625 | 0.250 | 0.625 |
| Shuffle@5 | 0.313 | 0.313 | — | — | — | — |
| Random@5 (macro) | 0.3125 | 0.3125 | 0 | 0.3125 | 0.3125 | 0.3125 |

**Micro = Macro**（\|T\| 恒定）：seed42 micro@5 = 0.5238 = macro@5。

### 分布

| 量 | mean | min | max |
|----|------|-----|-----|
| candidate_count | 16.0 | 16 | 16 |
| teacher_candidate_count | 8.0 | 8 | 8 |
| teacher_path_edge_count | ≈134 | 50 | 179+ |
| actual_topK_count @5 | 5.0 | 5 | 5 |

### 边界核对

- max observed Recall@5 = **0.625** = theoretical_max（5/8）
- max observed Recall@1 = **0.125** = theoretical_max（1/8）
- **从未**出现 recall > min(1, K/\|T\|)

---

## 5. 评估有效性

| 检查 | 结果 |
|------|------|
| Shuffle@5 ∈ [0.289, 0.351] vs Random 0.3125 | 全部落在随机基线邻域 |
| 硬断言 intersection ≤ topK ≤ K | 1260/1260 通过 |
| 硬断言 recall ≤ min(1,K/\|T\|) | 1260/1260 通过 |
| edge_id 重复 / label 冲突 | 0 |
| 定义一致性 recall_denominator == \|T\| | 全部成立 |

**结论：v0.4 指标自洽，0.527 不是越界假象；此前疑点来自把 candidate_count=16 误认为 Recall 分母。**

---

## 6. 状态与门禁

```text
METRIC_AUDIT_PASSED
```

| 门禁 | 状态 |
|------|------|
| 50K/100K 长训 | **暂缓**——审计虽通过，但按优先级应先做候选池扫描（见下），不得以「审计通过」直接跳入长训 |
| 下一步优先 | candidate pool **16 → 32 → 64 → 128 → 256**，测 Path-Edge Recall / Search Reduction / Runtime |

### 工作点目标（非追求漂亮 Recall）

找到：**候选池扩大后，模型仍能保留关键真实道路候选，同时减少实际 GraphHopper 搜索量与运行时间** 的有效工作点。

评价轴：

1. Path-Edge Recall@K（相对全路径 ~134 边的覆盖）
2. Search Reduction（剪掉的候选 / 总候选，或减少的 GH 查询）
3. Runtime（p50/p95）

---

## 7. 不变量（本审计未破坏）

- 未修改 `recall_at_k` / `random_baseline` / LambdaRank 目标
- 未改测试标准、未为结果好看调参
- 未合成道路、未 Haversine 正式路线、未 teacher 特征泄漏
- 唯一代码修正：random **micro** 聚合笔误（`K/Σn` → `Σ(K·|T|/n)/Σ|T|`），使 random 与 macro **同模型同聚合**；该修正只会让 random micro 与理论 K/n 对齐，不会抬高真实模型指标

许可证与第三方：`THIRD_PARTY_NOTICES.md`
