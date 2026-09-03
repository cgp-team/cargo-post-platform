# HACO-CPS 算法设计文档

## 1. 算法概述

**HACO-CPS** (Hybrid Ant Colony Optimization for Passenger-Cargo-Postal Integrated Scheduling)

面向客货邮一体化调度的混合蚁群优化算法，版本 `haco-cps-1.0.0`。

### 1.1 问题定义

客货邮一体化调度问题（CPS）是一个带约束的车辆路径问题（CVRP），包含：
- **乘客运输**：BOARD → ALIGHT（同车、先上后下）
- **货物派送**：场站 → 村站（DELIVER）
- **货物揽收**：村站 → 场站（PICKUP）
- **配对货运**：PICKUP → DELIVERY（同车、先揽后送）
- **公交骨架**：车辆必须按序经停的固定站点（Mandatory Passenger Service）

### 1.2 约束条件

1. **容量约束**：乘客数 ≤ passengerCapacity，货物件数 ≤ cargoCapacity
2. **时序约束**：BOARD < ALIGHT，PICKUP < DELIVER
3. **同车约束**：同一订单的上下车/揽送必须在同一辆车
4. **骨架约束**：骨架站点必须按序出现（允许其他站点插入其间）
5. **时间窗约束**：所有操作必须在 batchStart ~ batchEnd 内完成
6. **闭环约束**：每辆车从场站出发，最终返回场站

## 2. 算法架构

```
真实业务请求
   │
   ▼
任务编码 (TaskBlock)
   │
   ├── Passenger Block (BOARD + ALIGHT)
   ├── Shipment Block (PICKUP + DELIVERY)
   ├── Delivery Task
   └── Pickup Task
   │
   ▼
OR-Tools 初始解生成
   │
   ▼
HACO 元启发式搜索
   │
   ├── 信息素矩阵 (PheromoneMatrix)
   ├── 业务启发式 (Business Heuristic)
   ├── 蚂蚁构建 (Ant Construction)
   ├── 局部搜索 (Local Search)
   └── LNS Destroy-Repair
   │
   ▼
分层目标评估 (ObjectiveVector)
   │
   ▼
最佳解 → PlanResult
   │
   ▼
Java ResultValidator → DispatchPlan
```

## 3. 任务编码 (TaskBlock)

### 3.1 Passenger Block

```python
TaskBlock(
    task_id="P:order123",
    task_type=TaskType.PASSENGER,
    pickup_station="S1",      # 上车站
    delivery_station="S2",    # 下车站
    size=1,
    order_ids=["order123"],
)
```

- 不可拆分：BOARD 和 ALIGHT 必须在同一辆车
- 时序约束：BOARD 位置 < ALIGHT 位置

### 3.2 Shipment Block

```python
TaskBlock(
    task_id="S:shipment456",
    task_type=TaskType.SHIPMENT,
    pickup_station="S1",      # 揽收站
    delivery_station="S3",    # 派送站
    size=2,                   # 件数
    order_ids=["shipment456"],
)
```

- 不可拆分：PICKUP 和 DELIVERY 必须在同一辆车
- 容量约束：size 件货物占用货仓

### 3.3 Standalone DELIVERY / PICKUP

```python
TaskBlock(
    task_id="D:order789",
    task_type=TaskType.DELIVERY,
    pickup_station="S2",
    delivery_station="S2",    # 同一站点
    size=1,
    order_ids=["order789"],
)
```

## 4. Skeleton Backbone 模型

骨架定义车辆的固定主线路，例如：A → B → C → D

算法的核心决策是在骨架间隙中插入任务：

```
DEPOT → [tasks in gap 0] → A → [tasks in gap 1] → B → [tasks in gap 2] → C → [tasks in gap 3] → D → DEPOT
```

### 4.1 Skeleton Gap

```python
SkeletonGap(
    vehicle_id=1,
    gap_index=0,           # 间隙编号
    from_station="DEPOT",  # 前一站
    to_station="A",        # 后一站
)
```

### 4.2 插入决策

每个任务的插入决策 = (Vehicle, SkeletonGap, Position)

这使得 HACO-CPS 与普通 VRP-ACO 有本质区别。

## 5. 信息素模型 (PheromoneMatrix)

### 5.1 信息素矩阵

τ(task_i, task_j)：从任务 i 转移到任务 j 的信息素水平。

### 5.2 初始化

