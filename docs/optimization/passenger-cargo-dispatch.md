# 客运+货运联合调度（公交骨架 + 闲置运力）

## 范式
算法输入：公交线路/固定站点/班次（骨架）+ 乘客需求 + 货运订单 + 车辆双容量 + 道路距离/时长。
**客运班次是 Mandatory Passenger Service**：骨架站不可删、顺序不可改；货运作为绕行插入骨架间隙。

## 求解器（`algorithm/app/solver.py`，Phase 5）
- `Vehicle.skeleton`：该车必经站点（线路站序，去场站）。
- 骨架节点 action=PASS，`VehicleVar` 钉到指定车 + 距离维度 cumul 顺序约束。
- 货运单节点 DELIVER/PICKUP 作为绕行插入骨架间隙。
- **首解策略改 PATH_CHEAPEST_ARC**（PARALLEL_CHEAPEST_INSERTION 对骨架顺序约束会首解失败）。
- 载货双维度：CargoOut 派送累计 ≤ 容量 + CargoIn 揽收累计 ≤ 容量（出程派送/返程揽收，闲置运力复用）。

## 算法解释（Phase 5）
货运经停输出 accepted / serviceMode / servicePoint / detourDistance / detourDuration / passengerImpact / reasonCode。
骨架站 detour=0；绕行站 detour=进入该段的分段距离（代理，完整绕行成本待 Phase 6 路网细化）。
落库到 `transport_dispatch_plan_item`（service_mode/service_point/detour_*/reason_code）。

## 接线
- 管理端智能派单 `shiftId`（可选）：指定后该批车辆按班次线路骨架经停（`resolveSkeleton`：班次→线路→按序站点去场站）。
- 不指定 → 纯 VRP（向后兼容）。

## 已知项
- 骨架"整批车辆共用同一班次"（v1 简化）；多车多线路需按车指定班次。
- 乘客容量仍为累计（BOARD+1，批次内座位不复用），未改净载荷。
