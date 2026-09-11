# settlement 500 静态根因分析

**日期**: 2026-08-25
**接口**: `GET /admin-api/transport/dispatch/settlement`
**现象**: HTTP 200, `{"code": 500, "msg": "系统异常"}`
**确认正常**: Dashboard 三个接口 (`/statistics`, `/summary`, `/order-statistics`) 均 200

---

## 调用链逐行分析

```
DispatchController.settlement(DispatchSettlementReqVO)          // L94-99
  ↓ dispatchService.settlement(reqVO)
DispatchServiceImpl.settlement(DispatchSettlementReqVO)         // L502-608
  ├─ L503: 校验 batchStart/batchEnd 非空且 start < end
  ├─ L508: dispatchPlanMapper.selectList(status IN (2,3) + createTime BETWEEN)  ← SELECT * FROM transport_dispatch_plan
  ├─ L530: dispatchPlanItemMapper.selectList(planId IN (...))   ← SELECT * FROM transport_dispatch_plan_item ⚠️
  ├─ L540: orderMapper.selectBatchIds(orderIds)                 ← SELECT * FROM transport_order WHERE id IN (...)
  ├─ L550: preloadPassengerOrders(itemOrders)                   ← SELECT * FROM transport_passenger_order WHERE order_id IN (...)
  ├─ L551: preloadCargoOrders(itemOrders)                       ← SELECT * FROM transport_cargo_order WHERE order_id IN (...) ⚠️
  ├─ L552: preloadPostalOrders(itemOrders)                      ← SELECT * FROM transport_postal_order WHERE order_id IN (...)
  ├─ L566-582: 统计乘客/包裹数
  ├─ L586: vehicleMapper.selectBatchIds(vehiclePlans.keySet())  ← SELECT * FROM transport_vehicle WHERE id IN (...)
  └─ L589-599: 组装 perVehicle
```

---

## 根因 #1（最可能）: 数据库未执行 V012-V015 迁移 DDL

### 证据

1. **增量 SQL 注释明确标注为"人工执行入口"**:
   - V012: `"非破坏性 DDL 唯一维护在 sql/mysql/transport-schema.sql，本文件只做人工执行入口。已有库执行本文件后，若表已存在（老库），请人工执行文件内注释的 ALTER"`
   - V013、V014、V015 同样措辞
2. **Deploy workflow 只做 build + deploy**，不执行 SQL 迁移
3. **Dashboard 不触发问题的原因** — 三个接口全部用 `selectCount(null)` 或显式 `select("col1", "col2")`，**从不做 `SELECT *`**:
   ```java
   // DashboardController.statistics() — L52
   vehicleMapper.selectCount(null)  // SELECT COUNT(*) FROM transport_vehicle
   // DashboardController.orderStatistics() — L91
   transportOrderMapper.selectMaps(new QueryWrapper<>().select("order_type AS type", "COUNT(*) AS count"))
   ```
   而 settlement 调用 `selectList(new LambdaQueryWrapperX<>())` → **`SELECT *`**。

### 新增列清单（按表）

| 表 | 新增列 | 来源 |
|---|---|---|
| `transport_dispatch_plan` | `est_duration_minutes`, `est_revenue`, `est_cost`, `routeProvider`, `task_window_start`, `task_window_end` | V010 + V014 |
| `transport_dispatch_plan_item` | `segment_duration_seconds`, `segment_distance_km`, `planned_departure_time`, `service_duration_seconds`, `quantity`, `status`, `service_mode`, `service_point_station_id`, `detour_distance_km`, `detour_duration_seconds`, `reason_code` | V012 + V014 + V015 |
| `transport_cargo_order` | `review_status`, `review_reason_codes`, `pickup_service_mode`, `delivery_service_mode`, `service_point_station_id` | V013 |

### 触发条件

- 生产数据库是**老库**（在 V012-V015 之前创建），且从未手动执行过 ALTER TABLE
- settlement 是第一个对这些表做 `SELECT *` 的已部署接口

### 如何确认

```sql
-- 在生产数据库执行
SHOW COLUMNS FROM transport_dispatch_plan_item LIKE 'segment_duration_seconds';
SHOW COLUMNS FROM transport_cargo_order LIKE 'review_status';
SHOW COLUMNS FROM transport_dispatch_plan LIKE 'task_window_start';
-- 如果返回 Empty set，则确认缺失
```

