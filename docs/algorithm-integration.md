# 路线规划算法对接

本文档描述与算法组路线规划服务的对接方式，内容已与算法组 2026-08-09 的书面回复对齐。完整接口契约见 `docs/api/algorithm-api.yaml`；回复原文与逐条结论见 `docs/internal/algorithm-doc-review.md`。

## 调用边界

```text
管理端前端
  -> 管理端业务后端
    -> transport 模块中的算法适配层
      -> 路线规划算法服务
```

前端不得直接调用算法服务。算法服务不得访问或修改业务数据库；它只处理业务后端生成的快照并返回建议方案。业务后端负责鉴权、幂等、超时、重试、结果校验、人工审核和落库。

## 服务接口

算法服务以 Docker 镜像交付（linux/amd64，基镜像 python:3.11-slim），提供 HTTP REST 接口：

- `POST /api/v1/plan`：提交规划任务，同步计算最长 10 秒；成功返回完整结果，超时返回 HTTP 408 + `requestId`。
- `GET /api/v1/result/{requestId}`：凭 `requestId` 轮询异步结果。
- `GET /health`：存活检查，返回 200。
- `GET /ready`：就绪检查，模型加载完毕返回 200，未就绪返回 503。

站点坐标、订单数据均通过请求体传入，算法服务不依赖挂载文件。接口仅有 `requestId` 一个标识，无独立 `jobId`。

## 调度模式：半小时批次锁定

- 以半小时为一个批次区间（如 8:00–8:30），批次开始前 5 分钟（如 7:55）对已归集订单一次性规划，规划完成后该区间停接新单，新订单归入下一区间。
- 不做滚动窗口，不重规划已下发批次。原契约中的 `rollingWindowMinutes` 字段废弃。
- 时间窗简化为"乘客下单后半小时内能上车"；精确时间窗约束由算法组后续迭代。

## 坐标与距离

- 坐标统一使用 GCJ-02；GPS/北斗设备输出的 WGS-84 坐标由业务后端在调用算法前完成转换。
- 距离口径二选一（自研服务 ortools-1.1.0 起支持，按 `AMAP_KEY` 是否配置切换）：未配置时为两点
  欧氏直线（单位：度）；配置后切换为高德「距离测量」驾车路网距离（真实公里）。响应中的
  `distanceUnit` 字段始终标明本次结果单位（`"degree"` / `"km"`），后端据此决定是否再做
  Haversine 换算，全链路不得双重换算；切换细节见下文"编程组自研实现"一节。

## 输入约定

- 静态闭环：全部车辆空载从场站出发、规划结束后返回场站；不支持带初始位置和当前载量的增量规划，批次之间不继承车辆状态。
- 订单分三类：客运（上车站、下车站）、派送（卸货站）、揽收（收货站）。容量只按件数约束：单车实时载客 ≤ 5 人、载货 ≤ 4 件。重量、体积字段可传入留存，算法不校验；无优先级，所有订单平等对待。
- 客运订单强制时序：同一乘客的上车站必须先于下车站访问，违反即不可行。
- 车辆数由算法自动判定：默认优先单车，单车容量不足时自动增车，无需业务侧指定。

## 可调参数（algorithmConfig）

ACO 超参数全部可选，经请求体 `algorithmConfig` 传入，不传使用默认值；传入值覆盖默认值，超出建议范围时响应带 `warnings` 字段但不拒绝请求。默认值随镜像版本管理，镜像 CHANGELOG 记录参数变更。

默认值：`ant_count=30`、`max_iterations=100`、`alpha=1.0`、`beta=3.0`、`rho=0.1`、`Q=100`、`convergence_threshold=20`。

## 规模上限

单任务最大站点数 30、最大订单数 25（乘客+包裹合计）、最大车辆数 3、计算超时 10 秒。超过上限时由业务后端拆分任务分别调用，算法侧不自动拆分。

## 输出约定

- 算法直接产出：每台车辆的闭环站点访问序列、分段里程与总里程 `total_distance`，以及时序合规性标记。
- 业务后端自行估算（算法组不持有定价模型与业务参数）：预计耗时、载客/载货峰值、乘客延误、预计收入、预计成本、综合评分。
- 可行性状态：`status=feasible` 完整解；`status=infeasible` 无解并附 `reason_code`（`OVER_CAPACITY` 总量超总容量 / `TIMING_CONFLICT` 上下车时序矛盾 / `PARTIAL_ONLY` 仅能完成部分订单）。算法不返回失败订单明细与部分方案。

