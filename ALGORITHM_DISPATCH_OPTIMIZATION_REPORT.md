# ALGORITHM_DISPATCH_OPTIMIZATION_REPORT

客货邮智能调度算法第二阶段优化报告  
（公交主任务 + 局部货运绕行 + 成本/收益 + 发车锁定 + 未来承运 + MultiLeg 接力 + 不可达恢复）

工作范围：`cargo-post-platform/algorithm/app/dispatch_opt/` + 少量兼容字段  
原则：优化现有算法决策层，不重做业务系统，不实现乘客预约。

---

## 1. 当前仓库已经存在的能力（审计结论）

| 能力 | 位置 | 本轮处理 |
|------|------|----------|
| Vehicle.skeleton / Mandatory Passenger Stop | `models.Vehicle.skeleton` | **复用**为 Mandatory Passenger Service Skeleton |
| Gap/骨架间隙货运插入 | RouteGenome `get_gap_ranges` / construction | **复用**并强化 `can_detour_within_gap` |
| passenger impact / CargoOut / CargoIn | evaluator / FeasibilityEngine | **复用** |
| RouteGenome / invariant / SA / Swap | 第一阶段 HACO-CPS | **不重复**，P0 保持 |
| DispatchPlan / DispatchPlanItem / segment | Java transport 模块 | **复用**为执行层，算法不重建模 |
| Shift / Driver / Vehicle / Handover | Java dispatch | **复用** |
| MultiLegPlanner / MultiLegService | Java | **不重写**；上层 `TransportChainEvaluator` 包装 |
| MultiLegService.relayFarLegsToNearbyVehicles | Java | **复用**“附近车辆改派”语义 |
| Replan Policy（正常到站不 Smart Dispatch） | `docs/optimization/replan-policy.md` | **保持** |
| StationAccessUtil user/vehicle/dispatch | Java util | **复用**到 ReachabilityDecision |
| warnPickupAlreadyPassed / findBacktrackingViolations | Java | **复用**为 ALREADY_PASSED 恢复触发 |
| VehicleLocationProvider | Java | **复用** REAL / STALE 位置 |
| CargoReview ROAD_UNREACHABLE / DETOUR_TOO_LARGE | Java | **复用**原因码 |
| maxDetourDistanceKm / maxPassengerImpactSeconds | AlgorithmConfig | **复用**为 Trip Detour Budget |
| settlement / pricing | CargoPricingService / PricingRuleService | **可选接入** `economicValue` |

**本轮没有重复实现**：DispatchPlan 表、MultiLeg 数据模型、Shift/Driver 业务流、发车系统、预约体系、全局地图。

---

## 2. 新增/修改的算法组件

包路径：`algorithm/app/dispatch_opt/`（纯算法内部对象，**无新数据库表**）

| 组件 | 文件 | 边界 |
|------|------|------|
| CargoOpportunitySlot | models.py | Gap 窗口候选，不是订单表 |
| MarginalCostBreakdown + CostModel | models.py / marginal_cost.py | 增量成本可追溯；CostModel 可替换 |
| TripLockPolicy / HighValueRealtimeInsert | trip_lock.py | 发车锁定策略，复用 DEPARTED 语义 |
| TripCandidateSelector / RemainingDispatchAllocator | candidate_selector.py | 上层候选，不改 MultiLeg.Candidate |
| can_detour_within_gap / calculate_gap_detour | gap_detour.py | 局部绕行 + rejoin |
| can_handover / TransportChainEvaluator | handover.py | 交接与货运链状态 |
| TaskSegmentCompletionValidator | segment_completion.py | ARRIVED ≠ COMPLETED |
| classify_reachability / plan_recovery | reachability.py | 动态不可达 → 再分配 |
| RemainingSegmentReplan | remaining_replan.py | 只重优化未锁定段 |
| compare_trip_candidates / explain_choice | comparator.py / explanation.py | 结构化排序 + 真实解释 |

兼容修改：
- `PlanOrder.economicValue: float | None`（可选、向后兼容、缺失不报错）

---

## 3. TripLockPolicy

| 状态 | 行为 |
|------|------|
| PLANNED / READY | 允许正常货运插入（NORMAL） |
| DEPARTED / IN_PROGRESS | 默认 `LOCKED_ACTIVE_TRIP` → `FUTURE_DISPATCH` |
| COMPLETED / FAILED | 终态，不追加 |

