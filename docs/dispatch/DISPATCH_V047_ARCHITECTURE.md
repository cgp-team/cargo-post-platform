# DISPATCH_CORE_V047 — 客货邮调度核心架构

版本：`DISPATCH_CORE_V047`
生产算法：`haco-cps-1.4.1`（HACO-CPS 1.4.x + ALNS v14）
状态：见 [DISPATCH_V047_REPORT.md](./DISPATCH_V047_REPORT.md)

---

## 1. 分层与职责边界

```
HTTP 层            app/main.py
                    ├── POST /api/v1/plan            （批次求解，HACO-CPS 1.4.1）
                    ├── POST /api/v1/dispatch/allocate（**新订单动态调度，唯一主入口**）
                    ├── POST /api/v1/route / distance （真实道路查询）
                    └── GET  /api/v1/result/{id}

调度决策层          app/dispatch_opt/            ← 本任务重点
                    DynamicDispatchCoordinator （动态调度唯一主入口）
                    ├── candidate_builder          候选构造（8 类）
                    ├── route_cost_provider        正式道路成本唯一来源
                    ├── marginal_cost              真实增量成本
                    ├── reachability               候选级可达性
                    ├── trip_lock                  发车锁定策略
                    ├── handover                   交接/联运链时间可行性
                    ├── gap_detour                 Gap 级绕行（真实道路）
                    ├── economic_policy            经济准入
                    ├── flexibility                订单灵活性 + 保护优先级
                    ├── decision_trace             可解释决策轨迹
                    ├── pool_diagnostics           候选池漏检诊断
                    ├── remaining_replan           滚动重规划（未执行/未锁定）
                    └── failure_cases              失败用例分类 + Worst-20

求解层              app/solver.py
                    ├── HACO  → app/haco/v14_solver.py （production 1.4.1）
                    ├── BASELINE → app/baseline/ortools_solver.py
                    └── HYBRID → HACO + OR-Tools portfolio

硬约束层            app/haco/feasibility_engine.py
                    FeasibilityContext（统一 immutable context）

路由层              app/routing/
                    ├── coordinate (GCJ-02 ⇄ WGS-84 唯一边界)
                    ├── local_routing / graphhopper / engine（formal geometry）
                    └── gap_routing / real_road_cost
```

**禁止**：`main.py` / `solver.py` / `candidate_selector.py` 各自维护不同的动态调度排序逻辑。
新订单统一走 `DynamicDispatchCoordinator`。

## 2. 动态调度标准流程

```
NEW_ORDER
  ↓ Service Point Resolve        服务点解析（原位置 → 合法服务点，保留 original）
  ↓ Candidate Build              CURRENT / NEXT / LATER / OTHER_ROUTE / MULTILEG_2 / MULTILEG_3 / NEAREST_STATION
  ↓ Candidate-specific Reachability   逐候选独立判定（禁止“全局一次判定”）
  ↓ TripLock                     PLANNED/READY 可插；DEPARTED/IN_PROGRESS 默认冻结 + 高价值例外
  ↓ Real Marginal Cost           baseline vs candidate（formal 道路）
  ↓ Passenger Constraint         maxPassengerImpactSeconds（candidate 级 hard reject，标记 PROXY）
  ↓ SLA / ETA                    pickupDeadline / deliveryDeadline → sla_slack
  ↓ Economic Admission           ACCEPT / DEFER / TRANSFER / HOLD / REJECT
  ↓ Candidate Compare            结构化 comparator（feasibility → passenger → 稳定性 → 经济 → 成本）
  ↓ Current / Next / Other / MultiLeg
  ↓ DispatchPlan
  ↓ Decision Trace               为什么选、为什么没选其他
```

## 3. 候选类型与字段

`DispatchCandidate` 必须携带完整车辆/班次/时间/路线上下文：

`vehicle_id, driver_id, route_id, shift_id, departure_time, execution_state, current_location,
pickup_service_point, delivery_service_point, remaining_cargo_capacity,
remaining_passenger_capacity, trip_detour_remaining_m, passenger_impact_budget_s, gap_index,
estimated_pickup_eta, estimated_delivery_eta, sla_slack_s, handover_count, reason_code`

| 候选 | 语义 |
|---|---|
| `CURRENT_TRIP` | 当前班次插入（含 `gap_index`） |
| `NEXT_TRIP` / `LATER_TRIP` | 同线路后续班次（按 `next_trip_horizon_s` 划分） |
| `OTHER_ROUTE` | 其他线路 |
| `MULTILEG_2` / `MULTILEG_3` | 联运链（true 竞争方案，非空壳） |
| `NEAREST_STATION` | 车辆不可进入/用户点不可服务 → 最近合法服务点 |
| `HOLD` | 无可行候选 → 挂起 + 人工/下一轮 |

### MULTILEG 的“按腿”成本语义

联运链每段只承担自己的那一段任务：