### 最小修复方案

在生产数据库执行 V012-V015 中注释的 ALTER TABLE 语句:

```sql
-- V012
ALTER TABLE `transport_dispatch_plan_item`
  ADD COLUMN `segment_duration_seconds` int DEFAULT NULL AFTER `estimated_arrival_time`,
  ADD COLUMN `segment_distance_km` decimal(12,3) DEFAULT NULL AFTER `segment_duration_seconds`;

-- V014
ALTER TABLE `transport_dispatch_plan`
  ADD COLUMN `task_window_start` datetime DEFAULT NULL AFTER `approved_time`,
  ADD COLUMN `task_window_end` datetime DEFAULT NULL AFTER `task_window_start`;
ALTER TABLE `transport_dispatch_plan_item`
  ADD COLUMN `planned_departure_time` datetime DEFAULT NULL AFTER `segment_distance_km`,
  ADD COLUMN `service_duration_seconds` int DEFAULT NULL AFTER `planned_departure_time`,
  ADD COLUMN `quantity` int DEFAULT NULL AFTER `service_duration_seconds`,
  ADD COLUMN `status` tinyint NOT NULL DEFAULT 0 AFTER `quantity`;

-- V015
ALTER TABLE `transport_dispatch_plan_item`
  ADD COLUMN `service_mode` varchar(32) NOT NULL DEFAULT '' AFTER `status`,
  ADD COLUMN `service_point_station_id` bigint DEFAULT NULL AFTER `service_mode`,
  ADD COLUMN `detour_distance_km` decimal(12,3) DEFAULT NULL AFTER `service_point_station_id`,
  ADD COLUMN `detour_duration_seconds` int DEFAULT NULL AFTER `detour_distance_km`,
  ADD COLUMN `reason_code` varchar(64) DEFAULT NULL AFTER `detour_duration_seconds`;

-- V013
ALTER TABLE `transport_cargo_order`
  ADD COLUMN `review_status` tinyint NOT NULL DEFAULT 0 AFTER `reject_reason`,
  ADD COLUMN `review_reason_codes` varchar(255) NOT NULL DEFAULT '' AFTER `review_status`,
  ADD COLUMN `pickup_service_mode` varchar(32) NOT NULL DEFAULT '' AFTER `review_reason_codes`,
  ADD COLUMN `delivery_service_mode` varchar(32) NOT NULL DEFAULT '' AFTER `pickup_service_mode`,
  ADD COLUMN `service_point_station_id` bigint DEFAULT NULL AFTER `delivery_service_mode`;
```

或者直接执行 `SOURCE sql/mysql/transport-schema.sql;`（`CREATE TABLE IF NOT EXISTS` 不会破坏已有数据，但也不会加新列——必须用 ALTER）。

### 回归测试

1. 执行 ALTER 后，重新调用 `GET /admin-api/transport/dispatch/settlement?batchStart=2026-08-01 00:00:00&batchEnd=2026-08-26 00:00:00`
2. 确认返回 `{"code": 0, "data": {...}}`
3. 验证 Dashboard 三个接口仍然正常
4. 验证调度创建/审核流程不受影响

---

## 根因 #2: DispatchPlanItemDO.vehicleId 为 NULL 导致 NPE

### 代码位置

`DispatchServiceImpl.java` L567-571:

```java
for (TransportOrderDO order : orderMap.values()) {
    Long vehicleId = orderVehicleMap.get(order.getId());  // 可能为 null
    if (Objects.equals(order.getOrderType(), 1)) {
        int count = getPassengerCount(order, passengerMap);
        passengerCount += count;
        vehiclePassenger.merge(vehicleId, count, Integer::sum);  // null key → HashMap 允许，但语义错误
    } else {
        int count = getItemCount(order, cargoMap, postalMap);
        parcelCount += count;
        vehicleParcel.merge(vehicleId, count, Integer::sum);  // null key → 同上
    }
}
```

### 触发条件

`plan_item.order_id` 存在于 `orderVehicleMap`（即 plan_item 有 order_id），但该 plan_item 的 `vehicle_id` 为 NULL。

这在正常业务中不应出现（有订单的经停明细一定有车辆），但如果有脏数据：

