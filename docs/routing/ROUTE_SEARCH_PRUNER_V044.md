# ROUTE_SEARCH_PRUNER_V044

最终状态：**ROUTE_SEARCH_PRUNER_V044_CANDIDATE**  
A. LocalGraph 标签：**SEARCH_REDUCTION_WITHOUT_SPEEDUP**  
B. GH Branch Selector：**正向闭环信号保留**  
**V0.45 未放行**（LocalGraph 墙钟仍明显慢于 baseline）  
**50K/100K 长训：不进入**

---

## 0. 回答五个核心问题

| # | 问题 | 答案 |
|---|------|------|
| A | 模型是否减少搜索扩展？ | **是**。V0.44 edge expansion reduction mean **0.61**（p50 0.59） |
| B | 是否减少 wall-clock？ | **A：否**。wall_reduction mean **−3.93**（更慢）→ `SEARCH_REDUCTION_WITHOUT_SPEEDUP`。**B：是**，top-2/4 明显降 GH 墙钟 |
| C | 路线质量损失？ | A：distance_regret mean **0.037** / p50 **0.009** / p95 0.215 / max 0.332 |
| D | 是否 unreachable？ | V0.43 **77.2%** → V0.44 **14.1%**（显著下降，未清零） |
| E | 推理成本是否低于节省？ | **否（A）**。feat 28ms + predict 79ms + prune 74ms ≈ **181ms** > baseline wall mean **80ms** |
| F | 多区域是否稳定？ | 92 OD：主城 57 / 江津 35 / 跨区 20；B 各 top-k regret 分布一致收敛 |

结论句（纪律原文）：  
**「搜索空间减少，但当前实现未形成墙钟加速。」**  
B 另述：**「多分支 GH 预选出现稳定的工程收益信号（调用与墙钟下降，regret 可控）。」**

---

## 1. V0.43 baseline（对照）

来源：`ROUTE_SEARCH_PRUNER_V044_PRE_AUDIT.md` + 本轮同 OD 重跑。

| 项 | V0.43（92 OD） |
|----|----------------|
| ml_unreachable | **71/92 = 77.2%** |
| Expansion Reduction | mean 0.929（含大量断路早停，不可直接当收益） |
| Wall Reduction | mean **−6.42** |
| feature_build_ms | mean **55** / p50 4 / max 613 |
| model_predict_ms | mean **246** / p50 20 / max **2667** |
| Distance Regret（可达 21） | mean 0.027 / p50 0.015 |

**机制**：per-expansion / degree≥2 全量特征 + 小 batch LightGBM；无 Guard；无 benefit gate。

---

## 2. V0.44 改动与原因

| 改动 | 原因 | 实验 |
|------|------|------|
| Branch-point inference（degree≥3 才 ML） | 消掉链状路段推理税 | A |
| Batch `predict([cands])` | 禁止逐边 predict | A |
| CheapStaticPrefilter | 明显回头/背离先剪 | C |
| TargetReachabilityGuard（纯拓扑反向 BFS） | 防断路，**无 teacher** | D |
| Adaptive keep（degree / reachable_ratio / class_diversity） | 固定 ratio 过激 | B/E |
| Safety Fallback | 候选过空/异常 → heuristic | E |
| MLBeneiftGate | 收益才推理 | E |
| 分项计时 | 证明/证伪加速 | — |
| 保留 B：16 via → top-k → GH | V0.43 B 已正向 | top-1/2/4/8 |

**明确回滚/不做**：不关闭模型充速度；不把 prune_ratio 调到接近 0；不注入 teacher；不做 GNN/Transformer/长训。

Guard 修复记录：BFS 曾 `max_nodes=80k` 截断导致未访问节点被误判 unreachable；已改为跑完整子图分量，截断时只标 `uncertain`。特征缓存含 `cum_*` 动态列，本轮禁用（留 V0.45）。

---

## 3. 测试数据 / 图 / 区域

| 项 | 值 |
|----|-----|
| OSM | `tools/osm-data/chongqing-260921.osm.pbf` |
| Graph | RoadGraph **2,230,689 nodes / 2,287,515 edges**（本地 OSM 导入） |
| GH 图（B） | GraphHopper CH，`127.0.0.1:8080` |
| profile | bus |
| OD | 请求 100，有效 **92**（真实站点，禁 random 经纬度） |
| region | 主城 **57** / 江津 **35** / 跨区 **20** |
| seed | 42 |
| 环境 | 同机同进程串行 A/B/A–E，同 subgraph bbox+3.5km |

