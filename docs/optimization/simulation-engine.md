# 模拟运营引擎（SimulationEngine）

> ⚠️ **已废弃（2026-09-21）**：模拟运营子系统已随运营化改造（PR #153）整体下线——`/transport/simulation/*` 控制接口、`SimulationEngine/Runtime`、4 张模拟运行时表（由 `sql/incremental/V021__remove_simulation.sql` 清理）与 `SIMULATION_ENABLED` 开关均已移除。车辆位置现仅以司机端真实上报为准。本文仅作历史存档。

## 目标
模拟车辆沿**真实道路 polyline**移动（非站点间直线），时间按倍速推进，模拟时间影响任务段状态。

## 实现（Phase 7）
- `SimulationEngine`（@Service）：内存运行，每车一条 SimRun。
  - **状态机**：STOPPED / RUNNING / PAUSED / COMPLETED；推进中派生 ARRIVING（<50m 到站阈值）/ ARRIVED（停靠作业中）。
  - **倍速**：1x/5x/10x/30x/60x；`currentSimSeconds = 墙钟流逝 × multiplier`（暂停保留模拟时刻，改速不跳变）。
  - **沿 polyline 插值**：`computeTick` 定位当前段 → `interpolatePolyline` 按里程比例插值（纯函数，可单测）。
- `SimulationService`：从方案明细构建有序模拟段（每段 = 上一站→本站，行驶/作业秒取明细，`/route` 取真实 polyline，失败回退两点直线明确 euclidean）。
- 控制接口：`POST /transport/simulation/start|pause|resume|reset|speed`（管理端）。
- **开关**：`transport.simulation.enabled`（`SIMULATION_ENABLED`），生产默认 false；false 时所有控制 no-op。

## 监控联动
- `MonitoringServiceImpl.getRealtimeVehicles`：有活跃模拟运行且启用时，SIMULATED 位置用引擎推进；**REAL 司机上报仍优先**。
- 位置数据源：REAL / SIMULATED（/ STALE / NO_LOCATION 见 realtime-bus.md）。

## 测试
`SimulationEngineTest` 7 例：polyline 插值 / 段内推进 / 到站作业 / 控制状态机 / 未启用 no-op / 当前段判定。