```sql
SELECT * FROM transport_dispatch_plan_item WHERE order_id IS NOT NULL AND vehicle_id IS NULL;
```

如果返回行，则存在此问题。

### 实际影响分析

**不会导致 500**，但会导致数据不一致:
- `vehiclePassenger.merge(null, count, Integer::sum)` 在 HashMap 中创建 null-key 条目
- 后续 `vehiclePlans` 只在 `vehicleId != null && planId != null` 时才填充（L562-564）
- 最终迭代 `vehiclePlans.entrySet()` 时 null-key 条目不会被访问
- 结果: passengerCount/parcelCount 有值，但 perVehicle 中缺少对应车辆的统计

**结论**: 数据质量问题，但不是 500 的根因。

---

## 根因 #3: order_type 不在 {1, 2, 3} 范围导致子表查询遗漏

### 代码位置

`DispatchServiceImpl.java` L867-892 (preloadCargoOrders / preloadPostalOrders):

```java
// preloadCargoOrders: 过滤 orderType == 2
List<Long> orderIds = orders.stream()
    .filter(order -> Objects.equals(order.getOrderType(), 2))
    .map(TransportOrderDO::getId).toList();

// preloadPostalOrders: 过滤 orderType == 3
List<Long> orderIds = orders.stream()
    .filter(order -> Objects.equals(order.getOrderType(), 3))
    .map(TransportOrderDO::getId).toList();
```

### 触发条件

`transport_order.order_type` 不是 1/2/3（比如 NULL、0、或其他值）。

### 实际影响分析

**不会导致 500**:
- `getItemCount()` (L907-918) 对未知 orderType 返回默认值 1
- `getPassengerCount()` (L895-898) 对缺失的子表记录返回默认值 1

```java
private int getItemCount(TransportOrderDO order, ...) {
    Integer itemCount = null;
    if (Objects.equals(order.getOrderType(), 2)) { ... }
    else if (Objects.equals(order.getOrderType(), 3)) { ... }
    return itemCount != null ? itemCount : 1;  // 默认 1
}
```

**结论**: 不是 500 的根因。

---

## 逐项排查汇总

| # | 检查项 | 结论 |
|---|---|---|
| 1 | NPE 风险 | vehicleId 可能为 null 但 HashMap 允许 null key，不会 NPE |
| 2 | MyBatis/SQL 异常 | **根因 #1**: `SELECT *` 查询缺少列的表 → BadSqlGrammarException |
| 3 | 历史数据 null 字段 | 新增列都有 DEFAULT，不会因 null 导致映射失败 |
| 4 | 枚举/状态不一致 | status IN (2,3) 查询只过滤已有数据，不会因枚举不匹配报错 |
| 5 | 新版本才增加的字段 | V012-V015 新增 22 个列，全部在 DO 中声明但数据库可能未加 |
| 6 | plan_item.order_id 对应订单不存在 | `selectBatchIds` 静默跳过不存在的 id，不会报错 |
| 7 | orderType 不符合 1/2/3 | `getItemCount` 有默认值兜底，不会报错 |
| 8 | vehicleId 为 null | HashMap 允许 null key，不会 NPE |
| 9 | 订单子表缺失 | `getPassengerCount`/`getItemCount` 有默认值兜底 |
| 10 | 数据库迁移不完整 | **根因 #1**: 增量 SQL 标注"人工执行"，deploy 不执行 DDL |

---

## 结论

**最可能根因**: 生产数据库未执行 V012-V015 的 ALTER TABLE 语句。

**证据链**:
1. 增量 SQL 文件明确标注 "人工执行入口"
2. deploy workflow 只 build + deploy，不跑 DDL
3. Dashboard 不做 `SELECT *`，所以不受影响
4. settlement 做 `SELECT *` on `transport_dispatch_plan_item` + `transport_cargo_order`，这两个表有最多新增列
5. MyBatis Plus 在 `selectList` 时将 DO 声明的字段映射到结果集，列不存在 → `BadSqlGrammarException` → 全局异常处理返回 500

**验证步骤**:
1. SSH 到生产数据库，执行 `SHOW COLUMNS FROM transport_dispatch_plan_item LIKE 'segment_duration_seconds'`
2. 如果返回 Empty set → 确认根因
3. 执行上方 ALTER TABLE 语句
4. 重新调用 settlement 接口验证