## 幂等、超时与重试

- `requestId` 幂等保留 24 小时；重复提交直接返回首次缓存结果（HTTP 200 + `cached: true`），不重新计算。
- 计算超时 10 秒返回 HTTP 408 + `requestId`，业务后端凭 `requestId` 轮询结果，轮询间隔建议 ≥ 2 秒。
- 可重试错误：408（超时）、503（服务繁忙）、502/504（网关错误）；不可重试：400（参数校验失败）、422（业务不可行/无解）、413（规模超限）。

## 适配层责任

代码位于 `yudao-module-transport/.../integration/algorithm/`（`AlgorithmAdapter` 幂等与留痕、`AlgorithmClient` 超时/重试/轮询、`AlgorithmResultValidator` 结果校验），单测见 `src/test/.../integration/algorithm/`。职责清单：

1. 生成全局唯一 `requestId`，保存请求快照哈希，24 小时内重复请求复用同一业务任务。
2. 调用前完成坐标系转换（WGS-84 → GCJ-02），并按半小时批次归集订单、生成快照。⚠️ 坐标转换当前**未实现**：站点按 GCJ-02 直接录入、司机端微信上报同为 GCJ-02，现状安全；接入车载 GPS/北斗设备（WGS-84）前必须补转换，否则距离计算偏移百米级。
3. 设置连接、读取和任务总时限；只对 408/503/502/504 与网络错误执行有上限的退避重试。
4. 单任务超规模上限时本地预检报错、提示人工拆批（`DispatchServiceImpl.validateScaleLimit`，2026-08-23 落地；不做自动拆分与合并，原"拆分调用"口径废止）。
5. 校验结果中的车辆、站点、订单均属于原快照，容量与时序不越界，所有订单只出现一次。
6. 基于计价规则（`transport_pricing_rule`）估算每站 ETA、方案预计耗时、预计收入与预计成本（`DispatchEstimationService`，2026-08-23 落地）；乘客延误与综合评分未实现。
7. 保存算法/参数版本、原始请求和响应摘要，人工审核后才生成正式调度方案。
8. 算法不可用时允许回退手工派单或模拟派单，不自动覆盖已下发方案。

## 编程组自研实现（ortools-1.2.0）

算法组镜像迟迟未交付，编程组按本契约自研了路线规划算法服务（生产候选），代码在 `algorithm/`，
镜像 `cargo-post/algorithm:1.0.0`，`algorithmVersion=ortools-1.1.0`。决策依据：契约只约定接口行为
与输出字段，未限定求解器（见 `docs/internal/algorithm-doc-review.md` 差异表"求解算法：契约不限定，只要求
返回算法/参数版本"），因此求解器选用 Google OR-Tools（pywrapcp 路由模型），与算法组 ACO 方案互不阻塞——
算法组镜像交付后可按同一契约验收套件对比、择优切换。

实现要点：

- 求解器：订单三类（PASSENGER 强制同车、先上后下；DELIVERY 场站→站点；PICKUP 站点→场站）；
  容量双维度累计约束（载客 BOARD +1、载货 DELIVER/PICKUP +itemCount，批次内座位/仓位不复用）；
  每启用一车计大额固定成本，目标等价于"先最少用车、再最短里程"（Q7 优先单车）；
  距离提供方按 `AMAP_KEY` 配置切换（见下条）；确定性首解策略，同输入同输出。
