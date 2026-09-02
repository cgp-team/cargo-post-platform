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

-- transport_dispatch_plan.route_provider
SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'transport_dispatch_plan'
    AND COLUMN_NAME = 'route_provider'
);
SET @ddl := IF(
  @col_exists = 0,
  'ALTER TABLE `transport_dispatch_plan` ADD COLUMN `route_provider` varchar(32) DEFAULT NULL COMMENT ''ETA路网来源:AMAP=高德真实时长 EUCLIDEAN_FALLBACK=直线估算''',
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

-- ---------- 取件核销：transport_postal_order 补收件人/取件码/核销字段 ----------
-- 邮快件（order_type=3）下行快递进村：快递到总站 → 司机取件装车 → 送上门/定点 → 收件人取件核销。
-- 每列独立 information_schema 守卫，幂等安全。

-- receiver_name 收件人
SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='transport_postal_order' AND COLUMN_NAME='receiver_name');
SET @ddl := IF(@col_exists = 0,
  'ALTER TABLE `transport_postal_order` ADD COLUMN `receiver_name` varchar(64) NOT NULL DEFAULT '''' COMMENT ''收件人'' AFTER `weight_kg`', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- receiver_mobile 收件电话
SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='transport_postal_order' AND COLUMN_NAME='receiver_mobile');
SET @ddl := IF(@col_exists = 0,
  'ALTER TABLE `transport_postal_order` ADD COLUMN `receiver_mobile` varchar(32) NOT NULL DEFAULT '''' COMMENT ''收件电话'' AFTER `receiver_name`', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- receiver_address 收件地址
SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='transport_postal_order' AND COLUMN_NAME='receiver_address');
SET @ddl := IF(@col_exists = 0,
  'ALTER TABLE `transport_postal_order` ADD COLUMN `receiver_address` varchar(255) NOT NULL DEFAULT '''' COMMENT ''收件地址'' AFTER `receiver_mobile`', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- pickup_code 取件码（6位数字，收件人凭码取件）
SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='transport_postal_order' AND COLUMN_NAME='pickup_code');
SET @ddl := IF(@col_exists = 0,
  'ALTER TABLE `transport_postal_order` ADD COLUMN `pickup_code` varchar(32) NOT NULL DEFAULT '''' COMMENT ''取件码（6位数字，收件人凭码取件）'' AFTER `receiver_address`', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- pickup_status 取件状态：0待取件 1已取件
SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='transport_postal_order' AND COLUMN_NAME='pickup_status');
SET @ddl := IF(@col_exists = 0,
  'ALTER TABLE `transport_postal_order` ADD COLUMN `pickup_status` tinyint NOT NULL DEFAULT 0 COMMENT ''取件状态：0待取件 1已取件'' AFTER `pickup_code`', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- picked_up_time 取件时间
SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='transport_postal_order' AND COLUMN_NAME='picked_up_time');
SET @ddl := IF(@col_exists = 0,
  'ALTER TABLE `transport_postal_order` ADD COLUMN `picked_up_time` datetime DEFAULT NULL COMMENT ''取件时间'' AFTER `pickup_status`', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- picker_member_user_id 核销人会员编号
SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='transport_postal_order' AND COLUMN_NAME='picker_member_user_id');
SET @ddl := IF(@col_exists = 0,
  'ALTER TABLE `transport_postal_order` ADD COLUMN `picker_member_user_id` bigint DEFAULT NULL COMMENT ''核销人会员编号'' AFTER `picked_up_time`', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 货运拍照核对：transport_cargo_order.driver_photo_url ----------
-- 司机收件装车时强制拍照（快递总站核对"这是哪家货"的凭证）
SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='transport_cargo_order' AND COLUMN_NAME='driver_photo_url');
SET @ddl := IF(@col_exists = 0,
  'ALTER TABLE `transport_cargo_order` ADD COLUMN `driver_photo_url` varchar(255) NOT NULL DEFAULT '''' COMMENT ''司机收件照片(装车强制拍，快递总站核对凭证)'' AFTER `photo_url`', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 货运物品审核：transport_cargo_order.audit_status / reject_reason ----------
-- 村民寄货散件需管理端审核（危险品/违禁品拒绝运输）；未审核/被拒的货运不能进调度池
SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='transport_cargo_order' AND COLUMN_NAME='audit_status');
SET @ddl := IF(@col_exists = 0,
  'ALTER TABLE `transport_cargo_order` ADD COLUMN `audit_status` tinyint NOT NULL DEFAULT 0 COMMENT ''审核状态：0待审核 1已通过 2已拒绝'' AFTER `driver_photo_url`', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='transport_cargo_order' AND COLUMN_NAME='reject_reason');
SET @ddl := IF(@col_exists = 0,
  'ALTER TABLE `transport_cargo_order` ADD COLUMN `reject_reason` varchar(255) NOT NULL DEFAULT '''' COMMENT ''拒绝原因(审核拒绝时)'' AFTER `audit_status`', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 商城订单溯源：transport_product_order.vehicle_id / shift_id ----------
-- 发货时关联承运车辆/班次，小程序「商品溯源」据 vehicle_id 查轨迹
SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='transport_product_order' AND COLUMN_NAME='vehicle_id');
SET @ddl := IF(@col_exists = 0,
  'ALTER TABLE `transport_product_order` ADD COLUMN `vehicle_id` bigint DEFAULT NULL COMMENT ''承运车辆编号(发货时关联,溯源用)'' AFTER `status`', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='transport_product_order' AND COLUMN_NAME='shift_id');
