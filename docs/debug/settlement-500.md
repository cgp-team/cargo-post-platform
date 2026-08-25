# settlement 500 问题分析

**日期**: 2026-08-25
**接口**: `GET /admin-api/transport/dispatch/settlement`

---

## 现象

- HTTP 200，Response: `{"code": 500, "msg": "系统异常"}`
- 浏览器 Console: `Uncaught (in promise) Error: 系统异常`
- Dashboard 其他接口正常:
  - `/transport/dashboard/statistics` → 200 ✓
  - `/transport/dashboard/summary` → 200 ✓
  - `/transport/dashboard/order-statistics` → 200 ✓

---

## 静态审计：settlement 调用链

```
DispatchController.settlement(DispatchSettlementReqVO)
  ↓
DispatchServiceImpl.settlement(DispatchSettlementReqVO)
  ├─ 校验 batchStart/batchEnd 非空且 start < end
  ├─ dispatchPlanMapper.selectList(...)          ← SELECT * FROM transport_dispatch_plan
  ├─ dispatchPlanItemMapper.selectList(...)      ← SELECT * FROM transport_dispatch_plan_item ⚠️
  ├─ orderMapper.selectBatchIds(orderIds)        ← SELECT * FROM transport_order WHERE id IN (...)
  ├─ preloadPassengerOrders(itemOrders)          ← SELECT * FROM transport_passenger_order
  ├─ preloadCargoOrders(itemOrders)              ← SELECT * FROM transport_cargo_order ⚠️
  ├─ preloadPostalOrders(itemOrders)             ← SELECT * FROM transport_postal_order
  ├─ 统计乘客/包裹数
  ├─ vehicleMapper.selectBatchIds(...)           ← SELECT * FROM transport_vehicle
  └─ 组装 perVehicle 返回
```

### settlement 中的 SELECT * 清单

| 行号 | Mapper | 表 | 操作 |
|------|--------|---|------|
| L508 | dispatchPlanMapper | transport_dispatch_plan | selectList → SELECT * |
| L530 | dispatchPlanItemMapper | transport_dispatch_plan_item | selectList → SELECT * |
| L540 | orderMapper | transport_order | selectBatchIds → SELECT * WHERE id IN |
| L550 | passengerOrderMapper | transport_passenger_order | selectList → SELECT * |
| L551 | cargoOrderMapper | transport_cargo_order | selectList → SELECT * |
| L552 | postalOrderMapper | transport_postal_order | selectList → SELECT * |
| L586 | vehicleMapper | transport_vehicle | selectBatchIds → SELECT * WHERE id IN |

### Dashboard 不触发问题的原因

Dashboard 三个接口全部用 `selectCount(null)` 或显式 `select("col1", "col2")`，**从不做 SELECT ***:

```java
// statistics() — 只做 COUNT(*)
vehicleMapper.selectCount(null)

// order-statistics() — 显式指定列
transportOrderMapper.selectMaps(new QueryWrapper<>().select("order_type AS type", "COUNT(*) AS count"))
```

---

## 高概率原因

线上数据库 schema 可能落后于当前 Java DO 定义。

Phase 0-14 新增了 22 个字段，分布在 3 张表:

| 表 | 新增列数 | 来源 |
|---|---------|------|
| transport_dispatch_plan | 6 | V010 + V014 |
| transport_dispatch_plan_item | 11 | V012 + V014 + V015 |
| transport_cargo_order | 5 | V013 |

增量迁移文件（V012-V015）均标注为"人工执行入口":
> "非破坏性 DDL 唯一维护在 sql/mysql/transport-schema.sql，本文件只做人工执行入口。
> 已有库执行本文件后，若表已存在（老库），请人工执行文件内注释的 ALTER"

deploy workflow 只做 build + deploy，不执行 SQL 迁移。

---

## 待确认

**当前为高概率推测，待服务器 schema 检查确认。**

需要在生产数据库执行 `production-schema-check.sql`，检查关键列是否存在。

---

## 异常传播路径

```
MyBatis Plus selectList → 列不存在 → BadSqlGrammarException
  ↓
GlobalExceptionHandler 捕获未声明 ServiceException 的 Exception
  ↓
返回 CommonResult: code=500, msg="系统异常"
```

GlobalExceptionHandler 对未捕获 Exception 返回 code=500/msg=系统异常，
所以必须结合服务器日志确认真正异常类型和堆栈。

---

## 前端容错修复

已在 `src/views/transport/dashboard/index.vue` 增加 settlement 独立 try-catch:

- `loadSettlement()` 独立捕获异常
- 失败时 `settlement.value = undefined`，`settleFailed.value = true`
- 模板通过 `v-if="settleFailed"` 显示 `el-empty` 空状态
- 其他 Dashboard 数据不受影响
- `loadAll()` 保持不变
