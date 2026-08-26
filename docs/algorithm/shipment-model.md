# V2-1：Shipment 数据模型设计

> 日期：2026-08-26

## 1. 当前 Cargo Order 流转审计

### Java → AlgorithmClient → PlanRequest → solver

```
TransportOrderDO
  ↓
DispatchServiceImpl.toAlgorithmOrder()
  ↓
AlgorithmOrderDTO(orderId, orderType, stationId, itemCount)
  ↓
AlgorithmPlanReqDTO.orders[]
  ↓
POST /api/v1/plan
  ↓
PlanRequest.orders[] (PlanOrder)
  ↓
solver.py: nodes[]
```

### 关键发现

| 问题 | 答案 |
|------|------|
| 一个 cargo 订单是否拆成 PICKUP + DELIVERY？ | **否**，只生成一种类型 |
| 是否有相同 orderId/shipmentId？ | **否**，每个 order 独立 |
| 算法能否保证 pickup before delivery？ | **否**，无配对概念 |
| 是否能保证同车？ | **否**，无配对约束 |
| 是否能保证 currentCargoLoad？ | **部分**，CargoOut/CargoIn 各自独立 |

### 当前决策逻辑（DispatchServiceImpl）

```java
if (orderType == PASSENGER) {
    // 客运：上车站 + 下车站
    → AlgorithmOrderDTO(TYPE_PASSENGER, boardingStationId, alightingStationId)
}
else if (deliveryStation == depot) {
    // 揽收：村→场站（收货站为场站）
    → AlgorithmOrderDTO(TYPE_PICKUP, stationId=pickupStationId)
}
else {
    // 派送：场站→村
    → AlgorithmOrderDTO(TYPE_DELIVERY, stationId=deliveryStationId)
}
```

**核心问题：** 一个业务订单只能是 PICKUP 或 DELIVERY，不能同时表达"从 A 揽收 → 送到 B"。

## 2. PlanShipment 设计

### 目标模型

```python
class PlanShipment(BaseModel):
    """完整货运订单：从 pickupStation 揽收 → 送到 deliveryStation。"""
    shipmentId: str                    # 唯一标识
    pickupStationId: str               # 揽收站点
    deliveryStationId: str             # 送达站点
    quantity: int = Field(ge=1)        # 件数
    weightKg: float | None = None      # 重量（算法暂不校验）
    volumeM3: float | None = None      # 体积（算法暂不校验）
```

### 兼容性

保留现有 `PlanOrder` 不变：
- `PlanOrder(orderType=PASSENGER)` → 客运
- `PlanOrder(orderType=DELIVERY)` → 单向派送（场站→站点）
- `PlanOrder(orderType=PICKUP)` → 单向揽收（站点→场站）

新增 `PlanShipment`：
- 表达完整货运（A揽收→B送达）
- Solver 内部展开为 PICKUP + DELIVERY 两个节点

### PlanRequest 扩展

```python
class PlanRequest(BaseModel):
    # ... 现有字段 ...
    orders: list[PlanOrder] = Field(default_factory=list)
    # 新增：完整货运订单
    shipments: list[PlanShipment] = Field(default_factory=list)
```

### Solver 展开逻辑

```python
# 旧 PlanOrder：直接展开
for order in request.orders:
    if order.orderType == OrderType.PASSENGER:
        # BOARD + ALIGHT
    elif order.orderType == OrderType.DELIVERY:
        # DELIVER 节点
    elif order.orderType == OrderType.PICKUP:
        # PICKUP 节点

# 新 PlanShipment：展开为 PICKUP + DELIVERY 配对
for shipment in request.shipments:
    pickup_node = _Node(shipment.pickupStationId, StopAction.PICKUP, shipment.shipmentId)
    delivery_node = _Node(shipment.deliveryStationId, StopAction.DELIVER, shipment.shipmentId)
    # AddPickupAndDelivery(pickup_index, delivery_index)
    # 同车约束
    # 顺序约束
```

## 3. RouteStop 扩展

```python
class RouteStop(BaseModel):
    # ... 现有字段 ...
    # 新增：关联的 shipmentId（仅 shipment 类型的 PICKUP/DELIVER）
    shipmentId: str | None = None
```

## 4. 不破坏现有 API

- `PlanOrder` 保持不变
- `PlanRequest.orders` 保持不变
- 新增 `PlanRequest.shipments`（可选，默认空列表）
- Solver 同时处理 `orders` 和 `shipments`
- 现有 97 个测试不受影响

## 5. 实现计划

| Phase | 内容 | 状态 |
|-------|------|------|
| V2-1 | 数据模型设计 | ✅ 本文档 |
| V2-2 | Pickup/Delivery Pair 约束 | 待实现 |
| V2-3 | CurrentCargoLoad 维度 | 待实现 |
| V2-4 | 有限绕行 | 待实现 |
| V2-5 | Passenger Impact | 待实现 |
