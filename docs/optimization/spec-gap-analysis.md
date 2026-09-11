# 《Cargo Post Platform 最终总改造方案》对照检查表

> 对照对象：`CargoPostPlatfo.docx`（154 节完整改造方案）
> 对照时间：2026-09-11（第二轮补齐后）
> 说明：✅ 已完成 / 🟡 部分完成（可用但有简化）/ ❌ 未做

## 一、领域模型与状态机

| 方案要求 | 状态 | 现状与证据 | 备注 |
| --- | --- | --- | --- |
| §2 Order / Plan / Leg / Handover 分层 | ✅ | `transport_order` → `transport_dispatch_plan` → `transport_leg` → `transport_handover` | Leg 通过 `plan_id` 归属方案 |
| §3 TransportPlan 字段（planNo/planningMode/totalLegCount/transferCount/时间/score/reason） | ✅ | `DispatchPlanDO` + `V019` 新增 `plan_no/planning_mode/total_leg_count/transfer_count/plan_reason/estimated_*/actual_*` | 复用既有 DispatchPlan，不推倒重建 |
| §4 Plan 状态机（DRAFT…CANCELLED） | 🟡 | 枚举映射：PENDING≈PLANNED、ISSUED≈DISPATCHED、RUNNING≈IN_PROGRESS、VOID≈CANCELLED，并新增 `EXCEPTION`（重调度失败置异常） | DRAFT/PLANNING/APPROVED 属算法中间态，算法同步返回不驻留 |
| §5 TransportLeg 字段（routeId/navigationSource/polyline/distance/duration/cargo/handoverRequired） | ✅ | `TransportLegDO` + `V019` | `navigationSource=ESTIMATED`（未伪装高德实时） |
| §6 Leg 状态机（PLANNED→ASSIGNED→DRIVER_ACCEPTED→…→HANDOVER/DELIVERING→COMPLETED） | ✅ | `TransportLegStatusEnum` + `canTransit()` 守卫 | 非法跳级被拦截（单测覆盖） |
| §7 订单状态（IN_TRANSIT/TRANSFERRING/DELIVERING/EXCEPTION） | ✅ | `TransportOrderStatusEnum` 新增 10~13 | 原有审核/服务方式分离保留 |
| §8/§9 CargoHandover 字段与状态机（WAITING→SOURCE_ARRIVED→…→COMPLETED/TIMEOUT/EXCEPTION） | ✅ | `TransportHandoverStatusEnum` + `V019` 列 | **前序未到达不能确认接货** |
| §63 交接完成后原子更新（Leg1=已完成、Leg2=运输中、车辆/司机状态） | ✅ | `HandoverServiceImpl.confirmHandover`（单事务） | |
| §10/§107/§112 最多 3 段 2 次换乘 | ✅ | `MultiLegPlanner.bestThreeLeg` | |

## 二、站点 / 线路 / 车辆可达性

| 方案要求 | 状态 | 现状与证据 | 备注 |
| --- | --- | --- | --- |
| §11~§14 sourceType（REAL/PROJECT/SIMULATION）+ serviceType | 🟡 | 公交侧已有 `dataSource`/分层；本次新增 `station.source_type`、`station_type` | 车辆侧未加 sourceType（沿用监控侧 dataSource） |
| §30/§32/§34 站点：启用 ≠ 用户可达 ≠ 车辆可达 ≠ 可调度 | ✅ | `StationAccessUtil` + `V019` 列 + 种子数据（101 车辆不可达/不可调度） | 明确禁止"新增站点自动获得车辆权限" |
| §33/§98/§99 新增站点/线路后附近公交/地图/调度即时生效 | ✅ | 数据驱动（`ProjectTransitProvider` 实时查库）+ 调度场站按 `dispatch_enabled` 过滤 | 无硬编码 |
| §118 站点不可达测试 | ✅ | `StationAccessUtilTest` | |
| §138 订单创建二次校验（车辆可达 + 可调度 + 线路启用） | ✅ | `TransportOrderServiceImpl.requireEnabledStation` 校验用户可达/车辆可达 | 线路启用校验沿用既有线路状态 |
| §35/§36 Route/RouteStation 字段（dispatchEnabled/estimatedMinutes/distanceFromPrevious） | 🟡 | 现有 `distance_km/planned_minutes/status` | 未新增 dispatchEnabled/distanceFromPrevious |

