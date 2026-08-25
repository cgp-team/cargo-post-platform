# 任务段模型（DispatchPlan = 完整连续运营任务段）

## 定义
一个车辆在一个时间窗口内的完整连续运营任务段，一次性形成 **Ordered Stops**。
执行过程中不因到达某站而自动重新调度；全部 Stop 完成后方案才 COMPLETED。

## 表结构（Phase 4）
`transport_dispatch_plan`（方案级）：
- task_window_start / task_window_end（任务段窗口；开始=批次出发时刻，结束=开始+预计耗时）

`transport_dispatch_plan_item`（经停动作，即 orderedStops 的一个 Stop）：
- visit_sequence / station_id / action_type（0出发 1接客 2送客 3派送 4揽收 5返回 6经停PASS）
- estimated_arrival_time / planned_departure_time（=到达+作业时长）
- segment_duration_seconds / segment_distance_km（分段路网）
- service_duration_seconds / quantity（BOARD/ALIGHT=人数，PICKUP/DELIVERY=件数）
- status（TaskItemStatusEnum：待执行/行驶中/已到站/上车中/下车中/揽收中/派送中/已完成/失败，**后端为源**）
- service_mode / service_point_station_id / detour_distance_km / detour_duration_seconds / reason_code（算法解释）

## 估算
`DispatchEstimationService.estimatePlan`：逐站累计行驶（高德分段秒优先，否则直线÷均速）+ 作业分钟，回写到达/离站/作业/数量/窗口。

## 状态推进
- 司机到站（`arrive`）→ 当前站 ARRIVED、更早 COMPLETED、其后 PENDING（`syncTaskItemStatusOnArrive`）。
- 方案内订单全部 COMPLETED → 方案 COMPLETED（`maybeCompletePlan`）。

## 司机端
`/driver/tasks` 返回完整任务段（planId/窗口/visitSequence/离站/数量/状态），workbench 按站点聚合动作展示。
