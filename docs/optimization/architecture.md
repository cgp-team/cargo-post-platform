# 客货邮运输系统架构

> 阶段：Phase 0-14（2026-08）· 顶层业务定义见任务书。本文件为优化工程落地后的架构说明。

## 一、系统本质

**公交客运为主（Passenger Service = Primary），货运为公交闲置运力利用（Cargo Service = Secondary / Idle Capacity Utilization）。**
不是"快递订单→找车直送"，而是"公交车按班次跑固定线路，系统利用其剩余运力顺路带货"。

## 二、四大核心分离

| 概念 | 职责 | 实现 |
|---|---|---|
| **Operational Plan** | 调度算法输出"一车一时窗的完整任务段"（有序经停 + 动作 + 计划时刻） | `DispatchPlan` + `DispatchPlanItem`（Phase 4） |
| **Road Route** | 真实道路（高德驾车路径 polyline） | `/api/v1/route`（Phase 6） |
| **Vehicle State** | 车辆位置（REAL 司机上报 / SimulationEngine 模拟）统一 VehicleLocation | `VehicleLocationDO` + `SimulationEngine`（Phase 7） |
| **Service Decision** | 货运服务方式（上门/最近站/安全点/客户送站/站到站） | `ServiceModeEnum` + 承运审核（Phase 2） |

## 三、模块结构

- **Java 单体**（yudao）：`yudao-module-transport` 承载全部业务（调度/审核/监控/司机端/实时公交）。
- **Python 算法服务**（`algorithm/`）：OR-Tools 求解器（公交骨架 + 货运绕行插入）、高德路网/polyline。
- **小程序**（独立仓库 [cargo-post-miniprogram](https://github.com/cgp-team/cargo-post-miniprogram)）：寄货/包裹/实时公交/司机工作台。
- **管理端**（`yudao-ui-admin-vue3/`）：站点/车辆/线路/班次/订单池/调度/监控/模拟控制。

## 四、核心链路（全闭环）

```
客户寄货 → 承运审核(危险品/禁运/超重/生鲜/大件) → READY_FOR_POOL
→ 订单池归集 → POOLED → 智能/手工派单(公交骨架+货运绕行) → 方案待审核
→ 审核下发 ISSUED → 发车核验 RUNNING → 司机执行(BOARD/ALIGHT/PICKUP/DELIVERY)
→ 到站/装车/妥投 → 订单 COMPLETED → 方案内订单全完成 → 方案 COMPLETED
```

## 五、部署

- Docker 化；算法服务独立（`ALGORITHM_BASE_URL`）；模拟引擎 `SIMULATION_ENABLED=false`（生产）。
- 高德 AMAP_KEY 只在算法服务使用；Java/小程序/司机端不直连高德。

## 六、关键取舍与已知项

- 载货容量用**双维度**（出程派送/返程揽收分别累计）近似净载荷，因 pywrapcp 9.15 对不可行模型崩溃；严格插花重叠留 Phase 5+。
- 骨架是"整批车辆共用一班次"（v1 简化）；多车多线路按车指定班次待扩展。
- 完整指标面板/偏航重规划（Replan）/多端文案统一等见各分文档。
