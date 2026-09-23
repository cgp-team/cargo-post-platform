# 后台管理平台 × 算法联动审计（ADMIN_PLATFORM_V047_AUDIT）

范围：`yudao-ui-admin-vue3` / `yudao-server` / `yudao-module-transport` ↔ `algorithm/`（HACO + DISPATCH_CORE_V047 + RouteSearch V046/V047）  
日期：交付前联调审计  
结论：**后台主链路完整（订单池→智能/手工派单→审核→发车→结算→监控）**；与算法侧 **`/api/v1/plan` 已接通**，但 **`/api/v1/dispatch/allocate`（动态调度）与 GH Branch Selector 未进入后台生产路径**；另有 **distanceUnit=degree→Haversine 报表里程** 与 **算法端口默认 18081** 两处需对齐。

---

## 1. 平台分层（已有，非新建）

```
yudao-ui-admin-vue3          后台管理端（Vue3 + Element Plus）
  views/transport/dispatch           调度工作台（订单池 / 手工 / 智能派单 / 审核 / 发车检查）
  views/transport/dispatch-center    调度中心（拓扑 / 腿重规划）
  views/transport/monitoring         监控 / 轨迹回放
  views/transport/dashboard          看板
  views/transport/route|shift|…      线路 / 班次 / 站点 / 车辆
        ↓ 仅后端可访问算法（前端不直连）
yudao-server  (Spring Boot)
  yudao-module-transport
    controller/admin/dispatch/DispatchController
    service/dispatch/DispatchServiceImpl + AutoDispatchPlanner
    integration/algorithm/{AlgorithmClient,Adapter,Properties,Validator}
        ↓ HTTP
algorithm  FastAPI  /api/v1/plan | result | distance | route | dispatch/allocate
```

配置（`application.yaml`）：

```yaml
yudao.transport.algorithm:
  base-url: ${ALGORITHM_BASE_URL:http://127.0.0.1:18081}  # mock:18080 仅混沌
  connect-timeout: 2s / read-timeout: 15s / poll: 2s ×10 / max-retries: 3
  idempotency-window: 24h
```

---

## 2. 后台已具备的调度闭环

| 环节 | 入口 | 状态 |
|------|------|------|
| 订单池 | `GET /transport/dispatch/order-pool/page` | ✅ |
| 收池 / 取消 | `POST …/collect` `PUT …/cancel` | ✅ |
| 手工派单 | `POST …/plan/manual` | ✅ |
| **智能派单** | `POST …/plan/smart` → `AutoDispatchPlanner` + `AlgorithmAdapter.plan` | ✅ 核心 |
| 预校验 | `POST …/validate` | ✅ |
| 方案列表/详情/路线 | `plan/page` `plan/get` `plan/roadmap` `plan/route-between` | ✅ |
| 道路预取 | `POST …/plan/prefetch-road` | ✅ |
| 审核 / 发车检查 | `review` `departure-check` | ✅ |
| 结算 | `GET …/settlement` | ✅ |
| 调度中心拓扑/腿重规划 | `TransportTopologyController` | ✅ |
| 监控地图 | `MonitoringController` | ✅ |

智能派单 UI：`dispatch/index.vue` 步骤条（选场站/车→校验→提交）→ `DispatchApi.createSmartPlan` → 后端组包 → 算法 HACO。

**组包已带业务经济字段**（`DispatchServiceImpl`）：`economicValue=CargoPricingService`、`weightKg/volumeM3` 来自货运档案 —— 与 `DISPATCH_CORE_V047` / `models.py` 字段对齐，**算法侧可读**。

---

## 3. 算法契约对齐情况

| 算法 API | Java `AlgorithmClient` | 后台是否使用 | 说明 |
|----------|------------------------|--------------|------|
| `POST /api/v1/plan` | ✅ | ✅ 智能/手工派单 | 含 408 轮询、502/503/504 重试、幂等快照 |
| `GET /api/v1/result/{id}` | ✅ | ✅ 异步完成 | |
| `POST /api/v1/distance` | ✅ | ✅ 预估 | |
| `POST /api/v1/route` | ✅ | ✅ 道路预取/分段 | **有失败冷却**，防 12 段串行重试拖死页面 |
| **`POST /api/v1/dispatch/allocate`** | **❌ 无** | **❌** | **DISPATCH_CORE_V047 动态调度未进后台** |
| GH Branch Selector（间接） | — | ❌ | 仅在 algorithm 进程内；Java 不消费 |

算法结果校验：`AlgorithmResultValidator` 存在；幂等 `AlgorithmRequestDO` + 24h 窗口与 Python `jobs` TTL 一致。

---

## 4. 风险与缺口（按优先级）

### P0 — 影响「真实道路成本」可信度

