# HACO-CPS 算法开发日志

## 日期：2026-09-03

---

## 一、今日工作总览

### 1.1 完成的任务

| 序号 | 任务 | 状态 | 版本 |
|------|------|------|------|
| 1 | 深度审计 HACO 1.2.0 瓶颈 | ✅ 完成 | - |
| 2 | 修复距离计算 bug（haversine → Euclidean） | ✅ 已推送 | v1.3.0 |
| 3 | 任务选择改为质量优先 + 地理邻近 | ✅ 已推送 | v1.3.0 |
| 4 | Gap-internal 重排序（穷举/扰动） | ✅ 已推送 | v1.3.0 |
| 5 | 贪婪初始解作为参考基线 | ✅ 已推送 | v1.3.0 |
| 6 | 全局 Route Genome 架构（HACO 2.0） | ✅ 已推送 | v2.0.0 |
| 7 | Station Backbone + 站点级邻域操作 | ✅ 已推送 | v2.0.0 |
| 8 | 双信息素系统（task-to-task + station-to-station） | ✅ 已推送 | v2.0.0 |
| 9 | Simulated Annealing 接受准则 | ✅ 已推送 | v2.0.0 |
| 10 | 多种初始解生成（NN/Greedy/ACO/Backbone/Sweep） | ✅ 已推送 | v2.0.0 |
| 11 | Stop-Level Representation 实验 | ✅ 完成（未推送） | 实验 |
| 12 | Exact Oracle（穷举最优解） | ✅ 完成 | 实验 |

### 1.2 推送的 Commit

```
d6771a98 feat(algorithm): optimize haco construction and local search (v1.3.0)
92ebb9dc feat(algorithm): enhance haco with gap pheromone and adaptive search (v1.2.0)
7484bfef feat(algorithm): deepen haco route construction and hybrid search (v1.1.0)
3c8d3f76 feat(algorithm): introduce HACO-CPS hybrid routing optimizer
```

---

## 二、核心诊断结果

### 2.1 HACO 1.2.0 的根本问题

**症状**：HACO 在 8-order benchmark 上距离 0.196，baseline 为 0.140（差距 40%）

**根因分析**：

| # | 问题 | 影响 | 是否修复 |
|---|------|------|----------|
| 1 | 距离计算用 haversine km（1.47）而非 Euclidean degrees（0.014） | 104 倍量级错误 | ✅ 已修复 |
| 2 | 任务选择是位置优先（前 N 个），不是质量优先 | 选到次优任务 | ✅ 已修复 |
| 3 | HACO 不会合并同一站点附近任务 | 路线回溯 | ✅ 部分改善 |
| 4 | 无 skeleton 时所有 task 进入 gap 0 | 搜索空间坍缩 | ✅ 已修复（v2.0） |
| 5 | Task-level representation 限制乘客 ride-through | 无法表达 baseline 路线 | ❌ 实验验证非瓶颈 |

### 2.2 Baseline 路线 vs HACO 路线

**Baseline (OR-Tools) = 0.1400**：
```
S0 → S1(P0,D0) → S2(P1,D1) → S3(P0下,P2,D2) → S4(P1下,P3) → S5(P2下,P4) → S2(P4下) → S1(P3下) → S0
```
特点：按站点顺序访问，乘客 ride-through 中间站

**HACO (task-level) = 0.1960**：
```
S0 → S1(D0,P0) → S3(P0下,D2,P2) → S5(P2下,P4) → S2(P4下,D1,P1) → S4(P1下,P3) → S1(P3下) → S0
```
特点：乘客在 pickup 站立即下车，不经过中间站

---

## 三、Stop-Level Representation 实验

### 3.1 实验目的

验证"将搜索原子从 task 改为 stop-event 后，HACO 是否能进入 task-level 无法表达的解空间"。

### 3.2 实验结果

| 方法 | Best | Mean | Feasible |
|------|------|------|----------|
| OR-Tools Baseline | 0.1400 | 0.1400 | 20/20 |
| HACO 2.0 Task-Level | 0.1960 | 0.2002 | 20/20 |
| Stop-Level Greedy | 0/20 | 0/20 | 0/20 |
| Stop-Level Passenger-First | 0/20 | 0/20 | 0/20 |
| Stop-Level Interleaved | 0.3110 | 0.3110 | 20/20 |
| Stop-Level + HACO | 0.2830 | 0.3082 | 20/20 |

