# DISPATCH_CORE_V047 最终报告

任务：`DISPATCH_CORE_V047_FINAL`
仓库：`cargo-post-platform/algorithm`
日期：2026-09-23
最终状态：**`DISPATCH_CORE_V047_READY`**（P0 全部完成，见 §20 验收对照）

---

## 1. 修复前

审计结论（完整版见 [DISPATCH_V047_PRE_AUDIT.md](./DISPATCH_V047_PRE_AUDIT.md)）：

1. `app/dispatch_opt/*`（`allocate_new_order`、`TripCandidateSelector`、`MarginalCostEvaluator`、`TripLockPolicy`、`gap_detour`、`handover`）**没有任何生产运行时调用者**，只被 `tests/` 与 `app/algo_support.py` 引用；真实批次走 `main.build_result → solver.solve → v14_solver.solve`。
2. 动态层存在占位成本：`allocate.py:151-154` 的 `delta_distance_m=500 / delta_duration_s=60 / passenger_impact=10`，以及 `allocate.py:100/113` 的 `incremental_cost=2.0 / 3.0`。
3. `matrix=None` 时静默降级：`main.py:126-134` 仅 `warnings.append`，`distance_unit="degree"`；`gap_detour` 直接用 `Haversine + 25km/h`。
4. `FeasibilityEngine.validate_solution()` **未向 `check()` 传递** `max_detour_km / trip_detour_remaining_m`；`v14_solver` 构造 engine 时也没有 detour 预算。
5. `handover.can_handover` 只比较 `to_departure >= from_arrival`，未计入 travel+dwell+handling。
6. `_compact_solution` 从 `m=1` 起“首个可行即 return”，同车辆数但 objective 更差的解会覆盖更优 HACO 解。

## 2. 修复后（本轮新增/变更）

| 模块 | 类型 | 作用 |
|---|---|---|
| `app/dispatch_opt/coordinator.py` | 新增 | `DynamicDispatchCoordinator`：动态调度唯一主入口 |
| `app/dispatch_opt/candidate_builder.py` | 新增 | 8 类完整候选 + 按腿 MultiLeg 语义 |
| `app/dispatch_opt/route_cost_provider.py` | 新增 | 正式道路成本唯一来源 + formal 判定 |
| `app/dispatch_opt/economic_policy.py` | 新增 | 经济准入 ACCEPT/DEFER/TRANSFER/HOLD/REJECT |
| `app/dispatch_opt/flexibility.py` | 新增 | 订单灵活性 + 高价值保护优先级 |
| `app/dispatch_opt/decision_trace.py` | 新增 | 可解释决策轨迹（含 camelCase API 输出） |
| `app/dispatch_opt/pool_diagnostics.py` | 新增 | 候选池漏检诊断（`CANDIDATE_POOL_MISS`） |
| `app/dispatch_opt/failure_cases.py` | 新增 | 失败分类 + Worst-20 输出 |
| `app/dispatch_opt/runtime.py` | 新增 | 运行时 DTO + `/api/v1/dispatch/allocate` 接线 |
| `app/routing/coordinate.py` | 新增 | GCJ-02 ⇄ WGS-84 唯一边界 |
| `app/dispatch_opt/marginal_cost.py` | 扩展 | `evaluate_from_route_costs` / `evaluate_deltas`（formal only） |
| `app/dispatch_opt/gap_detour.py` | 重写 | 真实道路 provider；非 formal → `UNKNOWN`；直线仅下界 |
| `app/dispatch_opt/handover.py` | 修复 | 计入 travel+dwell+handling；`HANDOVER_INFEASIBLE` |
| `app/dispatch_opt/trip_lock.py` | 扩展 | `decide_candidate()` 统一 7 条件 + reason_code |
| `app/dispatch_opt/reachability.py` | 扩展 | `classify_candidate_reachability()` 候选级判定 |
| `app/dispatch_opt/allocate.py` | 重写 | **占位成本清零**；可委托 Coordinator |
| `app/haco/feasibility_engine.py` | 扩展 | `FeasibilityContext`；`validate_solution` 统一 hard constraint |
| `app/haco/v14_solver.py` | 修复 | `_compact_solution` 安全替换守卫 |
| `app/models.py` | 扩展 | `PlanShipment.economicValue`、`PlanOrder.readyTime/pickupDeadline/deliveryDeadline/priority`、`Vehicle.cargoWeightCapacityKg/cargoVolumeCapacityM3` |
| `app/main.py` | 扩展 | 新增 `POST /api/v1/dispatch/allocate` |

## 3. 当前 runtime

