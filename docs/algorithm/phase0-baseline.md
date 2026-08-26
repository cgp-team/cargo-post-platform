# Phase 0：Solver 基线审计

> 审计日期：2026-08-26
> 代码版本：当前 fix/dashboard-settlement 分支

## 1. 当前 Solver 所有 Dimension

| Dimension | demand 回调 | start cumul | min/max | 用途 |
|-----------|-------------|-------------|---------|------|
| **Passenger** | `BOARD → +1, 其他 → 0` | 0（隐式） | `[0, v.passengerCapacity]` | 累计载客数，座位不释放 |
| **CargoOut** | `DELIVER → +itemCount, 其他 → 0` | 0 | `[0, v.cargoCapacity]` | 出程派送件累计 |
| **CargoIn** | `PICKUP → +itemCount, 其他 → 0` | 0 | `[0, v.cargoCapacity]` | 返程揽收件累计 |
| **Distance** | `scaled_distance(from, to)` | 0 | `[0, 10^12]` | 里程累计，用于先后顺序约束 |

## 2. 各 Dimension 详细分析

### 2.1 Passenger Dimension（Phase 2 已修复）
- **demand**: `BOARD → +1, ALIGHT → -1, 其他 → 0`
- **语义**: 座位动态释放，允许重访站点分批上下客。
- **start cumul**: 0（fix_start_cumul_to_0=True）
- **capacity**: 每车独立 `passengerCapacity`
- **下界**: 0（阻止负 cumul，ALIGHT 超载 → INFEASIBLE）

### 2.2 CargoOut / CargoIn 双维度
- **CargoOut demand**: `DELIVER → +itemCount`，代表出程派送件占仓位
- **CargoIn demand**: `PICKUP → +itemCount`，代表返程揽收件占仓位
- **语义**: 出程/返程独立累计，货仓依次复用（闲置运力利用模型）
- **问题**: 没有"当前车上真实货物量"的概念。CargoOut/CargoIn 各自独立，不反映混合装载。

### 2.3 Distance Dimension
- **demand**: `scaled_distance(from_station, to_station)`
- **用途**: 主要用于先后顺序约束（`CumulVar(board) <= CumulVar(alight)`）
- **start cumul**: 0
- **max**: `10^12`（硬编码上界）

## 3. VehicleVar 的用途

```python
# 客运订单：强制同车
solver.Add(routing.VehicleVar(board_index) == routing.VehicleVar(alight_index))

# 骨架约束：骨架站点固定到指定车辆
solver.Add(routing.VehicleVar(manager.NodeToIndex(skel_node)) == vehicle_index)
```

- VehicleVar 返回 Constraint（不是数值）
- 只用于 `solver.Add(VehicleVar(...) == value)`
- 禁止 `sum(VehicleVar(...) == v)` 把 Constraint 当数字

## 4. Skeleton 约束

- **输入**: `vehicle.skeleton: list[str]`（骨架站点 ID 列表）
- **节点**: 每个骨架站点创建 `_Node(sid, StopAction.PASS)`
- **约束**:
  1. 固定车辆: `VehicleVar(skel_node) == vehicle_index`
  2. 顺序: `CumulVar(skel[i]) <= CumulVar(skel[i+1])`（用 Distance 维度）
- **绕行判定**: `skeleton_sets[vehicle_index]` 记录骨架站点集合

## 5. PickupDelivery 约束

```python
routing.AddPickupAndDelivery(board_index, alight_index)
solver.Add(routing.VehicleVar(board_index) == routing.VehicleVar(alight_index))
solver.Add(distance_dimension.CumulVar(board_index) <= distance_dimension.CumulVar(alight_index))
```

- 同车约束: 上下车必须同一辆车
- 顺序约束: 上车距离 ≤ 下车距离（通过 Distance 维度 cumul）

## 6. 当前 Objective

```python
routing.SetArcCostEvaluatorOfAllVehicles(distance_callback_index)
routing.SetFixedCostOfVehicle(VEHICLE_FIXED_COST, vehicle_index)  # 10^9
```

- **目标**: 先最小化用车数（固定成本 10^9），再最小化总里程
- **问题**: 10^9 是硬编码上界，不随问题规模动态调整

## 7. 当前 Time Window

**不存在。**

- `PlanRequest.batchStart/batchEnd` 已定义，但未在 Solver 中使用
- 没有 Time Dimension
- 没有时间约束
- `segmentDuration` 仅在输出时记录，不参与求解

## 8. 当前 DistanceMatrix

- **默认**: 欧氏直线（度），`hypot(lon_diff, lat_diff)`
- **注入**: 高德路网矩阵（km），通过 `matrix` 参数传入
- **缩放**: `DISTANCE_SCALE = 1000`，`int(round(distance * 1000))`
- **不可达点对**: 无特殊处理（高德 `available=False` 时矩阵中无该 key）

## 9. 当前不可达点对处理

- 高德 `available=False` 的点对不在矩阵中
- Solver 查矩阵时 `matrix[(from, to)]` 会 KeyError
- **风险**: 如果输入包含不可达点对，Solver 会崩溃

## 10. 当前 Result Validator

**不存在。**

- Solver 输出直接返回，无业务验证
- 无 passenger load 验证
- 无 cargo load 验证
- 无 time window 验证
- 无 skeleton 顺序验证

## 11. 当前 ReasonCode

| ReasonCode | 含义 |
|------------|------|
| `None` | 可行 |
| `OVER_CAPACITY` | 总需求超总容量（预检） |
| `TIMING_CONFLICT` | Solver 返回 None（泛化无解） |

- **问题**: 所有 Solver 无解都归为 `TIMING_CONFLICT`，不区分具体原因

## 12. 当前已知 pywrapcp 风险

1. **Constraint 当数值**: `VehicleVar(index) == v` 返回 Constraint，不能 `sum()`
2. **不可行模型 crash**: 某些极端不可行配置可能导致 Python 进程崩溃
3. **无异常捕获**: `routing.SolveWithParameters()` 可能抛异常，当前未捕获
4. **Distance 维度顺序约束**: 用 Distance 做先后顺序，但距离不是时间，可能有边界问题

## 13. 基线测试清单

### algorithm/tests/
| 文件 | 测试数 | 覆盖范围 |
|------|--------|----------|
| `test_solver.py` | 12 | 时序、容量、闭环、用车数、无解、确定性、骨架、性能 |
| `test_stability.py` | 2 | 随机打乱输入 60/80 次稳定性 |
| `test_distance.py` | 15 | 高德矩阵、缓存、降级、限流、单位口径 |
| `test_distance_endpoint.py` | - | 距离端点测试 |
| `test_route.py` | - | 路线端点测试 |
| `contract/test_contract.py` | - | API 契约测试 |

### 已知测试缺口
- 无 Passenger Dimension 动态容量测试
- 无初始载荷测试
- 无真实 Cargo Load 测试
- 无 Time Window 测试
- 无多车骨架隔离测试
- 无 Validator 测试
- 无 Solver 崩溃防护测试

## 14. 关键风险总结

| 风险 | 严重度 | 状态 |
|------|--------|------|
| Passenger 座位不释放 | 高 | 已知设计，待 Phase 2 修复 |
| 无 Time Dimension | 高 | 待 Phase 6 |
| 不可达点对 KeyError | 高 | 待 Phase 1 防护 |
| Solver 异常未捕获 | 高 | 待 Phase 1 防护 |
| 无 Validator | 中 | 待 Phase 13 |
| 10^9 硬编码成本 | 低 | 待 Phase 10 |