τ₀ = 1 / initial_solution_cost

### 5.3 转移概率

P(i,j) = τ(i,j)^α × η(i,j)^β / Σ(τ(i,k)^α × η(i,k)^β)

其中：
- α = 信息素权重
- β = 启发式权重
- η = 启发式信息

### 5.4 蒸发

τ = (1 - ρ) × τ

### 5.5 精英强化

全局最优路径额外沉积：
Δτ = Q / normalized_cost × elite_weight

### 5.6 MMAS 边界

- τ_min = 0.01（防止信息素为 0）
- τ_max = 10.0（防止早熟收敛）

## 6. 业务启发式 (Business Heuristic)

不使用简单的 η = 1/distance，而是综合多个业务指标：

```python
cost = w_d × normalized_distance
     + w_p × passenger_impact
     + w_c × normalized_detour
     + w_t × time_risk
     + w_s × skeleton_penalty

η = 1 / (ε + cost)
```

### 6.1 归一化

所有指标必须归一化，避免尺度失衡：
- normalized_distance = distance / 10km
- normalized_passenger_impact = impact / 300s
- normalized_detour = detour / 5km

### 6.2 权重配置

| 参数 | 默认值 | 含义 |
|------|--------|------|
| w_distance | 0.3 | 距离权重 |
| w_passenger_impact | 0.25 | 乘客影响权重 |
| w_detour | 0.2 | 绕行权重 |
| w_time_risk | 0.15 | 时间风险权重 |
| w_skeleton_penalty | 0.1 | 骨架偏离权重 |

## 7. 蚂蚁构建 (Ant Construction)

每只蚂蚁构建一个完整解：

1. 从未分配任务中选择（基于信息素和启发式）
2. 计算转移概率
3. 轮盘赌选择
4. 找最佳插入位置（可行性 + 成本）
5. 插入任务
6. 重复直到所有任务完成

### 7.1 候选集

每步只考虑 top-K 候选任务（默认 K=8），降低复杂度。

## 8. 局部搜索 (Local Search)

每轮 HACO 后对精英候选执行：

### 8.1 Relocate

将一个任务从一条路线移到另一条。

### 8.2 Swap

交换两个任务的位置（block-aware：不可拆分乘客/货运对）。

## 9. LNS Destroy-Repair

### 9.1 Destroy

随机移除一部分任务（默认 30%）。

### 9.2 Repair

贪婪重新插入被移除的任务。

### 9.3 自适应算子权重

根据结果奖励：
- 全局最优：5
- 迭代最优：3
- 改善：2
- 可行：1

## 10. 分层目标 (ObjectiveVector)

不使用权重混合，逐级比较：

1. **可行性**：infeasibility = 0 优先
2. **车辆数**：最小化用车数
3. **乘客影响**：最小化乘客延误
4. **货物绕行**：最小化货运绕行距离
5. **总距离**：最小化总里程
6. **总时长**：最小化总行驶时间

## 11. 随机种子

```python
randomSeed: int = 20260903
```

要求：same request + same config + same seed = same result

## 12. OR-Tools 的角色

OR-Tools 在 HACO-CPS 中的角色：

1. **初始解生成**：保证格式正确和约束满足
2. **Feasibility Oracle**：验证候选解
3. **Repair Engine**：对候选解做 repair
4. **Fallback Baseline**：HACO 异常时恢复旧 solver

## 13. 生产 Fallback

```python
try:
    haco_result = solve_haco(...)
except Exception:
    baseline_result = solve_baseline(...)
    baseline_result.warnings.append("HACO_FALLBACK_TO_BASELINE")
```

Fallback 条件：
- 算法内部异常
- 算法超时
- 算法返回不可验证结果

## 14. 生产参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| ant_count | 24 | 蚂蚁数量 |
| max_iterations | 40 | 最大迭代次数 |
| candidate_size | 8 | 候选集大小 |
| elite_count | 4 | 精英蚂蚁数 |
| lns_probability | 0.25 | LNS 触发概率 |
| local_search_rounds | 2 | 局部搜索轮数 |
| haco_time_limit | 4.0s | HACO 时间预算 |
| overall_time_limit | 5.0s | 总时间预算 |

## 15. 版本信息

- 算法版本：`haco-cps-1.0.0`
- 参数版本：`haco-cps-default-v1`
- Baseline 版本：`ortools-1.3.0`
