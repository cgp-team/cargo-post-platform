# 客货邮联合调度平台：技术栈结构解析报告

> **时效说明（2026-09-21）**：本文主体为运营化改造（PR #153）前的快照。本次改造已下线模拟运营子系统
> （`simulation` controller/service、`SimulationEngine/Runtime`、`DeterministicScheduleSimulator`、
> `transport.simulation.enabled` 开关）与开发者中心，并删除 mock-algorithm/ 与全部演示数据 SQL；
> 文中相关段落已就地修正，增量 SQL 范围更新为 V001–V022。其余 `文件:行号` 引用以原文撰写时为准。

> 范围：cargo-post-platform 全仓库（Java 后端 / 算法服务 / 管理端 / 小程序 / 数据库 / 部署）
> 本文性质：技术栈与架构全景解析（论文风格），与《HACO-CPS 求解器机制解析》（`docs/algorithm/haco-cps-solver-mechanism.md`）互为姊妹篇——那篇讲算法原理，这篇讲工程全景
> 核对方式：关键结论均标注 `文件:行号`

---

## 摘要

本平台是一套**"公交客运为主线、货运邮政顺路捎带"的客货邮联合调度系统**。工程上由四个运行时组件构成：**Java 单体后端**（Spring Boot 3.5 / JDK 21，基于芋道 RuoYi-Vue-Pro 2026.06 深度改造）、**Python 路径规划微服务**（FastAPI + 自研 HACO-CPS 元启发式 / OR-Tools）、**Vue3 管理端**（Vite 8 + Element Plus + 百度地图 GL）、**原生微信小程序**（C 端寄货/乘车 + 司机端执行）。数据层为 MySQL + Redis；外部依赖高德 Web 服务（路网距离/轨迹）、百度地图 GL（管理端可视化）、高德小程序 SDK（公交 POI）。部署形态为"Docker Compose 承载数据与算法 + 宿主机 systemd 承载 Java + Nginx 统一入口"的混合模式。

---

## 1. 全景总览

### 1.1 系统上下文

```
                        ┌──────────────────────────────────────────┐
                        │                用户侧                     │
                        │  管理端浏览器(Vue3 SPA)   微信小程序(C端)  │
                        └────────┬────────────────────┬────────────┘
                                 │ HTTPS              │ HTTPS
                        ┌────────▼────────────────────▼────────────┐
                        │   Nginx（静态托管 + /api 反代 + /ws）     │
                        └────────┬─────────────────────────────────┘
                                 │
        ┌────────────────────────┼─────────────────────────┐
        │                        │                         │
┌───────▼────────┐     ┌─────────▼──────────┐    ┌─────────▼─────────┐
│  Java 后端      │     │  Python 算法服务    │    │  外部地图服务      │
│  yudao-server   │────▶│  algorithm:18081   │───▶│  高德 Web 服务 API │
│  (48080, 宿主机)│HTTP │  (127.0.0.1 回环)  │    │  百度地图 GL(前端) │
│                 │◀────│                    │    │  高德小程序 SDK    │
└───────┬────────┘     └────────────────────┘    └───────────────────┘
        │
   ┌────▼────┐   ┌─────────┐
   │  MySQL  │   │  Redis  │
   │  (容器) │   │  (容器) │
   └─────────┘   └─────────┘
```

要点：

- **前端永不直连算法服务**——Nginx 刻意不提供算法 location（`deploy/nginx/nginx.conf:44`），算法容器只绑 `127.0.0.1`（`deploy/docker-compose.yml:78-89`）；所有规划请求必经 Java 后端适配层，由其负责鉴权、幂等、超时、重试、校验与落库；
- **算法服务永不访问业务库**——它只处理后端发来的快照并返回建议方案，是无状态纯函数式服务（requestId 幂等缓存除外）；
- **地图能力三处分工**：管理端用百度地图 GL、小程序用微信原生 `<map>`（腾讯底图）、路网计算统一收口在算法服务背后的高德 Web API。

### 1.2 组件清单速览

