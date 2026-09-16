# 订单状态机测试 (State Machine)

**日期**: 2026-08-25

---

## 1. 状态定义

### TransportOrderStatusEnum

| Code | 名称 | 说明 |
|------|------|------|
| 0 | CREATED | 待调度 — 订单刚创建 |
| 1 | POOLED | 已入池 — 已归集到调度池 |
| 2 | ASSIGNED | 已分配 — 已分配到调度方案 |
| 3 | DEPARTED | 已发车 — 车辆已出发 |
| 4 | COMPLETED | 已完成 — 配送/取货完成 |
| 5 | CANCELLED | 已取消 — 已取消 |

---

## 2. 状态转换图

```
                    ┌──────────────────────────────────────────────────────┐
                    │                                                      │
                    ▼                                                      │
┌─────────┐   collect   ┌─────────┐   plan created   ┌───────────┐        │
│ CREATED │ ──────────→ │ POOLED  │ ───────────────→ │ ASSIGNED  │        │
│   (0)   │             │   (1)   │                  │    (2)    │        │
└────┬────┘             └────┬────┘                  └─────┬─────┘        │
     │                       │                             │              │
     │                       │ plan rejected               │ departure    │
     │                       │ (rollback to POOLED)        │              │
     │                       │ ◄───────────────────────────┘              │
     │                       │                                            │
     │                       ▼                                            │
     │                  ┌───────────┐                                      │
     │                  │ ASSIGNED  │ (re-assigned)                        │
     │                  │    (2)    │                                      │
     │                  └─────┬─────┘                                      │
     │                        │                                            │
     │                        │ depart                                     │
     │                        ▼                                            │
     │                  ┌───────────┐                                      │
     │                  │ DEPARTED  │                                      │
     │                  │    (3)    │                                      │
     │                  └─────┬─────┘                                      │
     │                        │                                            │
     │                        │ deliver / pickupVerify                     │
     │                        ▼                                            │
     │                  ┌───────────┐                                      │
     │                  │ COMPLETED │                                      │
     │                  │    (4)    │                                      │
     │                  └───────────┘                                      │
     │                                                                      │
     │ audit reject                                                         │
     ▼                                                                      │
┌───────────┐                                                              │
│ CANCELLED │ ◄─────────────────────────────────────────────────────────────┘
│    (5)    │                    (audit reject from POOLED)
└───────────┘
```

---

## 3. 合法转换测试

### 3.1 CREATED → POOLED (归集入池)

| 测试 | 方法 | 守卫 | 预期 | 状态 |
|------|------|------|------|------|
| 正常归集 | collectOrders(orderIds) | status==CREATED | 成功 | ✅ |
| 批量归集 | collectOrders(orderIds) | 全部 CREATED | 成功 | ✅ |
| 时间范围归集 | collectOrders(batchStart/End) | status==CREATED | 成功 | ✅ |

**守卫实现**:
- `collectByOrderIds()`: 查询 `IN(ids) AND status=CREATED`，数量不匹配抛异常
- `collectByTimeRange()`: 查询 `status=CREATED AND createTime BETWEEN`

### 3.2 POOLED → ASSIGNED (创建调度方案)

| 测试 | 方法 | 守卫 | 预期 | 状态 |
|------|------|------|------|------|
| 手动派单 | createManualPlan() | validatePooledOrders() | 成功 | ✅ |
| 智能派单 | createSmartPlan() | 查询过滤 POOLED | 成功 | ✅ |

**守卫实现**:
- `validatePooledOrders()`: 检查所有订单 status==POOLED
- `createSmartPlan()`: 只查询 POOLED 状态的订单

### 3.3 ASSIGNED → DEPARTED (发车)

| 测试 | 方法 | 守卫 | 预期 | 状态 |
|------|------|------|------|------|
| 后台发车检查 | departureCheck() | ⚠️ 无显式守卫 | ⚠️ 风险 | ⚠️ |
| 司机发车 | depart() | CAS .eq(status, ASSIGNED) | 成功 | ✅ |

**守卫实现**:
- `departureCheck()`: 无源状态检查，直接 UPDATE → DEPARTED ⚠️
- `depart()`: `WHERE status=ASSIGNED` 作为 CAS 守卫

### 3.4 DEPARTED → COMPLETED (完成)

| 测试 | 方法 | 守卫 | 预期 | 状态 |
|------|------|------|------|------|
| 货物送达 | deliver() | CAS .eq(status, DEPARTED) | 成功 | ✅ |
| 邮件取件验证 | pickupVerify() | CAS + pickup code | 成功 | ✅ |

**守卫实现**:
- `deliver()`: `WHERE status=DEPARTED` 作为 CAS 守卫
- `pickupVerify()`: `WHERE status=DEPARTED` + 取件码验证

### 3.5 CREATED/POOLED → CANCELLED (审核拒绝)

| 测试 | 方法 | 守卫 | 预期 | 状态 |
|------|------|------|------|------|
| 审核拒绝 | audit(reject) | status in {CREATED, POOLED}, auditStatus==0 | 成功 | ✅ |

---

## 4. 非法转换拒绝测试

| 场景 | 操作 | 预期 | 状态 |
|------|------|------|------|
| 已取消订单归集 | collectOrders(CANCELLED) | 拒绝 | ✅ |
| 已完成订单归集 | collectOrders(COMPLETED) | 拒绝 | ✅ |
| 已发车订单归集 | collectOrders(DEPARTED) | 拒绝 | ✅ |
| 已取消订单派单 | createManualPlan(CANCELLED) | 拒绝 | ✅ |
| 已完成订单派单 | createManualPlan(COMPLETED) | 拒绝 | ✅ |
| 重复归集 | collectOrders(POOLED) | 拒绝 | ✅ |
| 重复派单 | createManualPlan(ASSIGNED) | 拒绝 | ✅ |
| 重复发车 | depart(COMPLETED) | 拒绝 | ✅ |
| 重复完成 | deliver(COMPLETED) | 拒绝 | ✅ |

---

## 5. 发现的问题

### 5.1 BUG-003: departureCheck 无源状态守卫

**位置**: DispatchServiceImpl.java:335
**问题**: `departureCheck()` 方法将订单状态更新为 DEPARTED 时，WHERE 子句只过滤 `IN(orderIds)`，不检查当前状态。
**风险**: 并发场景下，如果订单状态已被其他操作改变，UPDATE 仍会成功。
**修复建议**: 添加 `.eq(TransportOrderDO::getStatus, ASSIGNED)` 到 WHERE 子句。

### 5.2 BUG-004: 并发智能派单无乐观锁

**位置**: DispatchServiceImpl.java:280
**问题**: `createSmartPlan()` 查询 POOLED 订单后批量更新为 ASSIGNED，无乐观锁或版本检查。
**风险**: 两个并发调用可能读取同一批订单，创建重复的调度方案。
**修复建议**: 使用版本号或 CAS 更新（`WHERE status=POOLED`）。

---

## 6. 结论

订单状态机整体设计合理，核心转换路径有正确的守卫。主要问题集中在并发安全方面（departureCheck 无守卫、智能派单无乐观锁）。
