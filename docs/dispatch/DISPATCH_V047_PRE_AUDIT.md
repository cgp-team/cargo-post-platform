# DISPATCH_CORE_V047 — 调度运行时预审计（PRE_AUDIT）

任务：`DISPATCH_CORE_V047_FINAL`
范围：`algorithm/app/dispatch_opt`、`algorithm/app/haco`、`algorithm/app/routing`、`algorithm/app/main.py`、`algorithm/app/solver.py`
基线：`haco-cps-1.4.1`（production）/ `HACO 2.0 + GlobalRouteGenome`（experimental，未接入 `solver.solve`）
约束：本审计**只读**，不修改 PATH SEARCH V047 训练、不重写 V046 GH Branch Selector。

> 结论先行：`app/dispatch_opt/*`（含 `allocate_new_order`、`TripCandidateSelector`、`MarginalCostEvaluator`、`TripLockPolicy`、`gap_detour`、`handover`）目前**没有任何生产运行时调用点**。它们只被 `tests/` 与 `app/algo_support.py` 引用。真实批次求解走 `main.build_result → solver.solve → haco.v14_solver.solve → FeasibilityEngine`。这就是 P0-3「Dispatch Candidate Selector 有 default candidate 但不计算真实增量成本」的根因——它根本没有进入求解链。

---

## 0. 审计方法

- 静态引用检索：`rg -n "dispatch_opt" app/main.py app/solver.py app/haco/v14_solver.py app/baseline/ortools_solver.py`（无命中）。
- 调用链追踪：`main.build_result → solver.solve → _solve_haco → haco.v14_solver.solve`。
- 占位常量检索：`rg -n "delta_distance_m=500|delta_duration_s=60|delta_passenger_impact_s=10|incremental_cost=2.0|incremental_cost=3.0"`。
- 基线回归：`python -m pytest tests/test_*dispatch* tests/test_trip_* tests/test_marginal_cost.py tests/test_handover_feasibility.py -q` → 77 passed（见 §23）。

---

## 1. 当前 runtime 入口

| 层 | 文件 | 事实 |
|---|---|---|
| HTTP | `algorithm/app/main.py` | `POST /api/v1/plan` → `create_plan` → `asyncio.to_thread(build_result, request)` |
| 批构建 | `main.build_result` | 取高德矩阵 `matrix = amap_provider.get_matrix([...])`（`main.py:123-128`），失败仅 `warnings.append("路网距离不可用，已降级直线距离")` |
| 分发 | `app/solver.py::solve` | 按 `algorithmConfig.algorithmMode` 分流 `BASELINE / HACO / HYBRID` |
| 求解 | `app/haco/v14_solver.py::solve` | HACO-CPS 1.4.1 主链：Construction → FeasibilityEngine → ObjectiveVector → Pheromone → Local Search → ALNS(v14) → Archive → `_compact_solution` |
| 兜底 | `app/baseline/ortools_solver.py` | OR-Tools baseline（HYBRID 组合或 HACO 无解回退） |

**动态调度入口（新订单级）**：**不存在**在运行时路径中被调用。`app/dispatch_opt/allocate.py::allocate_new_order` 是项目里唯一「新订单动态分配」入口，但**仅被测试引用**。

## 2. 当前真正调用的 solver

- production：`app/haco/v14_solver.py::solve`（`HACO_VERSION = haco-cps-1.4.1`）。
- `app/haco/solver.py`、`app/haco/global_*.py`、`app/haco/route_genome.py` 的 2.0 线**未被** `solver.solve` 调用（属 experimental）。
- `app/dispatch_opt/coordinator.py`：**本任务新增**（审计时不存在）。

## 3–8. 关键模块是否被 runtime 调用

| # | 模块 | 运行时是否调用 | 证据 |
|---|---|---|---|
| 3 | `allocate_new_order` | **否** | 仅 `tests/test_allocate_new_order.py`、`tests/test_dispatch_scenarios.py` 引用；`app/` 内无调用点 |
| 4 | `candidate_selector.TripCandidateSelector` | **否**（仅被 `allocate_new_order` 调用，而后者未接入） | `app/dispatch_opt/allocate.py:145` |
| 5 | `marginal_cost.MarginalCostEvaluator` | **部分** | 被 `routing/real_road_cost.py` 引用，但 `real_road_cost` 亦无运行时调用者；`haco/evaluator` 用自己的口径 |
| 6 | MultiLeg（`handover.TransportChainEvaluator` / `candidate_selector.build_multileg`） | **否** | `build_multileg` 默认 `None`；`MultiLegPlanner` 在 JVM 侧，算法侧未接入 |
| 7 | `TripLockPolicy` | **否** | 仅测试引用 |
| 8 | `gap_detour.calculate_gap_detour` | **否** | 仅测试 + `battery`/`algo_support`；`haco/evaluator` 用另一套 `cargo_detour` 口径 |

