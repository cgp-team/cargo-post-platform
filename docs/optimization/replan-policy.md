# 重新规划策略（Replan Policy）

## 原则
正常运行不重新 Smart Dispatch。执行过程加载完整 DispatchPlan 连续执行；只有下列情况才 **Replan Remaining Segment**（重新规划的是"剩余任务段"，不是从头破坏整个班次）：

1. 车辆故障
2. 道路不可达
3. 订单取消
4. 紧急新增任务
5. 调度员人工触发
6. 司机申请
7. 当前任务段不可继续

## 当前实现状态
- **第一阶段（已落地）**：偏航只报警（`ROUTE_DEVIATED`，>100m，Phase 9），不自动修改 DispatchPlan，不自动 Smart Dispatch。
- **后续迭代**：Replan Remaining Segment（对剩余 orderedStops 重排，保留已完成的客运服务）待实现，属于后续 Phase 范围。

## 纪律
- 不因到达某站自动重新调度。
- 客运班次 Mandatory：重排不得删除/跳过必须服务站点、不得破坏 BOARD/ALIGHT 顺序。
