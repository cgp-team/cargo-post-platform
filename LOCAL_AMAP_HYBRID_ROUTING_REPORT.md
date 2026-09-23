# LOCAL_AMAP_HYBRID_ROUTING_REPORT

真实地图路网—算法—调度 Geometry 闭环（Local Routing + AMap Validation + Geometry Cache）

原则：最小侵入、复用 HACO/ALNS/Gap/Reachability/MultiLeg/DispatchPlan，不重写核心算法。

---

## 1. 当前路由调用链

| 层 | 现状 | 本轮处理 |
|----|------|----------|
| MultiLegPlanner.hopKm | 无路时 Haversine 退化 | 统一经 MapRoutingEngine；无真实路 → UNCERTAIN |
| directCandidate | Haversine 直达 | 同上；无 geometry 不得称 DIRECT 可执行 |
| RouteCorridorService / AMap | 已有 | 保留，作为 AMap 校验端口 |
| AlgorithmRoute / Distance API | 已有 | 不改协议 |
| GeoDistanceUtil.haversine | 全库估算 | **仅预筛选/下界/吸附** |
| 前端 polyline | 自绘风险 | 必须消费后端最终 Geometry |
| VehicleLocationProvider | 唯一真实位置 | 继续复用 |

**距离三分**
- `straightDistance`：预筛选、邻近、下界
- `routingDistance`：正式道路距离/时间/polyline
- `transitBaselineDistance`：公交主线 baseline / detour 增量

---

## 2. 修改前 / 修改后

**前**：HACO/ALNS/MultiLeg 各自估距，Haversine 可能进正式计划。

**后**：
```
Gap / Waypoint Candidate
    → LocalRoutingEngine (RoadGraph 真实道路)
    → GeometryCache (fingerprint)
    → Top-K 才 AMapRoutingEngine 校验
    → GeometrySanityValidator
    → FeasibilityEngine + RealRoadMarginalCost
    → HACO/ALNS
    → DispatchPlan 绑定 final geometry
```

新增包：`algorithm/app/routing/`
- `models.py`：RouteGeometryResult / GeometryStatus / RouteType
- `local_routing.py`：RoadGraph + LocalRoutingEngine（真实道路最短路）
- `geometry_cache.py`：GeometryCache + RoutingCallBudget + route_fingerprint
- `engine.py`：MapRoutingEngine + AMapRoutingEngine + GeometrySanityValidator
- `gap_routing.py`：FlexibleGapRoute（B→C / B→X→C / B→X→Y→C）
- `real_road_cost.py`：RealRoadMarginalCostEvaluator

---

## 3. LocalRoutingEngine

- 在 **RoadGraph**（节点 + 真实道路边 `road_m`/`polyline`）上 Dijkstra
- 返回真实道路距离/时间/折线
- **禁止** Haversine / 两点直线作为正式结果
- 图不可用 → `ROUTE_UNAVAILABLE` / ESTIMATED，不伪装成功

---

## 4. AMap Validation

- 定位：最终校验 + 权威 Geometry + 本地不确定时补充
- 只服务 Top-K / 最终候选 / 关键执行段
- 失败 → UNCERTAIN，禁止 fake success

---

## 5. Geometry Cache

- Key：origin + waypoints + destination + routeType + provider + routeVersion
- Value：distance / duration / polyline / fingerprint / status / timestamp
- TTL 300s；失败短 TTL 30s；支持 mark_stale / force_refresh
- fingerprint 保证同请求稳定，避免地图 API 轻微变化导致调度抖动

---

## 6. Gap Routing

- Skeleton = Mandatory Stop **顺序**，不是固定 Polyline
- 候选：`B→C` / `B→X→C` / `B→X→Y→C`
- **不要求** `B→X→B→C` 回原入口；rejoin = 下一 Mandatory Stop
- 只优化 middle waypoints

---

## 7. DispatchPlan Geometry 闭环

- `MapRoutingEngine.finalize()`：非 formal geometry → UNCERTAIN
- 正式计划必须有可信真实道路 polyline（LOCAL_ROAD / AMAP_VERIFIED / CACHED_REAL）
- ESTIMATED / UNCERTAIN 不得落成正式执行轨迹
- 前端只 render 后端 geometry，禁止两点连线

---

## 8. 高德调用次数对比（机制）

| 场景 | 旧（每候选 AMap） | 新（Local + Cache + Top-K AMap） |
|------|-------------------|----------------------------------|
| N 个 Gap 候选 | N 次 | 0～Top-K 次 + cache hit |
| 重复同请求 | 再次打 AMap | cache hit 0 次 |

`RoutingCallBudget.summary()` 输出：
local_routing_calls / amap_calls / cache_hit_rate / amap_reduction_rate / straight_line_formal_plan_count

**硬指标：straight_line_formal_plan_count = 0**

---

## 9. 测试结果

`tests/test_local_routing_engine.py`：**19 passed**

覆盖：
- Local 真实图路由（非 Haversine 正式）
- Gap B→C / B→X→C / B→X→Y→C，不要求回原路
- Skeleton 顺序保持
- Haversine 不得 formal
- AMap 失败 + Local 成功 / Local 失败 + AMap 成功 / 双失败 UNCERTAIN
- Cache hit / 过期 / fingerprint 稳定
- Top-K 限制 AMap 调用
- Sanity 拒绝距离异常
- MultiLeg 各段独立 geometry
- RealRoad 成本拒绝 ESTIMATED

既有 P0 / 动态调度测试不回归（单独跑通）。

---

## 10. 已知限制

1. RoadGraph 需从 OSM / RouteCorridor / 路网库灌入真实边（接口已就绪）
2. 未内置 Valhalla/OSRM/GraphHopper 进程（LocalRoutingEngine 可换后端）
3. Java MultiLegPlanner.hopKm 的 Haversine 退化点需接本引擎后改为 UNCERTAIN
4. AMap client 仍走现有适配层，本仓库内为可注入端口

---

## 11. 后续可扩展

- Valhalla / OSRM / GraphHopper 本地服务实现 RoadGraph 装载
- StationRoadMatch（站点吸附 + routeId + sequence）
- BASELINE vs ACTUAL Trip Geometry 分存
- RouteStabilityPolicy（微小漂移不换方案）
- 车辆限行 / 路权接入 vehicleAccessProfile

---

## 验收对照

| 项 | 状态 |
|----|------|
| A 搜索不高频打高德 | Local + Top-K + Cache |
| B 本地路由承担候选 | LocalRoutingEngine on RoadGraph |
| C 高德集中校验 | 仅 Top-K / 最终 |
| D Cache 稳定 | fingerprint + TTL |
| E B→X→C 正式路线 | FlexibleGapRoute |
| F 不要求回原路 | rejoin = 下一 Mandatory |
| G Skeleton 顺序 | middle-only 优化 |
| H 正式计划真实几何 | finalize 强制 formal |
| I 前端用后端几何 | 契约 RouteGeometryResult |
| J Haversine 仅预筛选 | estimated_only / lower bound |
| K 双失败 UNCERTAIN | 无 fake success |
| L MultiLeg 分段几何 | 每 leg 独立 resolve |
| M 可统计高德下降 | RoutingCallBudget |
| N 不破坏既有测试 | 保持通过 |