## 9–12. 成本来源

| # | 成本 | 现状 | 文件证据 |
|---|---|---|---|
| 9 | current trip cost | 生产：`evaluate_route_genome` 的 `cargo_detour`（直线/矩阵口径）；动态层：**占位** `delta_distance_m=500, delta_duration_s=60` | `app/dispatch_opt/allocate.py:151-154` |
| 10 | future trip cost | 动态层：**硬编码** `incremental_cost=2.0` | `app/dispatch_opt/allocate.py:100` |
| 11 | other route cost | 动态层：**硬编码** `incremental_cost=3.0` | `app/dispatch_opt/allocate.py:113` |
| 12 | MultiLeg cost | 动态层：`handover_cost=2.0 + transfer/1000`（`handover.py:66`）；无真实联运增量成本 | `app/dispatch_opt/handover.py:60-68` |

## 13–15. Routing / Matrix / Haversine

| # | 项 | 现状 |
|---|---|---|
| 13 | routing provider | 生产矩阵来自 `AmapDistanceProvider`（`main.py:123`）；`app/routing/*`（`MapRoutingEngine / LocalRoutingEngine / GeometryCache`）已具备 formal geometry 语义，但**未接入求解链** |
| 14 | matrix 来源 | `amap_provider.get_matrix(...)`；**未配置 `AMAP_KEY` 或高德失败 → `matrix=None`** |
| 15 | 是否存在 Haversine 正式成本 | **是**。`matrix=None` 时 `distance_unit="degree"`（`main.py:134`），求解按坐标欧氏/`haversine` 计费；`gap_detour` 直接用 `Haversine + 25km/h`（`gap_detour.py:33/42/89-93`）；`validators`/`heuristic.compute_duration` 在 `matrix=None` 时退化直线 |

> 结论：P0-7、P0-9、P0-10 **全部成立**。正式调度成本目前**允许** degree/直线进入。

## 16–17. Hard Constraint 贯穿性

| # | 项 | 现状 |
|---|---|---|
| 16 | detour hard constraint 是否贯穿最终出口 | **否**。`FeasibilityEngine.check()` 支持 `max_detour_km / trip_detour_remaining_m`（`feasibility_engine.py:73-74,120-128`），但 `validate_solution()`（`feasibility_engine.py:157`）**未向 `check()` 传递**这两个参数（`feasibility_engine.py:211-224`）；且 `v14_solver.solve` 构造 `FeasibilityEngine` 时**根本没传** detour 预算（`v14_solver.py:167-171`）。→ 最终出口无绕行硬约束 |
| 17 | passenger impact hard constraint | **未执行**。`AlgorithmConfig.maxPassengerImpactSeconds`（`models.py`）**在 `app/` 内无读取点**；仅在动态层 `TripLockPolicy.max_realtime_passenger_impact_s` 出现，而该策略未接入 runtime |

## 18–19. SLA / ETA 与 weight / volume

| # | 项 | 现状 |
|---|---|---|
| 18 | SLA/ETA | 仅 `PlanRequest.batchStart/batchEnd` → `max_duration = batchEnd - batchStart`（`v14_solver.py:161-170`）。**无** `readyTime / pickupDeadline / deliveryDeadline / priority`；无法表达单订单时效 |
| 19 | weight / volume | **未参与**。`PlanOrder.weightKg/volumeM3`、`PlanShipment.weightKg/volumeM3` 字段存在（`models.py`），但求解链容量口径为 `itemCount/quantity`（`size`）。`Vehicle` 无 `cargoWeightCapacityKg/cargoVolumeCapacityM3` |

## 20. `_compact_solution` 是否可能覆盖更好的 HACO 解