- leg1 用 `service="PICKUP"` 估：`detour = d(origin→pickup) - d(origin→delivery)`
- leg2/leg3 用 `service="DELIVERY"` 估：`detour = d(pickup→delivery) - d(origin→delivery)`

因此“单车一次完成 pickup+delivery”绕行超预算时，各腿仍可能都在预算内 —— 这才是 MultiLeg 真正成为
竞争方案的条件。链的绕行预算 = 各腿预算之和；同时保留 `per_leg_budget_ok` 逐腿硬校验。

## 4. 成本口径

| 类别 | 允许来源 | 禁止 |
|---|---|---|
| 正式距离/时长/绕行/增量 | `LOCAL_ROAD / CACHED_REAL / AMAP_VERIFIED / REAL_ROAD` | 直线、degree |
| 直线（Haversine） | prefilter、lower bound、cheap reject、snap reference | 正式成本 |
| routing 不确定 | `UNKNOWN / HOLD / FALLBACK` 显式返回 | 伪装成真实 |

`RouteCostProvider` 接口：

```python
route(from_, to, *, waypoints=(), route_type=...)
route_gap(gap_from, gap_to, *, pickup=None, delivery=None, extra=()) -> GapRoutePair
route_insert(insert_from, insert_to, insert_points)
route_candidate(waypoints)
is_formal() -> bool
```

实现优先级：`CachedRealRouteProvider` → `MapEngineRouteCostProvider`（Local/GraphHopper → Cache → AMap 校验）
→ `HaversineLowerBoundProvider`（**永不 formal**）。

## 5. 坐标边界（GCJ-02 / WGS-84）

```
业务/前端/AMap  GCJ-02
   ↓ gcj02_to_wgs84        （app/routing/coordinate.py，唯一边界）
OSM/GraphHopper  WGS-84
   ↓ wgs84_to_gcj02
业务             GCJ-02
```

`MapEngineRouteCostProvider` 在自身边界内完成 **一次** GCJ→WGS 与 **一次** WGS→GCJ；
`test_coordinate_conversion.py` 证明 round-trip 误差 < 1m、站点吸附必须同坐标系、polyline 往返一致。

## 6. 硬约束统一口径

`FeasibilityContext`（frozen）承载：

`station_map, matrix, max_duration, max_detour_km, trip_detour_remaining_m, expected_task_ids`

`FeasibilityEngine.check()` 与 `validate_solution()` 都接受 `context`，**最终出口**与内部调用
共享同一硬约束口径。

> 重要口径说明（与实测回归对齐）：
> `AlgorithmConfig.maxDetourDistanceKm` 在**批次全局求解**中保持既有产品语义
> （stop 级 `accepted=False` + `serviceMode=NEAREST_STATION`，方案仍 feasible —— 见
> `tests/test_detour_thresholds.py::test_case2/case8/case11`）。
> **候选级**的 trip 绕行硬约束走 `DynamicDispatchCoordinator`（`trip_detour_remaining_m`）
> 与 `FeasibilityContext`（当调用方显式传入预算时生效）。两者不混用、不互相覆盖。

## 7. 滚动重规划

`RemainingSegmentReplan` 只把 `UNEXECUTED + UNLOCKED` 作为搜索空间：

- 普通 `ARRIVED`：只更新状态，不触发全局重调度；
- 事件：`NEW_ORDER / VEHICLE_DEPARTED / VEHICLE_ARRIVED / VEHICLE_FAILED / ROAD_UNAVAILABLE /
  SERVICE_POINT_BLOCKED / REACHABILITY_CHANGED / ORDER_CANCELLED / EMERGENCY_ORDER / DRIVER_REQUEST`；
- 禁止修改 `COMPLETED / LOCKED / EXECUTED`。

## 8. 升级梯度

```
LEVEL_0_FAST_INSERT → LEVEL_1_NEXT_TRIP → LEVEL_2_OTHER_ROUTE_OR_MULTILEG
→ LEVEL_3_LOCAL_REPAIR → LEVEL_4_GLOBAL_HACO → LEVEL_5_HOLD
```

LEVEL 只是搜索升级顺序；最终选择由 Feasibility + Passenger + SLA + Economic + Marginal Cost + Stability 决定。
**默认不跑 Global HACO**，仅当低成本候选全部不可行且（高价值 或 SLA 高紧急 或存在明显全局优化空间）时升级，
并记录 `global_haco_trigger_reason` 与 `global_haco_trigger_count`。

## 9. 与 PATH SEARCH V046/V047 的接口

- Dispatch 侧只消费 `V046 GH Branch Selector` 的 **production integration interface**（候选 via → selector → Adaptive-K → GraphHopper CH/LM），
  不重写 ML routing；
- **Path Search ML ≠ Dispatch ML**：`learning/path_search/*` 与 `dispatch_opt/*` 完全解耦，本任务不修改
  V047 trainer / dataset label / branch ranker 核心逻辑；
- V047 ranker 训练完成后可替换 branch ranker，dispatch 侧无需改动。

