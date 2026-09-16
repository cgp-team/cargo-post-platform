# 系统测试审计报告（master @ b8031f44）

> 审计基线：`origin/master`（b8031f44，含 PR#83/84：寄货闭环 + 附近实时公交三阶段）。
> 目的：建立完整测试方案，审计现有实现，标记并修复明确 P0/P1。
> 说明：master 不含分支 `feat/dispatch-algorithm-integration` 最新 2 个修复 commit（`0743e1f1` undefined 参数、`6e8d02dd` 村庄切换刷新）——已作为 P1 记录，见 bug-report。

## 一、当前所有核心业务链路

### 1.1 用户定位链路
```
首页 loadUserLocation()
  → utils/location.js getCurrentLocation()
      1. 缓存(userLocation, TTL 5min)命中 → source=cache，后台异步刷新
      2. 微信原生高精度定位 wx.getLocation(wgs84, isHighAccuracy, 6s 回退)
      3. 逆地理 weather.reverseGeocode(lat,lon)（腾讯LBS空Key→BigDataCloud）→ district
      4. 失败 → 旧缓存 stale 兜底 → UNKNOWN
  → userLocation{success, latitude, longitude, accuracy, district, timestamp, source, stale, level}
  → 首页保存 userLocation + 更新 currentVillage（仅展示文本）
```

### 1.2 附近公交链路
```
首页 loadNearbyBusData(userLocation)
  → GET /app-api/transport/bus/nearby?latitude&longitude&radius&district
  → AppBusServiceImpl.getNearbyBuses
      ① 附近站点：StationMapper 全量 + GeoDistanceUtil.haversineKm ≤ radius(5000m) 过滤排序 → nearbyStations + nearestStation
      ② 附近车辆：MonitoringService.getRealtimeVehicles()（真实上报5min优先 / 模拟插值）
         - 距离 Haversine ≤ radius 过滤
         - dataSource=REAL/SIMULATED、status=RUNNING/IDLE/ARRIVED
         - fillEta：车辆坐标+nextStation坐标 → AlgorithmClient.route → POST /api/v1/route → AmapDistanceProvider.get_route → 高德
           → distanceToNextStationKm / etaMinutes（60s 内存缓存节流）
      ③ 无精确定位：district 区域 fallback（站点名/地址 contains district → 关联线路 → 线路车辆）
  → AppBusNearbyRespVO{buses, nearbyStations, nearestStation, dataSource, locationLevel, located}
```

### 1.3 寄货链路
```
send 页 station-picker 选「取货/送达」→ 点"下一步"
  → POST /app-api/transport/send/route-preview{pickupStationId, deliveryStationId}
  → AppSendRouteInfoService.routePreview：查 StationDO 校验（存在/未删/启用/不相同）
       → AlgorithmClient.distance → POST /api/v1/distance → get_route → 高德
  → RoutePreviewRespVO{available, distanceKm, durationMinutes, provider(amap|euclidean), warning}
  → 确认发布 POST /app-api/transport/send/create
  → TransportOrderServiceImpl.createSendOrder：二次校验站点（非空/不相同/存在/启用）→ 落库
```

### 1.4 实时公交（bus 页）
```
bus/index → GET /app-api/transport/bus/lines（线路+经停+在线车辆，15s 刷新）
bus/detail?id → 复用 /bus/lines 按 busId 匹配车辆 → 进度 + 经停状态
首页更多 → GET /app-api/transport/bus/realtime（全局，保留）
```

### 1.5 司机位置上报
```
司机工作台 → POST /app-api/transport/driver/location{vehicleId,shiftId,longitude,latitude,...}
  → transport_vehicle_location（每车一行 upsert）→ MonitoringService.selectRecent(5min) 读取
```

## 二、接口调用关系（层级）

