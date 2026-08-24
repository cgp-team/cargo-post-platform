# 实时公交链路现状审计（第二阶段）

> 目标：把"全局实时公交"改造成"附近实时公交"。审计结论供 nearby API 设计依据。

## 一、现状结论（对方案 8 问的逐条回答）

| # | 问题 | 结论 |
|---|---|---|
| 1 | 当前公交接口返回什么 | `GET /app-api/transport/bus/realtime` → `AppBusRespVO[]`（busId/plateNo/shiftCode/routeName/startStation/endStation/**status(0/1 数字)**/nextStation/longitude/latitude/etaMinutes/progress/speedKmh）；`GET /bus/lines` → 线路 + 经停点 + 在线车辆（车来了式） |
| 2 | 是否返回全部车辆 | **是但失真**：`MonitoringServiceImpl.getRealtimeVehicles()` 遍历全部车辆；`AppBusServiceImpl` 再 `filter(shiftCode != null)` → **只有模拟排班车辆进入列表，真实上报车辆（无 shiftCode）被过滤** |
| 3 | 车辆有没有经纬度 | 模拟车辆：`fillPosition` 按班次计划时间线性插值出 lat/lon；真实上报车辆：`realLocation` 分支有 lat/lon，但**无 shiftCode/routeName/nextStation** |
| 4 | nextStation 从哪来 | 模拟：`fillPosition` 按已行驶分钟在经停点区间线性插值，`nextStationName`=区间终点（下一站）；真实上报车辆：无；首页 `loadBusData` 映射时无则显示"—" |
| 5 | route 与 vehicle 怎么关联 | 经 shift：模拟排班把启用班次按发车时间轮转分配给可用车辆 → `shift.routeId` → route。真实上报车辆未回填 shift→route 关联 |
| 6 | 模拟车辆什么时候出现 | 非停用 + 无 5 分钟真实上报 + `selectCurrentShift` 命中（在途窗口 / 下一班待发 / 当天末班）→ 模拟插值 |
| 7 | 真实车辆位置什么时候出现 | 司机端 `POST /driver/location` 写 `transport_vehicle_location`（每车一行 upsert），`selectRecent(5min)` 有效 → 真实位置优先；**但被公交列表过滤，不对外** |
| 8 | 首页为什么显示 Demo | `index.js` `data.nearbyBuses` **初始硬编码 C302/C101/C202 + isDemo:true**；`loadBusData` 失败静默保留 Demo，成功才有真实数据替换 |

## 二、当前调用链（首页）

```
index.onShow
  └─ loadBusData()
       ├─ api.getRealtimeBuses() → /transport/bus/realtime（全局，不按用户位置）
       │     └─ AppBusService.getRealtimeBuses → MonitoringService.getRealtimeVehicles
       │           ├─ 全部车辆 → 停用排除 → 真实上报(5min)优先(但无shiftCode被过滤)
       │           └─ 模拟插值(shiftCode+routeName+nextStation+lat/lon)
       ├─ 有数据 → setData nearbyBuses（status 数字→前端猜 running/arrived）
       └─ 失败/空 → 保留硬编码 Demo（isDemo:true）
```

## 三、关键问题（本阶段要修的）

1. **真实上报车辆被过滤**：`getRealtimeVehicles` 真实位置分支不填 `shiftCode`，`AppBusServiceImpl.filter(shiftCode != null)` 把真实车辆剔除 → **公交列表实际全是模拟插值**。这与方案"真实车辆优先"相反。
2. **首页硬编码 Demo**：C302/C101/C202 是静态假数据，接口失败静默保留，用户看到假公交。
3. **状态用数字**（0 空闲/1 在途/2 停用），前端自己猜 `running/arrived`；无统一字符串状态（RUNNING/IDLE/ARRIVED/NO_LOCATION）。
4. **nextStation 失真**：模拟车辆有（插值），真实车辆无，首页显示"—"。
5. **全局返回**：不按用户位置过滤，无 radius/附近概念。
6. **无 dataSource 标记**：无法区分 REAL/SIMULATED。

## 四、可复用能力（不重复造）

- `MonitoringServiceImpl.getRealtimeVehicles()`：已实现"真实上报 5min 优先 + 模拟插值"——本阶段保留，改造为**对真实上报车辆补 shiftCode/route/nextStation 关联** + 返回 dataSource。
- `MonitoringServiceImpl.fillPosition`：站点区间线性插值 nextStation/进度。
- `GeoDistanceUtil.haversineKm`：附近直线过滤（方案第三：先用 Haversine，不调高德）。
- `bus/index.js` 已有 15s 定时刷新（`REFRESH_MS=15000`）+ onHide/onUnload stopTimer——首页刷新机制参照此模式。
- `AppBusRespVO` 已有字段（lat/lon/shiftCode/routeName）——nearby 复用扩展。

## 五、涉及文件（本阶段将改动/新增）

- 后端：`AppBusController`（+`/nearby`）、`AppBusService/Impl`（+getNearbyBuses）、新 VO `AppBusNearbyRespVO`、可能 `MonitoringServiceImpl` 小幅调整（真实车辆补关联，**不大改**）
- 前端：`utils/api.js`（+getNearbyRealtimeBuses）、`pages/index/index.js`（loadNearbyBusData + 刷新 + 去 Demo）、`index.wxml`（公交卡/空态/错误态）、`index.wxss`
- 测试：后端单测（nearby）、前端 node --check
- 文档：`docs/realtime-bus-current-audit.md`（本文件）
- 不动：`/bus/realtime`、`/bus/lines`（保留，bus 页不坏）、司机 GPS 上报、算法、高德、Redis