SET @ddl := IF(@col_exists = 0,
  'ALTER TABLE `transport_product_order` ADD COLUMN `shift_id` bigint DEFAULT NULL COMMENT ''承运班次编号(发货时关联,溯源用)'' AFTER `vehicle_id`', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 调度明细乘客影响：transport_dispatch_plan_item.passenger_impact_seconds ----------
-- 绕行对车上乘客的额外乘车时长（passenger-level，空车绕行为 NULL）
-- 注意：不加 AFTER 子句（依赖 detour_duration_seconds 等前置列存在，旧表列漂移时 ALTER 会失败），
-- 直接追加到表末尾，幂等安全。
SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='transport_dispatch_plan_item' AND COLUMN_NAME='passenger_impact_seconds');
SET @ddl := IF(@col_exists = 0,
  'ALTER TABLE `transport_dispatch_plan_item` ADD COLUMN `passenger_impact_seconds` int DEFAULT NULL COMMENT ''乘客影响(秒，绕行对车上乘客额外乘车时长，空车为NULL)''', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- V007：修复生产环境文件配置 domain ----------
-- 问题：初始数据 id=4 的 domain=http://127.0.0.1:48080（本地开发地址）
-- 修复：更新为生产服务器地址 http://1.15.29.107
-- 幂等：使用 JSON_SET 只修改 domain 字段，只有旧地址存在时才更新

UPDATE infra_file_config
SET
    config = JSON_SET(
        config,
        '$.domain',
        'http://1.15.29.107'
    ),
    updater = 'admin',
    update_time = NOW()
WHERE id = 4
  AND JSON_EXTRACT(config, '$.domain') = 'http://127.0.0.1:48080';

-- ---------- V008：修复历史文件 URL ----------
-- 问题：V007 修复了 infra_file_config.domain，但历史上传时 URL 已写死到 infra_file.url
--       和业务表 transport_cargo_order.photo_url / driver_photo_url。
--       这些 URL 仍指向 http://127.0.0.1:48080，浏览器直接请求该地址会 ERR_CONNECTION_REFUSED。
-- 修复：将 URL 前缀从 http://127.0.0.1:48080 替换为 http://1.15.29.107
-- 幂等：WHERE 子句只匹配旧 URL，已修复的行不受影响（REPLACE 结果相同，但 WHERE 过滤后 0 rows）

-- 1. infra_file.url：文件访问地址
UPDATE infra_file
SET url = REPLACE(url, 'http://127.0.0.1:48080', 'http://1.15.29.107')
WHERE url LIKE 'http://127.0.0.1:48080/%';

-- 2. transport_cargo_order.photo_url：村民寄货货物照片
UPDATE transport_cargo_order
SET photo_url = REPLACE(photo_url, 'http://127.0.0.1:48080', 'http://1.15.29.107')
WHERE photo_url LIKE 'http://127.0.0.1:48080/%';

-- 3. transport_cargo_order.driver_photo_url：司机收件装车照片
UPDATE transport_cargo_order
SET driver_photo_url = REPLACE(driver_photo_url, 'http://127.0.0.1:48080', 'http://1.15.29.107')
WHERE driver_photo_url LIKE 'http://127.0.0.1:48080/%';

-- ========== 部署后验证 SQL（只 SELECT，不执行） ==========
-- 验证 infra_file 坏 URL 已清零：
--   SELECT COUNT(*) AS bad_count FROM infra_file WHERE url LIKE 'http://127.0.0.1:48080/%';
-- 验证 transport_cargo_order 坏 URL 已清零：
--   SELECT COUNT(*) AS bad_count FROM transport_cargo_order
--   WHERE photo_url LIKE 'http://127.0.0.1:48080/%' OR driver_photo_url LIKE 'http://127.0.0.1:48080/%';
-- 预期结果：两个查询均返回 0

-- ---------- V009：开发者模式 + 模拟运营权限体系 ----------
-- 新增开发者中心菜单与独立模拟权限，解除对 transport:dispatch:smart-plan 的复用。
-- 幂等：INSERT ... SELECT ... WHERE NOT EXISTS。

-- 1. 开发者中心页面（父菜单：客货邮管理 6800）
--    type=2（页面）：点击直接打开开发者中心。
--    visible=b'0'：普通用户默认不可见，需有 transport:developer:access 权限才显示。
INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon,
    component, component_name, status, visible, keep_alive, always_show,
    creator, create_time, updater, update_time, deleted)