```
┌─ 小程序 ─────────────────────────────────────────────┐
│ index.loadNearbyBusData ──→ /bus/nearby              │
│ send 页 route-preview ────→ /send/route-preview      │
│ send 确认 ───────────────→ /send/create              │
│ bus 页 ──────────────────→ /bus/lines /bus/realtime  │
└──────────────┬───────────────────────────────────────┘
               │ (小程序不直连高德/算法)
               ▼
┌─ Spring Boot（yudao-module-transport）──────────────┐
│ AppBusServiceImpl → MonitoringService（车辆位置）     │
│                  → StationMapper（站点）              │
│                  → AlgorithmClient.route/distance    │
│ AppSendRouteInfoService → StationMapper + AlgorithmClient.distance
│ TransportOrderServiceImpl → StationMapper（校验）     │
└──────────────┬───────────────────────────────────────┘
               │ HTTP（RestTemplate，重试/降级语义）
               ▼
┌─ Algorithm Service（FastAPI）───────────────────────┐
│ POST /api/v1/route（坐标→坐标，公交 ETA）             │
│ POST /api/v1/distance（两站点，寄货预览）             │
│ POST /api/v1/plan（派单）                            │
│   └─ AmapDistanceProvider.get_route/get_matrix       │
└──────────────┬───────────────────────────────────────┘
               │ HTTP（v3/distance，AMAP_KEY）
               ▼
┌─ 高德路网 API ──────────────────────────────────────┐
│ distance + duration（驾车）                          │
└──────────────────────────────────────────────────────┘
```

## 三、前端与后端字段契约

### 3.1 定位（前端内部 + storage `userLocation`）
| 字段 | 类型 | 单位 | 可空 | 说明 |
|---|---|---|---|---|
| success | boolean | - | 否 | 是否有坐标 |
| latitude/longitude | number | 度 | 是 | 真实坐标（PRECISE/APPROXIMATE 时） |
| accuracy | number | 米 | 是 | 定位精度 |
| district | string | - | 是 | 逆地理区域名（展示文本） |
| source | string | - | 否 | wechat/cache/stale-cache/reverse-geocode/denied/unknown |
| level | string | - | 否 | PRECISE(≤100m)/APPROXIMATE/DISTRICT/UNKNOWN |
| stale | boolean | - | 是 | 旧缓存兜底 |

### 3.2 `/bus/nearby` → AppBusNearbyRespVO
| 字段 | 类型 | 单位 | 可空 | 说明 |
|---|---|---|---|---|
| buses[].busId | Long | - | 否 | 车辆 id |
| buses[].routeName/shiftCode | string | - | 是 | 线路/班次 |
| buses[].status | string | - | 否 | RUNNING/IDLE/ARRIVED/NO_LOCATION |
| buses[].nextStation | string | - | 是 | 下一站（无可靠位置 null） |
| buses[].dataSource | string | - | 否 | REAL/SIMULATED |
| buses[].locationSource | string | - | 否 | REAL_FRESH/REAL_STALE/SIMULATED/NO_LOCATION |
| buses[].distanceToNextStationKm | number | km | 是 | 高德真实道路距离 |
| buses[].etaMinutes | integer | 分钟 | 是 | 高德 duration 向上取整 |
| buses[].routeProvider | string | - | 是 | AMAP/EUCLIDEAN |
| buses[].longitude/latitude | number | 度 | 否 | 车辆位置 |
| buses[].lastLocationTime/updatedAt | string | ISO | 是 | 位置/数据时间 |
| nearbyStations[].id/name/longitude/latitude/distanceKm | - | km | 是 | 附近站点（距离升序） |
| nearestStation | object | - | 是 | 最近站点 |
| dataSource | string | - | 否 | REAL/SIMULATED/MIXED/NONE |
| locationLevel | string | - | 否 | PRECISE/APPROXIMATE/DISTRICT/UNKNOWN |
| located | boolean | - | 否 | 是否有精确坐标 |

### 3.3 `/send/route-preview` → RoutePreviewRespVO
| 字段 | 类型 | 单位 | 可空 |
|---|---|---|---|
| available | boolean | - | 否 |
| distanceKm | number | km | 是 |
| durationMinutes | integer | 分钟 | 是 |
| provider | string | - | 否（amap/euclidean） |
| warning | string | - | 是 |

### 3.4 算法 `/api/v1/route` / `/api/v1/distance`
| 字段 | 类型 | 单位 | 可空 |
|---|---|---|---|
| available | boolean | - | 否 |
| distanceKm | number | km | 是 |
| durationSeconds | number | 秒 | 是 |
| provider | string | - | 否（amap/euclidean） |
| reasonCode | string | - | 是（ROUTE_UNAVAILABLE） |

## 四、Algorithm Service 调用关系
- **`/api/v1/plan`**：调度派单（`DispatchServiceImpl.createSmartPlan`），OR-Tools 求解；408 轮询/幂等 24h。
- **`/api/v1/distance`**：寄货路线预览（`AlgorithmClient.distance`），两站点路网距离；`get_matrix`。
- **`/api/v1/route`**：公交 ETA（`AlgorithmClient.route`），坐标→坐标；`get_route` 单路线。
- Java 侧统一 `AlgorithmClient`（RestTemplate，重试/降级语义），**不直连高德**。

