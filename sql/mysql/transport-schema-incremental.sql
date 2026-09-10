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

-- ---------- V018：文件 URL 必须带 /api 前缀（否则 nginx 不转发，图片打不开） ----------
-- 问题：V007 把 domain 修成 http://1.15.29.107（缺 /api），生成的文件 URL 形如
--       http://1.15.29.107/admin-api/infra/file/4/get/xxx.jpg；
--       而 nginx 只把 /api/ 转发到后端（见 deploy/nginx/nginx.conf：location /api/ → business_backend，
--       转发时剥掉 /api 前缀），其余路径落到前端 SPA，浏览器拿到的是 index.html
--       → 后台订单列表/审核弹窗里的寄货照片显示为裂图（实测返回 text/html）。
-- 修复：domain 与历史 URL 统一改成带前缀的 http://1.15.29.107/api。
-- 幂等：WHERE 只匹配缺前缀的旧值。

UPDATE infra_file_config
SET
    config = JSON_SET(config, '$.domain', 'http://1.15.29.107/api'),
    updater = 'admin',
    update_time = NOW()
WHERE id = 4
  AND JSON_EXTRACT(config, '$.domain') IN ('http://1.15.29.107', 'http://127.0.0.1:48080');

-- 历史 URL 补齐 /api 前缀（仅改缺前缀的，已带 /api 的保持不变）
UPDATE infra_file
SET url = REPLACE(url, 'http://1.15.29.107/', 'http://1.15.29.107/api/')
WHERE url LIKE 'http://1.15.29.107/%' AND url NOT LIKE 'http://1.15.29.107/api/%';

UPDATE transport_cargo_order
SET photo_url = REPLACE(photo_url, 'http://1.15.29.107/', 'http://1.15.29.107/api/')
WHERE photo_url LIKE 'http://1.15.29.107/%' AND photo_url NOT LIKE 'http://1.15.29.107/api/%';

UPDATE transport_cargo_order
SET driver_photo_url = REPLACE(driver_photo_url, 'http://1.15.29.107/', 'http://1.15.29.107/api/')
WHERE driver_photo_url LIKE 'http://1.15.29.107/%' AND driver_photo_url NOT LIKE 'http://1.15.29.107/api/%';

UPDATE system_users
SET avatar = REPLACE(avatar, 'http://1.15.29.107/', 'http://1.15.29.107/api/')
WHERE avatar LIKE 'http://1.15.29.107/%' AND avatar NOT LIKE 'http://1.15.29.107/api/%';

-- 验证（预期均为 0）：
--   SELECT COUNT(*) FROM infra_file_config WHERE JSON_EXTRACT(config,'$.domain') NOT LIKE '%/api';
--   SELECT COUNT(*) FROM infra_file WHERE url LIKE 'http://1.15.29.107/%' AND url NOT LIKE 'http://1.15.29.107/api/%';
--   SELECT COUNT(*) FROM transport_cargo_order
--     WHERE (photo_url LIKE 'http://1.15.29.107/%' AND photo_url NOT LIKE 'http://1.15.29.107/api/%')
--        OR (driver_photo_url LIKE 'http://1.15.29.107/%' AND driver_photo_url NOT LIKE 'http://1.15.29.107/api/%');

-- ---------- V009：开发者模式 + 模拟运营权限体系 ----------
-- 新增开发者中心菜单与独立模拟权限，解除对 transport:dispatch:smart-plan 的复用。
-- 幂等：INSERT ... SELECT ... WHERE NOT EXISTS。

-- 1. 开发者中心页面（父菜单：客货邮管理 6800）
--    type=2（页面）：点击直接打开开发者中心。
--    visible=b'1'：侧边栏可见（权限由 transport:developer:access 控制）。
INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon,
    component, component_name, status, visible, keep_alive, always_show,
    creator, create_time, updater, update_time, deleted)
SELECT 6920, '开发者中心', 'transport:developer:access', 2, 15, 6800, 'developer', 'ep:setting',
    'transport/developer/index', 'TransportDeveloper', 0, b'1', b'1', b'1',
    '1', NOW(), '1', NOW(), b'0'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE id = 6920);

-- 1b. 修复已部署的 6920：升级 type、设置 component、确保可见。
--     幂等：只更新需要修复的记录。
UPDATE system_menu
SET type = 2,
    component = 'transport/developer/index',
    component_name = 'TransportDeveloper',
    visible = b'1',
    always_show = b'1',
    updater = 'admin',
    update_time = NOW()