SELECT 6920, '开发者中心', 'transport:developer:access', 2, 15, 6800, 'developer', 'ep:setting',
    'transport/developer/index', 'TransportDeveloper', 0, b'0', b'1', b'0',
    '1', NOW(), '1', NOW(), b'0'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE id = 6920);

-- 1b. 修复已部署的 6920：旧版 V009 曾以 type=1（目录）创建，需升级为 type=2（页面）。
--     幂等：只有 type=1 且 component 为空时才更新。
UPDATE system_menu
SET type = 2,
    component = 'transport/developer/index',
    component_name = 'TransportDeveloper',
    updater = 'admin',
    update_time = NOW()
WHERE id = 6920 AND type = 1 AND (component IS NULL OR component = '');

-- 2. 模拟运营页面（父菜单：开发者中心 6920）
INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon,
    component, component_name, status, visible, keep_alive, always_show,
    creator, create_time, updater, update_time, deleted)
SELECT 6921, '模拟运营', 'transport:simulation:view', 2, 1, 6920, 'simulation', 'ep:video-play',
    'transport/simulation/index', 'TransportSimulation', 0, b'1', b'1', b'1',
    '1', NOW(), '1', NOW(), b'0'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE id = 6921);

-- 3. 模拟控制按钮权限（父菜单：模拟运营 6915）
--    6921 已删除（与6915重复），6922 挂载到6915。
INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon,
    component, component_name, status, visible, keep_alive, always_show,
    creator, create_time, updater, update_time, deleted)
SELECT 6922, '模拟控制', 'transport:simulation:control', 3, 1, 6915, '', '',
    '', NULL, 0, b'1', b'1', b'1',
    '1', NOW(), '1', NOW(), b'0'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE id = 6922);

-- 3b. 修复已部署的6922：parent_id 从6921改为6915（6921已删除，与6915重复）。
UPDATE system_menu
SET parent_id = 6915, updater = 'admin', update_time = NOW()
WHERE id = 6922 AND parent_id = 6921;

-- 4. 更新旧模拟菜单权限：从 transport:dispatch:smart-plan 改为 transport:simulation:view
--    菜单 6915 是原有「车辆监控→模拟运营」入口，迁移后权限独立。
UPDATE system_menu
SET permission = 'transport:simulation:view', updater = 'admin', update_time = NOW()
WHERE id = 6915 AND permission = 'transport:dispatch:smart-plan';

-- 5. 删除6921（与6915重复，同为模拟运营页面，同 permission、同 component）。
--    幂等：已不存在则跳过。
DELETE FROM system_role_menu WHERE menu_id = 6921;
DELETE FROM system_menu WHERE id = 6921;

-- 6. 超级管理员角色授权
INSERT IGNORE INTO system_role_menu (role_id, menu_id) VALUES (1, 6920);
INSERT IGNORE INTO system_role_menu (role_id, menu_id) VALUES (1, 6922);
