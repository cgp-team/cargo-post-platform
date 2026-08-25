# 最终报告

## 目标达成情况（对照任务书 53 条）

### 核心业务范式
- ✅ **公交客运为主 + 闲置运力货运**：公交骨架 Mandatory（Phase 5），货运绕行插入骨架间隙，算法解释（accepted/serviceMode/servicePoint/detour）。
- ✅ **Operational Plan ≠ Road Route**：任务段（运营）与真实道路 polyline（路网）分离。
- ✅ **REAL 优先 SIMULATED**：监控统一 VehicleLocation，数据源四态（REAL_FRESH/STALE/SIMULATED/NO_LOCATION）。

### 业务闭环（Phase 2/3/4/8/9/10）
- 承运审核（危险品/禁运/超重/生鲜/大件）→ READY_FOR_POOL 门禁 → 订单池 → 客户替代交接确认。
- 任务段模型（orderedStops/动作/计划时刻/作业/数量/状态，后端为源）。
- 司机端完整任务段 + 真实道路地图 + 偏航报警（>100m 只报警不自动重规划）。

### 工程
- **P1 修复**（Phase 1）：segmentDuration 落库 / 载货净载荷双维度 / departureCheck 来源状态 + COMPLETED 流转 / 并发 CAS 抢占。
- **模拟引擎**（Phase 7）：沿真实 polyline + 倍速 + 状态机 + 控制，生产默认关闭。
- **RoadSegment**（Phase 6）：高德驾车路径 polyline + 坐标缓存 + euclidean 兜底明确标注。
- **算法稳定性**（Phase 13）：随机打乱输入 60/80 次，可行性/车辆数/里程稳定。

## 测试基线
- algorithm **65** / mock-algorithm **27** / transport **175**（全部实跑通过）。

## 已知差距与后续建议
1. **载货容量双维度近似**：严格净载荷（PICKUP+=/DELIVERY-=）因 pywrapcp 9.15 对不可行模型崩溃而采用近似；插花重叠场景需更强求解器后处理。
2. **骨架整批共用一个班次**：多车多线路需按车指定班次。
3. **司机导航 UI 深化**：开始前往/确认到达/确认派货/下一任务按钮流、已走/未走分段高亮、动作级状态推进。
4. **管理端监控前端**：任务段面板接入（后端 `/monitoring/vehicle-plan` 已就绪）。
5. **完整 11 项算法指标面板**：乘客延误/货运完成率/闲置运力利用率/时间窗违例等监控/报表。
6. **站点 sourceType**（PLANNED/REAL/IMPORTED）、Replan Remaining Segment、多端文案统一（后端枚举下发）。

## 结论
系统已从"订单驱动纯 VRP + 直线模拟"演进为"公交骨架联合调度 + 任务段 + 真实道路 + 模拟运营 + 承运审核 + 司机完整任务段"的客货邮闭环。剩余差距以增强型功能为主，核心业务范式与状态机已按任务书落地。
