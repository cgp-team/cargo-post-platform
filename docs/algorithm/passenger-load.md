# Phase 2：乘客动态容量

> 日期：2026-08-26

## 问题

旧模型：`BOARD → +1, ALIGHT → 0`

座位永不释放，6 名乘客必须 2 车，即使穿插上下客单车可行。

## 修复

新模型：`BOARD → +1, ALIGHT → -1`

```python
def passenger_demand(node: int) -> int:
    action = nodes[node].action
    if action == StopAction.BOARD:
        return 1
    if action == StopAction.ALIGHT:
        return -1
    return 0
```

## OR-Tools CumulVar 语义（实验确认）

通过 `tests/repro_passenger_dimension.py` 的 14 个最小实验确认：

- **CumulVar(node) = 到达该节点时的累计值**（transit 之前）
- transit(node) = 该节点的 demand（正值=装载，负值=卸载）
- 离开时的值 = CumulVar(node) + transit(node)
- `AddDimensionWithVehicleCapacity` 的 `fix_start_cumul_to_0=True` 将 depot 的 cumul 固定为 0
- 下界 0 自动阻止负 cumul（ALIGHT 超过当前载客 → INFEASIBLE）

## 行为变化

| 场景 | 旧模型 | 新模型 |
|------|--------|--------|
| 6 乘客同站上下，capacity=5 | 2 辆车（座位不释放） | 1 辆车（重访站点分批上下） |
| A 上 5, B 下 3, C 上 3 | 峰值=8（不释放） | 峰值=5（释放后重新装载） |
| A 上 5, B 下 5, C 上 5 | 峰值=10 | 峰值=5 |

## 重访站点

新模型下 Solver 可以选择重访站点（如 S1→S2→S1→S2），这是更优解：
- 减少车辆使用
- 降低总里程
- 更好地利用运力

## 测试

- `tests/repro_passenger_dimension.py`：14 个最小复现实验，全部通过
- `tests/test_solver.py`：更新 `simulate_loads` 和 `test_auto_second_vehicle_on_passenger_overload`
- 全量 65 测试通过，0 回归

## 风险

| 风险 | 影响 | 缓解 |
|------|------|------|
| 重访站点增加总里程 | 可能略增距离 | 固定成本优先单车，总成本仍更优 |
| OVER_CAPACITY 预检过保守 | 总需求 > 总容量时误判 | 预检仍作为保守守卫，Solver 做精确判断 |
| 业务方预期 6 人必须 2 车 | 与旧模型行为不同 | 新模型更符合实际运营（分批上下客） |