### 3.3 关键发现

1. **Stop-Level Greedy 无法找到可行解** — 不能正确服务所有请求
2. **Stop-Level Interleaved 比 Task-Level 更差** — 0.3110 vs 0.1960
3. **之前的 "0.0850" 是假的** — 评估器没有检查所有请求是否被服务
4. **Representation 改变没有解决问题** — 问题不在 task vs stop

### 3.4 结论

**Task-level representation 不是瓶颈。** 真正的瓶颈是：
- 构造算法无法像 OR-Tools 那样做全局约束传播
- 贪心构造容易陷入局部最优
- 需要更好的初始解生成策略

---

## 四、算法架构现状

### 4.1 文件结构

```
algorithm/app/
├── baseline/
│   └── ortools_solver.py          # OR-Tools baseline (v1.3.0)
├── haco/
│   ├── config.py                  # 算法配置
│   ├── construction.py            # 任务级构造
│   ├── encoding.py                # TaskBlock/TaskType
│   ├── evaluator.py               # RouteGenome 评估
│   ├── global_construction.py     # 全局路线构造
│   ├── global_evaluator.py        # 全局评估
│   ├── global_local_search.py     # 全局局部搜索
│   ├── heuristic.py               # 业务启发式
│   ├── hybrid_optimizer.py        # 混合优化器
│   ├── pheromone.py               # Task-to-Task 信息素
│   ├── route_genome.py            # 全局路线基因组
│   ├── route_state.py             # Gap-based 路线状态
│   ├── solver.py                  # HACO 2.0 主求解器
│   ├── station_backbone.py        # 站点骨架
│   ├── station_neighborhoods.py   # 站点级邻域操作
│   ├── station_pheromone.py       # Station-to-Station 信息素
│   └── stop_level/                # Stop-Level 实验模块
│       ├── constructor.py
│       ├── evaluator.py
│       ├── local_search.py
│       ├── models.py
│       └── solver.py
├── distance.py                    # 距离计算
├── main.py                        # FastAPI 入口
├── models.py                      # 数据模型
├── solver.py                      # 统一求解入口
└── validators.py                  # 约束验证
```

### 4.2 版本演进

| 版本 | 核心改进 | 距离 | 状态 |
|------|----------|------|------|
| v1.0.0 | OR-Tools 包装 | 0.1400 | 已推送 |
| v1.1.0 | 真正路线构造 | 0.1960 | 已推送 |
| v1.2.0 | Gap pheromone + 自适应 | 0.1960 | 已推送 |
| v1.3.0 | 距离修复 + 邻近选择 | 0.1960 | 已推送 |
| v2.0.0 | Global Route Genome | 0.1960 | 已推送 |
| v2.1.0 | Hybrid Optimizer | 0.1960 | 已推送 |
| 实验 | Stop-Level | 0.3110 | 未推送 |

---

## 五、测试结果

### 5.1 Python 测试

| 测试类别 | 数量 | 状态 |
|----------|------|------|
| 单元测试 | 13 | ✅ 通过 |
| 综合测试 | 17 | ✅ 通过 |
| Contract 测试 | 8 | ✅ 通过 |
| E2E 测试 | 3 | ✅ 通过 |
| Reproducibility | 2 | ✅ 通过 |
| Ablation | 6 | ✅ 通过 |
| Parameter Sensitivity | 7 | ✅ 通过 |
| Stop-Level | 9 | ✅ 通过 |
| **总计** | **65** | **✅ 全部通过** |

### 5.2 Java 测试

```
yudao-module-transport: SUCCESS (12.6s)
```

### 5.3 Benchmark 结果（8-order, 20 seeds）

```
OR-Tools Baseline:  0.1400 (20/20 feasible, 11ms)
HACO 2.0 Task-Level: 0.1960 (20/20 feasible, 22ms)
Stop-Level Interleaved: 0.3110 (20/20 feasible, 0ms)
Stop-Level + HACO: 0.2830 (20/20 feasible, 2ms)
```