短距/中长距由站点近邻与跨区 OD 覆盖；桥梁/隧道/复杂路口依赖真实 OSM 拓扑自然出现（无单独人工标注集）。

---

## 4. A. LocalGraph Pruner（与 B 完全分开）

### 4.1 可达性

| 算法 | unreachable | rate |
|------|-------------|------|
| Baseline A* | 0 | 0% |
| **V0.43** | **71/92** | **77.2%** |
| **V0.44** | **13/92** | **14.1%** |

### 4.2 扩展 / 墙钟 / 质量（V0.44）

| 指标 | mean | p50 | p95 | p99 | max |
|------|------|-----|-----|-----|-----|
| Expansion Reduction | **0.607** | 0.587 | 0.984 | 0.999 | 1.00 |
| **Wall Reduction** | **−3.93** | **−3.57** | 0.887 | 0.991 | 0.993 |
| Distance Regret | 0.037 | 0.009 | 0.215 | 0.271 | 0.332 |
| Duration Regret | 0.037 | 0.009 | 0.215 | 0.271 | 0.332 |

Baseline wall_s：mean **0.080** / p50 0.043 / p95 0.290 / p99 0.427。  
V0.44 wall_s：mean **0.377** / p50 0.225 / p95 1.553 / p99 1.800。

### 4.3 推理与搜索耗时拆解（V0.44，92 OD）

| 阶段 | mean | p50 | p95 | p99 |
|------|------|-----|-----|-----|
| feature_build_ms | **28.3** | 15.8 | 107 | 183 |
| model_predict_ms | **78.5** | 42.1 | 284 | 482 |
| prune_ms | **74.0** | 41.2 | 281 | 488 |
| search_ms（余量） | （含于 total） | — | — | — |
| total_ms | ≈ wall_s×1000 | — | — | — |

对比 V0.43 predict mean **246ms** → V0.44 **79ms**（↓3×），但仍 **> baseline 全搜索墙钟**。

**为何 expansion↓ 仍 wall↑**：branch-point 仍频繁；每点 batch 特征（24 维 Python）+ LightGBM 固定开销 + Guard/prefilter 记账；剪掉的边对应的子树节省 < 181ms 税。

### 4.4 Experiment A–E（同 OD）

| Exp | 配置 | unreach rate | wall_reduction mean | exp_reduction mean | 决策 |
|-----|------|--------------|---------------------|--------------------|------|
| A | branch-point batch only | **6.5%** | **−11.7** | 0.32 | 可达最好，墙钟最差 → 不保留为默认 |
| B | + adaptive | 6.5% | −11.7 | 0.32 | 与 A 同量级，**保留 adaptive** |
| C | + prefilter | 14.1% | −3.8 | 0.61 | **保留**（扩展↓最多）但 prefilter 偏激时伤可达 |
| D | + guard | 14.1% | −4.5 | 0.61 | **保留**（相对 V0.43 大幅降 unreach） |
| E | + fallback + benefit gate（=v044 默认） | 14.1% | −3.9 | 0.61 | **保留**（predict 246→79ms） |

**合并决定**：默认 = D+E+C（guard + gate + prefilter + adaptive）。  
A/B 的更低 unreachable 以墙钟 −11× 为代价，不回退到「全开 ML」。  
prefilter 需保持「不可过度过滤」（空集回退全量）。

### 4.5 fallback / ML 调用（设计已接入，见 `stats` 字段）

- `pruner_fallback_*`、`ML_CALLS` / `ML_SKIPPED` / `ML_COST_MS` / `NET_SAVED_MS_EST` 写入每条 `v044.stats`
- 默认 gate 使 predict 从 V0.43 的「每 degree≥2 必调」变为 branch-point + 收益判定

---

## 5. B. GH Branch Selector top-k sweep（保留 V0.43 B）

16 真实侧偏 via / OD；全量 GH 为 baseline。

