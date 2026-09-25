-- =====================================================================
-- V024 · BE-18 / BE-24 / BE-25：关键索引修复与唯一键强制化
-- 依据：修改清单_统一执行版_2026-09-25.md
-- 背景：多租户已关闭（yudao.tenant.enable=false），所有 (tenant_id, ...) 复合索引
--       左前缀失效，查询退化为全表扫描；本脚本只加索引/键，不改列、不删索引。
-- 执行前：先在测试库验证；文中 DELETE 均为"清理重复行保留最小 id"的幂等清理。
-- =====================================================================

-- ---------- BE-18-1：订单池查询索引（不含 tenant_id 前导列） ----------
-- 订单池查询：WHERE status=READY_FOR_POOL ORDER BY earliest_pickup_time
SET @idx_exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'transport_order' AND INDEX_NAME = 'idx_transport_order_pool_v2');
SET @ddl := IF(@idx_exists = 0,
  'ALTER TABLE `transport_order` ADD KEY `idx_transport_order_pool_v2` (`status`, `earliest_pickup_time`)',
  'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 用户维度订单查询：WHERE member_user_id=? AND status=?
SET @idx_exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'transport_order' AND INDEX_NAME = 'idx_transport_order_member_v2');
SET @ddl := IF(@idx_exists = 0,
  'ALTER TABLE `transport_order` ADD KEY `idx_transport_order_member_v2` (`member_user_id`, `status`)',
  'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- BE-18-2：调度经停明细按车辆查 ----------
SET @idx_exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'transport_dispatch_plan_item' AND INDEX_NAME = 'idx_plan_item_vehicle');
SET @ddl := IF(@idx_exists = 0,
  'ALTER TABLE `transport_dispatch_plan_item` ADD KEY `idx_plan_item_vehicle` (`vehicle_id`)',
  'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- BE-18-3：算法请求按快照哈希查幂等 ----------
SET @idx_exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'transport_algorithm_request' AND INDEX_NAME = 'idx_algorithm_request_snapshot');
SET @ddl := IF(@idx_exists = 0,
  'ALTER TABLE `transport_algorithm_request` ADD KEY `idx_algorithm_request_snapshot` (`snapshot_hash`, `create_time`)',
  'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- BE-18-4：班次执行记录按司机+日期查 ----------
SET @idx_exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'transport_shift_execution' AND INDEX_NAME = 'idx_shift_execution_driver_date');
SET @ddl := IF(@idx_exists = 0,
  'ALTER TABLE `transport_shift_execution` ADD KEY `idx_shift_execution_driver_date` (`driver_id`, `exec_date`)',
  'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- BE-18-5：调度方案按状态+时间查（V023 注释建议未落地） ----------
SET @idx_exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'transport_dispatch_plan' AND INDEX_NAME = 'idx_plan_status_create');
SET @ddl := IF(@idx_exists = 0,
  'ALTER TABLE `transport_dispatch_plan` ADD KEY `idx_plan_status_create` (`status`, `create_time`)',
  'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- BE-24：通知幂等唯一键强制化 ----------
-- 先清理历史重复（保留最小 id），再确保唯一键
DELETE n1 FROM `transport_user_notification` n1
JOIN `transport_user_notification` n2
  ON n1.event_id = n2.event_id AND n1.user_id = n2.user_id AND n1.event_type = n2.event_type
 AND n1.id > n2.id;

SET @uk_exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'transport_user_notification' AND INDEX_NAME = 'idx_notification_event');
SET @is_unique := (SELECT COUNT(DISTINCT NON_UNIQUE) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'transport_user_notification' AND INDEX_NAME = 'idx_notification_event');
SET @ddl := IF(@uk_exists = 0,
  'ALTER TABLE `transport_user_notification` ADD UNIQUE KEY `idx_notification_event` (`event_id`, `user_id`, `event_type`)',
  IF(@is_unique > 0 AND (SELECT NON_UNIQUE FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'transport_user_notification'
        AND INDEX_NAME = 'idx_notification_event' LIMIT 1) = 1,
     'ALTER TABLE `transport_user_notification` DROP KEY `idx_notification_event`, ADD UNIQUE KEY `idx_notification_event` (`event_id`, `user_id`, `event_type`)',
     'SELECT 1'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- BE-25：发车核验 (plan_id, vehicle_id) 唯一键 ----------
-- 先清理历史重复（保留最小 id）
DELETE c1 FROM `transport_departure_check` c1
JOIN `transport_departure_check` c2
  ON c1.plan_id = c2.plan_id AND c1.vehicle_id = c2.vehicle_id
 AND c1.id > c2.id;

SET @uk_exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'transport_departure_check' AND INDEX_NAME = 'uk_departure_check_plan_vehicle');
SET @ddl := IF(@uk_exists = 0,
  'ALTER TABLE `transport_departure_check` ADD UNIQUE KEY `uk_departure_check_plan_vehicle` (`plan_id`, `vehicle_id`)',
  'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 验收参考 ----------
-- EXPLAIN SELECT id FROM transport_order WHERE status=0 ORDER BY earliest_pickup_time LIMIT 20;
--   → 期待 key=idx_transport_order_pool_v2, type=ref（非 ALL）
-- SHOW INDEX FROM transport_user_notification WHERE Key_name='idx_notification_event';
--   → 期待 Non_unique = 0
-- SHOW INDEX FROM transport_departure_check WHERE Key_name='uk_departure_check_plan_vehicle';
--   → 期待 Non_unique = 0
