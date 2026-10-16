-- =====================================================================
-- V030 · V029 的**幂等（可重复执行）**形态：真实执行回流的两列 + 本批未派发货运行留痕一列。
--
-- 为什么要多这一支，而不是直接把 V029 丢进自动迁移：
--   V029 (`V029__execution_writeback.sql:20-26`) 是两段**裸 `ALTER TABLE ADD COLUMN`**，
--   没有存在性判断 ⇒ 第二次执行必然 `Duplicate column name` 报错。而部署流水线里那条
--   `Run database migration` 与 `执行幂等迁移` 两个步骤（`.github/workflows/deploy-dev.yml`、
--   `.github/workflows/db-migrate-guarded.yml`，按步骤名认，别锚行号）会把白名单里的脚本**反复重放**
--   （deploy-dev 在仓库变量 AUTO_DB_MIGRATE=1 时整段重放；guarded 每次 mode=apply 也都重跑本文件）
--   ⇒ 裸 ALTER 一旦进白名单，
--   第二次部署就会把整条流水线红掉（红在迁移步，后面 jar 重启与发布都不会发生）。
--   所以进白名单的版本必须逐列判存在性——沿用本仓已有的写法（`V024__key_indexes_and_unique_keys.sql:11-16`
--   的 `information_schema` + PREPARE/EXECUTE 形状，V024/V025 因此才可安全重复）。
--
-- 列语义与 V029 **逐字一致**（COMMENT 原文照抄，不另写一套说法）：
--   同一条规则两份文字迟早漂移，而这两列的注释本身就是"没上报就是 NULL、不拿计划冒充实际"这条口径的落点。
-- 只加可空列，不动既有语义、不回填历史行、无 DROP/TRUNCATE。
-- 新库不需要这一支：`sql/mysql/transport-schema.sql:272,295-296` 已经带这三列（CREATE TABLE IF NOT EXISTS 里）。
-- =====================================================================

-- ---------- 1) 明细级实际上报时刻（PICKUP=完成装车 / DELIVERY=完成妥投 / 其余经停=到站） ----------
SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'transport_dispatch_plan_item'
    AND COLUMN_NAME = 'actual_arrival_time');
SET @ddl := IF(@col_exists = 0,
  'ALTER TABLE `transport_dispatch_plan_item` ADD COLUMN `actual_arrival_time` datetime DEFAULT NULL COMMENT ''实际上报时刻：PICKUP=司机完成装车、DELIVERY=司机完成妥投，其余经停=司机到站；没上报就是 NULL（不拿计划冒充实际）'' AFTER `estimated_arrival_time`',
  'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 2) 明细完成时刻（状态推进到已完成/失败时落地；未完成为 NULL） ----------
SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'transport_dispatch_plan_item'
    AND COLUMN_NAME = 'actual_finish_time');
SET @ddl := IF(@col_exists = 0,
  'ALTER TABLE `transport_dispatch_plan_item` ADD COLUMN `actual_finish_time` datetime DEFAULT NULL COMMENT ''明细完成时刻（状态被推进到已完成/失败时落地；未完成为 NULL）'' AFTER `actual_arrival_time`',
  'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 3) 本批未派发的货运单与原因码（乘客单受业务底线保护，永不在这一列里） ----------
SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'transport_dispatch_plan'
    AND COLUMN_NAME = 'unassigned_orders');
SET @ddl := IF(@col_exists = 0,
  'ALTER TABLE `transport_dispatch_plan` ADD COLUMN `unassigned_orders` text COMMENT ''本批未派发的货运单与原因码（JSON 数组 [{"orderId":12,"reasonCode":"CAPACITY_OVERFLOW"}]）。乘客单受业务底线保护永不舍弃，故不在此列。NULL=本批没有未派单'' AFTER `plan_reason`',
  'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 4) 落地核对：打完必须能数出三列（数不出就是没执行到这台库，别让流水线报绿） ----------
SET @applied := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND ((TABLE_NAME = 'transport_dispatch_plan_item' AND COLUMN_NAME IN ('actual_arrival_time', 'actual_finish_time'))
      OR (TABLE_NAME = 'transport_dispatch_plan' AND COLUMN_NAME = 'unassigned_orders')));
SELECT CONCAT('[V030 核对] 执行回流列已存在 ', @applied, ' / 3 —— 少于 3 就是没落到这张表') AS v030_check;