| 组件 | 目录 | 语言/运行时 | 框架基线 | 部署形态 |
|---|---|---|---|---|
| 业务后端 | `yudao-server` + `yudao-module-*` + `yudao-framework` | Java 21 | Spring Boot 3.5.15（无 Spring Cloud） | 宿主机 systemd / 容器，端口 48080 |
| 路径规划 | `algorithm/` | Python 3.11 | FastAPI 0.116 + OR-Tools 9.15 | Docker，127.0.0.1:18081 |
| 管理端 | `yudao-ui/yudao-ui-admin-vue3/` | TypeScript / Node ≥20.19 | Vue 3.5 + Vite 8 + Element Plus 2.13 | Nginx 静态托管 |
| 小程序 | 独立仓库 [cargo-post-miniprogram](https://github.com/cgp-team/cargo-post-miniprogram) | 原生微信小程序（无 npm） | 基础库 3.17.0 | 微信分发 |
| 数据库 | `sql/` | MySQL 8 + Redis | — | Docker（127.0.0.1） |
| 运维工具 | `tools/` | Python | — | 手动脚本 |

## 2. Java 业务后端

### 2.1 语言 / 框架 / 依赖

- **基线**：芋道 RuoYi-Vue-Pro `2026.06` JDK 21 发布线（`README.md:116`），groupId `cn.iocoder.boot`，版本 `${revision}=2026.06-jdk21-SNAPSHOT`（`pom.xml:11-15,57`）；
- **Java 21**（`pom.xml:61-65`），**Spring Boot 3.5.15**（`pom.xml:77`；`yudao-dependencies/pom.xml:20`）；
- **单体架构，未引入 Spring Cloud / Alibaba**——BOM 中无 cloud 依赖，这是与上游 yudao 微服务版的重要差异；
- 关键依赖（版本见 `yudao-dependencies/pom.xml`）：

| 领域 | 选型 |
|---|---|
| 持久层 | MyBatis-Plus 3.5.16 + mybatis-plus-join 1.5.7 + Druid 1.2.28 + dynamic-datasource 4.5.0（`:25-30`） |
| 缓存/锁 | Redisson 4.6.1（配 redisson-spring-data-35）+ lock4j-redisson 2.2.7（`:31,39`） |
| 安全 | 自研 `yudao-spring-boot-starter-security`（Spring Security 6 + OAuth2 token 表；**无 Sa-Token**） |
| 任务调度 | Quartz（`yudao-spring-boot-starter-job`），JDBC 集群模式 |
| WebSocket | 自研 starter，支持 local/redis/MQ 广播（当前配置 local） |
| 接口文档 | springdoc 2.8.17 + Knife4j 4.5.0 |
| Excel | fastexcel 1.3.0（EasyExcel 继任者） |
| 工具 | Lombok、MapStruct 1.6.3、Hutool 5.8.46、fastjson2 2.0.62 |
| 监控 | SkyWalking 9.6.0 + Spring Boot Admin 3.5.9 |

### 2.2 Maven 模块结构

根 `<modules>`（`pom.xml:19-43`）：

```
yudao-dependencies          # BOM，统一锁版本
yudao-framework             # 16 个 starter（见下）
yudao-server                # 启动壳，装配各业务模块
yudao-module-system         # 用户/部门/权限/字典（管理端认证授权）
yudao-module-infra          # 基础设施：定时任务、代码生成、文件、日志
yudao-module-member         # 会员（小程序 C 端认证；司机身份复用其登录态）
yudao-module-transport      # 本项目核心业务模块（见 §2.4）
```

上游的 ai/bpm/crm/erp/pay 等模块已被移除（`pom.xml:39-41`）。

yudao-framework 的 starter（`yudao-framework/pom.xml:13-30`）：`common`（工具/枚举/错误码基座）、`web`（全局异常、API 日志、脱敏）、`security`、`mybatis`、`redis`、`websocket`、`job`（Quartz）、`mq`（Redis/RocketMQ/RabbitMQ/Kafka 抽象）、`monitor`（链路追踪/指标）、`protection`（分布式锁/幂等/限流/熔断）、`excel`、`test`、`biz-tenant`、`biz-data-permission`、`biz-ip`。

### 2.3 yudao-server 启动壳与关键配置

- 主类 `YudaoServerApplication.java:16`，pom 装配 system + infra + transport + member（注释注明 member "为小程序提供用户认证"，`yudao-server/pom.xml:24-66`）；打包插件产出 `yudao-server.jar`（`:72-86`）；
- `application.yaml` 关键点：
  - 端口 **48080**（`application-local.yaml:1-2`）；
  - WebSocket：`enable: true`、`path: /infra/ws`、`sender-type: local`（`application.yaml:133-136`）；
  - **算法服务地址**：`yudao.transport.algorithm.base-url=${ALGORITHM_BASE_URL:http://127.0.0.1:18081}`（`:154-157`）；
  - 多租户**已禁用**（`:169-170`）；本地环境短信验证码写死 9999、开启 security mock（`application-local.yaml:237-240`）；
- Dockerfile：`eclipse-temurin:21-jre` + 拷贝 jar，EXPOSE 48080（`yudao-server/Dockerfile:3,9,20`）。

### 2.4 yudao-module-transport：核心业务模块

**分层**：标准的 yudao 三层 + 一个集成层——

```
controller/admin/**   # 管理端 API，挂载 /admin-api
controller/app/**     # 小程序 API，挂载 /app-api
service/**            # 业务逻辑
dal/dataobject + dal/mysql   # DO 与 Mapper（按业务域子包划分）
integration/algorithm/       # 算法服务适配层（唯一通道）
```

**controller 域**（管理端）：`dashboard`（运营概览）、`dispatch`（智能调度闭环）、`monitoring`（车辆监控），以及资源域 `driver / vehicle / station / route / shift / order / product / handover / orderevent / notice / notification / feedback / topology`；`operation / resource / settlement` 目前仅有 `package-info.java` 占位。（原 `simulation` 模拟运营控制与 `developer` 健康诊断两个域已随 2026-09-21 运营化改造移除。）
**controller 域**（小程序端）：`bus`（乘车查询）、`driver`（司机执行）、`send`（寄件）、`order / product / feedback / notice / notification`。

**service 核心域**：

- `dispatch`：智能调度大脑——`AutoDispatchPlanner`（自动选场站选车）、`MultiLegPlanner` + `HandoverService` + `LegConflictService`（多段联运）、`CargoPricingService` / `PricingRuleService`（计价）、`DispatchEstimationService`（ETA/收入/成本估算）、`TransportTopologyService`（线网拓扑）；
- `monitoring`：车辆实时监控 + `VehicleLocationProvider`（统一位置模型：REAL 司机上报 / REAL_STALE / OFFLINE，不做模拟推算）；
- `order`：`CargoReviewService`（货运承运审核）、`OrderEventService`；
- `geo`：`RoadPolylineService` / `RouteCorridorService`（路网轨迹/走廊）。

**算法适配层**（`integration/algorithm/`）是后端访问 Python 算法服务的**唯一通道**：

- `AlgorithmClient`：RestTemplate 封装 plan/distance/route 三端点；408 转轮询、5xx/网络错误退避重试、422 归一为 infeasible、route 调用带 30s 故障冷却；
- `AlgorithmAdapter`：请求快照 SHA-256 幂等（24h 内相同快照直接复用落库结果）、请求/响应 JSON 留痕；
- `AlgorithmProperties`（`AlgorithmProperties.java:14`）：`yudao.transport.algorithm.*`——连接 2s / 读取 15s / 轮询 2s×10 / 重试 3 次；
- `AlgorithmResultValidator`：结果后置校验。

**智能调度主调用点**：`DispatchServiceImpl.createSmartPlan`（`service/dispatch/DispatchServiceImpl.java:837`）——取订单池 → 任务窗口过滤 → 自动选场站选车 → 按运营线路时间线构建骨架 → `buildPlanRequest`（`:3147`）组装算法请求 → 本地规模预检（`:2799`）→ CAS 抢占订单 → 落 `transport_dispatch_task` → 调算法 → 结果落库 → 人工审核 → 下发。

### 2.5 system / infra / member 的角色

- **system**：通用支撑（用户、部门、权限、字典），承担管理端认证授权；
- **infra**：运维与研发工具（定时任务管理、代码生成器、文件上传——小程序的图片上传走 `/app-api/infra/file/upload`）；
- **member**：小程序 C 端会员认证；**司机身份复用其登录态**（member_user.mobile → transport_driver.mobile 映射，`yudao-module-transport/pom.xml:19-56` 注释）。

## 3. 路径规划算法服务

> 机制细节见姊妹篇 `docs/algorithm/haco-cps-solver-mechanism.md`，此处只列工程面貌。

- **栈**：Python 3.11 + FastAPI 0.116 + Pydantic 2.13 + Uvicorn；OR-Tools 9.15（baseline 引擎）；httpx（调高德）；自研 HACO-CPS 元启发式**纯标准库手写**，无 numpy/pandas（`algorithm/requirements.txt`）；
- **结构**：`app/main.py` 直接承担路由层；`solver.py` 按 `algorithmMode` 三模式分流（HACO 默认 / BASELINE / HYBRID portfolio）；`haco/` 30+ 文件的求解器包；`baseline/ortools_solver.py`；`distance.py` 距离提供方抽象（高德路网 + 欧氏兜底）；`validators.py` 后置业务校验；
- **接口**：`POST /api/v1/plan`（规划主接口，幂等 requestId）、`GET /api/v1/result/{id}`（轮询）、`POST /api/v1/distance`、`POST /api/v1/route`（真实道路 polyline）、`/health`、`/ready`；契约文档 `docs/api/algorithm-api.yaml`；
- **规模上限**：100 站 / 25 单 / 3 车 / 10s（`main.py:46-48`）；
- **契约验收**：`tests/contract/` 用 `ALGORITHM_BASE_URL` 参数化，可对任意契约实现（如算法组镜像）直接验收。

## 4. 管理端（Vue3 SPA）

### 4.1 技术栈（`yudao-ui/yudao-ui-admin-vue3/package.json`）

| 项 | 版本/选型 |
|---|---|
| Vue | 3.5.34（`:83`）+ vue-router 5.0.6 |
| 构建 | **Vite 8.0.10**（`:135`），`minify: 'oxc'`，echarts 单独分包（`vite.config.ts:84,98`） |
| UI | Element Plus 2.13.7 + 图标库（`:57,33`） |
| 状态 | Pinia 3 + persistedstate 持久化（`:73-74`） |
| CSS | UnoCSS 66 + SCSS 全局变量（`:131`；无 Tailwind） |
| 图表 | echarts 6 + wordcloud |
| 语言/规范 | TypeScript 6.0.3 + vue-tsc、eslint 10、prettier 3.8、stylelint 17 |
| 环境 | Node ≥ 20.19.0，pnpm 10.14.0（`:151-154`） |
| 其他 | axios 1.16、vue-i18n、fetch-event-source（SSE）、livekit-client、bpmn-js、dhtmlx-gantt |

### 4.2 结构与 transport 视图

标准分层：`api/`（按后端模块分目录）、`views/`、`store/modules/`、`router/`、`layout/`、`components/`，路由守卫在 `src/permission.ts`。`src/api/transport/` 与 `src/views/transport/` 基本一一对应：

- `dashboard` 运营概览；`dispatch` **调度工作台**（订单池/方案/手工/智能向导，`dispatch/index.vue:2,117,176,199`）；`dispatch-center` **调度中心**（订单池+实时地图+运输详情+事件时间线四合一）；`monitoring` 实时监控 + `replay.vue` 轨迹回放；
- 资源管理：`station / route / shift / vehicle / driver / driver-vehicle`；业务：`order`（含货运审核）、`product / productOrder`、`handover`、`topology`（订单运输链可视化=多段联运展示位）、`notice / notification / expiry`（原 `developer / simulation` 视图已随运营化改造移除）；
- 注意：**没有独立的 send/multileg 目录**——寄货订单在 `order`，联运可视化在 `topology`；`settlement` 目录为空，结算走 dispatch API（`api/transport/dispatch/index.ts:311`）。

### 4.3 地图集成

- 百度地图 GL SDK **运行时动态加载**（`src/components/Map/src/utils.ts:23-62`，`ak=VITE_BAIDU_MAP_KEY`，全局单例 Promise），无 npm 包；
- 业务坐标统一 GCJ-02，上图前转 BD-09（`utils.ts:73-86`）；
- 调度可视化 `DispatchVisualDialog.vue`：经 `/transport/dispatch/plan/roadmap` 拿每车每段真实道路 polyline 上图（每车一色+经停序号）；**地图加载失败时降级为按真实坐标绘制的 SVG 线路示意图**（`DispatchVisualDialog.vue:29-96`）——一个用心的韧性设计。

### 4.4 构建与联调

- dev server proxy **已注释**（`vite.config.ts:39-46`，注明"server 端已支持跨域"），开发态直连后端走 CORS；
- 请求 baseURL = `VITE_BASE_URL + VITE_API_URL`（`src/config/axios/config.ts:10`），env 分 local/dev/test/stage/prod 五档；
- 产物 `dist/`（仓库内有 `dist-prod/`），由 Nginx 托管：SPA + history fallback（`nginx.conf:20-25`），`/api/` 反代后端并剥前缀（`:28-34`），`/ws/` WebSocket 升级（`:36-42`）。

## 5. 微信小程序

### 5.1 技术形态

- **原生小程序**（非 Taro/uni-app）：`app.js:1` 原生 `App()`；`project.config.json:42-45` compileType=miniprogram、基础库 3.17.0；**无 npm 依赖**（无 package.json）；
- **20 个页面**（`app.json:2-24`）分六组：login / index（首页）/ goods（商城+溯源）/ parcel（快递）/ send（寄货）/ orders / mine（我的）/ notification / settings / **driver（司机端：workbench/routes/earnings/handover）** / bus（实时公交）；
- 自定义 tabBar（`app.json:32-39` + `custom-tab-bar/index.js`，同步主题色与老年模式）；
- `libs/` 仅一个文件：`amap-wx.js`（高德小程序 SDK，仓库内置）。

### 5.2 接口层与鉴权

- 请求封装 `utils/api.js:63-104`：`wx.request` 包 Promise，`Authorization: Bearer <token>`，解包 yudao `{code:0,data}` 协议；401 防抖清理登录态并 reLaunch 登录页（`:14-36`）；`cleanParams` 过滤空值防止序列化成 `"undefined"`（`:49-58`）——细节见功力；
- baseURL 按 envVersion 切换（`utils/config.js:14-21`）：develop 走开发机 `http://1.15.29.107/api`，trial/release 为待填 HTTPS 占位；
- 三条登录路径（`pages/login/login.js`）：短信验证码（`:103`）、手机号+密码（`:123`）、**微信一键登录**（getPhoneNumber 的 phoneCode + wx.login 的 loginCode → `/app-api/member/auth/weixin-mini-app-login`，`:130-159`）；
- 全部 `/app-api/transport/...` 端点集中在 `utils/api.js`：寄货 send 系列（`:244-291`）、司机 driver 系列（`:297-476`）、实时公交 bus（`:374-393`）、商城/通知/公告/反馈等。

### 5.3 地图与实时性

- 底图用微信原生 `<map>`（腾讯底图），出现在 7 个页面；
- 路线 polyline **优先后端真实道路轨迹**（`/transport/bus/line-polyline`、`/transport/driver/route`），失败回退站点直线（`bus/index.js:339-367`）；
- "现实公交"叠加层用高德小程序 SDK 查公交站 POI（`utils/transit-amap.js:19-42`），key 绑定 AppID；
- **无 WebSocket**——实时性靠轮询（如公交车辆每 15s 刷新，`bus/index.js:10` 注释）；
- 测试形态：纯 Node assert 直跑（小程序仓库内 `node tests/xxx.test.js`），10 个文件，含一个静态扫描 WXML 中 Vue 指令误用的lint式测试。

## 6. 数据库与 SQL 资产

- **MySQL 8**（库名 `ruoyi-vue-pro`，`application-local.yaml:50`）+ **Redis**（缓存 TTL 1h、分布式锁、幂等）；
- `sql/mysql/`：上游全量 `ruoyi-vue-pro.sql` + **transport 唯一 DDL 源** `transport-schema.sql` + 菜单脚本（比赛期的演示数据系列已随运营化改造删除）；
- `transport-schema.sql` 表分组：资源域（vehicle/driver/station/route/shift，`:4-124`）、订单域（order/passenger/cargo/postal，`:142-210`）、**调度域**（dispatch_task `:231`、dispatch_plan `:249`、plan_item `:283`、plan_log、departure_check `:342`）、计价 `:327`、**算法留痕** `transport_algorithm_request :356`、商品溯源、执行监控（shift_execution/vehicle_location/track，`:451-492`）、**多段联运**（leg `:562`、handover `:599`、order_event `:636`、user_notification `:651`、driver_status `:674`）；
- 演进管理：`sql/incremental/V001–V022` **人工执行，无 Flyway**（`sql/incremental/README.md:3`）——V002 算法留痕、V003 调度闭环、V010 计价、V012 分段路网、V015 算法解释字段、V018 联运框架、V019 站点可达性模型、V020 plan_reason 扩长、V021 移除模拟运营表与菜单、V022 会员中心菜单。

## 7. 端到端数据流

### 7.1 智能调度闭环（主链路）

```
客户小程序寄货 → /app-api/transport/send → 承运审核(CargoReviewService)
→ 订单池 POOLED → 管理端调度工作台发起智能调度
→ DispatchServiceImpl.createSmartPlan (:837)
    ├─ 自动选场站/选车（AutoDispatchPlanner，同线路只出一辆车）
    ├─ 按运营线路时间线构建每车骨架 skeleton
    ├─ buildPlanRequest (:3147)：depot+站点+车辆(容量/骨架/初始载荷)+订单/货运对+批次窗口
    └─ 本地规模预检 (:2799) → CAS 抢占订单 → 落 dispatch_task
→ AlgorithmAdapter（快照 SHA-256 幂等）→ AlgorithmClient
→ POST 算法服务 /api/v1/plan（HACO-CPS 4s 求解）
→ 结果落库 dispatch_plan + plan_item（含 detour/impact/reason 算法解释字段）+ algorithm_request 留痕
→ 人工审核 reviewPlan → 下发 ISSUED → 发车核验 RUNNING
→ 司机小程序执行（depart/arrive/pickup-confirm/deliver…）
→ 妥投 COMPLETED，订单事件全程留痕
```

### 7.2 算法放不下的订单：多段联运

货运绕行超 2km 硬约束的订单由算法返回 `unassignedOrderIds`，后端 `MultiLegPlanner` 接力：拆成多个 `transport_leg` 任务段，段间经 `transport_handover` 交接，全程 `transport_order_event` 留痕、`transport_user_notification` 通知用户。

### 7.3 路网数据流

- 在线：算法服务 `distance.py` 调高德距离测量/路径规划 API（进程内 24h 缓存、QPS 限速、整单降级欧氏兜底）；
- 离线预热：`tools/prefetch_dispatch_roads.py` / `prefetch_route_polylines.py` 打后端管理接口，把高德真实轨迹落库到 `transport_leg / transport_route.navigation_polyline`，缓解个人 key 配额耗尽后前端退化为直线的问题；`tools/gen_bus_network_sql.py` 生成公交线网演示数据 SQL。

## 8. 配置、构建与测试

### 8.1 环境要求（`README.md:62`）

JDK 21 · Maven 3.9+（**无 Maven Wrapper**）· Node 22 · pnpm · Python 3.12+ · Docker Compose v2。

### 8.2 构建命令

| 组件 | 命令 |
|---|---|
| 后端 | `mvn -pl yudao-server -am clean test` / `spring-boot:run` / package 出 `yudao-server.jar` |
| 算法 | `pip install -r requirements.txt`；`pytest`（`-m "not slow"` 跳过基准）；Docker 构建 |
| 管理端 | `pnpm install && pnpm build-prod`（vite build，oxc 压缩） |
| 小程序 | 微信开发者工具直接打开，无构建 |

### 8.3 测试资产

- 后端：yudao starter-test（内嵌 Redis）；CI 见 `.github/workflows/ci.yml`；
- 算法：50+ pytest 文件分四层（契约 / 回归矩阵 / 最优性 gap / 消融与可复现性），另有 `benchmarks/` 性能留档；
- 小程序：10 个 Node assert 测试；
- e2e：`e2e/` 目录留存回归与演示证据文档；
- 契约测试是亮点：`algorithm/tests/contract/` 用 `ALGORITHM_BASE_URL` 参数化，即可验收任意契约实现。

### 8.4 外部化配置

`.env.example` 关键项：`ALGORITHM_PORT=18081`、`ALGORITHM_BASE_URL`、`AMAP_KEY`（Web 服务 key）；小程序另有 `AMAP_MINI_KEY`（小程序 key，两者不可混用）；dev 环境后端配置外部化在 `/opt/cargo-post/config/application-dev.yaml`。

## 9. 部署运行方式

- **Docker Compose**（`deploy/docker-compose.yml`）：MySQL、Redis、algorithm（18081）、Nginx——**全部仅绑 127.0.0.1**（除 Nginx 对外）；
- **Java 后端跑宿主机**：systemd 单元 `deploy/cargo-post.service` 托管 `yudao-server.jar`，经 `ALGORITHM_BASE_URL` 指向回环上的算法端口；
- **Nginx** 是唯一公网入口：SPA 静态 + `/api/` 反代 + `/ws/` 升级；刻意无算法 location；
- **监控**：Actuator 全开放 + Spring Boot Admin `/admin`、SkyWalking 链路追踪、Druid 监控台（慢 SQL 100ms）。

## 10. 讨论与局限

**架构优点**：

1. **边界清晰**：算法服务无状态、前端不直连、适配层唯一通道+留痕——职责分离做得到位；
2. **韧性设计贯穿全栈**：算法侧高德失败整单降级欧氏；后端侧 408 轮询/退避重试/30s 故障冷却；管理端地图加载失败降级 SVG 示意图；小程序 polyline 失败回退直线；
3. **可审计性**：算法请求/响应 JSON 全量留痕（`transport_algorithm_request`）、调度方案含算法解释字段、算法求解本身全链路确定性（固定种子），人工审核环节嵌入闭环；
4. **契约先行**：OpenAPI 契约 + 跨实现契约测试套件，使自研实现与未来算法组交付物可插拔替换。

**已知局限与风险**：

1. **BOM 残留**：RocketMQ、Flowable、Netty、weixin-java 等在 dependencies 中锁了版本但未挂载模块（`yudao-dependencies/pom.xml:37,49,72-86`），属上游裁剪不彻底，无碍运行但干扰阅读；
2. **安全卫生**：小程序 `AMAP_MINI_KEY` 硬编码在仓库（cargo-post-miniprogram 的 `utils/config.js:33`，虽有"绑定 AppID"注释，仍建议记入审计）；本地环境短信验证码写死 9999、security mock 开启（`application-local.yaml:187-192,237-240`），须确保不进生产 profile；
3. **文档时效性**：算法 `README.md` 标题停在 ortools-1.1.0（实际 haco-cps-1.4.1）；管理端 README 写 vite4（实际 Vite 8）；
4. **工程化缺口**：无 Maven Wrapper；DB 迁移无 Flyway，靠人工执行增量 SQL；小程序无 WebSocket，实时性靠 15s 轮询；WGS-84→GCJ-02 坐标转换未实现（接车载 GPS 前必须补，见 `docs/algorithm-integration.md`）；WebSocket sender 为 local，多实例部署时需切 redis/MQ；
5. **空占位**：`operation / resource / settlement` 三个 controller/service 域仅有 `package-info.java`，是规划中的业务边界，尚未实现。

---

## 附录：仓库目录速查

| 路径 | 内容 |
|---|---|
| `algorithm/` | 生产算法服务（HACO-CPS + OR-Tools baseline） |
| `yudao-dependencies/` | 版本 BOM |
| `yudao-framework/` | 16 个 starter |
| `yudao-module-{system,infra,member,transport}/` | 业务模块，transport 为核心 |
| `yudao-server/` | 启动壳 + 配置 + Dockerfile |
| `yudao-ui/yudao-ui-admin-vue3/` | 管理端 SPA |
| 小程序仓库 [cargo-post-miniprogram](https://github.com/cgp-team/cargo-post-miniprogram) | 原生微信小程序（已拆分独立仓库） |
| `sql/mysql/` + `sql/incremental/` | DDL 源 + V001–V022 人工增量 |
| `deploy/` | docker-compose / nginx / systemd |
| `tools/` | 路网预取与线网 SQL 生成脚本 |
| `docs/` | 文档库（api / algorithm / optimization / transport / testing / debug） |
| `e2e/` | 端到端回归与演示证据 |
