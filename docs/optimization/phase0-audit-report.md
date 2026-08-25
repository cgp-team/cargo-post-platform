# Phase 0 完整审计报告

> 日期：2026-08-25
> 分支：`fix/collect-orders`（PR#90 已合，含订单池按 orderIds 归集）
> 范围：全仓库代码审计 + 对照《客货邮运输系统总任务书》逐项盘点
> 方法：人工阅读核心代码 + MiMo 独立交叉复核

---

## 一、仓库结构总览

```
cargo-post-platform/
├── yudao-framework/            芋道框架（common/mybatis/security/…）
├── yudao-module-infra/         基础设施（文件上传等）
├── yudao-module-member/        会员（小程序登录态）
├── yudao-module-system/        系统（权限/字典）
├── yudao-module-transport/     ★ 运输核心业务模块
│   ├── controller/
│   │   ├── admin/              后台：dashboard/dispatch/monitoring/operation/order/resource/settlement/transport(…)
│   │   └── app/                小程序：bus/driver/feedback/notice/order/product/send
│   ├── dal/dataobject/         Station/Route/RouteStation/Shift/ShiftExecution/Vehicle/VehicleLocation(+Track)/
│   │                           TransportOrder/CargoOrder/PassengerOrder/PostalOrder/ProductOrder/
│   │                           DispatchTask/DispatchPlan/DispatchPlanItem/DispatchPlanLog/DepartureCheck/PricingRule/…
│   ├── service/
│   │   ├── dispatch/           DispatchService(订单池/派单/审核/结算) + DispatchEstimationService(ETA估算)
│   │   ├── monitoring/         MonitoringService(车辆位置统一出口: REAL 优先 + SIMULATED 插值)
│   │   ├── transport/          station/route/shift/vehicle/driver/order/product/… + AppBus/AppDriver/AppSendRouteInfo
│   │   └── operation/order/resource/settlement/…
│   ├── integration/algorithm/  AlgorithmClient/AlgorithmAdapter/DTO（统一代理算法服务）
│   └── enums/                  订单/方案/任务/经停动作/发车核验 状态枚举
├── algorithm/                  ★ Python OR-Tools 调度算法服务（/api/v1/plan、/distance、/route）
├── mock-algorithm/             ★ Python mock 算法（混沌测试）
├── miniprogram/                ★ WeChat 小程序（pages: index/bus/driver/goods/login/mine/orders/parcel/send/settings）
│   └── pages/driver/workbench  司机工作台（idle/driving/stopped）
├── yudao-ui/yudao-ui-admin-vue3/  后台管理（transport: dispatch/monitoring/station/route/shift/vehicle/…）
├── sql/incremental/            V001~V011 增量表
├── deploy/                     Docker/nginx/systemd 部署
└── docs/                       架构/接口/算法契约文档（docs/api/algorithm-api.yaml）
```

---

## 二、已具备的能力（审计确认）

| 能力 | 现状 | 位置 |
|---|---|---|
| 站点/线路/班次基础档案 | ✅ 完整（含 plannedMinutes 计划分钟） | Station/Route/RouteStation/Shift |
| 班次执行记录（司机发车/到站落库） | ✅ 当天执行记录 + loadedCount | ShiftExecutionDO |
| 车辆/人车绑定/位置上报 | ✅ 每车一行 upsert + 历史轨迹 | VehicleLocation(+Track)/DriverVehicle |
| 订单体系 | ✅ 客运/货运/邮快件/商城四类 | TransportOrder + 子表 |
| 订单池（勾选 orderIds 归集） | ✅ PR#90，orderIds 优先 + 时间范围 fallback | DispatchServiceImpl.collectOrders |
| 手工/智能派单 | ✅ OR-Tools VRP 求解 + 方案审核/下发/核验 | DispatchService + solver.py |
| 客运先上后下 + 双容量约束 | ✅ 算法侧（载客/载货累计） | solver.py |
| ETA 估算（逐站累计） | ✅ AMAP 路网时长优先 / 欧氏直线兜底 | DispatchEstimationService |
| 高德路网距离（Java 不直连） | ✅ 统一走 AlgorithmClient→/distance | AlgorithmClient + distance.py |
| 实时公交（车来了式） | ✅ 线路/车辆/下一站/ETA/附近，REAL/SIMULATED 标识 | AppBusService |
| 司机端三态 + 扫码装车/妥投/核销 | ✅ 真实落库，10s 位置上报 | DriverAppService + workbench |
| 寄货路线预览 | ✅ 取/送站路网距离+时间 | AppSendRouteInfoService |
| 后台监控地图 + 轨迹回放 | ✅ | monitoring/index.vue + replay.vue |
| 部署 | ✅ Docker/nginx/healthcheck | deploy/ |

