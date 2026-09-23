# ROUTE_SEARCH_PRE_AUDIT / ROUTING_TRAINING_BOTTLENECK_AUDIT

## 已存在（复用，不重写）

| 模块 | 状态 |
|------|------|
| OSM 重庆 PBF / RoadGraph（2.23M nodes / 2.29M edges） | 可用 |
| LocalRoutingEngine（RoadGraph Dijkstra） | 可用，**全图搜索是慢点** |
| GraphHopperRoutingEngine（HTTP adapter） | 接口就绪；本机 GH JAR 未跑通 |
| MapRoutingEngine + GeometryCache + fingerprint | 可用 |
| HACO/ALNS/Gap/MultiLeg/Feasibility/Objective | 保留 |
| LSR CandidateRanker（LambdaRank） | 可用 |
| TransitSnapshot（真实站点 6204 / snap 6197） | 可用 |
| Watchdog / health / holdout 语义 | 可用 |

## Dijkstra 调用点与慢点

1. `local_routing.LocalRoutingEngine._shortest_path`：每段 OD 一次全图 Dijkstra（~2M 边）
2. `RealRoadRouter.route`：每个 candidate 一次 resolve（group 20–40 候选 × 35 groups ≈ 1000 次）
3. 无 OD 缓存 → 同一 station pair 反复重算
4. 无 Station snap 缓存 → nearest_node 重复
5. 无批量 Matrix；无 CH/LM 预处理
6. 无 all-pairs，但隐式 N 候选近似 all-pairs on 工作站集

## 必须真实计算 vs 可复用

- 必须真实：每个 unique OD 的一次 road path（Teacher label）
- 可复用：相同 OD 二次查询、station snap、geometry、gap 重叠段
- 可 CH/LM：静态路网重复查询（生产 GraphHopper speed mode）
- Haversine：仅 prefilter / lower bound

## 训练最大瓶颈

全图 Dijkstra × 1000 OD（无 cache）→ 数十分钟级；已验证 5min smoke 超时。

## 数据最大问题

- 先前仅 8 站 fixture（已改 snapshot）
- OSM route relation = 0（站点有、线路缺）
- 工作站集为性能取 15 站子集（如实报告 DATA_LIMITED）

## 本轮改造

1. `RoadODCache` + `StationSnapCache`（memory/disk，graph_version 失效）
2. `RoutingSession`：图只加载一次
3. Query-driven OD（真实站/路节点），禁止 N×N
4. `path_search/`：Route Search Ranker（LambdaRank，edge/candidate 特征，无 teacher 特征）
5. 真实坐标订单生成器（OSM_ROAD_NODE / REAL_STATION，禁 SYNTHETIC）
6. 迭代 v0→v3 + Regression Gate + 多 seed
7. CH/LM：GraphHopper 生产路径；本地以 cache+子图模拟 speedup，GH 可用时切换
