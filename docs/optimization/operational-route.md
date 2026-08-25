# Operational Route（运营任务段）

## 定义
**Operational Plan ≠ Road Route。** 运营任务段是"业务上该车按什么顺序停哪些站、做什么动作"，由调度算法决定，与真实道路坐标无关。

## 结构
```
DispatchPlan（一车一时窗）
├── taskWindowStart / taskWindowEnd
└── orderedStops[]（DispatchPlanItem，按 visitSequence 有序）
    ├── stationId / stationName
    ├── actions[]（action_type + orderId + quantity + status）
    ├── plannedArrival / plannedDeparture / serviceDuration
    └── status（TaskItemStatusEnum，后端为源）
```

## 来源
- 智能派单：OR-Tools 求解（公交骨架 + 货运绕行插入）。
- 手工派单：调度员按订单顺序生成闭环经停。

## 消费者
- 司机端工作台（完整任务段展示）
- 后台调度地图（`/monitoring/vehicle-plan`）
- 模拟引擎（输入）
- 订单/方案状态推进

## 纪律
- 执行过程不因到达某站自动重新调度（只有故障/不可达/订单取消/紧急任务/人工/司机申请/当前段不可续才 Replan，见 replan-policy.md）。
