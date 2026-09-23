# ROUTE_SEARCH_PRUNER_V044_PRE_AUDIT

对象：V0.43 LocalGraph Pruner + 多分支 GH（`run_v043_pruner_closed_loop.py` / `RouteSearchPruner`）  
数据：`algorithm/data/v043_pruner_closed_loop.json`（23 OD 可用行，keep_ratio=0.7）  
结论先行：**扩展减少未转化为墙钟加速**；根因是 per-expansion 全量特征 + 单点 LightGBM predict，外加过激剪枝断路。

---

## 1. V0.43 每次 A* expansion 实际做了什么

`dijkstra_stats()` 弹出节点 `u` 后：

| 步骤 | 条件 | 动作 |
|------|------|------|
| 1 | 恒定 | `adj.get(u)` 取后继边 |
| 2 | `len(succ) >= 2`（几乎恒真） | 对**每条**后继调用 `road_edge_to_candidate` |
| 3 | 同上 | 选 1 条启发式最优边作 `protect` |
| 4 | 同上 | `pruner.prune(cands, min_candidates=2)` |
| 5 | 同上 | 按 `edge_id` 过滤 succ |
| 6 | 恒定 | 对剩余 succ 做 A* 松弛 |

**问题**：degree=2 的链状路段也进 ML；`min_candidates=2` 等于「凡有分叉就预测」。

---

## 2. 每次 candidate 构造做了什么

`road_edge_to_candidate(e, origin, dest, cum_m)`：

- 取 polyline 中点
- 查 road_class → code
- `haversine_m(mid, origin)` + `haversine_m(mid, dest)`
- 组装 **24 维** `EDGE_FEATURES` dict
- `new EdgeCandidate(...)`
- 随后 `feature_vector(c)` 再按 `EDGE_FEATURES` 抽 24 float → `np.asarray`

**每次 expansion 代价 ≈ O(degree × 24 特征) + 2 次 haversine + Python dict/对象分配。**  
V0.43 **无特征缓存、无向量化、无静态特征预计算。**

---

## 3. LightGBM `predict` 调用量

`RouteSearchPruner.prune()`：

```text
X = np.asarray([feature_vector(c) for c in candidates])  # 逐行 Python
scores = self.ranker.predict(X)                          # 每 junction 1 次
```

- 调用形态：**每 junction 一次 predict，batch size = degree（常 2–5）**
- V0.43 **禁止清单里要消掉的**「逐 candidate predict」已合并为一次，但仍是 **per-expansion / per-junction 小 batch**
- 节点扩展量级（base edge expansions mean **1.07e5**，node expansions 约 10⁴–10⁵）  
  → **每条搜索 10⁴–10⁵ 次** `model.predict`（每次 2–5 行）
- LightGBM 单次 predict 固定开销 >> 2–5 行收益 → **推理时间主导**

`ranker.fallback=True` 时 prune 直接放行；训练失败会静默不剪，但 V0.43 正式跑中 ranker 已训练，预测路径是热路径。

---

## 4. 时间花费拆解（V0.43 账目不全）

| 阶段 | V0.43 是否单独计时 | 实测/推断 |
|------|-------------------|-----------|
| feature build | **否** | 与 search 混在 `wall_s`；占 ML 路径大头 |
| model predict | **否** | 与 search 混在 `wall_s` |
| prune（argsort/keep） | **否** | 相对小 |
| graph search（松弛/堆） | **否** | base 路径可见：`base_wall_s` mean **0.339s** |
| cache | **否** | 无 cache |
| total | 是（`local_ml_wall_s`） | 可达样本 mean **1.28s** vs base **0.34s** |

`benchmark timer` 仅 `dijkstra_stats` 入口 `perf_counter` → 出口；**无法回答**「feature / predict / prune / search 各占多少」。  
V0.44 必须打点：`feature_build_ms / model_predict_ms / prune_ms / search_ms / total_ms`。

---