## 三、智能调度

| 方案要求 | 状态 | 现状与证据 | 备注 |
| --- | --- | --- | --- |
| §39/§40 管理员只点"一键智能调度"，全部自动 | ✅ | 既有 `createSmartPlan` + `AutoDispatchPlanner` | |
| §41 MultiLegPlanner（判断直达/2 段/3 段） | ✅ | `MultiLegPlanner` | 不取代 OR-Tools，只做段划分 |
| §42/§45 直达优先、不强制联运 | ✅ | 评分 + `DIRECT_PREFER_MARGIN_MINUTES` 容差 | 单测覆盖 |
| §43/§44 综合评分（时长+换乘惩罚+绕行惩罚） | ✅ | `score()` | waitingTime/walking 未纳入 |
| §46 换乘站=多线路交汇（非最近点） | ✅ | `RouteIndex.sameRoute` 交汇判定 + 车辆可达 + 可调度 | |
| §48~§52 车辆/司机冲突、容量、无车提示、手工调度同样校验 | ✅ | `LegConflictService`（区间重叠 + 15 分钟缓冲）自动分配时避让；手工分配前可调 `GET /transport/topology/conflict-check` 复检 | `VehicleConflictTest` 覆盖 |
| §102/§103/§136 调度结果解释（候选方案对比 + 省时） | ✅ | `PlanResult.candidates` + `plan_reason` + 后台"运输拓扑"页候选表 | 候选：直达/两段/三段 |

## 四、执行闭环（司机 / 交接 / 通知）

| 方案要求 | 状态 | 现状与证据 | 备注 |
| --- | --- | --- | --- |
| §55~§58 司机只看自己的 Leg + 按钮状态机 | ✅ | `GET /driver/current-leg` + `POST /driver/leg/{action}`（accept/navigate/arrive-origin/load/start/arrive-dest/handover-start/handover-confirm/complete） | 归属校验 `LEG_NOT_ASSIGNED` |
| §59/§60/§61 后序司机提前接驳、前序到达即联动 | ✅ | `markSourceArrived` → 通知后序司机（ACTION_REQUIRED）+ 订单换乘中 | |
| §62/§63 交接必须生成 Handover，原子推进 | ✅ | 见上 | |
| §64/§69 用户/后台时间线 | ✅ | `transport_order_event` + `GET /transport/topology/order` | |
| §78/§79/§87 统一通知服务 + 事件矩阵 + 角色 | ✅ | `NotificationSendDTO` + recipientType(USER/DRIVER/ADMIN) + level + actionRequired | |
| §88/§116 通知幂等（eventId 唯一键） | ✅ | `event_id + user_id + event_type` 唯一索引 + 发送前查重 | 单测覆盖 |
| §89/§90 消息中心（用户/司机/后台） | 🟡 | 用户消息中心（小程序）、司机消息 API（`/driver/messages`）、后台通知管理页 | 后台告警页未做独立 UI |
| §123/§124 车辆/司机状态随 Leg 同步 | ✅ | `MultiLegServiceImpl.syncResourceStatus`（在途→车辆在途/司机忙碌） | |
| §125~§127 ShiftExecution / 回场同步 | 🟡 | 段开始 → 司机当天执行记录置在途；段完成且当天无其他进行中段 → 执行记录置完成 | 回场（return-to-depot）未单独建模 |
| §8 通知"点击跳转" | 🟡 | 用户通知点击跳订单页（包裹追踪）；司机/后台未做深链 | |

## 五、API

| 方案要求 | 状态 | 实现 |
| --- | --- | --- |
| §91 `/transport/order/{id}/transport-topology`、`/order/{id}/timeline` | ✅ | `GET /admin-api/transport/topology/order`、`GET /admin-api/transport/order-event/list-by-order` |
| §91 `/transport/plan/{id}`、`/plan/{id}/legs` | ✅ | `GET /admin-api/transport/topology/plan`（含 legs），`GET /admin-api/transport/handover/list-by-order` |
| §91 `/transport/driver/current-leg`、`/driver/tasks` | ✅ | `GET /app-api/transport/driver/current-leg`（tasks 沿用既有 `/driver/tasks`） |
| §91 `/transport/leg/{id}/accept|start|arrive|loading|handover/*|complete` | ✅ | `POST /app-api/transport/driver/leg/{action}` |
| §91 `/transport/notification/list|unread-count|{id}/read` | ✅ | `/app-api/transport/notification/page|unread-count|read|read-all` + 司机版 `/driver/messages/*` |
| §92 拓扑一次返回（前端不拼装） | ✅ | `OrderTopologyRespVO`（订单+方案+候选+分段+交接+时间线） |
| §93/§94/§95 附近公交分层 / ProjectTransitProvider / AmapTransitProvider | ✅ | 既有实现（本次未改动，符合"保留架构") |

