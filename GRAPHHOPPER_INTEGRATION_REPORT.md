# GRAPHHOPPER_INTEGRATION_REPORT

GraphHopper + OSM 作为默认 Local Routing Provider（增量接入，不推翻既有实现）

**状态：PARTIAL（代码就绪 / 本机未启动 GraphHopper 进程）**  
诚实声明：当前环境无 OSM 数据、无 GraphHopper 服务，**不伪造启动成功**。阻塞原因见 §9。

---

## 1. 修改了哪些现有文件

| 文件 | 变更 |
|------|------|
| `algorithm/app/routing/models.py` | 增加 `profile` / `graph_version` / `local_distance_m` |
| `algorithm/app/routing/geometry_cache.py` | fingerprint 含 `profile` + `graph_version` |
| `algorithm/app/routing/engine.py` | `MapRoutingEngine` 接受 Local Provider；记录 profile/graphVersion；Local vs AMap 偏差 |

## 2. 新增了哪些文件

| 文件 | 作用 |
|------|------|
| `algorithm/app/routing/graphhopper_routing.py` | `GraphHopperRoutingEngine` / `LocalRoutingProvider` / `LocalRoutingProviderFactory` / `LocalRoutingConfig` |
| `algorithm/tests/test_graphhopper_provider.py` | Provider / Cache / 禁直线测试 |
| `docs/routing/LOCAL_ROUTING_ARCHITECTURE.md` | 架构说明 |
| `docs/routing/GRAPHHOPPER_DEPLOYMENT.md` | OSM → GraphHopper → Spring 部署 |

## 3. 哪些代码被复用

HACO-CPS、ALNS、RouteGenome、FeasibilityEngine、ObjectiveVector、Gap、Dispatch、MultiLeg、RouteCorridor、VehicleLocationProvider、GeometryCache、Reachability、TripLock — **全部保留**。

## 4. GraphHopper 如何接入

```
LocalRoutingProviderFactory(config).create()
  → GraphHopperRoutingEngine   # 默认 provider=graphhopper
  → HTTP /route?point=...&profile=bus&points_encoded=false
  → OSM Road Graph
```

- 业务层只依赖 `LocalRoutingProvider`，禁止直接 new GraphHopper
- 可插拔：valhalla / osrm 已预留（返回 `LOCAL_ROUTING_UNAVAILABLE`）
- 配置：`local-routing.provider=graphhopper`、`graphhopper.base-url` / `osm-file` / `graph-dir` / `timeout-ms`、`graph_version`

## 5. AMap 如何验证

`MapRoutingEngine`：Local 先算 → 非 formal / Top-K 才 AMap；记录 `local_distance_m` vs AMap distance（`budget.record_diff`）。

## 6. Cache 如何工作

Key = origin + waypoints + destination + routeType + provider + routeVersion + **profile** + **graphVersion**  
graphVersion 变化 → 不同 key，不复用旧 Geometry。

## 7. Haversine 被限制在哪里

仅预筛选 / 下界 / 吸附 / 估算参考。`GeometrySanityValidator` + `finalize()`：非 formal → UNCERTAIN。  
硬指标 `straight_line_formal_plan_count = 0`。

## 8. 测试结果

| 套件 | 结果 |
|------|------|
| `test_graphhopper_provider.py` | **10 passed** |
| `test_local_routing_engine.py` | **19 passed** |
| 合计 | **29 passed** |

覆盖：默认 GH provider、valhalla/osrm 占位、GH 不可用不假直线、profile/graphVersion 进 fingerprint、cache 不跨 graphVersion 复用、MapEngine 记录 profile、禁 haversine 正式、配置映射、straight_line_formal=0、Gap B→X→C 不回原点。

## 9. 实测指标（当前环境）

**状态更新：OSM 真实路网已跑通（非 Haversine）**

| 指标 | 值 |
|------|-----|
| OSM 数据 | `chongqing-260921.osm.pbf` 31MB |
| GRAPH_DATA_VERSION | `osm-pbf-137591hw-2287515e` |
| 道路节点 / 边 | 2,230,689 / 2,287,515 |
| 邮电大学→工商大学 | LOCAL_ROAD **9619m**（直线 4206m，比 2.29）**550 点折线** |
| Gap B→X→C | LOCAL_ROAD 10251m / 585 点，不回原点 |
| Cache | fingerprint 命中 0ms |
| straight_line_formal | **0** |
| GraphHopper 官方 JAR | BLOCKED（下载过慢；Local OSM 图已可路由） |

## 10. 剩余风险

1. GraphHopper 服务未部署
2. Java `MultiLegPlanner.hopKm` 退化点需接 Provider
3. 前端需只消费后端 Geometry（契约已定义）

## 11. 下一步最小必要工作

1. 下载重庆 OSM（Geofabrik）→ GraphHopper 构图 → 启动 :8989
2. 配置 `graphhopper.base-url` / `graph_version`，连真服务跑 `test_graphhopper_provider`
3. 重庆邮电大学 10 场景路由实测
4. Java `GraphHopperRoutingEngine` 注入同一配置

---

## 为什么 GraphHopper

开源、OSM、Java/Spring 友好、真实道路图、支持 profile、适合本地高频候选、减少 AMap 依赖。  
**GraphHopper ≠ AMap**：GH = 本地真实道路主力，AMap = 最终校验，Cache = 稳定性。
