# P1-C：Cargo Shipment 模型审计

> 日期：2026-08-26

## 1. 当前 Cargo Order 如何进入算法

### Java 后端 → AlgorithmClient

```
Java PlanOrder(orderType: DELIVERY/PICKUP)
  ↓
AlgorithmClient.convert()
  ↓
API PlanOrder(orderType: "DELIVERY"/"PICKUP")
  ↓
POST /api/v1/plan
  ↓
PlanRequest.orders[]
  ↓
solver.py
```

**关键发现：**
- Java 的 `PlanOrder` 只有 `orderType`（PICKUP/DELIVERY/PASSENGER）
- 没有 `shipmentId`、`pickupStationId`、`deliveryStationId` 配对概念
- 每个 cargo order 是独立的，不与其他 order 关联

### 数据模型

```java
// Java PlanOrder
class PlanOrder {
    String orderId;
    OrderType orderType;  // PASSENGER, DELIVERY, PICKUP
    String boardingStationId;  // 仅 PASSENGER
    String alightingStationId;  // 仅 PASSENGER
    String stationId;  // 仅 DELIVERY/PICKUP
    int itemCount;
}
```

## 2. 回答审计问题

### Q1: 当前是否一个 cargo 订单被拆成 PICKUP + DELIVERY？

**否。** 每个 cargo order 是独立的：
- `orderType=DELIVERY`：从场站派送到站点（出程）
- `orderType=PICKUP`：从站点揽收回到场站（返程）

它们不构成配对关系。

### Q2: 是否有相同 orderId/shipmentId？

**没有 shipmentId。** 当前 API 的 `orderId` 是唯一的：
- 同一 `orderId` 不能同时有 PICKUP 和 DELIVERY
- Java 后端创建 order 时，一个 order 只有一种 orderType

### Q3: 算法能否保证同货物 pickup before delivery？

**不能。** 因为：
1. 没有 shipmentId 配对概念
2. PICKUP 和 DELIVERY 是独立的 order
3. Solver 可以自由安排顺序
4. CargoOut 维度允许 DELIVERY-before-PICKUP（出程派送语义）

### Q4: 是否能保证 pickup/delivery 同车？

**不能。** 因为：
1. 没有配对约束
2. Solver 的 AddPickupAndDelivery 只用于客运订单（BOARD/ALIGHT）
3. 货运 order 没有同车约束

### Q5: 是否能保证 current cargo load 正确？

**部分能。** 当前有两层保障：
- **CargoOut 维度**：DELIVER +itemCount 累计 ≤ cargoCapacity（出程派送）
- **CargoIn 维度**：PICKUP +itemCount 累计 ≤ cargoCapacity（返程揽收）

但没有"真实车上货物量"的跟踪：
- 无法知道 PICKUP 后车上实际有多少货
- 无法验证 DELIVERY 时车上是否有足够的货

## 3. 差距总结

| 能力 | 当前状态 | 目标状态 |
|------|----------|----------|
| 单向 DELIVERY | ✅ 支持 | ✅ 支持 |
| 单向 PICKUP | ✅ 支持 | ✅ 支持 |
| Shipment 配对 | ❌ 不支持 | PICKUP→DELIVERY 同货物 |
| 同车约束 | ❌ 不支持 | 同一 shipmentId 必须同车 |
| 顺序约束 | ❌ 不支持 | PICKUP 在 DELIVERY 之前 |
| currentCargoLoad | ❌ 不支持 | 真实跟踪车上货物量 |

## 4. 未来契约升级建议

```json
{
  "orderId": "TP001",
  "shipmentId": "SHP001",
  "orderType": "SHIPMENT",
  "pickupStationId": "A",
  "deliveryStationId": "B",
  "quantity": 10,
  "weightKg": 50.0,
  "volumeM3": 0.5
}
```

此时算法需要：
1. 为 SHP001 创建两个节点：PICKUP(A) + DELIVERY(B)
2. AddPickupAndDelivery 约束同车
3. 顺序约束：PICKUP 在 DELIVERY 之前
4. currentCargoLoad 从 PICKUP 到 DELIVERY 期间增加