1. **报表里程在 `distanceUnit=degree` 时用 Haversine 累加**  
   `DispatchServiceImpl:840 / 2813 / 2831`：算法若返回 `degree`（无路网矩阵），后台把经停坐标 Haversine 当「真实公里」写入方案。  
   → 与项目红线「直线不得作正式成本」冲突。  
   **建议**：`degree` 时 `totalDistance` 标 `ESTIMATED/LOWER_BOUND`，或强制走 `/api/v1/distance` formal；禁止展示为「路网里程」。

2. **算法服务默认端口 18081 vs 本地常跑 8000/18080**  
   `AlgorithmProperties.baseUrl` 兜底 `127.0.0.1:18081`。联调时极易「静默失败/打到 mock」。  
   **建议**：文档/部署固定 `ALGORITHM_BASE_URL`；健康检查端点；禁止无 base-url 启动（已有 `@NotBlank`）。

### P1 — 与已交付算法能力未接上（功能缺口，非缺陷）

3. **`/api/v1/dispatch/allocate`（动态新单）未接入后台**  
   后台只有批次 `plan/smart`。司机/调度中心遇到「途中插单」无统一入口。  
   **建议**：`DispatchController` 增 `POST /transport/dispatch/allocate` → `AlgorithmClient.allocate`，UI 放在调度中心/订单详情。

4. **GH Branch Selector / 10K ranker 未在 Java 层暴露**  
   调用次数与墙钟收益停留在 Python 服务内。若要在管理端展示「预选降本」，需在 plan 响应附 `ghCallReduction` 等（算法侧已可出）。

5. **weight/volume 已传入算法 DTO，但 HACO 容量仍是 itemCount**  
   Java `weightKg/volumeM3` 已填充；Python `FeasibilityEngine` 未作硬约束（P1 遗留）。后台「运力充足」校验与算法可行域可能不一致。

### P2 — 体验/一致性

6. 智能派单提示「可能需要十几秒」与 `read-timeout=15s` 贴边；大批量建议异步任务 + 结果中心通知（已有 408 轮询，UI 可改为进度轮询）。  
7. `DispatchVisualDialog` / `dispatch-center` 未展示 DecisionTrace / reasonCode（算法已返回）。  
8. `mock-algorithm` 目录几乎为空，混沌依赖 18080 注释，需保证 CI 不误连。

---

## 5. 与 V046/V047 主线的关系（勿混）

| 线 | 位置 | 后台关系 |
|----|------|----------|
| HACO-CPS 1.4.1 批次求解 | `algorithm` `/api/v1/plan` | **已接智能派单** |
| DISPATCH_CORE_V047 动态调度 | `algorithm` `/api/v1/dispatch/allocate` | **未接后台** |
| RouteSearch V046 Branch Selector + V047 10K ranker | `algorithm` 进程内 | 后台无直接 UI；间接加快 route/distance |

手机 `index.html` 演示**不是**生产后台；生产以 **yudao-ui-admin-vue3** 为准。

---

## 6. 三天交付建议（性价比）

1. **修 P0-1**：禁止 degree→Haversine 冒充路网里程（改展示/字段语义）。  
2. **对齐 P0-2**：`.env` / 部署文档写死 `ALGORITHM_BASE_URL`，启动自检 `/openapi.json`。  
3. **演示链路**：后台「智能派单」→ 真实 `/api/v1/plan`（GH formal 矩阵）→ 路线可视化。  
4. 有余力再做 P1-3 `allocate` 进调度中心（答辩亮点，非阻塞）。

---

## 7. 状态

```text
ADMIN_PLATFORM_LINKED_PLAN_OK
ADMIN_PLATFORM_LINKED_DYNAMIC_ALLOCATE_MISSING
ADMIN_COST_SEMANTICS_NEEDS_FIX   # degree Haversine 里程
DISPATCH_CORE_V047_READY         # 算法侧（Codex）
ROUTE_SEARCH_MODEL_10K_CANDIDATE # 路径搜索侧
```

## 8. P0/P1 落地记录（本轮）

| 项 | 状态 | 位置 |
|----|------|------|
| degree 里程不再标「真实道路」 | **已修** | `DispatchServiceImpl`：`DistanceEstimate` + `routeProvider=FORMAL_ROAD\|STRAIGHT_ESTIMATE`，degree 打 warn |
| 算法 base-url 自检 | **已加** | `AlgorithmStartupChecker` + `AlgorithmClient.healthCheck` |
| `POST /transport/dispatch/allocate` | **已接** | Controller → `allocateDynamic` → `AlgorithmClient.allocate` → `/api/v1/dispatch/allocate` |
| UI 里程口径标签 | **已加** | `dispatch/index.vue`「里程口径」列（路网正式 / 直线估算） |

```text
ADMIN_PLATFORM_LINKED_PLAN_OK
ADMIN_PLATFORM_LINKED_DYNAMIC_ALLOCATE_WIRED
ADMIN_COST_SEMANTICS_FIXED
```