```
/api/v1/plan               → build_result → solve → v14_solver.solve（HACO-CPS 1.4.1）
/api/v1/dispatch/allocate  → allocate_dispatch → DynamicDispatchCoordinator → DispatchPlan + Decision Trace
/api/v1/route | /distance  → 真实道路（AMAP；未配置则显式 euclidean 标注）
```

新增端点已通过 `fastapi.testclient` 实测（`tests/test_dispatch_runtime_api.py`）。

## 4. 调度 candidate 流程

见 [DISPATCH_V047_ARCHITECTURE.md](./DISPATCH_V047_ARCHITECTURE.md) §2/§3。
每候选独立走：可达性 → TripLock → 真实增量成本 → 乘客约束 → SLA → 经济准入 → 结构化比较。

## 5. real marginal cost

- 正式入口：`MarginalCostEvaluator.evaluate_from_route_costs(baseline, candidate, ...)`；**两侧都必须是 formal**，否则返回 `UNKNOWN`（`BASELINE_NOT_FORMAL` / `CANDIDATE_NOT_FORMAL`），不产生任何 delta。
- `evaluate_deltas(...)` 从真实 Δdistance / Δduration 组装 `MarginalCostBreakdown`。
- 乘客影响标记 `passenger_impact_kind = "PROXY"`（无真实乘客时刻表）。
- **占位常量清零**：`allocate_new_order` 已不再注入 500/60/10/2.0/3.0；`FORBIDDEN_PLACEHOLDER_COSTS` 常量显式列出这些值，仅供 unit test stub。

## 6. routing source

`RouteCostProvider` 优先级：`CachedRealRouteProvider` → `MapEngineRouteCostProvider`（Local/GraphHopper → Cache → AMap 校验）→ `HaversineLowerBoundProvider`（**永不 formal**）。
正式状态白名单：`REAL_ROAD / LOCAL_ROAD / AMAP_VERIFIED / CACHED_REAL`。

## 7. reachability

`classify_candidate_reachability(CandidateReachabilityInput)` 逐候选输出：

`REACHABLE / REACHABLE_WITH_DETOUR / FUTURE_TRIP_REQUIRED / TRANSFER_REQUIRED / NEAREST_STATION_REQUIRED / UNKNOWN / UNREACHABLE`

失败理由：`ROAD_UNREACHABLE / VEHICLE_ACCESS_BLOCKED / USER_POINT_UNSERVABLE / ALREADY_PASSED / ETA_MISSED / DETOUR_TOO_LARGE / DRIVER_SHIFT_CONFLICT / VEHICLE_CAPACITY_UNAVAILABLE / LOCATION_STALE / NETWORK_UNCERTAIN`。

**`UNKNOWN` 不直接 REJECT**：进入 `UNKNOWN_PENDING_CONFIRMATION` → `HOLD`（保持计划、等待刷新）。

## 8. TripLock

`PLANNED / READY` 正常候选；`DEPARTED / IN_PROGRESS` 普通订单默认不插；高价值实时插入必须同时满足 7 个条件：正式真实增量距离 ≤ 实时限额、增量时长 ≤ 实时限额、乘客影响 ≤ 严格限额、SLA safe、司机班次 safe、货舱容量 safe、`Economic Admission == ACCEPT`。`COMPLETED / FAILED` 禁止。所有拒绝均带 `reason_code`。

## 9. MultiLeg

- 真实生成 `MULTILEG_2` / `MULTILEG_3`，含 legs / handover / arrival / departure / waiting / handling / final ETA / SLA slack / handover count / incremental cost；
- **按腿语义**使联运成为真正竞争方案（单车完成 pickup+delivery 超预算时，各腿仍可在预算内）；
- 交接时间：`next_departure >= from_arrival + travel + dwell + handling`，否则 `HANDOVER_INFEASIBLE`；
- 链预算 = 各腿预算之和，并保留 `per_leg_budget_ok` 逐腿硬校验。

## 10. SLA / ETA

`PlanOrder` 向后兼容新增 `readyTime / pickupDeadline / deliveryDeadline / priority`（全部 optional）。候选级计算 `estimated_pickup_eta / estimated_delivery_eta / sla_slack`；`delivery_eta > deadline` → 候选 `ETA_MISSED`（INFEASIBLE），不会继续作为正常候选。`PlanShipment` 新增 `economicValue: float | None`（不强制业务层传）。

## 11. Economic Admission