发车后高价值例外（须同时满足）：
- 不改已执行站点 / 不跳过 Mandatory Stop
- 当前 Gap 内可完成并 rejoin
- 乘客影响、ΔDuration、ΔDistance 在严格阈值内
- 无既有货运冲突、无 Shift 冲突、绕行预算未超
- 性价比明显更高（默认 efficiency ≥ 1.5）

通过后 `HIGH_VALUE_REALTIME_INSERT`：Fast Feasible Insert，不跑全局 HACO。

---

## 4. MarginalCostEvaluator

结构化 `MarginalCostBreakdown`：
distanceCost / timeCost / passengerImpactCost / driverCost / vehicleCost / handoverCost / waitingCost / riskCost  
+ 原始量（ΔDistance/ΔDuration/ΔPaxImpact/ΔWaiting…）

- 默认 `DefaultCostModel` 单价集中在一处，可替换为 RuralBus / ElectricBus / DieselBus
- **不硬编码业务订单金额**
- `efficiency = economicValue / incrementalCost`；`economicValue` 缺失时为 None
- 经济效率**不能突破硬约束**（乘客影响超限仍拒绝）

业务最终排序（comparator）：
1. Hard Feasibility  
2. Passenger Service  
3. Trip Stability  
4. Economic Efficiency  
5. Operational Cost  
6. Distance/Duration  

---

## 5. TripCandidateSelector

候选优先级：
1. Current Planned/Active（发车后严格）
2. Same Route Future（下一班 / 更晚班，独立 Trip Detour Budget）
3. Other Route Direct
4. MultiLeg（2/3 段）
5. Hold / Search Future（**不是** NO_FEASIBLE → 失败）

每个 `TripCandidate` 含 route/shift/vehicle/driver/时间/绕行/乘客影响/handover/成本/效率/feasiability/reason/explanation。

---

## 6. HighValueRealtimeInsert

见 TripLockPolicy。解释来自真实计算，例如：  
“高价值、低扰动，允许 Fast Feasible Insert 更新剩余计划。”

---

## 7. RemainingSegmentReplan

触发：车辆故障、道路不可用、取消、紧急单、人工、司机请求、当前段不可行、  
`REACHABILITY_CHANGED` / `ROAD_BECAME_UNAVAILABLE` / `SERVICE_POINT_BLOCKED` 等。

范围：仅 `UNEXECUTED / UNLOCKED`；保留 COMPLETED / LOCKED / 已服务乘客站。  
普通到站**不**触发全局 Smart Dispatch。  
局部无自由段且硬事件时才 `GLOBAL_ESCALATION`。

---

## 8. MultiLeg / Handover 强化

`can_handover()` 检查：到达/发车时间、dwell、同站/近站、货物在场、下一车容量、司机、Shift、等待超时。  
`SAME_STATION` handoverDistance=0；`NEARBY_STATION` 超阈值 → INFEASIBLE（不把“有线路关系”当可交接）。  
`TransportChainEvaluator`：Leg1+Transfer+… 整链 `IN_TRANSIT`，**最终 DELIVERY** 才 `ORDER_COMPLETED`。

---

## 9. TaskSegmentCompletion

- ARRIVED ≠ COMPLETED  
- 需 PICKUP/DELIVERY/HANDOVER 动作齐全  
- HANDOVER 未确认 → 不完成  
- 多段订单最后一段完成 + final delivery → ORDER_COMPLETED  

---

## 10. Unreachable Recovery

### 已有能力（复用）
CargoReview 原因码、StationAccessUtil、ServiceMode、warnPickupAlreadyPassed、  
findBacktrackingViolations、relayFarLegsToNearbyVehicles、VehicleLocationProvider。

### 本轮新增
动态 `ReachabilityDecision` + `plan_recovery()`（再分配，不是拒单）。

### 不可达分类
ROAD_UNREACHABLE / VEHICLE_ACCESS_BLOCKED / USER_POINT_UNSERVABLE / ALREADY_PASSED /  
ETA_MISSED / DETOUR_TOO_LARGE / DRIVER_SHIFT_CONFLICT / VEHICLE_CAPACITY_UNAVAILABLE /  
LOCATION_STALE / NETWORK_UNCERTAIN