- 路网距离切换（ortools-1.1.0 新增，`app/distance.py`）：compose 为 algorithm 服务配置
  `AMAP_KEY`（高德开放平台「Web服务」类型 key）即从欧氏直线切到高德「距离测量」API
  （type=1 驾车导航距离），镜像不变、注入 env 重启即生效；未配置 key 时行为与 ortools-1.0.0
  完全一致。**单位语义**：`PlanResult.distanceUnit` 始终输出——`"degree"`（欧氏直线，单位：度，
  后端按 Haversine 换算公里）或 `"km"`（路网真实公里，后端直通，不得二次换算），
  `segmentDistance` / `totalDistance` 跟随同一单位。**调用与缓存**：每个 destination 一次请求
  （origins 全量 ≤100，本服务上限 31 点），httpx 超时 5s、失败重试 1 次；进程内站点级缓存
  （key=站点集合坐标哈希，TTL 24h），站点坐标基本不变，命中缓存 0 次外部调用；返回的
  duration（秒）随矩阵缓存，留作后续 ETA 改进（本期不使用）。**降级**：任一 destination
  失败/超时/配额错误（含 results[].info/code 单项错误）即整单降级回欧氏直线，并在 `warnings`
  追加"路网距离不可用，已降级直线距离"，保证单次求解矩阵口径一致（不做部分对降级）。
  判错按官方文档：`status != "1"`（配额类 infocode 如 10003/10004/10044）或任一 result
  带 info/code（1 无可行车道路 / 2 起终点离道路过远 / 3 不在中国境内）。**限速**：高德个人
  key QPS 极低（实测 ~3/s 即报 `CUQPS_HAS_EXCEEDED_THE_LIMIT`），目的地请求间强制 0.35s
  限速间隔，限流错误按 0.5/1/2s 退避重试（上限 4 次）。
- 路网时长输出（ortools-1.2.0 新增）：`RouteStop.segmentDuration` 给出每站与上一站点间的
  分段行驶秒数（高德矩阵路径；欧氏路径为 None）。业务后端估算层（`DispatchEstimationService`）
  据此把方案 ETA 从"直线÷均速"升级为真实路网时长：distanceUnit=km 且带分段时长的站段按秒数
  累计，成本里程同步使用路网公里；缺失站段仍回退直线÷均速（手工派单与 degree 路径全量回退）。
- 遗留问题收敛口径：无解统一 `200 + status=infeasible + reasonCode`——总需求超总容量为
  `OVER_CAPACITY`，其余不可行为 `TIMING_CONFLICT`；不返回部分方案，`PARTIAL_ONLY` 只报状态不给方案
  （本服务亦不产出该原因码）。契约中 422 与 200+infeasible 的矛盾以 Q10 口径（200 + status 字段）为准，
  适配层对 422 的兼容兜底保留。
- ACO 超参数（`algorithmConfig`）：全部接受但不参与求解（求解器为 OR-Tools），超出建议范围时响应
  带 `warnings` 不拒绝（Q8 口径不变）；默认值与参数变更记录于 `algorithm/CHANGELOG.md`。
- 切换方式（已于 2026-08-30 在 dev 服务器完成切换）：后端跑在宿主机，须指向 compose 映射的宿主端口
  `http://127.0.0.1:18081`（docker 网络主机名 `http://algorithm:8000` 仅容器内可达，宿主机不可解析）。
  生效链路：服务器 `/opt/cargo-post/config/application-dev.yaml` 的 `yudao.transport.algorithm.base-url`
  为外部化字面量配置，优先级高于打包 `application.yaml` 的 `${ALGORITHM_BASE_URL:...}` 占位符——只改
  `.env`/`app.env` 不会生效，必须改该 yaml（或直接设 `YUDAO_TRANSPORT_ALGORITHM_BASEURL` 环境变量）。

## 待算法组澄清

- ~~无解时的 HTTP 状态码矛盾~~（已收敛，不再阻塞）：适配层对两种返回做了双向兼容并归一——`200 + status=infeasible` 原样通过；`422` 归一为无解结果，`reasonCode` 优先取 `details.reasonCode`，缺省回退为标准错误码，保证归一结果必带 `reasonCode`。算法组最终确认唯一形式后，可删除 `AlgorithmClient.toInfeasible` 兜底分支。
- `PARTIAL_ONLY` 语义：部分可完成时是否返回部分方案（当前按"只给状态不给方案"处理）。
- 高德路网数据的申请责任方、到位时间与接入后的算法改造排期（自研服务侧已于 ortools-1.1.0 完成接入并支持按 `AMAP_KEY` 切换；本项仅剩算法组镜像侧的排期）。
- Docker 镜像签名、版本号与兼容规则、正式交付时间。