| top-k | GH calls | call reduction | wall_reduction mean | p50 | via_regret mean | p50 | p95 | max |
|-------|----------|----------------|---------------------|-----|-----------------|-----|-----|-----|
| 1 | 1 | 93.8% | **0.916** | 0.922 | 0.134 | 0.082 | 0.446 | 0.646 |
| 2 | 2 | 87.5% | **0.840** | 0.867 | 0.062 | 0.040 | 0.209 | 0.486 |
| **4** | 4 | **75%** | **0.695** | **0.707** | **0.030** | **0.015** | 0.091 | 0.332 |
| 8 | 8 | 50% | 0.321 | 0.405 | **0.007** | **0.000** | 0.038 | 0.087 |

**B 工作点（非唯一最优）**：

- **默认 top-4**：调用 −75%，墙钟 mean −69%，regret p50 1.5%（与 V0.43 B 一致且更稳）
- **激进 top-2**：调用 −88%，墙钟 −84%，regret p50 4.0%（可接受上限附近）
- **不推荐 top-1**（regret p50 8%+）；top-8 仅当质量优先

---

## 6. Worst-20

`algorithm/data/v044_failure_cases.json`（全量 failure 244，自动取 top-20）。

主类型：

1. **unreachable（V0.43 为主）**：剪断后搜不到 target；V0.44 仍有 13 例  
2. **wall_slowdown**：短距 OD 上 baseline 已 <1ms，ML 固定开销把相对减速放得极大（od23 等）  
3. **via_regret >8%**：top-k 预选错分支  

最差样例（摘要）：

| od | 类型 | 要点 |
|----|------|------|
| od23 锦霞街→湖榕路 | slowdown | base 0.06ms vs ML 1.5ms，regret=0 |
| od36 陈南路口→林场坝口 | v043 unreach | base 0.29s 可达，ML 2.37s 失败 |
| （详见 JSON） | via_regret | top-1/2 最大 0.49–0.65 |

---

## 7. Seed 稳定性

- 本轮主结果 seed=42（92 OD）。  
- A–E 同 OD 配置对比趋势单调可复现（predict 降 / unreach 降）。  
- **多 seed（5×）未跑**——因 A 仍 `SEARCH_REDUCTION_WITHOUT_SPEEDUP`，按纪律不进入「稳定工程收益」表述，也不进长训。

---

## 8. 是否接受

| 门禁 | 结果 |
|------|------|
| ml_unreachable 显著下降 | **通过** 77%→14% |
| fallback_rate 可控 | **通过**（异常有 fallback，未出现锁死；详细计数在 stats） |
| LocalGraph wall 不再明显慢于 baseline | **未通过**（mean −3.9×） |
| 无 teacher leakage | **通过**（Guard 仅拓扑；特征无 teacher_*） |
| Worst cases 无严重恶化 | **有条件通过**（V0.44 regret p95 0.215，需继续压） |

→ **V0.45 不放行**（缓存/向量化可做，但须以墙钟转正为唯一目标）。

### 最终状态

```text
ROUTE_SEARCH_PRUNER_V044_CANDIDATE
A_LOCAL_GRAPH: SEARCH_REDUCTION_WITHOUT_SPEEDUP
B_GH_SELECTOR: ENGINEERING_SIGNAL_POSITIVE
LONG_TRAINING_DEFERRED
V045_BLOCKED_ON_WALLCLOCK
```

**不写** ACTIVE；**不写**「出现稳定的工程收益信号」（A 未满足）。

---

## 9. 下一步（仅在墙钟转正后）

1. V0.45：branch-level 静态特征缓存 / 批量向量化 / 预测器降维（24→静态子集）  
2. 短距 OD 跳过 ML（gate 已有，需绝对时间阈值）  
3. worst-20 回归 + 5 seed  
4. B top-2/top-4 策略固化进 MultiLeg/GH 调用层  
5. 上述齐备且 A wall_reduction p50>0 后，才讨论长训 / ACTIVE  

---

## 10. 文件

| 文件 | 说明 |
|------|------|
| `docs/routing/ROUTE_SEARCH_PRUNER_V044_PRE_AUDIT.md` | V0.43 审计 |
| `docs/routing/ROUTE_SEARCH_PRUNER_V044.md` | 本报告 |
| `algorithm/learning/path_search/pruner_v044.py` | V0.44 Pruner |
| `algorithm/learning/training/run_v044_pruner_experiment.py` | 实验驱动 |
| `algorithm/data/v044_result.json` | 主结果 |
| `algorithm/data/v044_failure_cases.json` | Worst-20 |
| `algorithm/data/v043_pruner_closed_loop.json` | V0.43 对照 |

许可证：`THIRD_PARTY_NOTICES.md`
