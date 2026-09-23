# LOCAL_ROUTING_ARCHITECTURE

真实路网混合路由：GraphHopper + OSM 本地主力，AMap 最终校验，GeometryCache 稳定。

## 为什么选 GraphHopper

1. 开源  
2. 基于 OpenStreetMap 真实道路图  
3. Java / Spring Boot 集成友好  
4. 支持 vehicle profile（bus / cargo_bus …）  
5. 适合本地高频候选搜索  
6. 显著降低 AMap 高频依赖  

**GraphHopper ≠ AMap**：GH = 本地真实道路主力；AMap = 最终校验与高质量 Geometry。

## 分层

```
RoutingEngine
├── CachedRoutingEngine / GeometryCache
├── GraphHopperRoutingEngine   ← 默认 Local Provider
├── AMapRoutingEngine           ← 最终真实道路校验
└── Future Providers (Valhalla / OSRM)
```

业务层只依赖 `LocalRoutingProvider` / `MapRoutingEngine`，禁止直接 `new GraphHopper`。

## 距离语义

| 类型 | 用途 |
|------|------|
| straightDistance | 预筛选、下界、吸附 |
| routingDistance | 正式道路距离/时间/polyline |
| transitBaselineDistance | 公交主线 baseline / detour 增量 |

Haversine **不得**进入正式 DispatchPlan / ObjectiveVector / MultiLeg Leg / 地图 polyline。

## Gap Routing

Skeleton = Mandatory Stop 顺序（不是固定 Polyline）。  
允许 `B→X→C` / `B→X→Y→C`，**不要求** `B→X→B→C`。rejoin = 下一 Mandatory Stop。

## 数据流

```
HACO-CPS → RouteGenome → Local Routing (GraphHopper+OSM)
  → Feasibility / Cost → Top-K → AMap Verify
  → Geometry Cache → DispatchPlan → Driver → GPS/Events → Remaining Replan
```

## 配置

```
local-routing.enabled=true
local-routing.provider=graphhopper
local-routing.profile=bus
graphhopper.base-url=http://127.0.0.1:8989
graphhopper.osm-file=/data/chongqing.osm.pbf
graphhopper.graph-dir=/data/graph-cache
graphhopper.timeout-ms=3000
graph_version=osm-cq-v1
```
