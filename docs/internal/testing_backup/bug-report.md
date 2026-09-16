# Bug 报告 (Bug Report)

**日期**: 2026-08-25
**分支**: fix/collect-orders

---

## BUG-001: segmentDuration 类型不匹配导致高德 API 反序列化失败

| 字段 | 内容 |
|------|------|
| **TEST ID** | BUG-001 |
| **Priority** | P1 |
| **Precondition** | 配置了 AMAP_KEY，算法使用高德路网距离 |
| **Steps** | 配置 AMAP_KEY → 启动算法 → 触发智能派单 → 算法返回 segmentDuration |
| **Expected** | Java 正常解析 segmentDuration |
| **Actual** | Python 返回 120.0 (float)，Jackson 反序列化 Long 失败 |
| **Relevant Code** | AlgorithmRouteStopDTO.java:36, algorithm/app/models.py:103 |
| **Root Cause** | Java Long vs Python float |
| **Fix** | 改为 Double 或启用 ACCEPT_FLOAT_AS_INT |

---

## BUG-002: 算法结果校验器容量语义不一致

| 字段 | 内容 |
|------|------|
| **TEST ID** | BUG-002 |
| **Priority** | P1 |
| **Relevant Code** | AlgorithmResultValidator.java:155-158 |
| **Root Cause** | 校验器滑动窗口 vs 求解器累计容量 |
| **Fix** | 移除 DELIVER 时 cargo 减少 |

---

## BUG-003: departureCheck 无源状态守卫

| 字段 | 内容 |
|------|------|
| **TEST ID** | BUG-003 |
| **Priority** | P1 |
| **Relevant Code** | DispatchServiceImpl.java:335 |
| **Root Cause** | updateOrdersStatus() WHERE 无状态过滤 |
| **Fix** | 添加 .eq(status, ASSIGNED) |

---

## BUG-004: 并发智能派单无乐观锁

| 字段 | 内容 |
|------|------|
| **TEST ID** | BUG-004 |
| **Priority** | P1 |
| **Relevant Code** | DispatchServiceImpl.java:280 |
| **Root Cause** | 查询和更新无原子性 |
| **Fix** | CAS UPDATE WHERE status=POOLED |

---

## BUG-005: collectByTimeRange 静默跳过未审核货物订单

| 字段 | 内容 |
|------|------|
| **TEST ID** | BUG-005 |
| **Priority** | P2 |
| **Relevant Code** | DispatchServiceImpl.java:collectByTimeRange() |
| **Root Cause** | 两条路径错误处理不一致 |
| **Fix** | 统一行为 |

---

## 汇总

| 优先级 | 数量 |
|--------|------|
| P0 | 0 |
| P1 | 4 |
| P2 | 1 |