**UNKNOWN ≠ UNREACHABLE**（定位过期、地图不确定 → 保持计划 + 等待）。

### 恢复顺序
SAME_TRIP_LOCAL_REPAIR → SAME_ROUTE_FUTURE_TRIP → OTHER_ROUTE → MULTI_LEG →  
NEAREST_STATION → CUSTOMER_ACTION_REQUIRED → MANUAL_REVIEW → UNSERVICEABLE

### 服务点降级
原始点 `original_point` 永不覆盖；`service_point` 可切到最近合法站。  
**重庆邮电大学校内场景**：vehicleAccess=false → 不进校园，改最近合法站 / 未来班 / 其他线 / MultiLeg。

### ALREADY_PASSED
禁止掉头；直接未来班/其他线/联运。

---

## 11. Benchmark 新指标（模板）

动态：current-trip acceptance / future-trip reassignment / realtime high-value insertion /  
waiting / handover count & success / incremental cost / economic efficiency /  
passenger SLA violation / locked-plan violation / replan 三级计数 /  
orders saved by next-trip & multi-leg / **Recovery Success Rate**

不可达：reachability_failure / temporarily_unreachable / same_trip|future|other|multi_leg|nearest_station recovery / manual / unserviceable / recovery cost & delay

稳定性（任一 >0 测试失败）：  
locked_task_violation / mandatory_stop_violation / task_loss / task_duplication /  
invalid_handover / driver_conflict / shift_conflict / capacity_violation /  
detour_budget_violation / passenger_sla_violation

---

## 12. 测试结果

```
dispatch_opt 专项：53 passed
P0 + 场景回归：  50 passed（含第一阶段 SA/Swap/Invariant）
```

覆盖文件：
- test_trip_lock_policy.py
- test_marginal_cost.py
- test_trip_candidate_selector.py
- test_remaining_segment_replan.py
- test_handover_feasibility.py
- test_task_segment_completion.py
- test_current_location_unreachable.py
- test_dispatch_scenarios.py（场景 A–H）

---

## 13. 典型场景结果

| 场景 | 结果 |
|------|------|
| A 顺路 | CURRENT_TRIP / PRE_DEPARTURE_OPEN |
| B 小绕行 | Gap 内 detour ok，mandatory 保持，rejoin 成功 |
| C 大绕行 | DETOUR_BUDGET_EXCEEDED / MANDATORY 拒绝 |
| D 已发车普通单 | NEXT_TRIP / LATER_TRIP，等待计入 |
| E 已发车高价值 | HIGH_VALUE_REALTIME_INSERT |
| F 其他线路 | OTHER_ROUTE |
| G 347→303 | MULTI_LEG，handover=1 |
| H 347→303→202 | 中段 IN_TRANSIT，最终 DELIVERY → ORDER_COMPLETED |

---

## 14. 性价比决策示例（真实计算结构）

当前 347-07:30：ΔDistance 2.0km，ΔDuration 7min，PassengerImpact 2min，Cost≈6  
下一班 347-08:00：Waiting 20min，Cost≈2  

Comparator 优先乘客影响 → 选下一班；解释：  
“等待 20 分钟由 347 08:00 承运，较当前绕行方案减少绕行并降低乘客扰动。”

---

## 15. 当前仍存在的问题

1. 算法层 `TripCandidate` 尚未与 Java `DispatchPlan` 自动持久化（需业务层适配）。
2. `economicValue` 依赖业务层传入真实字段，算法不读结算库。
3. Real road reachability 仍由调用方填 `AccessFlags`（复用 StationAccessUtil 结果）。
4. 增量 evaluator / 同 route SWAP 等第一阶段遗留项未在本轮展开。
5. 动态 benchmark 统计为模板与断言，尚未接到完整 10–20 seed 流水线。
6. MultiLegPlanner 仍是 Haversine+线网模型，UNCERTAIN fallback 需真实道路数据升级。

---

## 16. 下一阶段建议

1. 业务层把 CargoPricingService 真实金额写入 `economicValue`。  
2. DispatchServiceImpl 在新订单入口调用 TripCandidateSelector + ReachabilityDecision。  
3. 将 REACHABILITY_CHANGED 接入现有 Replan Policy 事件总线。  
4. 用 VehicleLocationProvider 填充 LocationSnapshot，验证校内场景 E2E。  
5. 扩展多 seed 动态 benchmark，输出 Recovery Success Rate。  
6. 第一阶段遗留：增量 evaluator、OR_OPT、时间预算→迭代预算。

