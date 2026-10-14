# 真实道路路线架构（REAL ROAD ROUTING）

> 一句话规则：**宁可没有路线，不允许假路线。**
> 任何会出现在司机屏幕、乘客屏幕、后台地图上的折线，必须来自真实路网几何；
> 取不到就把这一段留空并说明原因，绝不允许用起终点连线、Haversine、"÷均速"补出来。

本文是这条规则在三个仓库里的落地口径与事实记录（谁产生几何、谁校验、哪些来源算可信、坐标系在哪一步转换）。
逐轮改造过程记在 `cargo-post-android/AI_ITERATION_STATE.md` 与 `DECISION_LOG.md`，
三端一致性缺陷清单见 `cargo-post-android/ANDROID_PARITY_AUDIT_V2.md`（P0-6）。

---

## 1. 几何从哪来：两级链路

```
司机端 /driver/route、后台监控、实时公交 ETA
        │
        ▼
Java 后端（yudao-module-transport）              ← 第 1 级：选来源 + 拼折线
  ① 线路走廊切片 RouteCorridorService.alongRoute     （transport_route.navigation_polyline，逐站 waypoints 取自高德）
  ② 同时服务这两站的那条线路走廊 alongAnyLine
  ③ RoadPolylineService.routeWithMetrics            （后端直连高德 v3 driving，带缓存，返回真实里程/耗时）
  ④ AlgorithmClient /api/v1/route                   （见第 2 级）
  ✗ 全部失败 → RouteFetch=null → 折线在此截断（不补线）
        │
        ▼
算法服务 /api/v1/route（algorithm/app/main.py::real_road_route）   ← 第 2 级：同样的链路，独立实现
  A. 高德驾车路网 AmapDistanceProvider.get_route_with_polyline   provider=amap
  B. GraphHopper + 重庆 OSM（WGS-84 计算，返回前转回 GCJ-02）      provider=graphhopper
  ✗ A、B 都拿不到 → {"available": false, "provider": "unavailable", "reasonCode": "REAL_ROAD_UNAVAILABLE"}
```

第 2 级存在两个实现是有意的：Java 直连高德可以绕开算法服务不可用的情况，算法服务则在没有高德 key 的开发环境里
提供 OSM 兜底。两级的判定口径必须一致（见 §3），否则会出现"后端认为可信、客户端认为不可信"的静默无线。

`/api/v1/route` 里已经没有 euclidean 分支：`distance.py::_euclidean_route_with_polyline` 已删除，
高德失败时返回 `available=False`（而不是两点直线）。直线估算仍保留在**不产出几何**的链路上：
`/api/v1/distance`（报价预估）、`AppSendRouteInfoService`（寄件时效预览，标注 `provider=euclidean` + warning）、
`DispatchServiceImpl` 的后台调度轨迹图（EUCLIDEAN 段用虚线画且明确标注）。这些是否彻底清除是产品决策，记录在审计报告里。

## 2. 缺口处截断，而不是跨缺口连线

`DriverAppServiceImpl.buildRouteSpine` 逐段取几何，**遇到第一段没有真实道路的段就 `break`**：
跨缺口继续拼会在两个站之间连出一条不存在的路，而且这条线看起来完全正常，无法从图上发现。

- `routeAvailable=false`：整条线一段都没有；
- `routeComplete=false`：折线只覆盖了前 `coveredSegments` 段，后面被截断（客户端要显示"后半段路线待确认"，不能自己补）；
- `routeProvider`：单来源时是该来源标签，多来源拼接是 `MIXED`，无几何为 `unavailable`。

`MonitoringServiceImpl` 的后台监控折线同口径（`PlanSegment` + `polylineOpen`，同样有 `routeComplete`）。

## 3. 来源白名单（四份，必须同步改）

| 位置 | 作用 |
| --- | --- |
| `algorithm/app/main.py::real_road_route` | 只有 AMap / GraphHopper 的几何会带 polyline 输出 |
| `yudao-module-transport/.../service/geo/RealRoadProviders.java` | Java 侧接收算法服务结果时的唯一判据（`isRealRoad` / `labelOf`） |
| `cargo-post-android/.../core/map/RouteSource.kt` | 客户端白名单，`real=true` 才允许渲染；配合 `RealRoadRouteValidator` |
| `yudao-ui-admin-vue3/.../dispatch/DispatchVisualDialog.vue::REAL_ROAD_SOURCES` | 后台地图渲染白名单 |

可信标签（对客户端下发的大写形态）：`AMAP`、`OSM_GRAPHHOPPER`、`OSM_ROAD_GRAPH`、`VALHALLA`、`CACHE_REAL_ROAD`、`MIXED`。
不可信 / 一律按"没有路线"处理：`euclidean`、`haversine`、`straight`、`two_point`、`station_join`、`mock`、`demo`、
`estimated`、`none`、`unavailable`，以及**任何未知写法**（白名单不是黑名单：未知即不可信）。