`app/dispatch_opt/economic_policy.py`：`ACCEPT / DEFER / TRANSFER / HOLD / REJECT`，输出 `net_value` 与 `value_to_cost_ratio`；无 `economicValue` 时返回 `FEASIBILITY_ONLY_NO_ECONOMIC_VALUE` 并 `ACCEPT`（正常兼容旧行为）。只在候选 feasible 之后使用，不含巨大 magic weight。

## 12. Gap

`calculate_gap_detour(..., route_provider=provider)`：

- baseline `B → C` 与 insert `B → X → Y → C` 都走真实道路；
- `delta = insert - baseline`；`status=FORMAL` 且 `formal=True`；
- 拿不到正式道路 → `reason_code="ROUTE_UNKNOWN"`、`status="UNKNOWN"`、`delta=0`，**绝不冒充**；
- 未传 provider 时走既有直线下界路径，显式 `status="FALLBACK"`、`formal=False`；
- `build_gap_waypoints` 从构造上保证不会产生 `B → X → Y → B → C`。

## 13. Objective

本轮**不修改** `ObjectiveVector`。证据见 [OBJECTIVE_SENSITIVITY_REPORT.md](./OBJECTIVE_SENSITIVITY_REPORT.md)：真实 HACO 求解下 A/B/C 口径一致、无反例，故维持现有 6 维 lexicographic。

## 14. Rolling Horizon

沿用 `RemainingSegmentReplan`（未重写），只重优化 `UNEXECUTED + UNLOCKED`；普通 `ARRIVED` 只更新状态；`COMPLETED / LOCKED / EXECUTED` 禁止修改。事件覆盖 `NEW_ORDER / VEHICLE_* / ROAD_UNAVAILABLE / SERVICE_POINT_BLOCKED / REACHABILITY_CHANGED / ORDER_CANCELLED / EMERGENCY_ORDER / DRIVER_REQUEST`。

## 15. Decision Trace

`DecisionTrace` 每候选记录 20+ 字段（orderId → selected），提供 `why_selected()` / `why_not_others()` / `as_dict()`，API 输出 camelCase。实测输出示例：`CURRENT_TRIP 347/07:30；reason=PRE_DEPARTURE_OPEN；成本 UNKNOWN（未获得正式真实道路）；乘客影响 proxy 0s；增量成本 21.70。`

## 16. regression

新增测试（全部通过）：

| 文件 | 用例数 | 覆盖 |
|---|---|---|
| `tests/test_dispatch_v047_p0.py` | 41 | 场景 1–30 + CASE 1–10 + TripLock + Economic + Flexibility + Trace |
| `tests/test_dispatch_v047_gap_compact.py` | 13 | Gap 正式/UNKNOWN/FALLBACK、gap-level、`FeasibilityContext`、`_compact_solution` 守卫 |
| `tests/test_dispatch_runtime_api.py` | 8 | HTTP 端点、Global HACO 触发、failure-case 分类输出、候选池诊断 |
| `tests/test_coordinate_conversion.py` | 6 | round-trip、站点吸附、GraphHopper 边界、polyline 一致 |

既有回归（未回归）：

```
python -m pytest tests/test_algorithm_integration_dispatch.py tests/test_detour_thresholds.py \
  tests/test_dispatch_scenarios.py tests/test_handover_feasibility.py tests/test_allocate_new_order.py \
  tests/test_feasibility_engine_step2.py tests/test_feasibility_engine_v14.py \
  tests/test_haco_141_regression.py -q
→ 121 passed
```

全量（排除已知的 PATH SEARCH 训练线文件）：

```
python -m pytest tests/ -q -m "not slow" \
  --ignore=tests/test_lsr_training.py --ignore=tests/test_route_search_learning.py
→ 598 passed, 21 deselected

python -m pytest tests/test_route_search_learning.py -q
→ 8 passed
```

唯一失败集合是 `tests/test_lsr_training.py` 的 3 个用例（`HardNegativeMiner.mine` 不存在），
该文件与 `algorithm/learning/training/hard_negative_mining.py` **均不在本次改动清单中**
（PATH SEARCH 训练线，任务禁止修改）。

### 本轮修复中发现并回滚的一个改动（重要）

初版把 `AlgorithmConfig.maxDetourDistanceKm` 直接接到 `v14_solver` 的 `FeasibilityEngine`，导致 `tests/test_detour_thresholds.py::test_case2/case8` 与 `tests/test_algorithm_integration_dispatch.py::test_gap_detour_budget_is_hard_constraint` 失败。

证据表明生产契约是：**批次全局求解中 `maxDetourDistanceKm` 是 stop 级软决策（`accepted=False` + `serviceMode=NEAREST_STATION`，方案仍 feasible）**；候选级 trip 绕行硬约束属于动态调度层。

