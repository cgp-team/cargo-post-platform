-- =====================================================================
-- V027 · 幂等唯一键兜底：算法请求快照哈希 + 运输段 (plan_id, order_id, leg_sequence)
-- 背景：幂等「先查后插」存在 TOCTOU 窗口，并发下可产生重复行
--       （AlgorithmAdapter.plan 的同快照并发；MultiLegServiceImpl.planLegs 的同方案并发拆段）。
--       数据库唯一键兜底，代码侧 catch DuplicateKeyException 后重查走复用路径。
-- 幂等性：可重复执行（索引存在则跳过）；DELETE 均为「清理重复行保留最小 id」的幂等清理。
-- =====================================================================

-- ---------- transport_algorithm_request.snapshot_hash 唯一 ----------
-- 先清理历史重复（保留最小 id）
DELETE r1 FROM `transport_algorithm_request` r1
JOIN `transport_algorithm_request` r2
  ON r1.snapshot_hash = r2.snapshot_hash AND r1.id > r2.id;

SET @uk_exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'transport_algorithm_request' AND INDEX_NAME = 'uk_algorithm_request_snapshot');
SET @ddl := IF(@uk_exists = 0,
  'ALTER TABLE `transport_algorithm_request` ADD UNIQUE KEY `uk_algorithm_request_snapshot` (`snapshot_hash`)',
  'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- transport_leg (plan_id, order_id, leg_sequence) 唯一 ----------
-- 先清理历史重复（保留最小 id）；plan_id 为 NULL 的行不参与去重（MySQL 唯一键中 NULL 互不相等，
-- 与代码侧「无方案归属的段不做唯一约束」语义一致）
DELETE l1 FROM `transport_leg` l1
JOIN `transport_leg` l2
  ON l1.plan_id = l2.plan_id AND l1.order_id = l2.order_id
 AND l1.leg_sequence = l2.leg_sequence AND l1.id > l2.id;

SET @uk_exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'transport_leg' AND INDEX_NAME = 'uk_leg_plan_order_seq');
SET @ddl := IF(@uk_exists = 0,
  'ALTER TABLE `transport_leg` ADD UNIQUE KEY `uk_leg_plan_order_seq` (`plan_id`, `order_id`, `leg_sequence`)',
  'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 验收参考 ----------
-- SHOW INDEX FROM transport_algorithm_request WHERE Key_name='uk_algorithm_request_snapshot';
--   → 期待 Non_unique = 0
-- SHOW INDEX FROM transport_leg WHERE Key_name='uk_leg_plan_order_seq';
--   → 期待 Non_unique = 0
