-- =====================================================================
-- purge-demo-data.sql — 一次性手工清库：移除全部比赛演示数据，回到运营初始状态
--
-- ⚠️ 危险操作，仅允许手工执行，绝不加入任何 CI/自动化流程！
-- 执行前必须：
--   1. bash deploy/scripts/backup.sh 完成全量备份，且备份文件已拉离服务器
--   2. 已确认 V020（模拟表清理）已随部署执行
-- 用法（在服务器 /opt/cargo-post-platform 目录下）：
--   docker exec -i cargo-post-platform-mysql-1 mysql --default-character-set=utf8mb4 \
--     -uroot -p"$(grep ^DB_PASSWORD .env | cut -d= -f2)" cargo_post_dev < deploy/scripts/purge-demo-data.sql
--
-- 清空范围（2026-09-21 与运营方确认「全部清空」）：
--   · 全部业务数据：订单/调度/运输段/交接/监控位置/通知/反馈/公告/商品及其订单/上传文件
--   · 全部资源数据：站点/线路/班次/车辆/司机/人车绑定（含比赛期高德线网，运营后重新录入）
--   · 全部小程序会员（均演示账号）及收货地址
--   · 演示系统用户与演示租户（仅保留超级管理员 admin）
--   · 全部登录会话 token（强制重新登录）
--   · 早期遗留表 transport_app_user / transport_app_driver（代码已无引用，直接 DROP）
-- 保留：system_* 体系（admin/角色/菜单/字典/区域）、infra_job 等基础设施、
--       transport_pricing_rule 计价规则等配置数据
-- =====================================================================

-- ---- 执行前计数（人工核对范围） ----
SELECT '== BEFORE ==' AS phase;
SELECT 'transport_order' AS tbl, COUNT(*) AS cnt FROM transport_order
UNION ALL SELECT 'transport_product', COUNT(*) FROM transport_product
UNION ALL SELECT 'transport_station', COUNT(*) FROM transport_station
UNION ALL SELECT 'transport_route', COUNT(*) FROM transport_route
UNION ALL SELECT 'transport_vehicle', COUNT(*) FROM transport_vehicle
UNION ALL SELECT 'transport_driver', COUNT(*) FROM transport_driver
UNION ALL SELECT 'transport_shift', COUNT(*) FROM transport_shift
UNION ALL SELECT 'member_user', COUNT(*) FROM member_user
UNION ALL SELECT 'system_users', COUNT(*) FROM system_users
UNION ALL SELECT 'system_tenant', COUNT(*) FROM system_tenant;

SET FOREIGN_KEY_CHECKS = 0;

-- ---- 业务数据 ----
TRUNCATE TABLE transport_order;
TRUNCATE TABLE transport_cargo_order;
TRUNCATE TABLE transport_passenger_order;
TRUNCATE TABLE transport_postal_order;
TRUNCATE TABLE transport_order_event;
TRUNCATE TABLE transport_product_order;
TRUNCATE TABLE transport_product_order_item;
TRUNCATE TABLE transport_dispatch_plan;
TRUNCATE TABLE transport_dispatch_plan_item;
TRUNCATE TABLE transport_dispatch_plan_log;
TRUNCATE TABLE transport_dispatch_task;
TRUNCATE TABLE transport_departure_check;
TRUNCATE TABLE transport_leg;
TRUNCATE TABLE transport_handover;
TRUNCATE TABLE transport_shift_execution;
TRUNCATE TABLE transport_vehicle_location;
TRUNCATE TABLE transport_vehicle_location_track;
TRUNCATE TABLE transport_driver_status;
TRUNCATE TABLE transport_user_notification;
TRUNCATE TABLE transport_feedback;
TRUNCATE TABLE transport_notice;
TRUNCATE TABLE transport_product;
TRUNCATE TABLE transport_algorithm_request;
TRUNCATE TABLE infra_file;

-- ---- 资源数据（站点/线路/班次/车辆/司机） ----
TRUNCATE TABLE transport_station;
TRUNCATE TABLE transport_route;
TRUNCATE TABLE transport_route_station;
TRUNCATE TABLE transport_shift;
TRUNCATE TABLE transport_vehicle;
TRUNCATE TABLE transport_driver;
TRUNCATE TABLE transport_driver_vehicle;

-- ---- 小程序会员（均演示账号） ----
TRUNCATE TABLE member_user;
TRUNCATE TABLE member_address;

-- ---- 演示系统用户与租户（仅保留超级管理员 admin） ----
DELETE FROM system_user_role WHERE user_id <> 1;
DELETE FROM system_user_post WHERE user_id <> 1;
DELETE FROM system_users WHERE id <> 1;
DELETE FROM system_tenant WHERE id <> 1;

-- ---- 登录会话（强制重新登录） ----
TRUNCATE TABLE system_oauth2_access_token;
TRUNCATE TABLE system_oauth2_refresh_token;

-- ---- 早期遗留表（代码已无引用） ----
DROP TABLE IF EXISTS transport_app_user;
DROP TABLE IF EXISTS transport_app_driver;

SET FOREIGN_KEY_CHECKS = 1;

-- ---- 执行后计数（应全为 0 / 仅剩 admin 与系统租户） ----
SELECT '== AFTER ==' AS phase;
SELECT 'transport_order' AS tbl, COUNT(*) AS cnt FROM transport_order
UNION ALL SELECT 'transport_product', COUNT(*) FROM transport_product
UNION ALL SELECT 'transport_station', COUNT(*) FROM transport_station
UNION ALL SELECT 'transport_route', COUNT(*) FROM transport_route
UNION ALL SELECT 'transport_vehicle', COUNT(*) FROM transport_vehicle
UNION ALL SELECT 'transport_driver', COUNT(*) FROM transport_driver
UNION ALL SELECT 'transport_shift', COUNT(*) FROM transport_shift
UNION ALL SELECT 'member_user', COUNT(*) FROM member_user
UNION ALL SELECT 'system_users', COUNT(*) FROM system_users
UNION ALL SELECT 'system_tenant', COUNT(*) FROM system_tenant;
