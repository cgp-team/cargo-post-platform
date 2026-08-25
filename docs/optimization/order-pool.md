# 订单池

## 门禁
**只有 `READY_FOR_POOL`（承运审核通过）可归集入池 → `POOLED`。**
禁止入池：待审核 / 待客户操作 / 需人工 / 拒运 / 已取消 / 已分配 / 已发车 / 已完成。

## 归集
- 优先 `orderIds`（勾选归集，Phase 2/3）；`batchStart/batchEnd` 仅作兼容 fallback。
- 后端再验证：订单存在（未删除）/ 审核允许 / 状态允许 / 未重复入池。
- **并发防护（P1-004）**：`createSmartPlan` 先 CAS 抢占（`UPDATE ... SET status=ASSIGNED WHERE status=POOLED`，InnoDB 行锁串行化并发派单）；claimed==0 抛池空；失败/无解显式回滚抢占（ASSIGNED→POOLED）。

## 客户替代交接
- CONDITIONAL 订单 `WAITING_CUSTOMER_ACTION` → 小程序「我已送到指定站点，确认入池」（`POST /transport/send/confirm-station-action`）→ `READY_FOR_POOL`。
- 归属校验：仅下单人本人；状态校验：仅待客户操作（CAS 防重复确认）。

## 数据
- 订单池分页（管理端）查询 `IN (READY_FOR_POOL, POOLED)`；管理端文案已统一（待入池 status=8）。
- 错误码：`DISPATCH_ORDER_NOT_COLLECTABLE`（仅待入池可归集）、`SEND_ORDER_STATUS_ILLEGAL`/`SEND_ORDER_NOT_YOURS`。