## 5. keep_n / min_candidates / prune_ratio / 保护 / 连通

| 项 | V0.43 | 影响 |
|----|-------|------|
| `min_candidates` | 2（闭环传入） | degree≥2 就 ML |
| `keep_n` | `max(1, round(n * keep_ratio))` | degree=2、ratio=0.5 → **只留 1 边** |
| `prune_ratio` | 固定 0.5 / 0.7 | 无自适应 |
| heuristic protection | **仅 1 条** 最小 `haversine(mid,dest)` 边 | 不够防断路 |
| connectivity | **无** TargetReachability | 剪断唯一桥即 unreachable |
| safety fallback | **无** | 模型剪死就失败 |
| ML-benefit gate | **无** | 每 junction 必付推理税 |

`ml_unreachable = 18/23 = 78%` 与「degree=2–3 只留 1–2 边 + 无拓扑可达保护」一致。

---

## 6. Corridor / reachability / 候选生成

| 项 | V0.43 |
|----|-------|
| corridor | `subgraph_bbox`：OD+seed 折线采样点 bbox **+3.5km**（真实边，非合成） |
| target reachability | **无** 反向连通预计算 |
| candidate generation | 仅当前 `adj[u]` 后继（正确、局部） |
| teacher 用于连通 | **无**（符合禁令） |

Corridor 过大（可达时 base expansions 仍 1e5 级）放大了 predict 次数。

---

## 7. 为什么 Expansion Reduction ≠ Wall Reduction

定量（可达子集 n=5，keep=0.7）：

| 指标 | 值 |
|------|-----|
| Expansion Reduction | mean **0.79** |
| ML expansions | mean **9.0e3**（vs base 1.07e5） |
| Wall Reduction | mean **−8.7**（更慢） |

机制：

1. **固定成本**：每次 predict + 24 维 Python 特征，与「省下的边数」无关  
2. **调用次数仍高**：ML 路径在**每个** degree≥2 节点调用，直到被剪到窄胡同；可达样本 node 数仍成千上万  
3. **小 batch 亏本**：LightGBM predict(3×24) 的开销 ≈ 甚至大于再松弛 3 条边  
4. **失败税**：78% 跑到最后不可达，总时间被搜索/回溯浪费，却仍付了全程 predict  

故：**「搜索空间减少，但当前实现未形成墙钟加速。」**（V0.43 结论，与正式报告一致）

---

## 8. V0.43 计时与指标缺口清单

- [x] total wall（A/B 分开）  
- [ ] feature_build_ms  
- [ ] model_predict_ms  
- [ ] prune_ms  
- [ ] search_ms  
- [ ] cache_ms  
- [ ] ML_CALLS / ML_SKIPPED  
- [ ] cheap_prefilter_input / removed / keep_ratio  
- [ ] fallback_count / rate / reason  
- [ ] reachable rate 分桶（degree / road_class / region / distance）  
- [ ] duration regret（仅有 distance）  
- [ ] worst-20 结构化失败案例  
- [ ] Baseline vs V0.43 vs V0.44 同 OD 对照  
- [ ] B 的 top-1/2/4/8 sweep  

---

## 9. 对 V0.44 的直接约束（由审计推出）

1. **禁止** per-expansion、degree≤2 进 ML → **branch-point only（degree≥3）**  
2. **必须** 同 junction 一次 `predict(batch)`  
3. **必须** cheap static prefilter（长度/航向/回头/路类/明显下界）  
4. **必须** TargetReachabilityGuard（纯拓扑反向可达，禁 teacher）  
5. **必须** adaptive keep + Safety Fallback + ML-benefit gate  
6. **必须** 分项计时；否则无法证明加速  
7. B 保留，只做 top-k sweep  

---

## 10. 状态

```text
V043_PRE_AUDIT_DONE
问题定位：inference overhead + connectivity break
下一步：V0.44 实现（branch-point / batch / prefilter / guard / adaptive / fallback / benefit gate）
```