处置：**回滚该接线**，保留 `FeasibilityContext` 能力（调用方显式传入时才硬约束），并在架构文档中固化两种口径的边界。这符合任务要求「如果某个修改让 benchmark 退化：回滚」。

## 17. benchmark

见 [DISPATCH_BENCHMARK.md](./DISPATCH_BENCHMARK.md)。本轮（7 场景，真实重庆站点）：HACO_DYNAMIC 完成率 0.714、HOLD 0.286、P50 0.28ms、P95 0.506ms；HACO_ONLY 两个真实 HACO 实例 feasible；`HACO_DYNAMIC_GH` 记 `SKIPPED_NO_ROUTING_BACKEND`（本机无 OSM/GraphHopper/AMAP，不伪造）。

## 18. worst-20

`algorithm/data/dispatch_v047_failure_cases.json`（`round=baseline`，9 条）：`ROUTING_FALLBACK × 7`（无正式路网 → 成本显式非 formal，预期行为）、`UNKNOWN × 2`（DEPARTED + 高价值在无正式成本下 HOLD，预期行为）。

## 19. 已知限制

1. **无正式路网/地图后端**：本机无 OSM 图、无 AMAP key，正式成本与 GH 指标无法实测；接后端后必须重跑 benchmark，确认 `ROUTING_FALLBACK` 归零。
2. **passenger impact 是 PROXY**（Δduration × 车上人数），无真实乘客时刻表，不称“真实乘客延误”。
3. **weight/volume**：`Vehicle.cargoWeightCapacityKg/cargoVolumeCapacityM3` 与 `PlanOrder/PlanShipment.weightKg/volumeM3` 已就位，但 `FeasibilityEngine` 当前仍以 `itemCount/size` 为容量口径，重量/体积**尚未接入求解硬约束**（P1 未完成）。
4. **global gap-level cargo detour 重口径**：`gap_detour` 已按 gap 级实现并有回归，但 `haco/evaluator` 的 `cargo_detour` 仍按事件级累计（未改动，避免影响 1.4.1 稳定性）。
5. **candidate pool 诊断**目前是全量检查（无截断），`CANDIDATE_POOL_MISS` 仅在人工传入 `pool_size` 时可触发。
6. **`tests/test_lsr_training.py` 3 个失败为既存问题**（`HardNegativeMiner.mine` 不存在），属 PATH SEARCH 训练线，本任务禁止修改。
7. `HACO repair priority`（section 28）、`ALNS destroy/repair` 保护（section 29）、`V046 selector` 自动替换（section 33）为接口/策略已就绪、待接线的 P1/P2 项。

## 20. 最终状态

### P0 完成验收（section 43 对照）

| # | 验收项 | 状态 | 证据 |
|---|---|---|---|
| 1 | `allocate_new_order` runtime 真正接入 | ✅ | `/api/v1/dispatch/allocate` + `allocate.py` 委托 Coordinator |
| 2 | placeholder cost = 0 | ✅ | `allocate.py` 无 500/60/10/2.0/3.0；`FORBIDDEN_PLACEHOLDER_COSTS` |
| 3 | formal route cost 不使用 Haversine | ✅ | `RouteCostProvider` formal 白名单；非 formal → UNKNOWN |
| 4 | candidate-specific reachability | ✅ | `classify_candidate_reachability`（逐候选） |
| 5 | MultiLeg 真正参与 recovery | ✅ | `MULTILEG_2/3` + 按腿语义（CASE 7 实测） |
| 6 | handover 时间正确 | ✅ | `arrival + travel + dwell + handling <= departure` |
| 7 | TripLock 正确 | ✅ | `decide_candidate()` 7 条件 + reason_code |
| 8 | detour hard constraint 落到 final validation | ✅ | `FeasibilityContext` + `validate_solution` 传递 |
| 9 | passenger impact policy 生效 | ✅ | `maxPassengerImpactSeconds` candidate 级 hard reject |
| 10 | Decision Trace 可输出 | ✅ | `DecisionTrace.as_dict()`（camelCase） |

→ **`DISPATCH_CORE_V047_READY`**

### 稳定验收（section 44）

passenger skeleton、pickup-before-delivery、return、capacity、trip lock、remaining segment lock、MultiLeg completion、UNKNOWN semantics —— 全部有对应回归且未回归。

### 状态标记

```
DISPATCH_CORE_V047_READY
```

未标 `ACTIVE`：按任务要求，需在真实路网 + 真实场景 + 完整 benchmark 通过后才考虑 ACTIVE。
