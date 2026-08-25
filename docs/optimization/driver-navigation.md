# 司机导航

## 司机任务（Phase 8）
- 工作台展示**完整任务段**（按方案分组、站点聚合 BOARD/ALIGHT/PICKUP/DELIVERY 动作，来源 `/driver/tasks`）。
- 到站阈值：50m（`ARRIVE_RADIUS_METERS`）。
- 任务状态后端为源：到站 → 当前站 ARRIVED / 之前 COMPLETED / 其后 PENDING。

## 司机路线（Phase 9）
- `/driver/route`：完整任务段有序经停 + **真实道路 polyline**（拼接 /route 每段，euclidean 兜底明确标注）+ **偏航判定**。
- 地图 polyline 优先用后端真实道路（workbench `driverRoutePolyline`），否则退化为站点连线兜底。
- **偏航**：车辆上报位置距规划 polyline > 100m → `ROUTE_DEVIATED`，workbench 显示偏航告警横幅（**只报警，不自动改方案**）。

## 服务方式展示
司机只执行"已审批 + 已调度"的服务方式（Phase 2 审核落库 pickup/delivery_service_mode），不自行改服务点。

## 已知项
- 完整导航按钮流（开始前往 / 确认到达 / 确认派货 / 下一任务）与已走/未走分段高亮仍在 UI 层可继续按 `/driver/route`+`/driver/tasks` 接入。
- 动作级状态推进（depart→EN_ROUTE、pickup/deliver→对应动作 COMPLETED）待细化。
