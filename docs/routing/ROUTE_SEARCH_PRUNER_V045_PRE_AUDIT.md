# ROUTE_SEARCH_PRUNER_V045_PRE_AUDIT

对象：V0.44 `BranchPointPruner` + `run_v044_pruner_experiment`  
抽样：10 真实 OD（seed=42，instrumented `PruneStats`）  
对照全量：`v044_result.json` 92 OD

---

## 1. 每个 OD 调用多少次 feature build / predict

（抽样 10 OD 求和）

| 项 | 合计 | 每 OD 均值 | 备注 |
|----|------|-----------|------|
| branch_points（degree≥3） | **18,981** | 1,898 | 进入 ML 流程的路口 |
| degree≤2 点 | **73,580** | 7,358 | 已禁 ML，但仍在热路径 |
| **ML_CALLS** | **5,706** | **571** | 真实 `ranker.predict` |
| ML_SKIPPED | 13,275 | 1,328 | benefit gate 跳过 |
| ML 命中率（CALLS/branch） | **30.1%** | — | |
| feature build 次数 | = ML_CALLS | 571 | 仅 ML 前构造 |
| batch size | prefilter_in/branch ≈ **3.3** | — | 仍是小 batch |

极端例 od4（长距/复杂）：branch **10,686**，ML **3,375**，feat 161ms，pred 436ms，prune 394ms，wall **1897ms** vs baseline **360ms**。

---

## 2. degree / batch 分布

| 观察 | 值 |
|------|-----|
| degree≤2 : branch | ≈ **3.9 : 1**（链状路为主） |
| 典型 branch batch | 3–4 条候选 |
| prefilter 移除率 | ≈ **33%**（5002→3193 等） |
| `protected`（Guard.must_keep） | **全部为 0** |

**问题**：Guard 桥接/唯一出口保护从未触发（抽样 10/10），断路只能靠 uncertain 保留 + keep≥2，仍不稳定。

---

## 3. prune_ms 组成（V0.44 记账）

`prune_ms` 混入了：

1. CheapStaticPrefilter（每边 2×haversine + 航向）
2. ML 后 argsort / 选 keep
3. heuristic 保护边查找

抽样 od2：feat 22.6 + pred 56.0 + prune 58.7 ≈ **137ms**，其中 prefilter 与 argsort 同量级。  
**V0.45 必须拆成** `prefilter_ms` / `prune_ms`，并单列 `guard_ms`。

---

## 4. Guard / BFS 耗时（V0.44 未入账）

`TargetReachabilityGuard(...)` 在 `t0` **之前**构造，反向 BFS **不计入** `wall_s` 分解，却发生在真实调用路径上。  
子图 ~5e4–9e4 节点时 BFS 可达数十毫秒。  
→ 端到端被低估；V0.45 计入 `guard_ms`，并做 **Guard cache**。

---

## 5. 为何仍不能墙钟转正（抽样直证）

| od | base_ms | ml_ms | ML_CALLS | 主导成本 |
|----|---------|-------|----------|----------|
| 6 短距 | **0.01** | 0.01 | 0 | 无意义 |
| 7 | 0.9 | 2.5 | 7 | 固定开销 |
| 0 unreach | 93 | 6.5 | 11 | 早停 |
| 5 unreach | 43 | 10 | 7 | 早停 |
| 1 | 52 | 167 | 506 | pred+prune |
| 2 | 62 | 280 | 587 | pred+prune |
| 8 | 97 | 419 | 784 | pred+prune |
| 4 | 360 | 1897 | 3375 | 全面 |

- Expansion Reduction 高的 od4 仍 **5× 更慢**：剪 50% 边 ≠ 省 50% 时间（堆/Python 开销非线性，且 3k 次 LightGBM）。
- 短距 baseline &lt;1ms 时，任何 ML 都是纯税 → 需要 **absolute_ml_budget_ms**。
- gate 已跳过 70% branch，但 571 次/OD 仍过多。

---

## 6. 静态 vs 动态特征

V0.44 `_build_features` 每次拼 24 维，含 `cum_*` / `origin_dist` / `dest_dist` / `progress_ratio`（动态）。  
动态项阻止整行缓存（已禁用错误缓存）。  
V0.45：**STATIC**（路类/速度/长度/单行/桥隧/静态航向）进 cache；**DYNAMIC** 仅在 ML 判断时增量拼接。

---

## 7. 实验模式要求（由审计推出）

1. **BASELINE** 纯 A*  
2. **HEURISTIC** prefilter + Guard + adaptive，**无 LightGBM**（候选墙钟转正路径）  
3. **ML** = heuristic + cache + batch LGB + absolute bypass + 真实 benefit gate  

外加 Exp A cache / B bypass / C guard cache / D 联合。

---

## 8. 状态

```text
V045_PRE_AUDIT_DONE
主因：branch 过多 × LightGBM 固定开销 + prefilter 记入 prune + Guard BFS 未入账 + 短距无 bypass
下一步：static cache / absolute ML bypass / guard cache / 三模式墙钟 benchmark
```
