-- =====================================================================
-- V025 · BE-15：运输段冲突检测索引（配套"条件查询替代全表载入"改造）
-- 依据：修改清单_统一执行版_2026-09-25.md BE-15
-- 背景：LegConflictServiceImpl 冲突检测已改为
--         WHERE status != 4 AND vehicle_id/driver_id = ? AND estimated_departure <= ? AND estimated_arrival >= ?
--       但既有 idx_leg_vehicle / idx_leg_driver 均以 tenant_id 为前导列
--       （多租户已关闭，左前缀失效），必须补资源维度前导索引，否则 10 万行仍走全表扫描。
--       只加索引，不改列、不删索引、不碰数据。
-- =====================================================================

-- ---------- 车辆维度冲突检测 ----------
SET @idx_exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'transport_leg' AND INDEX_NAME = 'idx_leg_vehicle_time');
SET @ddl := IF(@idx_exists = 0,
  'ALTER TABLE `transport_leg` ADD KEY `idx_leg_vehicle_time` (`vehicle_id`, `estimated_departure`)',
  'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 司机维度冲突检测 ----------
SET @idx_exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'transport_leg' AND INDEX_NAME = 'idx_leg_driver_time');
SET @ddl := IF(@idx_exists = 0,
  'ALTER TABLE `transport_leg` ADD KEY `idx_leg_driver_time` (`driver_id`, `estimated_departure`)',
  'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
