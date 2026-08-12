-- ============================================
-- 客货邮平台 - 增量列迁移（幂等，可重复执行）
--
-- 背景：transport-schema.sql 用 CREATE TABLE IF NOT EXISTS 维护唯一非破坏性 DDL，
-- 但该语句不会给「已存在」的表补新列，导致历史部署的库发生列漂移（老表缺新列）。
-- 本文件用 information_schema 守卫 + 动态 SQL，补齐这类新增列，且重复执行安全。
--
-- 已纳入 deploy-dev.yml 迁移步骤（在 transport-schema.sql 之后自动执行）。
-- ============================================

-- ---------- V004：车辆货仓件数上限 cargo_capacity ----------
SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'transport_vehicle'
    AND COLUMN_NAME = 'cargo_capacity'
);
SET @ddl := IF(
  @col_exists = 0,
  'ALTER TABLE `transport_vehicle` ADD COLUMN `cargo_capacity` int NOT NULL DEFAULT 4 COMMENT ''货仓件数上限（算法容量约束按件数）'' AFTER `cargo_capacity_kg`',
  'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------- V006：班次执行已装车件数 loaded_count ----------
SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'transport_shift_execution'
    AND COLUMN_NAME = 'loaded_count'
);
SET @ddl := IF(
  @col_exists = 0,
  'ALTER TABLE `transport_shift_execution` ADD COLUMN `loaded_count` int NOT NULL DEFAULT 0 COMMENT ''已装车件数(行李舱运力,受 vehicle.cargo_capacity 约束)'' AFTER `current_station_id`',
  'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