WHERE id = 6920 AND (type = 1 OR visible = b'0' OR component IS NULL OR component = '');

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

-- ---------- V010：修复 transport_cargo_order schema drift ----------
-- 问题：CargoOrderDO 定义了 review_status 等5个字段，但 transport-schema.sql 的
--       CREATE TABLE IF NOT EXISTS 不会给已有表补列，导致生产数据库缺少这些列。
--       查询时 MyBatis Plus SELECT * 报 Unknown column，订单列表返回500。
-- 修复：幂等补齐5个缺失列。
-- 幂等：information_schema 守卫，已存在则跳过。

-- review_status 承运审核结果
SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'transport_cargo_order'
    AND COLUMN_NAME = 'review_status'
);
SET @ddl := IF(
  @col_exists = 0,
  'ALTER TABLE `transport_cargo_order` ADD COLUMN `review_status` tinyint NOT NULL DEFAULT 0 COMMENT ''承运审核结果(ReviewStatusEnum)：0待审 1通过 2需客户操作 3需人工 4拒运'' AFTER `reject_reason`',
  'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- review_reason_codes 承运审核原因码
SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'transport_cargo_order'
    AND COLUMN_NAME = 'review_reason_codes'
);
SET @ddl := IF(
  @col_exists = 0,
  'ALTER TABLE `transport_cargo_order` ADD COLUMN `review_reason_codes` varchar(255) NOT NULL DEFAULT '''' COMMENT ''承运审核原因码(ReviewReasonCodeEnum，逗号分隔多个)'' AFTER `review_status`',
  'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- pickup_service_mode 取货服务方式
SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'transport_cargo_order'
    AND COLUMN_NAME = 'pickup_service_mode'
);
SET @ddl := IF(
  @col_exists = 0,
  'ALTER TABLE `transport_cargo_order` ADD COLUMN `pickup_service_mode` varchar(32) NOT NULL DEFAULT '''' COMMENT ''取货服务方式(ServiceModeEnum)'' AFTER `review_reason_codes`',
  'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- delivery_service_mode 送达服务方式
SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'transport_cargo_order'
    AND COLUMN_NAME = 'delivery_service_mode'
);
SET @ddl := IF(
  @col_exists = 0,
  'ALTER TABLE `transport_cargo_order` ADD COLUMN `delivery_service_mode` varchar(32) NOT NULL DEFAULT '''' COMMENT ''送达服务方式(ServiceModeEnum)'' AFTER `pickup_service_mode`',
  'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- service_point_station_id 建议服务站点编号
SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'transport_cargo_order'
    AND COLUMN_NAME = 'service_point_station_id'
);
SET @ddl := IF(
  @col_exists = 0,
  'ALTER TABLE `transport_cargo_order` ADD COLUMN `service_point_station_id` bigint DEFAULT NULL COMMENT ''建议服务站点编号(替代交接：客户送站/最近站点时推荐)'' AFTER `delivery_service_mode`',
  'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------- Schema drift repair：transport_dispatch_plan 估算与任务段窗口 ----------
-- 来源：sql/incremental/V010__dispatch_estimation.sql + V014__task_segment_model.sql
--      （原为注释，未被 deploy-dev.yml 自动执行，生产缺列会导致 SELECT * 报 Unknown column）。
-- 幂等：information_schema 守卫。

-- est_duration_minutes 预计耗时
SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'transport_dispatch_plan'
    AND COLUMN_NAME = 'est_duration_minutes'
);
SET @ddl := IF(
  @col_exists = 0,
  'ALTER TABLE `transport_dispatch_plan` ADD COLUMN `est_duration_minutes` int DEFAULT NULL COMMENT ''预计耗时(分钟，估算)'' AFTER `total_distance`',
  'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- est_revenue 预计收入
SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'transport_dispatch_plan'
    AND COLUMN_NAME = 'est_revenue'
);
SET @ddl := IF(
  @col_exists = 0,
  'ALTER TABLE `transport_dispatch_plan` ADD COLUMN `est_revenue` decimal(12,2) DEFAULT NULL COMMENT ''预计收入(元，按计价规则估算)'' AFTER `est_duration_minutes`',
  'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- est_cost 预计成本
SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'transport_dispatch_plan'
    AND COLUMN_NAME = 'est_cost'
);
SET @ddl := IF(
  @col_exists = 0,
  'ALTER TABLE `transport_dispatch_plan` ADD COLUMN `est_cost` decimal(12,2) DEFAULT NULL COMMENT ''预计成本(元，按计价规则估算)'' AFTER `est_revenue`',
  'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- task_window_start 任务段窗口开始
SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'transport_dispatch_plan'
    AND COLUMN_NAME = 'task_window_start'
);
SET @ddl := IF(
  @col_exists = 0,
  'ALTER TABLE `transport_dispatch_plan` ADD COLUMN `task_window_start` datetime DEFAULT NULL COMMENT ''任务段窗口开始(该方案车辆运营起始时刻，默认=批次开始)'' AFTER `approved_time`',
  'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- task_window_end 任务段窗口结束
SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'transport_dispatch_plan'
    AND COLUMN_NAME = 'task_window_end'
);
SET @ddl := IF(
  @col_exists = 0,
  'ALTER TABLE `transport_dispatch_plan` ADD COLUMN `task_window_end` datetime DEFAULT NULL COMMENT ''任务段窗口结束(默认=开始+预计耗时，方案完成后回写实际终点时刻)'' AFTER `task_window_start`',
  'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------- Schema drift repair：transport_dispatch_plan_item 任务段模型 + 算法解释 ----------
-- 来源：sql/incremental/V012__dispatch_plan_item_segment.sql + V014__task_segment_model.sql
--      + V015__plan_item_explanation.sql
--      （原为注释，未被 deploy-dev.yml 自动执行，生产缺列会导致 SELECT * 报 Unknown column）。
-- 幂等：information_schema 守卫。

-- segment_duration_seconds 分段行驶秒数
SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'transport_dispatch_plan_item'
    AND COLUMN_NAME = 'segment_duration_seconds'
);
SET @ddl := IF(
  @col_exists = 0,
  'ALTER TABLE `transport_dispatch_plan_item` ADD COLUMN `segment_duration_seconds` int DEFAULT NULL COMMENT ''分段路网行驶秒数(上一站→本站；高德真实时长或直线÷均速估算)'' AFTER `estimated_arrival_time`',
  'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- segment_distance_km 分段里程
SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'transport_dispatch_plan_item'
    AND COLUMN_NAME = 'segment_distance_km'
);
SET @ddl := IF(
  @col_exists = 0,
  'ALTER TABLE `transport_dispatch_plan_item` ADD COLUMN `segment_distance_km` decimal(12,3) DEFAULT NULL COMMENT ''分段里程(km；高德路网公里或 Haversine 直线公里)'' AFTER `segment_duration_seconds`',
  'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- planned_departure_time 计划离站时间
SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'transport_dispatch_plan_item'
    AND COLUMN_NAME = 'planned_departure_time'
);
SET @ddl := IF(
  @col_exists = 0,
  'ALTER TABLE `transport_dispatch_plan_item` ADD COLUMN `planned_departure_time` datetime DEFAULT NULL COMMENT ''计划离站时间(=预计到达+本站作业时长)'' AFTER `segment_distance_km`',
  'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- service_duration_seconds 本站作业时长
SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'transport_dispatch_plan_item'
    AND COLUMN_NAME = 'service_duration_seconds'
);
SET @ddl := IF(
  @col_exists = 0,
  'ALTER TABLE `transport_dispatch_plan_item` ADD COLUMN `service_duration_seconds` int DEFAULT NULL COMMENT ''本站作业时长(秒，接/送/派/揽计停站作业)'' AFTER `planned_departure_time`',
  'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- quantity 数量
SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'transport_dispatch_plan_item'
    AND COLUMN_NAME = 'quantity'
);
SET @ddl := IF(
  @col_exists = 0,
  'ALTER TABLE `transport_dispatch_plan_item` ADD COLUMN `quantity` int DEFAULT NULL COMMENT ''数量(BOARD/ALIGHT=人数，PICKUP/DELIVERY=件数)'' AFTER `service_duration_seconds`',
  'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- status 任务段明细状态
SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'transport_dispatch_plan_item'
    AND COLUMN_NAME = 'status'
);
SET @ddl := IF(
  @col_exists = 0,
  'ALTER TABLE `transport_dispatch_plan_item` ADD COLUMN `status` tinyint NOT NULL DEFAULT 0 COMMENT ''任务段明细状态(TaskItemStatusEnum)：0待执行 1行驶中 2已到站 3上车中 4下车中 5揽收中 6派送中 7已完成 8失败'' AFTER `quantity`',
  'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- service_mode 算法解释-服务方式
SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'transport_dispatch_plan_item'
    AND COLUMN_NAME = 'service_mode'
);
SET @ddl := IF(
  @col_exists = 0,
  'ALTER TABLE `transport_dispatch_plan_item` ADD COLUMN `service_mode` varchar(32) NOT NULL DEFAULT '''' COMMENT ''算法解释-服务方式(ServiceModeEnum，仅货运/揽收经停)'' AFTER `status`',
  'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- service_point_station_id (plan_item) 算法解释-服务点站点编号
SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'transport_dispatch_plan_item'
    AND COLUMN_NAME = 'service_point_station_id'
);
SET @ddl := IF(
  @col_exists = 0,
  'ALTER TABLE `transport_dispatch_plan_item` ADD COLUMN `service_point_station_id` bigint DEFAULT NULL COMMENT ''算法解释-服务点站点编号(替代交接时推荐)'' AFTER `service_mode`',
  'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- detour_distance_km 算法解释-绕行距离
SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'transport_dispatch_plan_item'
    AND COLUMN_NAME = 'detour_distance_km'
);
SET @ddl := IF(
  @col_exists = 0,
  'ALTER TABLE `transport_dispatch_plan_item` ADD COLUMN `detour_distance_km` decimal(12,3) DEFAULT NULL COMMENT ''算法解释-绕行距离(km，相对公交骨架，骨架站为0)'' AFTER `service_point_station_id`',
  'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- detour_duration_seconds 算法解释-绕行时长
SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'transport_dispatch_plan_item'
    AND COLUMN_NAME = 'detour_duration_seconds'
);
SET @ddl := IF(
  @col_exists = 0,
  'ALTER TABLE `transport_dispatch_plan_item` ADD COLUMN `detour_duration_seconds` int DEFAULT NULL COMMENT ''算法解释-绕行时长(秒)'' AFTER `detour_distance_km`',
  'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- reason_code 算法解释-未接受原因码
SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'transport_dispatch_plan_item'
    AND COLUMN_NAME = 'reason_code'
);
SET @ddl := IF(
  @col_exists = 0,
  'ALTER TABLE `transport_dispatch_plan_item` ADD COLUMN `reason_code` varchar(64) DEFAULT NULL COMMENT ''算法解释-未接受原因码(ReviewReasonCodeEnum；已接受为NULL)'' AFTER `detour_duration_seconds`',
  'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------- Schema drift repair：transport_vehicle 保险到期日 ----------
-- 来源：sql/incremental/V011__vehicle_insurance_expiry.sql
--      （原为注释，未被 deploy-dev.yml 自动执行，生产缺列会导致到期预警报错）。
-- 幂等：information_schema 守卫。

-- insurance_expire_date 保险到期日
SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'transport_vehicle'
    AND COLUMN_NAME = 'insurance_expire_date'
);
SET @ddl := IF(
  @col_exists = 0,
  'ALTER TABLE `transport_vehicle` ADD COLUMN `insurance_expire_date` date DEFAULT NULL COMMENT ''保险到期日'' AFTER `cargo_capacity`',
  'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------- Historical URL Repair ----------
-- 问题：历史上传文件 URL 写死了 http://127.0.0.1:48080，浏览器直接请求会 ERR_CONNECTION_REFUSED。
--       另有测试数据使用 http://example.com/ 无效域名。
-- 修复：将旧域名替换为生产域名，清理测试 URL。
-- 幂等：WHERE 只匹配旧 URL，已修复的行不受影响。
-- 注意：不删除 infra_file 记录，只修复 URL 引用。

-- 1. infra_file.url：修复旧 localhost URL
UPDATE infra_file
SET url = REPLACE(url, 'http://127.0.0.1:48080', 'http://1.15.29.107')
WHERE url LIKE 'http://127.0.0.1:48080/%';

-- 2. system_users.avatar：修复旧 localhost URL
UPDATE system_users
SET avatar = REPLACE(avatar, 'http://127.0.0.1:48080', 'http://1.15.29.107')
WHERE avatar LIKE 'http://127.0.0.1:48080/%';

-- 3. transport_cargo_order.photo_url：修复旧 localhost URL
UPDATE transport_cargo_order
SET photo_url = REPLACE(photo_url, 'http://127.0.0.1:48080', 'http://1.15.29.107')
WHERE photo_url LIKE 'http://127.0.0.1:48080/%';

-- 4. transport_cargo_order.driver_photo_url：修复旧 localhost URL
UPDATE transport_cargo_order
SET driver_photo_url = REPLACE(driver_photo_url, 'http://127.0.0.1:48080', 'http://1.15.29.107')
WHERE driver_photo_url LIKE 'http://127.0.0.1:48080/%';

-- 5. 清理 example.com 测试 URL（置空，不删除记录）
--    transport_cargo_order.photo_url
UPDATE transport_cargo_order
SET photo_url = ''
WHERE photo_url LIKE 'http://example.com/%';

-- 6. 清理 example.com 测试 URL
--    transport_cargo_order.driver_photo_url
UPDATE transport_cargo_order
SET driver_photo_url = ''
WHERE driver_photo_url LIKE 'http://example.com/%';

-- 7. 清理 example.com 测试 URL
--    system_users.avatar
UPDATE system_users
SET avatar = ''
WHERE avatar LIKE 'http://example.com/%';

-- ---------- V017：模拟运营运行时表（simulation_run / simulation_event / simulation_scenario / simulation_scenario_event）----------
-- 来源：sql/incremental/V017__simulation_runtime.sql

-- 模拟运行记录
CREATE TABLE IF NOT EXISTS `simulation_run` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  `plan_id` bigint DEFAULT NULL COMMENT '调度方案ID',
  `vehicle_id` bigint DEFAULT NULL COMMENT '车辆ID',
  `driver_id` bigint DEFAULT NULL COMMENT '司机ID',
  `scenario_id` bigint DEFAULT NULL COMMENT '场景ID',
  `multiplier` double DEFAULT 10 COMMENT '模拟倍速',
  `status` tinyint NOT NULL DEFAULT 0 COMMENT '状态：0已创建 1运行中 2已暂停 3已完成 4已终止',
  `start_time` datetime DEFAULT NULL COMMENT '模拟开始时间',
  `end_time` datetime DEFAULT NULL COMMENT '模拟结束时间',
  `total_sim_seconds` bigint DEFAULT 0 COMMENT '总模拟秒数',
  `actual_sim_seconds` bigint DEFAULT 0 COMMENT '实际模拟秒数',
  `total_distance_km` double DEFAULT NULL COMMENT '总里程(km)',
  `actual_distance_km` double DEFAULT NULL COMMENT '实际行驶里程(km)',
  `station_count` int DEFAULT 0 COMMENT '总站点数',
  `completed_station_count` int DEFAULT 0 COMMENT '已完成站点数',
  `order_count` int DEFAULT 0 COMMENT '总订单数',
  `completed_order_count` int DEFAULT 0 COMMENT '完成订单数',
  `exception_count` int DEFAULT 0 COMMENT '异常事件数',
  `result_summary` text COMMENT '结果摘要(JSON)',
  `creator` varchar(64) DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`),
  KEY `idx_plan_id` (`plan_id`),
  KEY `idx_vehicle_id` (`vehicle_id`),
  KEY `idx_status` (`status`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='模拟运行记录';

-- 模拟事件
CREATE TABLE IF NOT EXISTS `simulation_event` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  `run_id` bigint NOT NULL COMMENT '模拟运行ID',
  `vehicle_id` bigint DEFAULT NULL COMMENT '车辆ID',
  `event_type` varchar(50) NOT NULL COMMENT '事件类型',
  `severity` tinyint NOT NULL DEFAULT 0 COMMENT '严重级别：0信息 1警告 2严重',
  `title` varchar(200) NOT NULL COMMENT '事件标题',
  `content` varchar(1000) DEFAULT NULL COMMENT '事件内容',
  `sim_seconds` bigint DEFAULT NULL COMMENT '模拟时刻(秒)',
  `station_id` bigint DEFAULT NULL COMMENT '相关站点ID',
  `station_name` varchar(100) DEFAULT NULL COMMENT '相关站点名',
  `longitude` decimal(10,7) DEFAULT NULL COMMENT '经度',
  `latitude` decimal(10,7) DEFAULT NULL COMMENT '纬度',
  `extra_data` text COMMENT '扩展数据(JSON)',
  `creator` varchar(64) DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`),
  KEY `idx_run_id` (`run_id`),
  KEY `idx_vehicle_id` (`vehicle_id`),
  KEY `idx_event_type` (`event_type`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='模拟事件';

-- 模拟场景定义
CREATE TABLE IF NOT EXISTS `simulation_scenario` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  `name` varchar(100) NOT NULL COMMENT '场景名称',
  `description` varchar(500) DEFAULT NULL COMMENT '场景描述',
  `scenario_type` varchar(50) NOT NULL COMMENT '场景类型',
  `severity` tinyint NOT NULL DEFAULT 0 COMMENT '严重级别：0正常 1警告 2严重',
  `is_builtin` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否内置',
  `enabled` bit(1) NOT NULL DEFAULT b'1' COMMENT '是否启用',
  `config_json` text COMMENT '场景配置(JSON)',
  `creator` varchar(64) DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`),
  KEY `idx_scenario_type` (`scenario_type`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='模拟场景定义';

-- 模拟场景事件
CREATE TABLE IF NOT EXISTS `simulation_scenario_event` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  `scenario_id` bigint NOT NULL COMMENT '场景ID',
  `event_type` varchar(50) NOT NULL COMMENT '事件类型',
  `trigger_seconds` bigint DEFAULT NULL COMMENT '触发时刻(模拟秒)',
  `duration_seconds` bigint DEFAULT NULL COMMENT '持续时长(秒)',
  `title` varchar(200) NOT NULL COMMENT '事件标题',
  `content` varchar(1000) DEFAULT NULL COMMENT '事件内容',
  `severity` tinyint NOT NULL DEFAULT 0 COMMENT '严重级别：0信息 1警告 2严重',
  `parameters` text COMMENT '参数(JSON)',
  `creator` varchar(64) DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`),
  KEY `idx_scenario_id` (`scenario_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='模拟场景事件';

-- 内置场景数据（幂等：IGNORE 防重复）
INSERT IGNORE INTO `simulation_scenario` (`id`, `tenant_id`, `name`, `description`, `scenario_type`, `severity`, `is_builtin`, `enabled`, `config_json`, `creator`, `create_time`, `updater`, `update_time`, `deleted`) VALUES
(1, 0, '正常运营', '标准运营场景，无异常事件', 'NORMAL', 0, b'1', b'1', '{}', '1', NOW(), '1', NOW(), b'0'),
(2, 0, '高峰拥堵', '模拟高峰时段道路拥堵，车辆速度降低', 'CONGESTION', 1, b'1', b'1', '{"speedFactor":0.4,"triggerAtPercent":30,"durationPercent":20}', '1', NOW(), '1', NOW(), b'0'),
(3, 0, '车辆晚点', '模拟车辆因故晚点到达', 'DELAY', 1, b'1', b'1', '{"delaySeconds":300,"triggerAtPercent":50}', '1', NOW(), '1', NOW(), b'0'),
(4, 0, '车辆故障', '模拟车辆发生故障停驶', 'FAULT', 2, b'1', b'1', '{"triggerAtPercent":40,"durationSeconds":600}', '1', NOW(), '1', NOW(), b'0'),
(5, 0, 'GPS 丢失', '模拟GPS信号丢失', 'GPS_LOST', 1, b'1', b'1', '{"triggerAtPercent":20,"durationSeconds":120}', '1', NOW(), '1', NOW(), b'0'),
(6, 0, '司机离线', '模拟司机离线无法操作', 'DRIVER_OFFLINE', 1, b'1', b'1', '{"triggerAtPercent":35,"durationSeconds":300}', '1', NOW(), '1', NOW(), b'0'),
(7, 0, '临时订单增加', '模拟运行中新增临时订单', 'ORDER_SURGE', 0, b'1', b'1', '{"orderCount":5,"triggerAtPercent":25}', '1', NOW(), '1', NOW(), b'0'),
(8, 0, '临时订单取消', '模拟运行中取消部分订单', 'ORDER_CANCEL', 0, b'1', b'1', '{"cancelCount":3,"triggerAtPercent":60}', '1', NOW(), '1', NOW(), b'0'),
(9, 0, '道路异常', '模拟道路施工或事故导致路线变更', 'ROAD_BLOCK', 2, b'1', b'1', '{"triggerAtPercent":45,"durationPercent":10}', '1', NOW(), '1', NOW(), b'0');
