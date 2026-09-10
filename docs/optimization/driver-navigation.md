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
- 完整导航按钮流（开始前往 / 确认到达 / 确认派货 / 下一任务）已可用；**已走/未走分段高亮**仍可在 UI 层继续细化。
- 动作级状态推进：到站 `ARRIVED`、更早经停 `COMPLETED`（`arrive` 回写），发车/装车/妥投分别推进订单状态；动作级 EN_ROUTE/执行中细分可继续细化。

## 2026-09-11 修订
- **班次自动匹配派单片区**：`pickShiftForNav` 选"经停站与任务段重合度最高"的班次（同分在途优先），
  发车/表头线路名与地图任务段一致，避免"任务在重邮片区、发车却是成都线路"。
- **跳过纯出发场站**：恢复进度时跳过只有 DEPART/RETURN、无取派/上下客作业的经停，发车后不会提示"下一站=出发点"。
- **返场确认补回队尾**：`buildNavPoints` 去重后把计划末站（返场）补回队尾并标记 `isReturn`，
  司机端出现「🏁 返场确认」，班次执行记录能真正结束，用户端"司机已到达交付点"据此触发。
- **智能派单方案也能装车**：经停明细不绑定固定班次（`shift_id` 为空）时，装车/妥投按"司机今天实际发车的那条执行记录"兜底，
  修掉"一键演示生成的方案 → 扫码装车报班次执行记录不存在"的断点。
- **商城订单（同理寄货）**：`/driver/pickups` 带上本车待执行商城订单（`bizType=PRODUCT`，orderType=4），
  新增 `/driver/product-load`（装车拍照核验）、`/driver/product-deliver`（妥投交付凭证）；
  发货时写入承运司机与交付站点（= 班次线路终点站），用户端溯源展示司机/到站提醒/凭证照片。
