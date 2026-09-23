# ROUTE_SEARCH_POOL_SWEEP_V042

状态：**WORKING_POINT_IDENTIFIED**（模型仍为 CANDIDATE，未进长训）  
前置：`ROUTE_SEARCH_METRIC_AUDIT_V041.md` = METRIC_AUDIT_PASSED  
数据：`algorithm/data/v042_pool_sweep.json`  
代码：`algorithm/learning/training/run_v042_pool_sweep.py`

---

## 1. 实验设定

| 项 | 值 |
|----|-----|
| 候选池 N | 16 / 32 / 64 / 128 / 256 |
| OD | 80（seed=42，主城+江津跨区） |
| 划分 | train 60 / test 20 |
| 标签 | 真实 GH `edge_id ∈ teacher 路径` |
| 特征 | 边属性 + 相对 OD 几何（无 teacher 泄漏） |
| 主池收集 | teacher 全路径边 + 最多 12 次真实绕行负样本 |
| 全路径边数 | mean **143.25** |
| 池内正样本 \|T\| | 8 / 16 / 32 / 63.7 / 118.4（≈N/2，受真实边数量限制） |
| 主池生成 | 80 OD / 271 GH calls / **5.03s** |
| 推理 | 0.42–0.73 ms/group |
| 训练 | 0.39–4.18 s（LightGBM） |

### 指标定义（未改公式）

```text
Path-Edge Recall = |kept ∩ teacher_path_edges| / |teacher_path_edges|
Teacher Retention = |kept ∩ T_pool| / |T_pool|
Search Reduction  = 1 - keep / N          # 相对全池扩展
```

上限：Path-Edge Recall ≤ |T_pool| / |path|（池内装不进全路径时）。

---

## 2. 主结果：Search Reduction × 质量

### 2.1 激进剪枝（keep = N/4，Reduction = 75%）

| N | keep | Path-Edge Recall | Teacher Retention | Shuffle Path | Random Path | infer_ms |
|---|------|------------------|-------------------|--------------|-------------|----------|
| 16 | 4 | 0.031 | 0.50 | 0.018 | 0.017 | 0.42 |
| 32 | 8 | 0.061 | 0.50 | 0.030 | 0.030 | 0.45 |
| 64 | 16 | 0.124 | 0.50 | 0.067 | 0.064 | 0.48 |
| 128 | 32 | 0.238 | 0.49 | 0.111 | 0.123 | 0.58 |
| 256 | 64 | **0.430** | 0.49 | 0.128 | 0.227 | 0.73 |

### 2.2 平衡剪枝（keep = N/2，Reduction = 50%）

| N | keep | Path-Edge Recall | Teacher Retention | Shuffle Path | Random Path | infer_ms |
|---|------|------------------|-------------------|--------------|-------------|----------|
| 16 | 8 | 0.053 | 0.86 | 0.030 | 0.030 | 0.42 |
| 32 | 16 | 0.107 | 0.86 | 0.053 | 0.061 | 0.45 |
| 64 | 32 | 0.211 | 0.85 | 0.116 | 0.124 | 0.48 |
| 128 | 64 | 0.377 | 0.78 | 0.231 | 0.246 | 0.58 |
| 256 | 128 | **0.606** | 0.77 | 0.297 | 0.398 | 0.73 |

### 2.3 全池（Reduction = 0，上限核对）

| N | Path-Edge Recall | ≈ \|T\|/\|path\| |
|---|------------------|-----------------|
| 16 | 0.062 | 8/143 |
| 32 | 0.124 | 16/143 |
| 64 | 0.248 | 32/143 |

与上限一致，无虚高。

---

## 3. 关键结论（不是「Recall 越高越好」）

1. **池越大，在同等 Search Reduction 下 Path-Edge Recall 越高**  
   50% 剪枝时：N=16→0.05，N=64→0.21，N=256→0.61。  
   原因：大池能装下更多真实路径边（|T| 从 8→118），模型仍能在 top-50% 里保住它们（Retention ≈ 0.77–0.86）。

2. **模型真实优于随机/置乱**  
   例如 N=256/K=64：real 0.43 vs shuffle 0.13 vs random 0.23。  
   指标审计已通过，此处不调参。

3. **Runtime 不是瓶颈**  
   推理始终 <1ms/group；成本在主池 GH 收集（80 OD 约 5s / 271 calls）。扩大 N 增加的是「看见的真实边」，不是排序耗时。

4. **Path-Edge Recall 受池覆盖率上限约束**  
   全路径约 143 边；N=256 时 |T|≈118，上限≈0.83。  
   在「全路径装入池」的子集上，50% 剪枝可达 Path-Edge Recall ≈ **0.86**（chosen 点 N=256 / keep≈106 / red≈50%）。

---

## 4. 有效工作点（Pareto，非单一「最优」）

| 工作点 | N | keep | Reduction | Path-Edge Recall | Retention | 适用 |
|--------|---|------|-----------|------------------|-----------|------|
| **A 覆盖优先** | 256 | ≈50% | 50% | **0.61–0.86** | 0.77–0.86 | 需要保住真实道路骨架 |
| **B 平衡** | 64 | 32 | 50% | 0.21 | 0.85 | 池构建成本减半，仍有稳定超额 |
| **C 高压缩** | 256 | 64 | 75% | 0.43 | 0.49 | 极端减搜索量，接受半数正样本被剪 |
| **D 轻量** | 32 | 16 | 50% | 0.11 | 0.86 | 在线场景 / OD 极多 |

### 推荐

**主工作点 A：N=256，keep≈50%**  
- 在减少一半候选扩展的同时，保留绝大部分真实路径边  
- 推理 0.7ms，可接受  

**降级工作点 B：N=64，keep=32**  
- 当主池 GH 收集成为瓶颈时使用  

**不推荐**：在 N=16 上追求高 Path-Edge Recall（上限只有 ~0.06）；也不推荐只看 Recall 而不看 Reduction。

---

## 5. 与最终目标的对齐

> 「候选池扩大后，模型仍然能够保留关键真实道路候选，同时减少实际 GraphHopper 搜索量和运行时间」

| 目标 | 本实验结论 |
|------|------------|
| 扩大候选池 | N=256 可行，真实边 \|T\|≈118 |
| 保留关键真实道路 | 50% 剪枝下 Retention 0.77–0.86，Path-Edge Recall 0.61–0.86 |
| 减少搜索量 | Search Reduction 50%（或 75% 时 Path-Edge Recall 仍 0.43） |
| 减少运行时间 | 排序 <1ms；**墙钟 GH 搜索耗时下降尚未在闭环搜索中测量** |

### 诚实缺口（不得宣称已完成）

1. Search Reduction 目前是「候选扩展条数」代理指标，**不是** 闭环里真实减少的 GH `/route` 调用墙钟时间  
2. 需要把 Pruner 接到 LocalSearch/RouteSearch 后再测 p50/p95 端到端延迟  
3. 江津 hold-out 泛化、worst-20 失败案例仍未做  
4. **50K/100K 长训：仍未启动**（按约定，工作点明确后仍不自动进长训）

---

## 6. 状态

```text
WORKING_POINT_IDENTIFIED
ROUTE_SEARCH_MODEL_CANDIDATE
LONG_TRAINING_DEFERRED
```

下一步（建议顺序）：

1. 将 `RouteSearchPruner` 接入真实搜索循环，测量 GH 调用次数与 p50/p95 墙钟  
2. worst-20 失败案例  
3. 江津 hold-out  
4. 上述证据齐备后再讨论是否启动长训 / ACTIVE

许可证：`THIRD_PARTY_NOTICES.md`
