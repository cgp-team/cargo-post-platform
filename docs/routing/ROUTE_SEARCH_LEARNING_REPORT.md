# ROUTE_SEARCH_LEARNING_REPORT

GraphHopper(CH) + OSM 真实道路 + HACO-CPS + LSR 多轮实验

## 版本迭代

| Ver | OD/groups | 区域 | label | Recall@1 | @5 | Shuffle@5 | Random@5 | 决策 |
|-----|-----------|------|-------|----------|----|-----------|----------|------|
| v0.1 | 25 groups | — | 规则切分 | — | — | — | — | REJECT label shortcut |
| v0.2 | 80 | 76/0/4 | 并列 relevance | 0.17 | 0.82 | — | — | REJECT NDCG 假 1.0 |
| v0.3 | 120 | 29/26/65 | 位置/并列 | **1.00** | **1.00** | 0.71 | ~0.36 | **REJECT 评估失效**（SHUFFLED 仍 0.71） |
| **v0.4** | **5×250** | **含江津 183/跨区 175** | **GH edge_id membership** | **0.113** | **0.527** | **0.317** | **0.3125** | **CANDIDATE（评估已可信）** |

## v0.4 评估定义（修复）

- **label**：`edge_id ∈ teacher 最优路径 edge_id 集合`（0/1，无并列、无 `i < m/2` 位置规则）
- **候选**：teacher 真实边 + GraphHopper 绕行路径真实边（不足则丢弃，禁止合成）
- **Recall@K** = `|model_topK ∩ T| / |T|`，其中 **T = 池内 label==1 的候选**（`teacher_candidate_count`，本实验 =8）
- **注意**：`candidate_count=16` 是池大小 n，**不是** Recall 分母；上限 `min(1, K/|T|)`，@5 上限 = 5/8 = 0.625
- **Path-Edge Recall@K** = `|model_topK ∩ T| / |teacher_path_edges|`
- **Distance/Duration Regret** = `(cost(top-|T|) - cost(teacher)) / cost(teacher)`
- **金标准**：仅置乱训练 label，测试 label 保持真实 → 必须落在随机基线
- **随机基线**：`E[Recall@K] = K / n_cands`（本实验 n=16 → @1=0.0625 / @5=0.3125）
- **Metric Audit**：见 `ROUTE_SEARCH_METRIC_AUDIT_V041.md` → **METRIC_AUDIT_PASSED**

## v0.4 多 seed 真实结果（42 / 123 / 3407 / 2026 / 8888）

| seed | groups | Recall@1 | @5 | Shuffle@5 | D-Regret | shuffle 金标准 |
|------|--------|----------|----|-----------|----------|----------------|
| 42 | 250 | 0.113 | 0.524 | 0.313 | 0.203 | OK |
| 123 | 250 | 0.121 | 0.537 | 0.289 | 0.133 | OK |
| 3407 | 250 | 0.111 | 0.526 | 0.331 | 0.123 | OK |
| 2026 | 250 | 0.123 | 0.536 | 0.351 | 0.202 | OK |
| 8888 | 250 | 0.115 | 0.524 | 0.298 | 0.101 | OK |

- 平均 lift@1 ≈ **+0.054**，lift@5 ≈ **+0.217**（相对随机，稳定）
- Shuffle@5 全部落在 0.29–0.35（随机 0.3125）→ **评估有效**
- label_balance=0.5；teacher 路径边数均值 ≈ 134；synthetic=0 / teacher 特征泄漏=0
- Path-Edge Recall@5 ≈ 0.04（候选仅覆盖全路径一小部分，上限受候选池限制——诚实反映检索难度）
- 训练时间 0.12s/250 groups（LightGBM 小样本；**时间短 ≠ 记忆**，以 shuffle 金标准为准）

## 真实基准

- GH 图：218,402 nodes / 277,491 edges（CH）
- 邮电→工商：13,860m / **44ms**
- v0.4 生成：250 OD ≈ 23s / 1250 GH calls（约 5 calls/OD 含绕行）

## 对 v0.3 的收回

- v0.3 Recall@1/5=1.0 **作废**：SHUFFLED-label 仍 0.71，且 NDCG 在并列 relevance 下退化
- 「长训就绪」结论 **收回**
- MODEL_NOT_TRUSTED → v0.4 起 **评估可信**；模型本身仅 **CANDIDATE**

## 残留 / 下一步（未完成不得升 ACTIVE）

1. GH CH vs LM vs Cache+CH 完整 benchmark（p50/p95/p99）
2. worst-20 失败案例分析
3. 候选池扩大后的 Path-Edge Recall（当前受 16 边候选上限约束）
4. 50k/100k 长训 + checkpoint（仅在指标保持合理分布时）
5. 区域泛化：江津 hold-out

## 状态

**ROUTE_SEARCH_MODEL_CANDIDATE**（评估链路 HEALTHY；模型稳定优于随机，但证据不足以 ACTIVE）

许可证：`THIRD_PARTY_NOTICES.md`
