-- =====================================================================
-- V035 · 支付订单补 `request_no`：客户端幂等键，让"点两次充值"落回同一行
--
-- 为什么现在要加：`PayService.createPayOrder` 原来每次调用都无条件 `insert` 一行 pending，
--   而真正加余额的只有回调（`handleAlipayNotify` 里 status==1 就幂等返回）。
--   ⇒ 用户重复点击 = 库里多出一堆永远 pending 的脏单，钱只加一次，但脏单要靠人工对账才说得清，
--     而且订单号没有任何唯一约束能证明"这两次是同一笔意图"。有了 (user_id, request_no) 唯一键，
--     代码侧的「先查后插」即使撞上 TOCTOU 窗口也会被库兜住（同 V027 的处置手法）。
--
-- 为什么键里带 user_id：request_no 由客户端生成，只用它建唯一键会让 A 用户生成的串撞掉 B 用户的单。
--
-- 写法沿用 V024/V025/V027/V030/V033/V034：逐列/逐索引查 information_schema + PREPARE/EXECUTE
--   ⇒ **可安全重复执行**。
-- ⚠ 与 V027 不同，这里**不需要先 DELETE 清重复**：新列加进来全是 NULL，而 MySQL 唯一索引里
--   NULL 互不相等 ⇒ 老数据（没传键的调用）不会因为建索引而报错。这也是列取 NULL 默认值、
--   不用 '' 空串的原因：空串会把"同一个用户所有没传键的调用"撞成一条，直接写不进去。
-- ⚠ 三条前置：① 本文件不在 `deploy-dev.yml` / `db-migrate-guarded.yml` 的迁移白名单里 ⇒ 需人工执行；
--   ② 基线 `sql/mysql/transport-schema.sql` 本轮**没动**（工作树里有他人未提交改动，不裹进这一笔），
--      新库同样靠执行本文件补列；③ 未执行前，带 requestNo 的 create-pay-order 会因缺列报错，
--      不传 requestNo 的老调用不受影响（列不存在时 MyBatis 不会引用它）。
-- =====================================================================

SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'transport_pay_order' AND COLUMN_NAME = 'request_no');
SET @ddl := IF(@col_exists = 0,
  'ALTER TABLE `transport_pay_order` ADD COLUMN `request_no` varchar(64) DEFAULT NULL COMMENT ''客户端幂等键（NULL=本次调用不参与幂等；同 (user_id, request_no) 重复调用复用同一笔订单）'' AFTER `order_no`',
  'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @uk_exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'transport_pay_order' AND INDEX_NAME = 'uk_pay_order_user_request');
SET @ddl := IF(@uk_exists = 0,
  'ALTER TABLE `transport_pay_order` ADD UNIQUE KEY `uk_pay_order_user_request` (`user_id`, `request_no`)',
  'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 验收参考 ----------
-- SHOW COLUMNS FROM transport_pay_order LIKE 'request_no';
--   → 期待存在，Type = varchar(64)，Null = YES，Default = NULL
-- SHOW INDEX FROM transport_pay_order WHERE Key_name='uk_pay_order_user_request';
--   → 期待 Non_unique = 0，且 Seq_in_index 为 (user_id=1, request_no=2)
-- 重复执行本文件：两条 SET @ddl 都应落到 'SELECT 1'，不报错。
