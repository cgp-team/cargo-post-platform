# 首页实时公交

## 数据源（Phase 10）
统一 `MonitoringService.getRealtimeVehicles()` → REAL（司机 15min 内上报）优先，否则 SimulationEngine 或班次时刻插值（SIMULATED）。
位置新鲜度四态：**REAL_FRESH**（真实 <5min）/**REAL_STALE**（真实 ≥5min，司机中断上报）/ **SIMULATED**（模拟）/ **NO_LOCATION**（无坐标）。

## 首页（小程序 index + bus 页）
- 附近公交：`/transport/bus/nearby`（Haversine 过滤 + 真实道路 ETA：车辆→下一站经 `/route`，60s 缓存节流）。
- 数据源标识：实时（🟢）/ 模拟运营 / **位置可能过期**（🟠 STALE）/ 位置暂不可用。
- 15s 定时刷新；空/错误/加载有明确状态；演示村庄定位仅 release 前可见。

## 监控
- `MonitoringServiceImpl`：REAL 窗口 15min；模拟位置沿站点直线插值（Phase 7 前过渡态，标注 SIMULATED 不冒充真实）。
- 模拟引擎启用时 SIMULATED 用引擎沿真实 polyline 推进。

## 纪律
- SIMULATED 永不冒充 REAL（dataSource 强制标注）；高德公交数据不冒充自己车辆。
