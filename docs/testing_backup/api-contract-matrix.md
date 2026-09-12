# API 契约矩阵 (API Contract Matrix)

**日期**: 2026-08-25

---

## 1. 寄货流程 API

### 1.1 路线预览

| 层级 | 组件 | 关键字段 |
|------|------|---------|
| 小程序 | pages/send/send.js | originStationId, destStationId |
| HTTP | POST /app-api/transport/send/route-preview | originStationId, destStationId |
| Controller | AppSendController.routePreview() | SendRoutePreviewReqVO |
| Service | AppSendRouteInfoService | 调用算法 /api/v1/route |
| Algorithm | /api/v1/route | stations, distance, duration |

**字段映射**:

| 小程序字段 | Java DTO | Python Model | 类型 | 单位 |
|-----------|----------|-------------|------|------|
| originStationId | originStationId | - | Long | - |
| destStationId | destStationId | - | Long | - |
| distance | distance | distance | Double | km |
| duration | duration | duration | Double | 秒 |
| provider | provider | provider | String | - |

### 1.2 创建订单

| 层级 | 组件 | 关键字段 |
|------|------|---------|
| 小程序 | pages/send/send.js | orderType, originStationId, destStationId |
| HTTP | POST /app-api/transport/order/create | TransportOrderCreateReqVO |
| Controller | AppSendController / TransportOrderController | - |
| Service | TransportOrderServiceImpl | - |
| Database | transport_order | - |

### 1.3 订单列表

| 层级 | 组件 | 关键字段 |
|------|------|---------|
| 小程序 | pages/orders/orders.js | status, pageNo, pageSize |
| HTTP | GET /app-api/transport/order/page | status, pageNo, pageSize |
| Controller | TransportOrderController | - |

---

## 2. 调度流程 API

### 2.1 归集入池

| 层级 | 组件 | 关键字段 |
|------|------|---------|
| 管理后台 | views/dispatch/ | orderIds / batchStart, batchEnd |
| HTTP | POST /admin-api/transport/dispatch/order-pool/collect | DispatchCollectReqVO |
| Controller | DispatchController | - |
| Service | DispatchServiceImpl.collectOrders() | - |

**字段映射**:

| 前端字段 | Java DTO | 类型 | 说明 |
|---------|----------|------|------|
| orderIds | orderIds | List<Long> | PR#90 新增，优先使用 |
| batchStart | batchStart | LocalDateTime | 时间范围 fallback |
| batchEnd | batchEnd | LocalDateTime | 时间范围 fallback |

**时间格式契约**:
- 前端发送: 毫秒时间戳 (number)
- 后端接收: LocalDateTime (Jackson 全局配置时间戳序列化)
- 验证: ✅ 一致

### 2.2 智能派单

| 层级 | 组件 | 关键字段 |
|------|------|---------|
| 管理后台 | views/dispatch/ | vehicleIds |
| HTTP | POST /admin-api/transport/dispatch/plan/smart | DispatchSmartPlanReqVO |
| Service | DispatchServiceImpl.createSmartPlan() | - |
| Algorithm | POST /api/v1/plan | stations, orders, vehicles |

### 2.3 手动派单

| 层级 | 组件 | 关键字段 |
|------|------|---------|
| HTTP | POST /admin-api/transport/dispatch/plan/manual | DispatchManualPlanReqVO |
| Service | DispatchServiceImpl.createManualPlan() | - |

---

## 3. 附近公交 API

| 层级 | 组件 | 关键字段 |
|------|------|---------|
| 小程序 | pages/bus/index.js | latitude, longitude, radius |
| HTTP | GET /app-api/transport/bus/nearby | latitude, longitude, radius |
| Controller | AppBusController | - |
| Service | AppBusServiceImpl | - |

**字段映射**:

| 小程序字段 | Java DTO | 类型 | 说明 |
|-----------|----------|------|------|
| latitude | latitude | Double | WGS84 纬度 |
| longitude | longitude | Double | WGS84 经度 |
| radius | radius | Integer | 搜索半径(米) |

**null 检查**: ✅ 小程序端有 undefined 检查，不会发送 undefined 参数

---

## 4. 关键字段类型检查

| 字段 | 小程序 | Java | Python | 一致性 |
|------|--------|------|--------|--------|
| orderId | number/string | Long | - | ⚠️ 需确认 |
| stationId | number | Long | int | ✅ |
| vehicleId | number | Long | int | ✅ |
| latitude | number | Double | float | ✅ |
| longitude | number | Double | float | ✅ |
| distance | number | Double | float | ✅ |
| distanceKm | number | Double | float | ✅ |
| duration | number | Double | float | ✅ |
| durationSeconds | number | Long | float | ⚠️ BUG-001 |
| provider | string | String | str | ✅ |
| available | boolean | Boolean | bool | ✅ |

---

## 5. 时间格式检查

| 场景 | 格式 | 一致性 |
|------|------|--------|
| 归集入池 batchStart/End | 毫秒时间戳 | ✅ |
| 订单创建时间 | 毫秒时间戳 | ✅ |
| 算法请求时间 | ISO 8601 +08:00 | ✅ |
| 高德 ETA | 秒 (number) | ✅ |

---

## 6. 结论

主要契约问题:
1. **BUG-001**: segmentDuration Long vs float (P1)
2. **orderId 类型**: 小程序可能发送 string，需确认后端处理
3. **时间格式**: 已统一为毫秒时间戳
