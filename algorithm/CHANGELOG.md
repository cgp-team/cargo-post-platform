# CHANGELOG

本服务镜像版本与参数版本变更记录（契约 Q8：默认值随镜像版本管理，CHANGELOG 记录参数变更）。

## [ortools-1.3.0] - 2026-08-26

算法优化 Phase 0-14：动态容量、初始载荷、时间窗口、矩阵完整性、动态成本。

- 载客维度：BOARD +1, ALIGHT -1（座位动态释放），支持重访站点分批上下客。
- 初始载荷：`Vehicle.initialPassengerLoad`，fix_start_cumul_to_0=False + SetRange。
- 时间窗口：所有路径（含欧氏）均估算 segmentDuration，post-solve 验证 batchStart~batchEnd。
- 矩阵完整性：预检所有站点对，缺失返回 `DISTANCE_MATRIX_INCOMPLETE`（防 pywrapcp 崩溃）。
- 动态固定成本：`num_nodes × max_dist + 1`（取代硬编码 10^9）。
- Validator：`app/validators.py`（passenger_load / cargo_load / order_precedence / skeleton / road_segments / time_window）。
- 路径策略：PATH_CHEAPEST_ARC（取代 PARALLEL_CHEAPEST_INSERTION，骨架约束更稳健）。
- `algorithmVersion=ortools-1.3.0`，`parameterVersion=params-v2`。

## [ortools-1.2.0] - 2026-08-23

输出分段路网行驶时长（`segmentDuration`），支持业务后端 ETA 从"直线÷均速"升级为真实路网时长。

- `RouteStop.segmentDuration`：高德矩阵路径下每站给出与上一站点间的分段行驶秒数
  （DEPART 无入弧为 None）；欧氏路径为 None，后端按原直线÷均速兜底，完全向后兼容。
- 契约文档 `docs/api/algorithm-api.yaml` 增补 `segmentDuration` 与此前漏记的 `distanceUnit`。
- `algorithmVersion=ortools-1.2.0`，`parameterVersion=params-v2`（参数无变更）。

## [ortools-1.1.0] - 2026-08-23

接入高德路网距离（可降级直线）。

- 距离提供方抽象（`app/distance.py`）：`DistanceProvider` 协议 + 欧氏 / 高德两个实现，
  求解器 `solve(request, matrix=None)` 支持矩阵注入，成本缩放与输出换算逻辑不变。
- 高德「距离测量」API（`v3/distance`，type=1 驾车导航距离）：配置 `AMAP_KEY` 环境变量即启用；
  未配置时行为与 ortools-1.0.0 完全一致（欧氏直线，单位：度）。
- 单位语义：`PlanResult.distanceUnit` 始终输出——`"degree"`（欧氏路径，后端按 Haversine
  换算公里）或 `"km"`（高德路径，真实公里，后端直通，不二次换算）；
  `segmentDistance` / `totalDistance` 跟随同一单位。
- 调用与缓存：每个 destination 一次请求（origins 全量 ≤100，本服务上限 31 点）；
  httpx 超时 5s、失败重试 1 次；进程内站点级缓存（key=站点集合坐标哈希，TTL 24h），
  站点坐标不变时命中缓存 0 次外部调用；duration（秒）随矩阵缓存，留作后续 ETA 改进。
- 降级：任一 destination 失败/超时/配额错误（含 results[].info/code 单项错误）→
  整单降级回欧氏直线，响应 `warnings` 追加"路网距离不可用，已降级直线距离"，
  保证单次求解矩阵口径一致（不做部分对降级）。
- `algorithmVersion=ortools-1.1.0`，`parameterVersion=params-v2`。
- 参数版本 params-v2 变更：新增距离口径开关（`AMAP_KEY` 配置 = 高德路网公里 /
  未配置 = 欧氏直线度）；其余默认参数同 params-v1（缩放 ×1000、固定成本 10^9、
  求解护栏 5 秒、默认容量 5 人 / 4 件不变）。

## [ortools-1.0.0] - 2026-08-23

首版。编程组自研实现，替换算法组未交付的镜像。

- 求解器：Google OR-Tools 9.15（pywrapcp 路由模型），确定性首解策略
  PARALLEL_CHEAPEST_INSERTION，无随机元启发式，同输入必然同输出。
- `algorithmVersion=ortools-1.0.0`，`parameterVersion=params-v1`。
- 参数版本 params-v1 默认参数：
  - 距离口径：GCJ-02 坐标两点欧氏直线（单位：度，×1000 取整求解，输出 3 位小数）。
  - 车辆固定成本：10^9（等效"先最少用车、再最短里程"，契约 Q7 优先单车）。
  - 求解时间护栏：5 秒（契约上限 10 秒）。
  - 车辆默认容量：passengerCapacity=5 / cargoCapacity=4（请求未传时）。
- 容量口径：双维度累计约束（批次内座位/仓位不复用），总需求超总容量 →
  `200 + status=infeasible + reasonCode=OVER_CAPACITY`；其余无解 → `TIMING_CONFLICT`。
- `algorithmConfig`（ACO 超参数）接受但不参与求解，超建议范围（ant_count>100 /
  max_iterations>500）响应带 `warnings` 不拒绝。