**会**。`_compact_solution`（`v14_solver.py:880`）从 `m=1` 起，**第一个「完整 + 可行」的 m 直接 `return cand.routes`**（`v14_solver.py:936-940`）。
调用点无条件接受：`compacted = _compact_solution(...)`；`if compacted is not None: best_routes = compacted; best_obj = evaluate_route_states(...)`（`v14_solver.py:493-500`）。
→ 当 `vehicle_count` 相同但 `passenger_impact / cargo_detour / total_distance` 更差时，**仍会替换掉更好的 HACO 解**（P0 反例 CASE 10 / §25）。

## 21. 1.4 与 2.0 哪个是 production

**1.4.x 是 production**。`solver._solve_haco` 导入 `haco.v14_solver`（`solver.py`），`HACO_1_4_VERSION = "haco-cps-1.4.1"`；`haco/global_*` + `route_genome` 2.0 线不在 `solve()` 分流中。

## 22. 当前最重要的五个调度逻辑风险

1. **动态调度层整体未接线**：`dispatch_opt` 无运行时调用者，新订单无法获得车辆/班次/联运的差异化决策（影响 P0-1/2/3/4）。
2. **正式成本可用 Haversine/degree 冒充**：`matrix=None` 静默降级（`main.py:126-134`），违反「直线不得进入正式成本」。
3. **最终出口缺 detour/passenger 硬约束**：`validate_solution` 未传预算（风险 16/17），最终解可能违反绕行与乘客影响约束。
4. **`_compact_solution` 无目标值守卫**：同车辆数但更差的解可覆盖更优 HACO 解（风险 20）。
5. **Handover / MultiLeg 时间可行性不完整**：`handover.can_handover` 仅比较 `to_departure >= from_arrival`（`handover.py:57`），未计入 `transfer travel + dwell + handling`；MultiLeg 无真实增量成本与链式回归。

---

## 23. 基线回归（审计时实测）

```
$ python -m pytest tests/test_trip_candidate_selector.py tests/test_trip_lock_policy.py \
    tests/test_marginal_cost.py tests/test_dispatch_scenarios.py \
    tests/test_handover_feasibility.py tests/test_allocate_new_order.py \
    tests/test_remaining_segment_replan.py tests/test_task_segment_completion.py \
    tests/test_local_routing_engine.py tests/test_graphhopper_provider.py -q
77 passed in 1.27s
```

→ 现有测试**锁定**了占位行为（如 `tests/test_trip_candidate_selector.py:67` 期望 `incremental_cost=3.0`）。V047 的修复必须**保持这些测试通过**，因此占位常量只能收敛到「不进入 runtime」的边界内，并在新代码路径中废弃。

## 24. P0 缺口 → 本轮修复映射

| P0 | 缺口 | 本轮处置 |
|---|---|---|
| P0-1 | `allocate_new_order` 占位 500/60/10 | 由 `DynamicDispatchCoordinator` + `RouteCostProvider` 真实计算替换 |
| P0-2 | future/other `2.0/3.0` | 由真实增量成本替换；常量仅保留在测试 stub |
| P0-3 | candidate 无 vehicle/trip/time 增量成本 | `candidate_builder` + `marginal_cost.evaluate_baseline_vs_candidate` |
| P0-4 | MultiLeg 只返回空壳 | `candidate_builder` 生成真实 2/3-leg 链 + `handover` 链式校验 |
| P0-5 | handover 只比 arrival/departure | `handover.can_handover` 计入 travel+dwell+handling |
| P0-6 | `validate_solution` 未传 detour | `FeasibilityContext` 统一签名 |
| P0-7 | Haversine 进正式成本 | `RouteCostProvider.is_formal()`；非 formal → UNKNOWN/HOLD |
| P0-8 | detour/passenger 硬约束未统一 | `FeasibilityContext` + `DispatchPolicy` |
| P0-9 | `matrix=None → compute_distance()` | 显式 routing provider，缺 formal 时 `UNKNOWN` |
| P0-10 | Gap Detour Haversine + 25km/h | `route_gap` 走真实道路 provider |

## 25. 允许 / 禁止边界确认

- 允许：`dispatch_opt`、`haco/feasibility_engine.py`、`haco/v14_solver._compact_solution`、`routing/*`、`main.py` 增量端点、`models.py` 向后兼容新增字段。
- 禁止并已遵守：不触碰 `learning/path_search/*`（V046 GH Branch Selector / V047 trainer / dataset label）、不重写 `haco/global_*` 2.0 线、不引入 GNN/Transformer/RL。