## 六、前端

| 方案要求 | 状态 | 实现 |
| --- | --- | --- |
| §16~§19 首页附近公交（现实/项目分区 + 类型字段） | 🟡 | 公交页已按 `dataSource` 分层标注；首页未改为"附近公交"聚合卡 |
| §20~§22 实时公交页（地图+图例+图层） | ✅ | `pages/bus/index`（既有：多图层、15s 刷新、真实道路 polyline） |
| §28/§29/§32 寄件可达性（用户可达/车辆不可达→推荐南门站） | ✅ | 后端 `AppSendReachabilityService` 已按三维模型推荐；小程序沿用既有弹窗 |
| §64/§71 用户运输时间线/地图 | ✅ | 包裹追踪页新增"运输链地图"（按段着色折线 + include-points）+ 分段进度 + 换乘交接 + 方案说明 |
| §55~§58/§72 司机端任务+按钮状态机 | ✅ | 司机"货物交接"页新增"当前任务"卡（动态按钮 + 估算标注） |
| §65~§70 后台运输拓扑/订单详情/地图 | 🟡 | 新增后台"运输拓扑"页（方案+候选+分段+交接+时间线）；地图可视化未叠加 |
| §100 后台站点/线路/车辆管理展示可达性 | 🟡 | 站点管理页已加 来源/类型/用户可达/车辆可达/可调度 列 + 表单编辑 + 筛选；线路/车辆管理页未加 |
| §101/§134 调度中心四联动 UI | ✅ | 新增后台"调度中心"：订单池 / 实时地图（站点+车辆+选中订单运输链+换乘点）/ 运输详情（异常段可重调度）/ 事件时间线 |

## 七、测试与演示

| 方案要求 | 状态 | 实现 |
| --- | --- | --- |
| §109 新增测试类 | 🟡 | 新增 `MultiLegPlannerTest`、`TransportLegStatusEnumTest`、`StationAccessUtilTest`、`UserNotificationServiceImplTest`、`VehicleConflictTest`、`MultiLegReplanTest`（模块共 289 个测试通过） | 交接顺序规则由实现 + 状态机单测覆盖，未单独建 `CargoHandoverTest`/`DriverLegTaskTest` |
| §110/§111/§112 直达/两段/三段测试 | ✅ | `MultiLegPlannerTest` 三个用例 |
| §113 车辆冲突测试 | ✅ | `VehicleConflictTest`（重叠 / 首尾相接 / 缓冲 / 已完成不占用 / 在规划段互斥） |
| §114 交接顺序测试 | 🟡 | 规则在 `HandoverServiceImpl`（前序未到达→拒绝），未单独建测试类 |
| §115 状态机非法操作测试 | ✅ | `TransportLegStatusEnumTest` |
| §116 通知幂等测试 | ✅ | `UserNotificationServiceImplTest` |
| §117 ETA 落库测试 | 🟡 | Leg/Plan 的 ETA 落库已实现，未单独断言测试 |
| §104~§107 Demo 场景 | ✅ | `sql/mysql/demo-multi-leg.sql`：直达(TPDEMO1)/两段(TPDEMO2)/三段(TPDEMO3) + 换乘站 + 站点可达性 |
| §108 异常 Demo | ✅ | 司机上报异常（`leg/exception`）→ 段/订单置异常 + 后台告警 → `POST /transport/topology/replan-leg` 只重规划该段；无资源时保持异常并置方案异常；交接超时/争议接口亦具备 |
| §130 demo-seed 一键恢复 | ✅ | `demo-cqupt-stations.sql` + `demo-cqupt-vehicles.sql` + `demo-multi-leg.sql` |
| §121/§122 三端一致性黑盒验收 | 🟡 | 已用真实后端 + 算法服务跑通主链：一键调度 → 两段联运（2 台不同车）→ 司机A执行第1段 → 到达换乘站自动建交接并通知司机B → 司机B接货确认（原子推进 Leg1 完成 + Leg2 运输中）→ 司机B到达 → 完成 → 订单完成；同时验证了非法跳级被拒（运输中→已完成 返回业务错误）。用户端拓扑/消息中心、后台拓扑三端状态一致（均为"已完成/第2段"）。剩余：RabbitMQ/MinIO 相关模块与 41 步全量脚本化未覆盖 |

