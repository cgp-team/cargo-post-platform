# OBJECTIVE SENSITIVITY REPORT — DISPATCH_CORE_V047

脚本：`algorithm/benchmarks/objective_sensitivity_v047.py`
原始结果：`algorithm/data/dispatch_v047_objective_sensitivity.json`

## 结论（先行）

在本轮真实重庆站点、真实 HACO-CPS 1.4.1 求解（3 seeds × 3 cases）的样本上：

> **A / B / C 三种排序口径一致，`disagreements = []`，`counterexamples = []`。
> 因此本轮不修改 `ObjectiveVector`，维持当前 6 维 lexicographic。**

这是一个**证据驱动的“不改”**，而不是凭感觉保留旧逻辑。

## 对比口径

| 口径 | 排序键 |
|---|---|
| A（当前 lexicographic） | `vehicle_count → passenger_impact → cargo_detour → total_distance → total_duration` |
| B（SLA 违规优先） | `sla_violation → vehicle_count → passenger_impact → cargo_detour → total_distance` |
| C（passenger-safe） | `passenger_impact → vehicle_count → cargo_detour → total_distance → total_duration` |

## 重点反例检索

脚本显式检索两类反例，答案是**本轮未出现**：

1. **低车辆数但高 passenger impact**：`LOW_VEHICLE_HIGH_PASSENGER_IMPACT` — 未命中；
2. **低距离但高 SLA violation**：`LOW_DISTANCE_HIGH_SLA_VIOLATION` — 未命中。

## 口径与局限（PROXY 标注）

- **SLA violation 是 PROXY**：当前无真实乘客时刻表，使用
  `max(0, total_duration - batch_window)` 计算，报告与代码中均标注 `PROXY`，
  **不称其为“真实乘客延误”**。
- 无 AMAP key，矩阵口径为 `matrix=None`（degree/直线）。
- 因此本轮的“A/B/C 一致”是**必要不充分**证据：接入正式路网与真实时刻表后必须重跑。

## 后续建议（不阻塞本轮交付）

1. 接入 GraphHopper/AMAP 正式成本后重跑本脚本；
2. 引入真实 passenger timetable 后，把 SLA violation 从 PROXY 升级为真实口径；
3. 若届时出现反例，再按 §24 决定是否把 SLA 违规提升为全局 hard constraint；
4. 现阶段 **不**把 passenger proxy 塞进 `FeasibilityEngine` 作为全球绝对硬约束 ——
   passenger 影响只在 `DispatchPolicy` / `TripPolicy` 层做 candidate hard reject
   （`maxPassengerImpactSeconds`），HACO 全局求解保持既有 `ObjectiveVector` 逻辑。

