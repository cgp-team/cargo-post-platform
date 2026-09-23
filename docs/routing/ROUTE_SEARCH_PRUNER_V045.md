# ROUTE_SEARCH_PRUNER_V045

最终状态：**ROUTE_SEARCH_PRUNER_V045_REJECTED**  
标签：**V045_BLOCKED_ON_WALLCLOCK**  
**LONG_TRAINING_DEFERRED**（50K/100K 禁止）  
**B_GH_SELECTOR：ENGINEERING_SIGNAL_POSITIVE（保留，不因 A 失败而推翻）**

---

## 0. 必须诚实回答的问题

> 「减少搜索扩展是否真正减少了计算时间？」

**否。**

| 模式 | Expansion Reduction | Wall Reduction mean | 判定 |
|------|---------------------|---------------------|------|
| Baseline A* | — | — | 对照 |
| V0.44 ML | 0.61 | **−3.93** | 更慢 |
| **V0.45 HEURISTIC（无 LGB）** | **0.61** | **−18.0** | 更慢 |
| **V0.45 ML（cache+bypass）** | 0.61–0.77 | **−16.6** | 更慢 |

即使 **完全去掉 LightGBM** 的 HEURISTIC，墙钟仍显著慢于纯 A*。  
→ 根因不是「模型不够好」，而是 **Python A* 上的 per-node 回调 / Guard BFS / prefilter 固定开销 >> 省下的堆松弛**。

结论句（纪律）：**「搜索空间减少，但当前实现未形成墙钟加速。」**

---

## 1. V0.44 基线（原样，不美化）

| 项 | 值 |
|----|-----|
| OSM | 2,230,689 nodes / 2,287,515 edges |
| ml_unreachable | 13/92 = **14.1%** |
| Expansion Reduction | mean **0.607** |
| Distance Regret | mean **0.037** / p95 **0.215** |
| Baseline wall | mean **0.080s** |
| V0.44 wall | mean **0.377s** |
| Wall Reduction | **−3.93** |
| feature_build | **28.3ms** |
| model_predict | **78.5ms** |
| prune | **74.0ms** |

V0.44 B：top-4 call reduction **75%**，wall reduction mean **≈69.5%**，via regret p50 **≈1.5%**；top-2 wall **≈84%** / regret p50 **≈4.0%**。

---

## 2. V0.45 预审计摘要

（全文 `ROUTE_SEARCH_PRUNER_V045_PRE_AUDIT.md`）

- 10 OD 抽样：branch **18,981**，**ML_CALLS 5,706**（命中率 30%），batch ≈ **3.3**
- degree≤2 : branch ≈ 3.9 : 1
- `protected`（Guard.must_keep）**全 0**
- Guard 反向 BFS 建图 **未入账** 却在真实路径
- 短距 OD baseline 可 &lt;1ms，任何 ML 必亏

---

## 3. V0.45 改动

| 改动 | 实现 | 结果 |
|------|------|------|
| STATIC/DYNAMIC 特征拆分 | `branch_feature_cache.py` | Exp A 未使墙钟转正 |
| Branch Feature Cache | key=`graph_version\|node\|target_region\|profile` | 有 hit，不够抵回调开销 |
| absolute_ml_budget_ms=5 | 短剩余搜索 bypass | Exp B 仍 −17.9 |
| Guard Cache + **共享反向邻接** | 同子图只建一次 radj | Exp C 仍 −17.0 |
| Benefit gate 真实 est/actual | `ESTIMATED_SAVED_MS` / `ACTUAL_SAVED_MS` / `estimation_error` | 估计有偏，gate 不能单独救墙钟 |
| 三模式 | BASELINE / HEURISTIC / ML | HEURISTIC **仍最慢级别** |
| 计时拆分 | feature/prefilter/guard/ml/prune/search/total | 可证伪「有加速」 |

**接受规则执行**：Wall 无改善 → **REJECT**（不看其它指标是否漂亮）。

---

## 4. 测试数据

| 项 | 值 |
|----|-----|
| OSM | `chongqing-260921.osm.pbf` → RoadGraph 2.23M nodes / 2.29M edges |
| OD | 80 请求 / **77 有效**（真实站点） |
| region | 见 `v045_result.json` region_dist |
| bucket | SHORT / MEDIUM / LONG（&lt;3km / 3–12km / &gt;12km） |
| seed | 42 |
| repeats | 2（取中位墙钟） |
| 同环境 | 同机同进程，Baseline / V044 / V045 同 subgraph |

短距分桶：SHORT 上 wall_reduction 可达 **−68 ~ −100×**（baseline 亚毫秒级），与预审计一致。

---

## 5. A. LocalGraph（V0.45 正式）

### 5.1 HEURISTIC（无 LightGBM）

| 指标 | mean | p50 | p95 | p99 | min | max |
|------|------|-----|-----|-----|-----|-----|
| Wall Reduction | **−18.02** | −3.69 | −0.91 | −0.76 | −684.5 | **−0.36** |
| Expansion Reduction | 0.607 | 0.557 | 0.986 | 0.997 | 0.12 | 1.00 |
| unreachable | **15/77 = 19.5%** | | | | | |
| Distance Regret | 0.011 | 0 | 0.046 | — | — | — |

**最好的一例仍慢 36%**（max wall_reduction = −0.36）。