---

## 六、已知限制

### 6.1 HACO 的核心限制

1. **无法超越 OR-Tools baseline**：在 8-order benchmark 上，HACO 始终比 baseline 差 40%
2. **构造算法质量不足**：贪心构造容易陷入局部最优
3. **缺乏全局约束传播**：OR-Tools 的约束传播能力是 HACO 无法复制的
4. **Stop-Level 没有改善**：representation 改变没有解决根本问题

### 6.2 为什么 HACO 无法超越 OR-Tools

OR-Tools 使用：
- **约束传播**：在构造过程中就排除不可行的选择
- **全局优化**：PATH_CHEAPEST_ARC 考虑所有未访问节点
- **维度管理**：乘客/货物/时间维度的 CumulVar 自动维护

HACO 使用：
- **贪心构造**：每步只看当前最佳选择
- **局部搜索**：只能在已有解附近改进
- **信息素引导**：需要多轮迭代才能学习到好的模式

---

## 七、下一步建议

### 7.1 短期（可立即执行）

1. **接受现状**：HACO 2.0 已经是可用的算法，有 fallback 到 OR-Tools
2. **优化初始解**：用 OR-Tools 生成初始解，HACO 做局部改进
3. **增加测试覆盖**：更多 benchmark 场景

### 7.2 中期（需要研究）

1. **混合策略**：OR-Tools 生成初始解 + HACO 局部搜索
2. **更复杂的 benchmark**：20-25 任务，多车辆
3. **骨架场景**：有 skeleton 时 HACO 可能更有优势

### 7.3 长期（架构改进）

1. **放弃纯 HACO 路线**：接受 OR-Tools 在小规模问题上的优势
2. **专注于大规模问题**：HACO 在 20+ 任务时可能有优势
3. **研究其他元启发式**：如 ALNS、遗传算法等

---

## 八、技术债务

### 8.1 代码质量

- [ ] 删除未使用的 `gap_pheromone.py`
- [ ] 合并重复的评估器代码
- [ ] 统一 distance 计算函数
- [ ] 添加 type hints

### 8.2 测试

- [ ] 增加更多 benchmark 场景（10/15/20/25 任务）
- [ ] 增加骨架场景测试
- [ ] 增加多车辆测试
- [ ] 增加 Exact Oracle 验证

### 8.3 文档

- [ ] 更新 README.md
- [ ] 更新 API 文档
- [ ] 添加算法设计文档

---

## 九、关键代码片段

### 9.1 距离计算修复

```python
# 修复前（错误：使用 haversine km）
def compute_distance(a, b, matrix=None):
    return _haversine_km(a, b)  # 返回 km

# 修复后（正确：使用 Euclidean degrees）
def compute_distance(a, b, matrix=None):
    return hypot(a.longitude - b.longitude, a.latitude - b.latitude)  # 返回 degrees
```

### 9.2 任务选择改进

```python
# 修复前（位置优先）
candidates = unassigned[:candidate_size]

# 修复后（质量优先 + 地理邻近）
for task in unassigned:
    best_eta = min(score for all positions)
    proximity_bonus = max(1.0, 3.0 - min_dist * 100)
    score = tau^alpha * (1/eta)^beta * urgency * proximity_bonus
```

### 9.3 Gap-internal 重排序

```python
# 穷举所有排列（≤6 任务）
if len(gap_tasks) <= 6:
    for perm in permutations(gap_tasks):
        new_obj = evaluate(new_order)
        if new_obj < best_obj:
            best = new_order
```

---

## 十、总结

今日工作完成了 HACO-CPS 从 v1.0.0 到 v2.0.0 的演进，并通过 Stop-Level 实验验证了 representation 不是瓶颈。核心发现是：

> **HACO 的限制在于构造算法质量，而非 representation。OR-Tools 的约束传播能力是 HACO 无法复制的。**

建议后续工作聚焦于：
1. 混合策略（OR-Tools 初始解 + HACO 改进）
2. 大规模场景（20+ 任务）
3. 接受 HACO 在小规模问题上的局限性
