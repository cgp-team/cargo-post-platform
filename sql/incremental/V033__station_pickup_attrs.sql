-- =====================================================================
-- V033 · 站点（揽收点）补四项属性：联系人 / 联系电话 / 营业时间 / 电商站点标记
--
-- 为什么是这四列（《标记说明》后台九："县城/乡镇揽收点/电商站点/联系人/时间/状态"，标的是"否=核心功能"）：
--   `transport_station` 现有列只有 code/name/level/经纬度/address/status/source_type/station_type/
--   user_access/vehicle_access/dispatch_enabled/sort/remark —— 层级与"能不能用"有，**联系人与营业时间没有**；
--   而 `station_type` 枚举只有 BUS_STOP/CARGO_STATION/MIXED，**表达不了"电商站点"这一类**
--   ⇒ 用独立旗标 ecommerce_flag，不去动既有枚举（它已被 App/小程序按值消费）。
--
-- 写法沿用 V024/V025/V030：逐列查 information_schema + PREPARE/EXECUTE ⇒ **可安全重复执行**。
--   基线 `sql/mysql/transport-menu.sql`/`transport-schema.sql` 是裸 INSERT/CREATE，
--   而白名单脚本会被反复重放（`deploy-dev.yml` 要仓库变量 `AUTO_DB_MIGRATE=1`，`db-migrate-guarded.yml` 每次 apply）
--   ⇒ 不幂等的脚本进白名单就会红在迁移步。
--
-- ⚠ 三条部署前置（都在本文件解决范围之外）：
--   1) 本文件**不在** `deploy-dev.yml` 的迁移白名单里（白名单现读 V018/V019/V021/V022/V024/V025）⇒ 需人工执行一次；
--   2) 基线 `transport-schema.sql` 本轮**没有**同步加这四列（该文件在工作树里有他人未提交改动，
--      不裹进这一笔）⇒ 新库也靠执行本文件补列，两条路都通；
--   3) 未执行之前，后台站点表单提交这四项会因缺列报错——所以"云上这一格可用"要等它被执行。
-- =====================================================================

SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'transport_station' AND COLUMN_NAME = 'contact_name');
SET @ddl := IF(@col_exists = 0,
  'ALTER TABLE `transport_station` ADD COLUMN `contact_name` varchar(64) NOT NULL DEFAULT '''' COMMENT ''揽收点联系人（空串=未填，界面要念“没填”而不是“无联系人”）'' AFTER `address`',
  'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'transport_station' AND COLUMN_NAME = 'contact_mobile');
SET @ddl := IF(@col_exists = 0,
  'ALTER TABLE `transport_station` ADD COLUMN `contact_mobile` varchar(32) NOT NULL DEFAULT '''' COMMENT ''揽收点联系电话'' AFTER `contact_name`',
  'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'transport_station' AND COLUMN_NAME = 'business_hours');
SET @ddl := IF(@col_exists = 0,
  'ALTER TABLE `transport_station` ADD COLUMN `business_hours` varchar(64) NOT NULL DEFAULT '''' COMMENT ''营业时间，自由文本（如 08:00-18:00；空=未公布，不拿默认值冒充 24 小时）'' AFTER `contact_mobile`',
  'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'transport_station' AND COLUMN_NAME = 'ecommerce_flag');
SET @ddl := IF(@col_exists = 0,
  'ALTER TABLE `transport_station` ADD COLUMN `ecommerce_flag` bit(1) NOT NULL DEFAULT b''0'' COMMENT ''是否电商站点（station_type 只有 BUS_STOP/CARGO_STATION/MIXED，表达不了这一类）'' AFTER `dispatch_enabled`',
  'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