### 5.2 ML（cache + bypass + gate）

| 指标 | mean | p50 | p95 |
|------|------|-----|-----|
| Wall Reduction | **−16.59** | −5.74 | −1.56 |
| unreachable | **14/77 = 18.2%** | | |
| ML_CALLS | 452 | 227 | 1321 |
| ML_OVERHEAD_MS | 105 | 51 | 350 |
| Distance Regret | 0.016 | 0.013 | 0.044 |

### 5.3 Experiment A–D

| Exp | wall_reduction mean | unreach | 决定 |
|-----|---------------------|---------|------|
| A cache only | **−14.95** | 18.2% | **REJECT** |
| B bypass only | **−17.90** | 18.2% | **REJECT** |
| C guard cache only | **−16.99** | 18.2% | **REJECT** |
| D joint (=ML) | **−16.59** | 18.2% | **REJECT** |

无一使 wall_reduction 转正。按规则：**全部 REJECT**；D 不因「联合」而放行。

### 5.4 为何 HEURISTIC 仍输

1. 每节点 Python `on_expand` ≫ 紧凑 A* 内联松弛  
2. Guard BFS（即使共享 radj）每 OD 仍要从 target 扫连通分量  
3. Prefilter 两跳 haversine × branch  
4. Expansion 降 60% 不等于时间降 60%（二叉堆/字典非线性 + 回调固定税）

**这是实现/语言层结构成本，不是超参没调好。** 禁止用「关模型 / prune_ratio→0」假装通过。

---

## 6. B. GH Branch Selector（保留）

| top-k | call reduction | wall_reduction mean | p50 | via_regret mean | p50 | via_dur_regret p50 |
|-------|----------------|---------------------|-----|-----------------|-----|---------------------|
| 1 | 93.8% | 极高 | — | 过大 | — | — |
| 2 | 87.5% | 高 | — | 偏大 | ~4% | — |
| **4** | **75%** | **0.767** | **0.793** | **0.028** | **0.014** | **0.008** |
| 8 | 50% | 中 | — | 小 | ~0 | — |

本轮 B 仍 **Wall Reduction mean ≈ +77%**（top-4），与 V0.44 同向。  
**不把 top-2 写死**；默认观察点仍 top-4。

**A 失败不推翻 B。**

---

## 7. Worst-20 回归

`algorithm/data/v045_failure_cases.json`（failure_count **260**，worst_20 已截取）。

| 类型 | 说明 |
|------|------|
| wall_slowdown | 大量；含 SHORT 相对减速爆炸 |
| unreachable | H 15 + ML 14 |
| ml_overhead | ML_CALLS 数百～数千时 OVERHEAD 数百 ms |
| benefit_gate_error | estimation_error 与 actual_saved 偏差大 |

**V0.44 已知 od23 / od36**：在 `v044_known_od23_od36` 字段；短距 od23 类 slowdown **未修复**（absolute bypass 不能消除 sub-ms baseline 上的相对放大），od36 类 unreachable 在 H/ML 仍可见同类。

---

## 8. 多 seed

**未跑**（42/123/3407/2026/8888）。  
原因：单 seed 墙钟明确未转正，按纪律不写「稳定」，也不进入多 seed 包装。

---

## 9. 最终决策

| 门禁 | 结果 |
|------|------|
| Wall-clock 改善 | **未通过**（H −18 / ML −16.6） |
| unreachable 可控 | 部分（18–19%，高于 V044 14%） |
| regret 可控 | 可达子集 regret 低 |
| teacher leakage = 0 | **通过** |
| B 保留 | **是** |

```text
ROUTE_SEARCH_PRUNER_V045_REJECTED
A_LOCAL_GRAPH: SEARCH_REDUCTION_WITHOUT_SPEEDUP
B_GH_SELECTOR: ENGINEERING_SIGNAL_POSITIVE
LONG_TRAINING_DEFERRED
V045_BLOCKED_ON_WALLCLOCK
```

**不写** CANDIDATE；**不写**「出现稳定的工程收益信号」。

---

## 10. 下一步（若继续）

仅当能把 **per-node 回调成本** 降到可与纯 A* 竞争时才值得再做 V0.46：

1. 剪枝逻辑 **内联进 A* 主循环**（无 Python 回调）或 Cython/原生  
2. Guard 改为 **双向 A* / 目标导向剪枝**，避免全分量 BFS  
3. 只在 **degree≥4 且 estimated_remaining ≫ ml_cost** 时抽样 ML（而不是每个 branch）  
4. 生产路径优先落地 **B：GH top-2/4 预选**（已具备墙钟收益）  
5. 禁止：50K/100K、GNN、Transformer、RL、关闭模型充数

---

## 11. 文件

| 文件 | 说明 |
|------|------|
| `ROUTE_SEARCH_PRUNER_V045_PRE_AUDIT.md` | V0.44 调用/耗时审计 |
| `ROUTE_SEARCH_PRUNER_V045.md` | 本报告 |
| `learning/path_search/branch_feature_cache.py` | static/branch cache + GuardCache |
| `learning/path_search/pruner_v045.py` | V045 controller |
| `learning/training/run_v045_experiment.py` | 三模式 + A–D + B top-k |
| `data/v045_result.json` / `v045_failure_cases.json` | 结果与 Worst-20 |

许可证：`THIRD_PARTY_NOTICES.md`
