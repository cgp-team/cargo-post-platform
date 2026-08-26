# Phase 10：Objective 优化

> 日期：2026-08-26

## 目标函数结构

OR-Tools RoutingModel 的目标函数：

```
min Σ(vehicle_fixed_cost × vehicle_used) + Σ(arc_cost)
```

其中 `arc_cost = distance_callback(from, to) × DISTANCE_SCALE`

## 优先级

1. **最少启用车辆**（最高优先级）
2. **总时间最小**（Phase 6 后加入）
3. **总距离最小**（当前实现）
4. **货运绕行最小**（Phase 8 后加入）

## 固定成本计算

旧方案：`VEHICLE_FIXED_COST = 10^9`（硬编码）

新方案：动态计算上界

```python
# 所有站点对的最大距离
max_pair_distance = max(
    distance(a, b) for a in stations for b in stations
)

# 固定成本 = 所有可能弧成本之和 + 1
# 这确保"多用一辆车"的成本 > "任何额外距离"的成本
distance_upper_bound = num_nodes * max_pair_distance * DISTANCE_SCALE
VEHICLE_FIXED_COST = distance_upper_bound + 1
```

## 证明

假设 N 个节点，最大单段距离为 D_max。

- 最大可能总距离 = N × D_max（每段弧都取最大值）
- 固定成本 = N × D_max × SCALE + 1

如果方案 A 用 1 辆车、总距离 = N × D_max：
  成本 = (N × D_max × SCALE + 1) + N × D_max × SCALE

如果方案 B 用 2 辆车、总距离 = 0：
  成本 = 2 × (N × D_max × SCALE + 1)

方案 A 成本 < 方案 B 成本：
  (N × D_max × SCALE + 1) + N × D_max × SCALE < 2 × (N × D_max × SCALE + 1)
  N × D_max × SCALE < N × D_max × SCALE + 1
  0 < 1 ✓

因此固定成本保证"先最小化用车数"。

## 当前实现

```python
# 计算动态上界
max_dist = 0
for a in station_map.values():
    for b in station_map.values():
        d = scaled_distance(a, b)
        if d > max_dist:
            max_dist = d

num_nodes = len(nodes)
vehicle_fixed_cost = num_nodes * max_dist + 1
```

## 限制

- 当前只实现"最少车辆 + 最小距离"
- 时间和绕行目标需要后续 Phase 集成
- 动态上界在小规模问题中有效，大规模可能需要更精确的估计
