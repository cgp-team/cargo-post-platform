# 寄货路线预览（Send Route Preview）

> 小程序寄货页「取货站点 + 送达站点 → 真实道路距离 + 预计时间」闭环。
> 本阶段不引入 Redis；复用算法服务进程内缓存。

## 调用链

```
小程序 send 页（点"下一步：拍照确认"触发，不选站后立即请求）
  │  POST /app-api/transport/send/route-preview
  ▼
Java 后端 AppSendController.routePreview（@PermitAll）
  │  AppSendRouteInfoService.routePreview(pickupStationId, deliveryStationId)
  │  ① 按 stationId 查真实 StationDO（不信任前端坐标）
  │     · 校验：两站非空 / 不相同 / 存在（逻辑删除自动过滤）/ 已启用(status=0)
  │  ② AlgorithmClient.distance() → 算法服务
  ▼
算法服务 POST /api/v1/distance
  │  AmapDistanceProvider.get_route(origin, destination)  ← 单路线能力（薄封装 get_matrix）
  │     · 高德成功 → provider=amap，真实路网 km + 秒
  │     · 高德明确不可达（无可行车道路）→ available=false
  │     · 高德失败/超时/配额 → provider=euclidean，Haversine 直线 km + 均速秒
  ▼
高德 v3/distance（AMAP_KEY 仅在算法服务侧，绝不进入小程序）
```

AMAP_KEY 不进入小程序；小程序 → Java 后端 → 算法路网服务 → 高德，单向四段。

## 数据结构

`POST /app-api/transport/send/route-preview`
```json
{ "pickupStationId": 2, "deliveryStationId": 7 }
```
```json
{
  "available": true,
  "distanceKm": 18.62,
  "durationMinutes": 32,
  "provider": "amap",
  "warning": null
}
```
- `available=false`：路线不可达或服务不可用，`distanceKm/durationMinutes` 为 null。
- `provider=amap`：高德真实路网；`provider=euclidean`：直线估算降级（`warning` 说明，不伪装成高德）。

算法服务 `POST /api/v1/distance`（详见 `docs/api/algorithm-api.yaml`）：
```json
{
  "requestId": "req-...",
  "distanceUnit": "km",
  "pairs": [{
    "fromStationId": "2", "toStationId": "7",
    "distanceKm": 18.62, "durationSeconds": 1920,
    "provider": "amap", "available": true
  }],
  "computedAt": "..."
}
```

## 缓存

- **算法服务进程缓存**：`AmapDistanceProvider` 对站点集合坐标哈希缓存全量矩阵，TTL 24h（`distance.py` `CACHE_TTL_SECONDS`）。相同站点组合（坐标不变）命中缓存，不重复打高德。缓存 key 基于**坐标**（含坐标系语义，算法服务统一 GCJ-02），非仅 stationId。
- **前端页面缓存**：send 页 `routePreviewKey = ${pickupStationId}:${deliveryStationId}`，相同组合已成功查询则直接复用；修改任一站点立即清空旧结果。

## 失败策略

| 场景 | 返回 |
|---|---|
| 高德成功 | `available=true, provider=amap` |
| 高德失败/超时/配额 | `available=true, provider=euclidean, warning="路网暂不可用，当前为直线估算"` |
| 高德明确无可行车道路 | `available=false` |
| 算法服务不可用 | `available=false, warning="路线服务暂不可用"` |
| 站点不存在 / 停用 / 两站相同 | 后端抛明确业务错误（不返回 preview） |

路线预览结果**仅用于前端体验**，不作为订单创建依据；订单创建时后端独立校验站点有效性（见下节）。

## 站点校验（订单创建二次校验）

`TransportOrderServiceImpl.createSendOrder` 落库前再次校验（不信任前端）：
1. `pickupStationId` / `deliveryStationId` 非空
2. 两站不相同（`SEND_STATIONS_SAME`）
3. 两站存在且未删除（`STATION_NOT_EXISTS`）
4. 两站启用状态 `status=0`（`STATION_DISABLED`，"所选站点已停用，请重新选择"）
5. 当前无额外"货运站点规则"，故无第 5 项约束

创建订单**不强制调用路线预览/高德**（避免每次下单打高德）；仅复用路线缓存（如有）。

## ETA

`DispatchEstimationService.estimatePlan` 的站间行驶时长优先算法返回的路网秒（`segmentDuration`，来自高德），站点作业按 `SERVICE_ACTIONS` 累计 `stopServiceMinutes`；无路网段（手工派单 / `distanceUnit=degree`）回退 Haversine ÷ 均速。方案 `transport_dispatch_plan.route_provider` 记录来源：`AMAP` / `EUCLIDEAN_FALLBACK`，避免把直线估算误当高德真实时长。

## 坐标

- 统一 GCJ-02（业务后端站点经纬度、算法服务、高德三方一致）。
- 业务后端按 `stationId` 查 `StationDO` 取坐标，不信任前端传参。

## 单位

- 全链路统一公里（km）+ 秒/分钟：算法 `distanceUnit=km`、Route Preview `distanceKm`、Java `distanceKm`、数据库 `total_distance`、前端展示 `km`，均不做 degree/÷1000 重复换算。