---

## 17. 本轮没有做的事

- 未实现乘客预约 / PassengerReservation / 购票 / 预约库表  
- 未重写 MultiLegPlanner / 调度中心 / 地图  
- 未把公交改成普通 VRP  
- 未允许跳过 Mandatory Passenger Stop  
- 未因普通到站自动全局重调度  
- 未硬编码订单金额或虚假 benchmark 收益  
- 未删除 HACO / ALNS / Replan Policy  
- 未改无关前端  
- **未交付调度台/演示 UI**：目标是算法优化，`dispatch_opt` 已接入构造评分与 FeasibilityEngine 硬裁决

---

## 18. 算法接入点（真实调用链，不是旁路模块）

| 接入点 | 变化 |
|--------|------|
| `encoding.TaskBlock.economic_value` | 可选经济价值透传 |
| `v14_solver._encode_tasks` | PlanOrder.economicValue → TaskBlock |
| `construction.generate_insertion_candidates` Stage B | SEARCH_ENERGY 排序 + MarginalCostEvaluator 分解；有 economic_value 时性价比作次级微调（不改量纲） |
| `FeasibilityEngine.check` | 新增 `max_detour_km` / `trip_detour_remaining_m` 硬约束（DETOUR_BUDGET_EXCEEDED） |
| `dispatch_opt.gap_detour` | mandatory 顺序 + rejoin + 预算 |
| `dispatch_opt.trip_lock / candidate_selector` | 发车后订单去向决策（算法层） |
| `algo_support` | 增量评估 / RecoveryCost / SearchBudget / 位置适配 / 同route 邻域 / Benchmark 统计 |
| `dispatch_opt.allocate.allocate_new_order` | Pickup+Delivery 双侧可达 → 恢复/候选 |

集成测试：`tests/test_algorithm_integration_dispatch.py`（6 passed）  
验证：Gap 预算硬约束、FeasibilityEngine 裁决、边际成本可追溯、业务 key() 仍权威、economic_value 不破坏硬约束。

---

## 18b. 后端接线（本轮）

| 组件 | 文件 | 作用 |
|------|------|------|
| `AlgorithmOrderDTO.economicValue` / `AlgorithmShipmentDTO.economicValue` | integration/algorithm/dto | 向后兼容可选字段 |
| `DispatchServiceImpl.resolveEconomicValue` | DispatchServiceImpl | 用 **CargoPricingService 真实报价** 填充，异常返回 null 不造假 |
| PICKUP/DELIVERY/SHIPMENT 构建 | `toAlgorithmOrders` | 透传 economicValue 到算法 |
| `ReachabilityDecisionService` | service/dispatch | 10 类不可达 + UNKNOWN≠不可达 + 校园禁入/已过站 |
| `TaskSegmentCompletionService` | service/dispatch | ARRIVED≠COMPLETED；HANDOVER 未确认不算完成；多段最终 DELIVERY 才 ORDER_COMPLETED |
| `HandoverFeasibilityService` | service/dispatch | 同站/近站（150m）/容量/司机/班次/等待超时 |
| Java 单测 | `DispatchAlgorithmSupportTest` | 覆盖段完成、锁定、交接、可达分类 |

说明：本机无 Maven，Java 侧以现有 Lombok/服务风格编写并配了单测，需在有 Maven 的环境执行 `mvn -pl yudao-module-transport test` 做编译级确认。

---

## 结论

本轮目标是**优化现有客货邮算法决策链**（纯算法）：  
公交骨架不变 → Gap 局部绕行 → 边际成本/性价比 → 发车锁定 → 未来/其他线/MultiLeg 承运 → 不可达动态恢复。  

**“当前订单位置不能让这辆公交直接去，系统怎么办？”**  
> 先判断为什么不可达，再判断当前班次能否低扰动恢复；不能恢复则自动比较下一班次、其他线路、最近合法服务点和 MultiLeg 联运方案，按照乘客服务、可达性、时效和增量成本选择可执行方案；全部方案都不可行时才进入人工处理/不可服务。