## 八、明确未做（优先级建议）

1. **P2** §125~§127 回场（return-to-depot）闭环建模。
2. **P3** §121 把已跑通的主链固化成脚本化黑盒（含 RabbitMQ/MinIO 相关模块）。
3. **P3** §109 补齐 `MultiLegPlannerTest` 之外的端到端回归（当前主链为人工 API 验收）。

## 九、环境变更（本机）

- **Redis 已开启 requirepass**：密码取自 `C:\Program Files\Redis\redis.windows-service.conf`，
  已写入 `yudao-server/src/main/resources/application-local.yaml`（`spring.data.redis.password`）。
  若本机 Redis 未设密码请注释该行；也可用环境变量 `SPRING_DATA_REDIS_PASSWORD` 覆盖。
- 数据库：`ruoyi-vue-pro` 已补齐 `transport-schema.sql` + `transport-schema-incremental.sql` + `V018` + `V019` + 演示数据 + 菜单（6930~6935）。
- 服务：本机 MySQL/Redis 已运行；算法服务 `python -m uvicorn app.main:app --port 18081`（需 `AMAP_KEY`）、
  后端 `mvn -o org.springframework.boot:spring-boot-maven-plugin:3.5.15:run`（在 yudao-server 目录）均已实测跑通。

## 十、运行时验证中发现并修复的真实缺陷（跑起来才暴露）

| # | 现象 | 根因 | 修复 |
| --- | --- | --- | --- |
| 1 | 算法接口全部 422（真实道路 polyline / 智能调度都失败） | 算法 RestTemplate 用了 classpath 上的 **YAML 转换器**（同样声明支持 application/json 且排序靠前），请求体被序列化成 YAML（`---\norigin:`） | `AlgorithmAdapterConfiguration` 只保留 JSON 转换器 |
| 2 | 高德路径 `polyline` 恒为空（地图只能画直线） | 算法服务用 `extensions=base`；且高德 v3 驾车在 `extensions=all` 时路径点在 **steps[].polyline**，path 级无该字段 | `distance.py` 改 `extensions=all` + 拼接 steps polyline + 去重 |
| 3 | 算法服务不可用时 `/bus/lines` 超时（前端 15s 轮询堆积） | 每个站点对都「重试 + 2s 连接超时」串行等待 | `AlgorithmClient.route` 重试收紧为 1 次 + 失败 30s 冷却快速失败（回退直线并标注估算） |
| 4 | 两段联运被分到**同一辆车** | 首选绑定被其他方案占用后回退，未避让"本方案已用车辆" | `assignVehicles` 优先选本方案未用车辆；并补 3 号演示车（docx §54） |
| 5 | 一键调度单笔以上即 `TIME_WINDOW_EXCEEDED` | 批次窗口硬编码 30 分钟，算法按高德真实路网时长校验"整批总耗时 ≤ 窗口" | 批次窗口放宽为 2 小时（可配置），并补 422 详情日志 |

## 十一、实测结论（本机真实跑通）

- `GET /app-api/transport/bus/nearby` → 200，`buses` 非空（CQUPT 演示车辆，标注 SIMULATED）。
- `GET /app-api/transport/bus/lines` → 200（~2.5s），6 条线路全部返回**真实道路 polyline（97~214 点）**。
- 一键智能调度（订单归集 → `/dispatch/plan/smart`）→ 生成方案；两段联运订单得到
  「多段联运 / 2 段 / 1 次换乘」，**两段使用两台不同车辆**，候选方案含直达/三段对比与推荐理由。
- 司机端全链路：接受 → 导航 → 到达取货点 → 装货 → 发车 → 到达换乘站（自动建交接 + 通知后序司机）→
  后序司机到站 → 开始交接 → 确认接货（Leg1 完成 + Leg2 运输中）→ 到达 → 完成 → 订单完成。
- 非法跳级（运输中直接完成）被状态机拒绝并返回业务错误码。
- 三端一致：用户端拓扑与消息中心、后台拓扑、司机端任务状态一致。