客户端 `RealRoadRouteValidator` 在白名单之外还钉住三件事，都是历史缺陷的回归：
1. 折线少于 2 个不同点 → 拒收；
2. 两点恰好等于首末站点坐标 → 判为"站点连线"，即使来源写着 `amap` 也拒收；
3. 道路里程 < 首末直线距离（或比例 > 4）→ 判为脏数据整体拒收；来源没给里程时保持"未知"，**不用直线值补齐**。

## 4. 坐标系：只有一个转换边界

| 环节 | 坐标系 |
| --- | --- |
| 站点表 / 司机 GPS / 高德 / 小程序 / 客户端渲染 | GCJ-02 |
| OSM pbf / GraphHopper / 离线图路由内部 | WGS-84 |
| 边界 | `algorithm/app/routing/coordinate.py`（`to_wgs84` / `to_gcj02_polyline`，Krasovsky 1971 参数） |
| **Android 客户端边界** | `cargo-post-android` 的 `core/loc/Locator.toGcj02`（设备定位唯一出口）+ `core/map/CoordinateTransform`（唯一换算入口）+ `RealRoadRoute.gcj02Points`（折线渲染前唯一换算出口） |

`real_road_route` 走 B 档时：入参 GCJ-02 → `to_wgs84` → GraphHopper → `to_gcj02_polyline` 转回 GCJ-02 后下发。
**禁止在任何其他位置再转一次**（重复偏移的表现是整条线往东南偏 500–700 米）。
`round_trip_error_m()` 用来证明转换自反（< 1e-6 度）。境外坐标不做偏移（`out_of_china`，`coordinate.py:29-35`）。

**Android 侧补记（2026-09-29，P1-3 ⑦）**：本表第 74 行的"司机 GPS = GCJ-02"此前对 Android **不成立** ——
`LocationManager` 出的是 WGS-84，而工程里已写好的 `CoordinateTransform` 有**零个**生产调用点，
上报与 `/bus/nearby`、可达性评估都带着未换算坐标（实测重庆主城偏移 **480.6 m**）。现在换算在 `Locator` 出口发生一次，
业务层拿到的 `Geo` 一律 GCJ-02，`Repo.reportLocation` / `reverseGeo` 不做二次换算；
判据是 `cargo-post-android/tools/check_location_probes.sh` 的 L-a=2 / L-b=0 / L-e=0 / L-h=0。
两条已知局限照此登记，避免后来人以为是完备方案：
① 客户端的 `CoordinateSystem.inChina()` 与 `out_of_china` 是**同一个矩形**（经度 `72.004..137.8347`、纬度 `0.8293..55.8271`，
   本轮逐条对过），所以"东南亚点（如 `(15.0, 105.0)`）也会被偏移"这个局限**两侧共有**，不是 Android 独有的 bug ——
   对本项目（重庆 / 境内线路）无实际影响，跨境外使用时必须先收紧判定；
② 若某 OEM 的 network provider 本身已回 GCJ-02，客户端的无条件换算会**转两次** —— 真机核对方式见
   `cargo-post-android/tools/verify_device.sh` 导出的 `BUS_LOC` 日志（偏移≈0 即为此情形，届时须按 provider 分流）。
详情见 `cargo-post-android/ANDROID_PARITY_AUDIT_V2.md` §17 / §45 / §65 与 `DECISION_LOG.md` D-019。

## 5. 里程与耗时口径

| 来源 | 里程 | 耗时 |
| --- | --- | --- |
| 高德直连（`RoadPolylineService.routeWithMetrics`） | 高德 `paths[0].distance` | 高德 `paths[0].duration` |
| 线路走廊切片 | 真实折线自身顶点累加（`lengthKm`） | **null**（无来源，不下发，客户端不得换算） |
| GraphHopper | `paths[0].distance` | `paths[0].time` |

多段拼接时：只要有一段缺里程/耗时，聚合值就整体下发 null（`distanceKnown`/`durationKnown`），
而不是"有的段用路网、缺的段用直线凑"。

## 6. 资产与启动（实测记录）

```
tools/osm-data/chongqing-260921.osm.pbf     30 MB   Geofabrik 重庆提取，data_timestamp 2026-09-21
tools/osm-data/chongqing-districts-gcj02.json       区县边界（GCJ-02）
tools/graphhopper/graphhopper-web.jar       45 MB   GraphHopper 8.0（Build-Jdk 1.8）
tools/graphhopper/graph-cache               34 MB   已构图（CH 就绪，无需重新 import）
tools/graphhopper/config.yml                        profiles: car, bus（vehicle 均为 car，自定义 priority/speed）
                                                    import.osm.ignored_highways: footway,path,...
```

启动（**必须在 `cargo-post-platform/` 目录执行**，config 里是相对路径）：

```bash
java -Xmx1500m -jar tools/graphhopper/graphhopper-web.jar server tools/graphhopper/config.yml
```

