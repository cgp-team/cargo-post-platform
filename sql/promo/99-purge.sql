-- ============================================
-- 宣传片演示数据 · 99 全量清除（恢复拍前空库状态）
--
-- 删除 promo 套件创建的一切数据（基础层 + 历史层 + 实况层 + 点缀层），
-- 不触碰 system_*/infra_*/QRTZ_* 与 admin 账号。
-- 幂等：可重复执行。
-- ============================================

-- 实况/历史/点缀层（按 id 段）
DELETE FROM transport_cargo_order            WHERE id >= 900000;
DELETE FROM transport_postal_order           WHERE id >= 900000;
DELETE FROM transport_passenger_order        WHERE id >= 900000;
DELETE FROM transport_order                  WHERE id >= 900000;
DELETE FROM transport_order_event            WHERE id >= 900000;
DELETE FROM transport_dispatch_plan_item     WHERE id >= 900000;
DELETE FROM transport_leg                    WHERE id >= 900000;
DELETE FROM transport_handover               WHERE id >= 900000;
DELETE FROM transport_dispatch_plan          WHERE id >= 900000;
DELETE FROM transport_dispatch_task          WHERE id >= 900000;
DELETE FROM transport_user_notification      WHERE id >= 900000;
DELETE FROM transport_algorithm_request      WHERE id >= 900000;
DELETE FROM transport_notice                 WHERE id >= 920000;
DELETE FROM transport_feedback               WHERE id >= 920000;
DELETE FROM transport_product_order_item     WHERE id >= 920000;
DELETE FROM transport_product_order          WHERE id >= 920000;
DELETE FROM transport_departure_check;
DELETE FROM transport_dispatch_plan_log;
DELETE FROM system_notice WHERE id >= 930000;
DELETE FROM transport_vehicle_location;
DELETE FROM transport_vehicle_location_track;
DELETE FROM transport_shift_execution;
DELETE FROM transport_driver_status;

-- 演示订单池（07 脚本：TPCQ/TPDEMO/TP346/TPJTU/TPCQUA 前缀）
DELETE FROM transport_cargo_order WHERE order_id IN (SELECT id FROM (SELECT id FROM transport_order WHERE order_no LIKE 'TPCQ%' OR order_no LIKE 'TPDEMO%' OR order_no LIKE 'TP346%' OR order_no LIKE 'TPJTU%' OR order_no LIKE 'TPCQUA%') t);
DELETE FROM transport_postal_order WHERE order_id IN (SELECT id FROM (SELECT id FROM transport_order WHERE order_no LIKE 'TPCQ%' OR order_no LIKE 'TPDEMO%') t);
DELETE FROM transport_order WHERE order_no LIKE 'TPCQ%' OR order_no LIKE 'TPDEMO%' OR order_no LIKE 'TP346%' OR order_no LIKE 'TPJTU%' OR order_no LIKE 'TPCQUA%';

-- 基础层：班次/站序/线路/站点/绑定/司机/车辆/商品/会员
DELETE FROM transport_shift;
DELETE FROM transport_route_station;
DELETE FROM transport_route;
DELETE FROM transport_station;
DELETE FROM transport_driver_vehicle;
DELETE FROM transport_driver;
DELETE FROM transport_vehicle;
DELETE FROM transport_product;
DELETE FROM transport_pricing_rule;
DELETE FROM member_address;
DELETE FROM member_user WHERE mobile IN (
    '13800000000',
    '13800138001','13800138002','13800138003','13800138004',
    '13800138006','13800138007','13800138008','13800138009',
    '13900001001','13900001002','13900001003',
    '13911002001','13911002002','13911002003','13911002004','13911002005','13911002006',
    '13911002007','13911002008','13911002009','13911002010','13911002011','13911002012');

SELECT 'purge done' AS info,
       (SELECT COUNT(*) FROM transport_order) AS orders,
       (SELECT COUNT(*) FROM transport_station) AS stations,
       (SELECT COUNT(*) FROM transport_vehicle) AS vehicles,
       (SELECT COUNT(*) FROM member_user) AS members;