## 五、高德 API 调用关系
- 唯一入口：`algorithm/app/distance.py` `AmapDistanceProvider`（AMAP_KEY）。
- 端点：`GET https://restapi.amap.com/v3/distance`（origins≤100，type=1 驾车）。
- 降级：未配 key / 失败 / 配额 → `euclidean` 直线估算（provider 明确标注），不伪装。
- 不可达：单点 info/code 错误 → `available=false`（unreachable 标记）。
- 缓存：进程内站点坐标哈希矩阵缓存 TTL 24h；Java 端 ETA 60s 缓存。

## 六、REAL / SIMULATED / FALLBACK 数据来源
| 数据 | 来源判定 | 说明 |
|---|---|---|
| 车辆位置 REAL | `transport_vehicle_location` 司机 5min 内上报 | `dataSource=REAL`，`locationSource=REAL_FRESH` |
| 车辆位置 SIMULATED | 无真实上报，按班次计划时间线性插值 | `dataSource=SIMULATED`，`locationSource=SIMULATED` |
| 距离 AMAP | 高德路网（AMAP_KEY 配） | `routeProvider=AMAP`，真实 km/秒 |
| 距离 FALLBACK | 高德不可用/未配 key → Haversine 直线 | `routeProvider=EUCLIDEAN`，前端标注"模拟位置/位置暂不可用" |
| 定位 FALLBACK | 定位失败/拒绝 → 旧缓存 stale / district | `stale=true` / `level=DISTRICT/UNKNOWN` |
| 寄货预览 FALLBACK | 算法服务不可达 → 后端 Haversine 直线 | `provider=euclidean` + `warning="直线估算"` |

## 七、当前已有测试覆盖范围
- **transport 单测 138 个**：AppBusServiceImpl（realtime/lines/nearby/ETA 16）、AppSendRouteInfoService（route-preview 7）、TransportOrderServiceImpl（createSendOrder 校验 10）、DispatchEstimationService（ETA/routeProvider 8）、DispatchServiceImpl（16）、DriverAppServiceImpl（30）等。
- **算法 pytest 60 个**：plan/result、distance（矩阵/降级/缓存）、route（成功/不可达/缓存/malformed/并发）、contract。
- **mock-algorithm 契约 8 个**。
- **小程序 `node --check`**：全部 JS 语法。
- **`miniprogram/tests/location.test.js`**：LocationService 12 场景（Node 直跑 mock wx）。
- **pre-push hook**：transport 测试 + 小程序 node check + 管理端 build。

## 八、当前缺失测试（审计结论）
| 缺口 | 说明 |
|---|---|
| **前端 E2E（真机/开发者工具）** | 定位授权/拒绝/切村庄/附近公交 UI 均未自动化，只能人工 |
| **线上接口契约验证** | /bus/nearby、/send/route-preview 线上真实返回未形成回归 |
| **异常场景端到端** | Redis 不可用/算法不可用/高德不可用时的页面行为未自动化（仅代码容错） |
| **寄货完整 UI 集成** | station-picker 选站→预览→下单 全流程未自动化 |
| **bus/detail 真实数据** | detail 仍复用 /bus/lines 估算字段，真实 ETA 数据源缺失（架构 gap） |
| **定位"演示位置/切换村庄"一致性** | master 无演示定位（switchVillage 仅改文本，坐标不同步——P1，见 bug-report） |
| **接口字段契约回归** | latitude/longitude/distanceKm/provider/available 等跨端字段名/单位/可空未固化为测试 |

## 九、审计基线已知问题（master @ b8031f44）
1. **P1** `api.js getNearbyRealtimeBuses` 不过滤 undefined 参数 → 无精确定位时后端 400（分支已修复 `0743e1f1` 未合）。
2. **P1** `switchVillage` 切换村庄不触发 nearby 查询、district 不随切换更新 → 切村庄后附近公交不刷新（分支已修复 `6e8d02dd` 未合）。
3. **P2/架构** bus/detail 无真实 ETA 数据源（显示"等待实时位置"）。
4. **P2** `MonitoringVehicleRespVO.lastLocationTime` 真实车辆才有；SIMULATED 无位置时间。
5. **依赖外部** AMAP_KEY 未配置时公交 ETA/寄货预览走直线估算（部署注意）。