实测（2026-09-29）：

- 监听 **8080**（application）/ **8081**（admin）—— 与 `docs/routing/GRAPHHOPPER_DEPLOYMENT.md`、
  `docs/routing/LOCAL_ROUTING_ARCHITECTURE.md` 里写的 8989 不一致：8989 是 GraphHopper 上游默认值，
  本仓库这套 config 没有 `server` 段，DropWizard 用的是自身默认 8080。
  同时 `LocalRoutingConfig` 字段默认 8080、`from_mapping` 默认 8989、`route_cost_provider.py` 默认 8080。
  **以 8080 为准**，并且显式设置 `GRAPHHOPPER_URL` 覆盖默认值（算法服务的 B 档在 `GRAPHHOPPER_URL` 为空时直接跳过）。
- `/info` → `{"version":"8.0","bbox":[105.197,28.151,110.249,32.194],"profiles":[{"car"},{"bus"}],"elevation":false}`。
  bbox 只覆盖重庆：重庆外的坐标会 snapping 失败 → 该段没有几何 → 折线截断（这是预期行为，不是 bug）。
- `profile=foot` → HTTP 400 `The requested profile 'foot' does not exist. Available profiles: [car, bus]`。
  默认 profile 是 `bus`（`GRAPHHOPPER_PROFILE` 可改）。**不要**把 `foot`/`walk`/`bike` 配进生产：那会稳定失败。

端到端实测（无 `AMAP_KEY`、`GRAPHHOPPER_URL=http://127.0.0.1:8080`、`profile=bus`）：

```json
POST /api/v1/route
{"origin":{"latitude":29.5580,"longitude":106.5770},"destination":{"latitude":29.6000,"longitude":106.6300}}
→ {"available":true,"distanceKm":9.904,"durationSeconds":692.42,"provider":"graphhopper",
   "polyline":[{"latitude":29.557919,"longitude":106.577185}, ... 159 个点 ...]}
```

- 直线 6.934 km，道路 9.904 km，**绕行系数 1.43**（山城跨江合理区间）；
- 折线首点距请求起点 **20 米**：这是坐标系没转错的证据 —— 直接把 GCJ-02 坐标喂给 GraphHopper 时同一段路返回 17.3 km
  （相当于把起终点搬到江的另一侧），偏移会立刻暴露成荒谬里程。
- 把 `GRAPHHOPPER_URL` 指向不存在的端口 → `{"available":false,"provider":"unavailable","reasonCode":"REAL_ROAD_UNAVAILABLE"}`，
  **响应里没有 polyline 字段**。

## 7. 当前可信度结论

| 链路 | 结论 |
| --- | --- |
| `/driver/route` 折线（走廊 → 高德 → 算法 → 截断） | **可信**：无任何直线兜底分支，缺口截断，来源标签如实 |
| 算法服务 `/api/v1/route` | **可信**：AMap → GraphHopper → unavailable，实测两档行为见 §6 |
| 后台监控折线 | **可信**：逐段白名单 + 缺口截断（本次改造） |
| 运输段 `navigation_source` | **可信**：OSM 来源不再被误标 `ESTIMATED`；后台地图按同一白名单渲染 |
| 实时公交 ETA | **可信**：来源不在白名单时不返回 ETA（原来会把任何来源打成 `EUCLIDEAN`/`AMAP` 二选一） |
| `/api/v1/distance`、寄件时效预览、后台调度轨迹图 | **带标注的估算**：不产出可渲染的假折线；是否清零是产品决策，见审计报告 |
| 司机端实时导航（GPS → 下一步指引） | **未完成**：仍是页面内步进模拟，见 P1-2 `core/navigation` |

## 8. 验证方式（本机可复现）

```bash
# 算法服务（25 个 route/graphhopper 用例，含"失败时不得画直线"的回归）
cd cargo-post-platform/algorithm && python -m pytest tests/test_route.py tests/test_graphhopper_provider.py -q

# Java 后端语法/类型检查（本机没有 mvn；C:/proj、C:/m2repo 是 ASCII junction，
# 因为 javac 用 ANSI 代码页解码 @argfile，CJK 路径会乱码）
bash C:/temp/check-backend.sh          # 期望：status=0，无 "错误:" 行

# 客户端契约测试（RouteGuideContractTest 11 + RealRoadRouteValidatorTest 16）
cd cargo-post-android && ./gradlew :app:testDebugUnitTest \
  --tests "*RealRoadRouteValidatorTest*" --tests "*RouteGuideContractTest*" \
  -Dorg.gradle.jvmargs="-Xmx3072m -XX:MaxMetaspaceSize=768m -Dfile.encoding=GBK"

# 自检：这几条链路里不该再出现"直线当道路"
git grep -n -E "euclidean|haversine|straight|mock|fake|demo" -- \
  'yudao-module-transport/src/main/java/**/driver/**' 'algorithm/app/main.py' 'algorithm/app/distance.py'
```
