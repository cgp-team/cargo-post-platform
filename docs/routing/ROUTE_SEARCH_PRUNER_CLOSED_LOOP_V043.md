# ROUTE_SEARCH_PRUNER_CLOSED_LOOP_V043

状态：**CLOSED_LOOP_MEASURED**（结果诚实记录；**未**宣称全面加速；长训仍暂缓）  
数据：`algorithm/data/v043_pruner_closed_loop.json`  
代码：`algorithm/learning/training/run_v043_pruner_closed_loop.py`  
前置：Pool Sweep 工作点 `ROUTE_SEARCH_POOL_SWEEP_V042.md`

---

## 1. 闭环设定

| 项 | 值 |
|----|-----|
| 真实路网 | OSM PBF → RoadGraph **2,230,689 nodes / 2,287,515 edges** |
| 走廊子图 | OD+seed 折线 bbox +3.5km |
| 搜索 | A*（haversine **仅启发**，不作正式成本） |
| 剪枝 | `RouteSearchPruner` + LightGBM，`min_candidates=2`，保护启发式最优后继 |
| prune_ratio | 0.5（冒烟）/ **0.7（正式）** |
| 多分支 | 16 个真实侧偏 via；Baseline 全查 GH；LSR 预选 **top-4** |
| OD | 24（seed=42）；有效行 **23** |
| Ranker | 25 组真实 GH 边样本现训 |

### 过程修正（非调参美化，是闭环可运行的 bugfix）

1. `RouteSearchPruner.prune` 原 `keep_n=max(3,·)` 在真实路口度数 2–5 上**恒不剪**；改为 `max(1, round(n*ratio))`，并开放 `min_candidates`（默认 8，闭环传 2）。  
2. 预选评分从「per-via 双向 Dijkstra」改为「两次单源距离表」（否则墙钟被局部搜索吃掉）。  
3. 保护启发式最优后继，避免剪断连通（仍非 teacher 泄漏）。

---

## 2. A. LocalGraph 扩展剪枝

| 指标 | 结果 |
|------|------|
| **ml_unreachable** | **18 / 23（78%）** — keep=0.7 仍大量断路 |
| 可达子集 n | 5 |
| Expansion Reduction | **mean 0.79**（min 0.51 / p50 0.90 / max 0.93） |
| Distance Regret | mean **0.023**（p50 0.012 / max 0.064） |
| Wall Reduction | **mean −8.7（更慢）** |
| Base expansions | mean 1.07e5 |
| ML expansions（可达） | mean 9.0e3 |

### 解读（A）

- 一旦路径可达，扩展次数可降 **约 50–93%**，距离代价约 **2%**。  
- 但 **78% 不可达** = 当前 Pruner 对真实路口仍过激，不能直接当生产搜索器。  
- **墙钟反而更慢**：每个扩展构 `EdgeCandidate` + LightGBM predict 的开销 > 省下的松弛。  
  → 「扩展少」≠「更快」，除非特征/推理降到近似 O(1) 或批量向量化。

---

## 3. B. 多分支 GH：调用次数与 p50/p95

| 指标 | Baseline | LSR（预选+GH） |
|------|----------|----------------|
| GH calls / OD | **16** | **4**（固定 top-4） |
| Call Reduction | — | **75%** |
| GH wall / OD | mean **0.281s** | mean **0.188s** |
| Wall Reduction | — | **mean +24.2% / p50 +36.5% / p95 +60.1%**（有离群 −184%） |
| 单次 GH latency | mean 17.5ms | mean 17.3ms |
| GH latency p50 | 14.5ms | 13.5ms |
| GH latency p95 | 36.7ms | 33.9ms |
| GH latency p99 | 42.8ms | 38.9ms |
| Via Distance Regret | — | **mean 0.035 / p50 0.015 / p95 0.120 / max 0.123** |

### 解读（B）

- **GH 调用次数下降 75% 成立**（16→4，真实计数）。  
- **墙钟 p50/p95 改善成立**（中位数快约 37%，p95 快约 60%）；均值 +24%，但存在少数 OD 预选反而更慢（min wall_reduction −1.84）。  
- 单次 GH 延迟基本不变——省的是调用次数，不是单次路由变快。  
- 质量代价：via regret 中位数 **1.5%**，p95 **12%**（预选错分支时）。

---

## 4. 与目标对齐

> 「减少实际 GraphHopper 搜索量和运行时间」

| 目标 | 状态 |
|------|------|
| 减少 GH 搜索量 | **成立**：调用 16→4（−75%） |
| 减少运行时间 | **部分成立**：多分支墙钟 p50/p95 改善；LocalGraph+Pruner 墙钟未改善 |
| 保留真实道路质量 | via regret p50 1.5%；Local regret（可达时）2.3% |
| Pruner 可作生产搜索器 | **否** — 78% 不可达 |

---

## 5. 状态

```text
CLOSED_LOOP_MEASURED
ROUTE_SEARCH_MODEL_CANDIDATE
LONG_TRAINING_DEFERRED
```

**不宣称**：全面加速、Pruner 可替换 Dijkstra、指标已最优。

### 下一步（按缺口）

1. **降低 Pruner 推理开销**（批量 predict / 轻量特征）— 否则 Local 闭环墙钟无法转正  
2. **连通性保护**（keep_ratio 自适应、或保护 top-2 启发式边）— 把 unreachable 从 78% 压下来  
3. worst-20：via regret 12% 的失败分支  
4. 江津 hold-out  
5. 以上齐备后再议长训 / ACTIVE

许可证：`THIRD_PARTY_NOTICES.md`
