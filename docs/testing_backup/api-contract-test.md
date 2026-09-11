# 接口契约测试（MiniProgram → Backend → Algorithm）

> 验证跨端字段名/类型/单位/可空。线上地址 `http://1.15.29.107/api`（nginx 去 `/api` 前缀转发到后端 `/app-api`）。
> 登录 token：`POST /api/app-api/member/auth/sms-login`（验证码 9999）。

## 一、附近公交 `GET /app-api/transport/bus/nearby`

**请求**：`?latitude=30.xxx&longitude=104.xxx&radius=5000` 或 `?district=青山镇`

```bash
curl -s "http://1.15.29.107/api/app-api/transport/bus/nearby?latitude=30.5723&longitude=104.0657&radius=5000"
```

**响应契约断言**：
```json
{
  "located": true,
  "locationLevel": "PRECISE",
  "dataSource": "REAL|SIMULATED|MIXED|NONE",
  "nearbyStations": [{"id":2,"name":"红花村站","longitude":104.12,"latitude":30.6,"distanceKm":0.8}],
  "nearestStation": {"id":2,"name":"红花村站","longitude":104.12,"latitude":30.6,"distanceKm":0.8},
  "buses": [{
    "busId": 1, "routeName": "R001", "shiftCode": "SH001",
    "status": "RUNNING|IDLE|ARRIVED|NO_LOCATION",
    "nextStation": "青山镇站",
    "longitude": 104.13, "latitude": 30.61,
    "dataSource": "REAL|SIMULATED",
    "locationSource": "REAL_FRESH|SIMULATED|NO_LOCATION",
    "distanceToNextStationKm": 2.8, "etaMinutes": 6,
    "routeProvider": "AMAP|EUCLIDEAN",
    "lastLocationTime": "2026-08-24T12:00:00", "updatedAt": "2026-08-24T12:00:01",
    "distanceKm": 0.9
  }]
}
```
- `latitude/longitude`：number（度，GCJ-02），可空（无精确定位）
- `distanceToNextStationKm/distanceKm`：number（km）
- `etaMinutes`：integer（分钟）
- `status/locationSource/dataSource/routeProvider`：字符串枚举，不可空（status/locationSource/dataSource），routeProvider 可空（无 ETA 时）

## 二、寄货路线预览 `POST /app-api/transport/send/route-preview`

**请求**：`{"pickupStationId":2,"deliveryStationId":1}`
```bash
curl -s -X POST "http://1.15.29.107/api/app-api/transport/send/route-preview" \
  -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
  -d '{"pickupStationId":2,"deliveryStationId":1}'
```
**响应**：`{"available":true,"distanceKm":2.8,"durationMinutes":6,"provider":"amap|euclidean","warning":null}`
- `available`：boolean（不可空）
- `distanceKm`：number km（可空）；`durationMinutes`：integer（可空）；`provider`：string（不可空）；`warning`：string（可空）
- 校验错误：`{"code":1005010007,"msg":"取货站点和送达站点不能相同"}` / `1005001002 站点已停用`

## 三、创建寄货订单 `POST /app-api/transport/send/create`

**请求**：`{"pickupStationId":2,"deliveryStationId":1,"goodsName":"...","goodsWeight":2.5,...}`
**响应**：`{"code":0,"data":{"orderNo":"TP...","status":0,...}}`
- 后端二次校验：站点非空/不相同/存在/启用，不信任前端

## 四、实时公交 `GET /app-api/transport/bus/realtime` / `lines`

- `realtime`：`[{busId,plateNo,shiftCode,routeName,startStation,endStation,status(0/1 数字!),nextStation,longitude,latitude,etaMinutes,progress,speedKmh}]` —— 注意 status 为**数字 0/1**（与 nearby 的字符串枚举不一致，历史接口保留）
- `lines`：线路 + 经停点 + 该线在线车辆

## 五、算法服务 `POST /api/v1/route` / `/api/v1/distance`

**route 请求**：`{"origin":{"latitude":30.57,"longitude":104.06},"destination":{"latitude":30.60,"longitude":104.12}}`
**route 响应**：`{"available":true,"distanceKm":2.8,"durationSeconds":360,"provider":"amap|euclidean","reasonCode":null}`
- 非法坐标（lat 超范围）→ 422
- 不可达 → `{"available":false,"provider":"amap","reasonCode":"ROUTE_UNAVAILABLE"}`

**distance 请求/响应**：`{requestId, stations:[{stationId,longitude,latitude}]}` → `{distanceUnit:"km", pairs:[{fromStationId,toStationId,distanceKm,durationSeconds,provider,available}]}`

## 六、契约风险点（审计）
1. **status 类型不一致**：`/bus/realtime` 返回数字 0/1，`/bus/nearby` 返回字符串 RUNNING/IDLE——两套并存，前端需区分来源。
2. **master `nearby` 无精确定位传 `undefined`** → 后端 400（P1，见 bug-report）。
3. **`lastLocationTime`**：仅 REAL 车辆有；SIMULATED 为 null。
4. **单位**：全链路距离 km、时长（nearby=分钟、route/distance=秒），前端换算在 `fillEta`（秒→分钟向上取整）完成，无重复换算。