---

## 三、对照任务书 53 点的差距清单

### 差距分级
- **P0（核心架构缺失，决定系统性质）**：任务段模型、公交骨架联合调度、真实道路 polyline、模拟引擎、承运审核状态机
- **P1（明确缺陷）**：4 个已知 P1 + 若干数据一致性/并发
- **P2（增强/合规）**：sourceType、缓存策略、指标、告警、文档

### 核心差距表

| # | 任务书点 | 当前状态 | 差距 | 修复 Phase |
|---|---|---|---|---|
| G1 | 一/二：Operational Plan ≠ Road Route ≠ Vehicle State ≠ Service Decision 四层分离 | 调度输出是扁平 PlanItem 列表；地图/监控 polyline 是站点间**直线**；模拟是时间插值，非独立引擎 | **四层未分离** | 4/6/7 |
| G2 | 三：站点 sourceType(PLANNED/REAL/IMPORTED) | StationDO 无 sourceType | 模型缺字段（不影响核心调度，可后置） | 后续 |
| G3 | 四：客运班次是 Mandatory，站点不可删/顺序不可改 | 算法是**订单驱动 VRP**，无班次骨架输入 | **核心范式错误**：当前算法把客运订单当"货"做 VRP，没有"公交骨架 + 闲置运力插入" | 5 |
| G4 | 五：有限绕行(detourDistance/Duration/passengerDelay) | 完全缺失 | 无绕行评估/代价计算 | 5 |
| G5 | 六：5 种 ServiceMode，取/送分别判断 | 完全缺失 | 无 ServiceMode 枚举/决策 | 2/5 |
| G6 | 七：订单审核先于订单池（15 项内容） | 货运仅 CargoOrder.auditStatus(0/1/2) 手工审核 | **审核引擎缺失** | 2 |
| G7 | 八：审核结果 ACCEPTED/CONDITIONAL/MANUAL_REVIEW/REJECTED | 无独立枚举 | 缺失 | 2 |
| G8 | 九：reasonCode 统一枚举，前端映射文案 | 无 reasonCode 枚举；后端个别中文文案硬编码 | 缺失 + 前端文案硬编码 | 2 |
| G9 | 十：OrderLifecycle/ReviewStatus/ServiceMode 分离 | OrderStatus 只有 6 状态，无 PENDING_REVIEW/WAITING_CUSTOMER_ACTION/READY_FOR_POOL；无 ReviewStatus | **状态机不完整** | 2 |
| G10 | 十一：只有 READY_FOR_POOL→POOLED；后端再验证 | 校验 CREATED + auditStatus=1 | 校验口径与目标状态机不符 | 2/3 |
| G11 | 十二/十三：任务段 = 一车一时窗完整连续运营（orderedStops+actions+plannedArrival/Departure+serviceDuration） | DispatchPlanItemDO 扁平（planId/vehicleId/driverId/orderId/stationId/visitSequence/actionType/estimatedArrivalTime）；无 taskWindowStart/End、无 serviceDuration、无 quantity、无 plannedDeparture | **核心模型缺失** | 4 |
| G12 | 十四：Action 复用 BOARD/ALIGHT/PICKUP/DELIVERY/PASS | 已有 DEPART/BOARD/ALIGHT/DELIVER/PICKUP/RETURN 六类 | ✅ 可复用，需补 PASS/quantity/status | 4 |
| G13 | 十五：联合调度，载客/载货净载荷(先装后卸) | 载客 BOARD+1 累计 ✅；**载货 DELIVER/PICKUP 均 +itemCount 累计** | **P1-002 载货语义错误**（详见第四节） | 1 |
| G14 | 十六/十八：闲置运力 + 调度目标优先级 | 目标 = 最小用车数 + 最小里程，无客运优先/班次/时序软约束 | 目标函数未体现"客运优先" | 5 |
| G15 | 十九：算法解释(accepted/serviceMode/servicePoint/detour/passengerImpact/reasonCode) | 无任何解释字段 | 缺失 | 5 |
| G16 | 二十/二十二：Road Route 真实 polyline，AlgorithmClient 统一 | /api/v1/route 只有距离/时长，**无 polyline**；高德驾车路径 API 未用 | **RoadSegment/polyline 缺失** | 6 |
| G17 | 二十三：RoadSegment 缓存(fromStation/toStation/coordinate/provider) | 高德距离矩阵有 24h 缓存（坐标哈希 key）✅，但**无 polyline/路段缓存** | 需扩展 | 6 |
| G18 | 二十四/二十五/二十六：SimulationEngine(1x/5x/…/60x，STOPPED/RUNNING/ARRIVING/ARRIVED/PAUSED/COMPLETED，simulationEnabled 生产 false) | 监控是"班次轮转 + 计划时刻线性插值"（**直线**），非独立引擎 | **模拟引擎完全缺失** + 违反"严禁直线" | 7 |
| G19 | 二十七：REAL 优先 SIMULATED | MonitoringService REAL(5min)优先 ✅，dataSource 标记 ✅ | ✅ 基本满足，缺 STALE 态 | 10 |
| G20 | 二十八/二十九/三十：司机端显示完整任务段 + 真实地图 + 导航按钮 | workbench 显示班次站点序列 + 扫码；tasks API 返回**扁平列表**；地图**直线**；无导航(开始前往/确认到达/确认派货/下一任务)；无到站阈值参数化(硬编码 300m，任务书默认 50m) | **司机端重大改造** | 8/9 |
| G21 | 三十二：司机端必须同时显示乘客任务(BOARD/ALIGHT) | 司机端只处理货运/邮快件；客运任务不可见 | 缺失 | 8 |
| G22 | 三十三：任务状态 PENDING/EN_ROUTE/ARRIVED/BOARDING/ALIGHTING/PICKUP/DELIVERY/COMPLETED/FAILED 后端为源 | DispatchPlanItemDO 无状态字段；订单状态粒度粗 | 缺失 | 4/8 |
| G23 | 三十四：到站阈值默认 50m，模拟可自动触发 | 司机端硬编码 ARRIVE_RADIUS_METERS=300；后端 arrive 是人工确认 | 需参数化 | 9 |
| G24 | 三十五：偏航>100m 标记 ROUTE_DEVIATED 只报警 | 无偏航检测 | 缺失 | 9 |
| G25 | 三十六：不自动重规划，6 种情况才 Replan Remaining Segment | 无重规划机制 | 缺失（可后置，任务书明确"未来单独开发"） | 9+ |
| G26 | 三十八：首页数据源 REAL/SIMULATED/STALE/NO_LOCATION | 有 REAL/SIMULATED/MIXED/NONE + locationSource(REAL_FRESH/SIMULATED/NO_LOCATION)，**无 STALE** | 小差距 | 10 |
| G27 | 三十九：后台调度地图与司机共享同一 DispatchPlan+RoadSegments | 后台监控只有车辆位置/轨迹，无任务段/Operational Stops/RoadSegments | 依赖 G1/G11 落地 | 12 |
| G28 | 四十/四十一/四十二：承运审核客户实时反馈 + 替代交接 + 司机看到审核结果 | 无审核流程；司机看不到 serviceMode | 依赖 G6-G9 | 2/8 |
| G29 | 四十三：算法评价指标 11 项 | 仅 settlement 有基础统计 | 缺失 | 14 |
| G30 | 四十四：算法稳定性测试(乱序 50~100 次) | 无 | 缺失 | 14 |
| G31 | 四十五：P1-001~004 回归测试 | 无专项回归 | **Phase 1 目标** | 1 |
| G32 | 四十六：视觉无 undefined/null/NaN/Infinity，状态文案与后端一致 | 后台订单/方案状态文案**前端硬编码**（dispatch/index.vue:403-413）；小程序多处 `||` 兜底但不完整 | 需统一枚举下发文案 | 各 Phase |
| G33 | 四十七：每 Phase Unit+Integration+API Contract+E2E+Visual | 有 15 个 JUnit + algorithm 单测；**无 E2E、无视觉测试、无模拟/真实混跑测试** | 需补充 | 各 Phase |
| G34 | 四十八：禁止每车每5秒打高德；缓存 | 附近公交 ETA 有 60s TTL 缓存 ✅；派单距离矩阵 24h 缓存 ✅；**polyline/路段无缓存** | 基本满足，扩展路段缓存 | 6 |
| G35 | 四十九：Docker/healthcheck/环境变量 | deploy/ 有 ✅；**无 simulationEnabled 环境变量**（模拟引擎尚未实现） | 随 Phase 7 | 7 |
| G36 | 五十一：禁止项 | 无推倒重写/无第二套 Station/Action/VehicleLocation ✅；**模拟直线运行存在**(#11)；**无未审核订单入池管控**(审核状态机缺失时校验不严) | 需随 Phase 治理 | 各 Phase |
| G37 | 五十二：docs/optimization/ 15 份文档 | 无 | 后续 Phase 各写一份 | 各 Phase |

---

## 四、P1 四项详细分析

### P1-001：segmentDuration 未落库
- **确认是问题**：是。`DispatchPlanItemDO` 无 segmentDuration 字段；算法返回的 `RouteStop.segmentDuration`（仅 AMAP 矩阵路径非 None）在 `estimatePlan` 时用于累计估算，但**未持久化到明细**。欧氏路径 segmentDuration=None，由后端按直线÷均速兜底。
- **根因**：数据模型未建模"分段道路时长"。
- **修复建议**：`DispatchPlanItemDO` 增加 `segmentDuration`(秒/分钟) + 可选 `segmentDistance`；`DispatchEstimationService` 回写；算法契约保持向后兼容。
- **回归测试**：智能派单后断言每站明细 segmentDuration 非空且与算法返回一致（AMAP 注入矩阵时）；EUCLIDEAN 时兜底值正确。

### P1-002：cargo capacity semantics
- **确认是问题**：是，核心业务逻辑错误。`solver.py` 载货维度 `DELIVER/PICKUP 均 +itemCount`（累计，派送件占仓位整个车次），而任务书要求 **PICKUP+= / DELIVERY-=（净载荷，先装后卸）**。
- **根因**：旧语义"派送件占仓位整个车次"与任务书"闲 置运力净载荷"冲突。
- **修复建议**：solver.py 改为带方向的容量维度（DELIVER 动作 -itemCount，PICKUP +itemCount），配合先装后卸约束；同步更新 `DispatchServiceImpl.validate` 的容量预检口径与 mock-algorithm。
- **回归测试**：单车容量 10，PICKUP(6)→DELIVERY(4)→PICKUP(3)，验证净载荷 5≤10（旧逻辑 13 会误报超载）；先卸后装的时序非法用例。
- **注意**：这是一个**语义变更**，会改变现有"一车次占用仓位"的行为，需与现有邮快件 loaded_count 口径对齐说明。

### P1-003：departureCheck source state
- **确认是问题**：是，状态机不一致。`departureCheck` 允许 `ISSUED(1)` 或 `RUNNING(2)` 两种源状态；且方案**无 COMPLETED 流转**（settlement 注释明示"当前终态为执行中"）。
- **根因**：发车核验语义应只从 ISSUED→RUNNING 一次；COMPLETED 终态未实现。
- **修复建议**：
  1. `departureCheck` 严格只允许 `ISSUED` 源状态（或明确"从 RUNNING 重复核验=幂等放行"的意图并加注释）；
  2. 实现方案 COMPLETED 流转：方案内全部订单 COMPLETED（或所有站到终点的班次执行完成）→ 方案 RUNNING→COMPLETED。
- **回归测试**：从 RUNNING 再核验应拒绝/幂等；订单全完成后方案自动 COMPLETED。

### P1-004：concurrent smart dispatch
- **确认是问题**：是，严重并发缺陷。`createSmartPlan` 直接查全部 `POOLED` 订单 → 调算法 → 置 `ASSIGNED`，**无锁/无 CAS**。并发两次智能派单会读同一批 POOLED 订单，重复创建方案、重复 ASSIGNED。
- **根因**：订单池取数与状态推进非原子。
- **修复建议**：
  1. 取数后以 **CAS**（`UPDATE transport_order SET status=ASSIGNED WHERE status=POOLED`）先占单，`affected==0` 的订单跳过；
  2. 或对池加 `SELECT ... FOR UPDATE` / Redis 分布式锁（派单入口串行化）；
  3. 方案落库与订单状态更新同事务。
- **回归测试**：并发发起两个 createSmartPlan（同一批 POOLED 订单），断言订单只被一个方案 ASSIGNED、无重复方案；用 `@Transactional` + 模拟并发线程/数据库行锁。

---

## 五、MiMo 补充审计（独立复核发现）

### 前端硬编码（对应 G8/G32）
- 后台 `dispatch/index.vue` 状态/方案文案硬编码映射（orderStatusLabelMap/planStatusLabelMap），与后端枚举散落多处；审核原因、服务模式文案无统一字典。→ 后段枚举 + `dict`/接口下发，前端统一引用。

### 数据一致性
- 派单/归集并发原子性（P1-004）；订单状态推进普遍有 CAS（装车/妥投）✅，但派单缺。
- 事务边界：`createSmartPlan` 算法调用在事务内，ServiceException 不回滚（有注释说明，正确）；但并发时仍会双方案。

### 性能与缓存
- 附近公交 ETA 60s TTL ✅；高德矩阵 24h ✅；**缺 polyline/路段缓存**。
- 监控 `getRealtimeVehicles` 每次全量加载 station/route/shift 表，量大时可批处理优化（非紧急）。

### 监控与告警
- 无偏航检测（G24）；ETA 无准确率统计；SIMULATED 位置与 REAL 在地图上需明确区分（已标记 dataSource，前端需按此渲染"模拟运营"）。

### 安全
- 司机端身份从登录态解析 + driverId 一致性校验 ✅（DriverAppServiceImpl.requireCurrentDriver）；订单归属校验强 ✅。审计日志有 DispatchPlanLog ✅。

### 配置与部署
- 无 `simulationEnabled`（随 Phase 7）；生产默认 false 的断言需在配置层加。

### 算法稳定性/可解释性
- solver 用确定性首解策略（PARALLEL_CHEAPEST_INSERTION），同输入同输出 ✅（注释明确）；但订单输入顺序影响结果（VRP 固有），**稳定性测试（G30）需覆盖**。

---

## 六、修正后的 Phase 实施顺序建议

基于差距清单，对任务书 Phase 0~14 的修正与理由：

| 修正 Phase | 名称 | 目标 | 主要差距 |
|---|---|---|---|
| **Phase 1** | 4 个 P1 修复 + 回归 | 修 P1-001~004，各带回归测试 | G31/G13/G10 |
| **Phase 2** | 订单承运审核（状态机 + 审核引擎 + reasonCode + ServiceMode） | 建 OrderLifecycle 前置态/ReviewStatus/ServiceMode；审核 15 项 → ACCEPTED/CONDITIONAL/MANUAL_REVIEW/REJECTED + reasonCode；前端文案映射 | G6-G9/G32 |
| **Phase 3** | 订单池收紧 | 只有 READY_FOR_POOL→POOLED；后端再验证（存在/审核/状态/未重复）；并发防护落地 | G10/G11 前置 |
| **Phase 4** | 任务段模型 | DispatchPlan 重定义：taskWindowStart/End + orderedStops(actions[]/plannedArrival/Departure/serviceDuration/quantity/status)；PlanItem 状态字段 | G1/G11/G12/G22 |
| **Phase 5** | 客运+货运联合调度 | 算法输入加"公交骨架/班次/固定站点"；净载荷容量（承接 P1-002）；有限绕行评估；服务模式决策；算法解释输出；目标函数客运优先 | G3-G5/G13-G15 |
| **Phase 6** | RoadSegment / 真实道路 polyline | /route 扩展 polyline(+steps 可选)；RoadSegment 缓存(from/to/provider)；后端/DispatchPlan 落 polyline | G16/G17/G34 |
| **Phase 7** | 模拟运营引擎 | SimulationEngine：输入 DispatchPlan+RoadSegments，1x/5x/10x/30x/60x，状态机，沿 polyline 移动，simulationEnabled(生产 false)；REAL 优先 | G1/G18/G19 |
| **Phase 8** | 司机任务工作台 | 显示完整任务段 + 乘客任务(BOARD/ALIGHT) + 货运(PICKUP/DELIVERY)；任务状态后端为源；装车/妥投/到站接入任务段动作 | G20-G22/G28 |
| **Phase 9** | 司机真实道路导航 | 地图=当前车辆+完整 polyline+当前 segment+已走/未走+固定站+货运点+当前/下一任务+depot；到站阈值 50m 参数化；偏航>100m ROUTE_DEVIATED 报警 | G20/G23/G24 |
| **Phase 10** | 车辆位置联动 | VehicleLocation 统一（REAL/Simulation）；首页实时公交 + STALE 态；模拟运营标识 | G19/G26 |
| **Phase 11** | 后台监控地图 | 调度地图：选车→完整任务段/Operational Stops/RoadSegments/乘客货运任务/ETA；与司机共享同一 DispatchPlan+RoadSegments | G27 |
| **Phase 12** | 完整 E2E | 全链路 E2E + Visual；REAL/SIMULATED/AMAP/EUCLIDEAN + 站点类型 + 订单异常场景覆盖 | G33 |
| **Phase 13** | 算法评估与性能优化 | 11 项指标 + 乱序稳定性测试(50~100 次) + 性能调优 | G29/G30 |
| **Phase 14** | 文档归档 | docs/optimization/ 15 份文档 + final-report | G37 |

### 与任务书原 Phase 的差异说明
1. **P1 修复保持独立先行（Phase 1）**：正确。但 **P1-002 是"语义变更"不是纯 bug**，会连锁影响 Phase 5 算法与 Phase 2 审核口径，建议 Phase 1 只修 solver 容量语义 + 后端口径对齐，回归测试先行锁定。
2. **数据模型先行**：任务书把任务段模型放在 Phase 4、审核放 Phase 2/3。建议 **状态机（OrderLifecycle/ReviewStatus/ServiceMode）在 Phase 2 先冻结 DDL**，任务段模型在 Phase 4 冻结——两者都是"骨架"，先定义避免后续接口反复改。
3. **无"第二阶段并行"**：任务书把司机端拆为 8/9、车辆位置 10、首页 11、后台 12，本审计保留但调整为依赖顺序：8 依赖 4（任务段模型）+5（调度输出），9 依赖 6（polyline）+7（模拟），10 依赖 7，11 依赖 4/6/7。

---

## 七、已知风险与建议

1. **P1-002 语义变更影响面大**：改动 solver + validate 预检 + mock-algorithm + 现有邮快件 loaded_count 口径，需在 Phase 1 单独梳理。
2. **"公交骨架"是最大架构缺口**：当前算法是纯 VRP，转型为"班次骨架 + 闲置运力插入"是 Phase 5 的重头，涉及算法契约扩展（PlanRequest 加 shifts/routes），Java 侧 buildPlanRequest 也要改。
3. **模拟"严禁直线"**：Phase 7 前，监控 SIMULATED 仍是直线插值——属已知的过渡态，需在文档标注，不得冒充真实道路。
4. **前端文案硬编码**：建议在 Phase 2 顺带引入"枚举→文案"后端下发（沿用 yudao dict 能力），避免后续多端重复改。
5. **测试纪律**：每 Phase 必须实跑测试验证（见 verify-tests-every-time 记忆）；mvn 在 `/c/Users/袁/apache-maven-3.9.16/bin/mvn`。

---

## 八、Phase 0 交付清单

- [x] 仓库结构总览
- [x] 数据模型盘点（DO/枚举/SQL）
- [x] 对照任务书 53 点差距清单（G1~G37）
- [x] P1 四项详细分析
- [x] MiMo 独立交叉复核
- [x] 修正后 Phase 顺序
- [ ] 下一步：等待明确指令进入 **Phase 1（4 个 P1 修复）**
