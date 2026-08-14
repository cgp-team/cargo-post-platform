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

-- ---------- 2026-08-13：全量 schema 漂移比对补齐（13 列） ----------
-- 活库建于 2026-07-23，此后多个 PR 只改了 CREATE TABLE IF NOT EXISTS（不会给老表补列），
-- 导致 demo-data 引用 batch_start 等列报 Unknown column。以下按 information_schema 守卫幂等补齐。

-- transport_order.member_user_id
SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'transport_order'
    AND COLUMN_NAME = 'member_user_id'
);
SET @ddl := IF(
  @col_exists = 0,
  'ALTER TABLE `transport_order` ADD COLUMN `member_user_id` bigint NOT NULL DEFAULT 0 COMMENT ''下单会员编号(小程序寄货)''',
  'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- transport_cargo_order.goods_name
SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'transport_cargo_order'
    AND COLUMN_NAME = 'goods_name'
);
SET @ddl := IF(
  @col_exists = 0,
  'ALTER TABLE `transport_cargo_order` ADD COLUMN `goods_name` varchar(128) NOT NULL DEFAULT '''' COMMENT ''货物名称''',
  'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- transport_cargo_order.goods_note
SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'transport_cargo_order'
    AND COLUMN_NAME = 'goods_note'
);
SET @ddl := IF(
  @col_exists = 0,
  'ALTER TABLE `transport_cargo_order` ADD COLUMN `goods_note` varchar(255) NOT NULL DEFAULT '''' COMMENT ''货物备注''',
  'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- transport_cargo_order.photo_url
SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'transport_cargo_order'
    AND COLUMN_NAME = 'photo_url'
);
SET @ddl := IF(
  @col_exists = 0,
  'ALTER TABLE `transport_cargo_order` ADD COLUMN `photo_url` varchar(255) NOT NULL DEFAULT '''' COMMENT ''货物照片''',
  'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- transport_cargo_order.receiver_name
SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'transport_cargo_order'
    AND COLUMN_NAME = 'receiver_name'
);
SET @ddl := IF(
  @col_exists = 0,
  'ALTER TABLE `transport_cargo_order` ADD COLUMN `receiver_name` varchar(64) NOT NULL DEFAULT '''' COMMENT ''收货人''',
  'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- transport_cargo_order.receiver_mobile
SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'transport_cargo_order'
    AND COLUMN_NAME = 'receiver_mobile'
);
SET @ddl := IF(
  @col_exists = 0,
  'ALTER TABLE `transport_cargo_order` ADD COLUMN `receiver_mobile` varchar(32) NOT NULL DEFAULT '''' COMMENT ''收货电话''',
  'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- transport_cargo_order.receiver_address
SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'transport_cargo_order'
    AND COLUMN_NAME = 'receiver_address'
);
SET @ddl := IF(
  @col_exists = 0,
  'ALTER TABLE `transport_cargo_order` ADD COLUMN `receiver_address` varchar(255) NOT NULL DEFAULT '''' COMMENT ''收货地址''',
  'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- transport_dispatch_task.batch_start
SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'transport_dispatch_task'
    AND COLUMN_NAME = 'batch_start'
);
SET @ddl := IF(
  @col_exists = 0,
  'ALTER TABLE `transport_dispatch_task` ADD COLUMN `batch_start` datetime DEFAULT NULL COMMENT ''批次区间开始（半小时）''',
  'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- transport_dispatch_task.batch_end
SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'transport_dispatch_task'
    AND COLUMN_NAME = 'batch_end'
);
SET @ddl := IF(
  @col_exists = 0,
  'ALTER TABLE `transport_dispatch_task` ADD COLUMN `batch_end` datetime DEFAULT NULL COMMENT ''批次区间结束（半小时）''',
  'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- transport_dispatch_task.error_message
SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'transport_dispatch_task'
    AND COLUMN_NAME = 'error_message'
);
SET @ddl := IF(
  @col_exists = 0,
  'ALTER TABLE `transport_dispatch_task` ADD COLUMN `error_message` varchar(512) DEFAULT NULL COMMENT ''失败原因''',
  'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- transport_dispatch_plan.mode
SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'transport_dispatch_plan'
    AND COLUMN_NAME = 'mode'
);
SET @ddl := IF(
  @col_exists = 0,
  'ALTER TABLE `transport_dispatch_plan` ADD COLUMN `mode` tinyint NOT NULL DEFAULT 0 COMMENT ''派单方式:0 手工 1 智能''',
  'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- transport_dispatch_plan.total_distance
SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'transport_dispatch_plan'
    AND COLUMN_NAME = 'total_distance'
);
SET @ddl := IF(
  @col_exists = 0,
  'ALTER TABLE `transport_dispatch_plan` ADD COLUMN `total_distance` decimal(12,3) DEFAULT NULL COMMENT ''总里程(算法产出)''',
  'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- transport_dispatch_plan_item.station_id
SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'transport_dispatch_plan_item'
    AND COLUMN_NAME = 'station_id'
);
SET @ddl := IF(
  @col_exists = 0,
  'ALTER TABLE `transport_dispatch_plan_item` ADD COLUMN `station_id` bigint DEFAULT NULL COMMENT ''经停站点编号''',
  'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------- transport_dispatch_plan_item.order_id：应为可空（场站起止点无订单） ----------
-- 历史活库该列是 NOT NULL，而手工/智能派单对 DEPART(场站出发)/RETURN(返回场站) 经停不填
-- order_id（insertPlanItems 对 null orderId 不落该列）→ 插入报 Field 'order_id' doesn't have a
-- default value。这里同时覆盖「列缺失→ADD」与「列已存在且 NOT NULL→MODIFY」两种情况，幂等安全。
SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'transport_dispatch_plan_item'
    AND COLUMN_NAME = 'order_id'
);
SET @col_is_nullable := (
  SELECT IS_NULLABLE FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'transport_dispatch_plan_item'
    AND COLUMN_NAME = 'order_id'
);
SET @ddl := IF(
  @col_exists = 0,
  'ALTER TABLE `transport_dispatch_plan_item` ADD COLUMN `order_id` bigint DEFAULT NULL COMMENT ''订单编号（场站起止点无订单）'' AFTER `shift_id`',
  IF(@col_is_nullable = 'NO',
     'ALTER TABLE `transport_dispatch_plan_item` MODIFY COLUMN `order_id` bigint DEFAULT NULL COMMENT ''订单编号（场站起止点无订单）''',
     'SELECT 1')
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
