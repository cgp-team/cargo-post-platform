# 首页实时公交

> **2026-09-21 更新**：模拟运营子系统（SimulationEngine、`DeterministicScheduleSimulator`、班次时刻插值兜底）已随运营化改造（PR #153）整体下线，
> SIMULATED 数据源不复存在；车辆位置现**只认司机端真实上报**。下文已按现状改写。

## 数据源
统一 `MonitoringService.getRealtimeVehicles()` → `VehicleLocationProvider`：**REAL**（司机 15min 内上报）优先，无上报则为 **OFFLINE**。
位置新鲜度三态：**REAL**（司机上报 <5min，新鲜）/ **REAL_STALE**（上报 ≥5min，司机中断上报）/ **OFFLINE**（15min 内无上报，无坐标，不上图）。

## 首页（小程序 index + bus 页）
- 附近公交：`/transport/bus/nearby`（Haversine 过滤 + 真实道路 ETA：车辆→下一站经 `/route`，60s 缓存节流）。
- 数据源标识：实时（🟢）/ **位置可能过期**（🟠 STALE）/ 位置暂不可用（OFFLINE）。
- 15s 定时刷新；空/错误/加载有明确状态。

## 监控
- `MonitoringServiceImpl`：REAL 窗口 15min；超过窗口未上报的车辆标记 REAL_STALE，真正无位置的标记 OFFLINE。
- 无真实位置时**不做任何模拟/推算兜底**——监控图上宁可不显示，也不展示伪造位置。

## 纪律
- 位置只来自司机端真实上报；高德公交数据不冒充自己车辆。
