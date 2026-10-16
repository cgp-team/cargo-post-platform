-- ============================================
-- 宣传片演示数据 · 21 当日实况（拍前必跑，可反复执行）
--
-- 让平台在"拍摄当下"看起来正在真实运营：
--   A. 今日订单：从早上 06:00 到当前小时逐小时有单（大屏 24h 趋势图有曲线），
--      状态随时间推进（早上已完成 → 中午运输中/派送中 → 当前小时待入池/已入池）；
--   B. 调度流水线全状态：已完成方案 ×1、执行中方案 ×1（含跨车联运交接）、
--      已下发方案 ×1、待审核方案 ×1（算法刚算出，等调度员点"审核"）；
--   C. 班次执行：已发车的班次按"计划时间+线路站序"自动落成 在途/已完成，
--      在途车辆写入实时位置（监控地图 15 分钟窗口内，立即上图）与当日轨迹；
--   D. 通知/订单事件/算法请求记录。
--
-- 幂等且自清理：开头按 id 段删除本脚本负责的全部数据后重建，拍前重跑即"刷新到现在"。
-- 依赖：01~10、20 已执行。
-- ============================================

-- ---------- 0. 自清理 ----------
DELETE FROM transport_cargo_order            WHERE id >= 910000;
DELETE FROM transport_postal_order           WHERE id >= 910000;
DELETE FROM transport_passenger_order        WHERE id >= 910000;
DELETE FROM transport_order                  WHERE id >= 910000;
DELETE FROM transport_order_event            WHERE id >= 910000;
DELETE FROM transport_dispatch_plan_item     WHERE id >= 910000;
DELETE FROM transport_leg                    WHERE id >= 910000;
DELETE FROM transport_handover               WHERE id >= 910000;
DELETE FROM transport_dispatch_plan          WHERE id >= 910000;
DELETE FROM transport_dispatch_task          WHERE id >= 910000;
DELETE FROM transport_user_notification      WHERE id >= 910000;
DELETE FROM transport_algorithm_request      WHERE id >= 910000;
DELETE FROM transport_shift_execution        WHERE exec_date = CURDATE();
DELETE FROM transport_vehicle_location;       -- 每车一行，全量重建
DELETE FROM transport_vehicle_location_track WHERE id >= 910000;
DELETE FROM transport_driver_status;          -- 心跳全量刷新（含 04 脚本写入的 101~103）

SET @demo_uid = (SELECT id FROM member_user WHERE mobile = '13800000000' ORDER BY id LIMIT 1);

-- ---------- A1. 今日逐小时订单（06:00 ~ 当前小时） ----------
INSERT INTO transport_order
    (id, order_no, order_type, pickup_station_id, delivery_station_id,
     earliest_pickup_time, latest_delivery_time, status, total_amount, member_user_id,
     tenant_id, creator, create_time, updater, update_time, deleted)
WITH RECURSIVE hrs(h) AS (
    SELECT 6 UNION ALL SELECT h + 1 FROM hrs WHERE h < LEAST(HOUR(NOW()), 21)
),
tpl(k, otype, from_id, to_id, amt) AS (
    SELECT * FROM (VALUES
        ROW(0, 2, 410, 420,  8.50),   -- 邮电大学 → 海棠溪
        ROW(1, 2, 421, 448,  7.00),   -- 南坪站 → 四公里枢纽站
        ROW(2, 3, 409, 419,  5.50),   -- 黄桷垭 → 上新街（邮快件）
        ROW(3, 2, 448, 450,  6.50),   -- 四公里枢纽站 → 五公里
        ROW(4, 1, 101, 421,  4.50),   -- 重庆邮电大学站 → 南坪站（客运）
        ROW(5, 2, 420, 421,  7.50)    -- 海棠溪 → 南坪站
    ) AS v(k, otype, from_id, to_id, amt)
)
SELECT
    910100 + hrs.h * 10 + tpl.k,
    CONCAT('TP', DATE_FORMAT(CURDATE(), '%Y%m%d'), LPAD(hrs.h, 2, '0'), LPAD(tpl.k, 2, '0')),
    tpl.otype, tpl.from_id, tpl.to_id,
    TIMESTAMP(CURDATE(), MAKETIME(hrs.h, (tpl.k * 17 + hrs.h * 7) % 60, 0)),
    TIMESTAMP(CURDATE(), '23:59:59'),
    CASE
        WHEN hrs.h <= HOUR(NOW()) - 4 THEN 4                              -- 四小时前：已完成
        WHEN hrs.h  = HOUR(NOW()) - 3 THEN 12                             -- 派送中
        WHEN hrs.h  = HOUR(NOW()) - 2 THEN 10                             -- 运输中
        WHEN hrs.h  = HOUR(NOW()) - 1 THEN 3                              -- 已发车
        ELSE IF(tpl.k % 2 = 0, 8, 1)                                      -- 当前小时：待入池/已入池
    END,
    ROUND(tpl.amt + (hrs.h % 3) * 0.5, 2),
    IF((hrs.h + tpl.k) % 2 = 0, @demo_uid,
       COALESCE((SELECT m.id FROM (
           SELECT id, ROW_NUMBER() OVER (ORDER BY id) AS rn FROM member_user
           WHERE deleted = b'0' AND mobile LIKE '13911002%'
       ) m WHERE m.rn = 1 + MOD(hrs.h + tpl.k, 12)), 0)),
    0, '1',
    TIMESTAMP(CURDATE(), MAKETIME(hrs.h, (tpl.k * 17 + hrs.h * 7) % 60, 0)),
    '1',
    TIMESTAMP(CURDATE(), MAKETIME(LEAST(hrs.h + 3, 23), (tpl.k * 17 + hrs.h * 7) % 60, 0)),
    b'0'
FROM hrs JOIN tpl ON tpl.k < 1 + (hrs.h % 3);   -- 每小时 1~3 单，早高峰更多

-- A1 子表
INSERT INTO transport_cargo_order
    (id, order_id, cargo_category, fresh_flag, item_count, weight_kg, volume_m3, goods_name, goods_note,
     audit_status, review_status, pickup_service_mode, delivery_service_mode,
     receiver_name, receiver_mobile, receiver_address,
     tenant_id, creator, create_time, updater, update_time, deleted)
SELECT o.id, o.id,
    ELT(1 + (o.id % 5), '农产品', '日用品', '生鲜果蔬', '文件票据', '服饰鞋帽'),
    IF(o.id % 5 = 3, b'1', b'0'),
    1 + (o.id % 4), ROUND(1.5 + (o.id % 13) * 1.6, 2), ROUND(0.008 + (o.id % 7) * 0.007, 4),
    ELT(1 + (o.id % 8), '南山土鸡蛋', '应季蔬菜箱', '手工红薯粉', '学生书籍', '日用百货', '服饰包裹', '高山脆李', '农家蜂蜜'),
    ELT(1 + (o.id % 3), '轻拿轻放', '勿压', ''),
    1, 1, 'STATION_TO_STATION', 'STATION_TO_STATION',
    ELT(1 + (o.id % 6), '刘先生', '王女士', '李老师', '张同学', '陈老板', '杨女士'),
    CONCAT('135', LPAD(10000000 + (o.id % 89999999), 8, '0')),
    ELT(1 + (o.id % 4), '南岸区海棠溪正街', '南岸区南坪东路', '南岸区黄桷垭正街', '南岸区四公里'),
    0, '1', o.create_time, '1', o.update_time, b'0'
FROM transport_order o WHERE o.id BETWEEN 910100 AND 910499 AND o.order_type = 2;

INSERT INTO transport_passenger_order
    (id, order_id, passenger_count, contact_name, contact_mobile,
     tenant_id, creator, create_time, updater, update_time, deleted)
SELECT o.id, o.id, 1 + (o.id % 2),
    ELT(1 + (o.id % 6), '刘先生', '王女士', '李老师', '张同学', '陈老板', '杨女士'),
    CONCAT('136', LPAD(10000000 + (o.id % 89999999), 8, '0')),
    0, '1', o.create_time, '1', o.update_time, b'0'
FROM transport_order o WHERE o.id BETWEEN 910100 AND 910499 AND o.order_type = 1;

INSERT INTO transport_postal_order
    (id, order_id, mail_no, carrier_code, item_count, weight_kg,
     receiver_name, receiver_mobile, receiver_address, pickup_code, pickup_status, picked_up_time,
     tenant_id, creator, create_time, updater, update_time, deleted)
SELECT o.id, o.id,
    CONCAT('SF', DATE_FORMAT(CURDATE(), '%Y%m%d'), LPAD(o.id % 1000, 3, '0')), 'SF',
    1, ROUND(0.6 + (o.id % 5) * 0.7, 2),
    '张同学', CONCAT('135', LPAD(10000000 + (o.id % 89999999), 8, '0')),
    '南岸区上新街自取', LPAD(2000 + (o.id % 8000), 4, '0'),
    IF(o.status = 4, 1, 0), IF(o.status = 4, o.update_time, NULL),
    0, '1', o.create_time, '1', o.update_time, b'0'
FROM transport_order o WHERE o.id BETWEEN 910100 AND 910499 AND o.order_type = 3;

-- ---------- A2. 剧情订单（与下方调度方案成套；状态机与方案状态保持一致） ----------
INSERT INTO transport_order
    (id, order_no, order_type, pickup_station_id, delivery_station_id,
     earliest_pickup_time, latest_delivery_time, status, total_amount, member_user_id,
     tenant_id, creator, create_time, updater, update_time, deleted)
VALUES
    -- 早班已完成（方案 910001，320 路走廊）
    (910501, CONCAT('TP', DATE_FORMAT(CURDATE(), '%Y%m%d'), 'A01'), 2, 420, 448,
     TIMESTAMP(CURDATE(), '06:40:00'), TIMESTAMP(CURDATE(), '12:00:00'), 4, 9.50, @demo_uid,
     0, '1', TIMESTAMP(CURDATE(), '06:38:00'), '1', TIMESTAMP(CURDATE(), '08:12:00'), b'0'),
    (910502, CONCAT('TP', DATE_FORMAT(CURDATE(), '%Y%m%d'), 'A02'), 2, 448, 450,
     TIMESTAMP(CURDATE(), '06:50:00'), TIMESTAMP(CURDATE(), '12:00:00'), 4, 6.50, @demo_uid,
     0, '1', TIMESTAMP(CURDATE(), '06:47:00'), '1', TIMESTAMP(CURDATE(), '08:25:00'), b'0'),
    (910503, CONCAT('TP', DATE_FORMAT(CURDATE(), '%Y%m%d'), 'A03'), 2, 449, 451,
     TIMESTAMP(CURDATE(), '07:05:00'), TIMESTAMP(CURDATE(), '12:00:00'), 4, 6.50, @demo_uid,
     0, '1', TIMESTAMP(CURDATE(), '07:02:00'), '1', TIMESTAMP(CURDATE(), '08:40:00'), b'0'),
    -- 执行中（方案 910002，347→303 跨车联运 + 346 直达）
    (910511, CONCAT('TP', DATE_FORMAT(CURDATE(), '%Y%m%d'), 'B01'), 2, 410, 486,
     DATE_SUB(NOW(), INTERVAL 100 MINUTE), DATE_ADD(NOW(), INTERVAL 4 HOUR), 10, 15.00, @demo_uid,
     0, '1', DATE_SUB(NOW(), INTERVAL 105 MINUTE), '1', NOW(), b'0'),
    (910512, CONCAT('TP', DATE_FORMAT(CURDATE(), '%Y%m%d'), 'B02'), 2, 409, 419,
     DATE_SUB(NOW(), INTERVAL 90 MINUTE), DATE_ADD(NOW(), INTERVAL 4 HOUR), 10, 8.00, @demo_uid,
     0, '1', DATE_SUB(NOW(), INTERVAL 95 MINUTE), '1', NOW(), b'0'),
    -- 已下发（方案 910003，347 区间）
    (910513, CONCAT('TP', DATE_FORMAT(CURDATE(), '%Y%m%d'), 'C01'), 2, 410, 420,
     TIMESTAMP(CURDATE(), '12:00:00'), TIMESTAMP(CURDATE(), '20:00:00'), 2, 8.50, @demo_uid,
     0, '1', DATE_SUB(NOW(), INTERVAL 42 MINUTE), '1', NOW(), b'0'),
    (910514, CONCAT('TP', DATE_FORMAT(CURDATE(), '%Y%m%d'), 'C02'), 2, 420, 400,
     TIMESTAMP(CURDATE(), '12:00:00'), TIMESTAMP(CURDATE(), '20:00:00'), 2, 7.00, @demo_uid,
     0, '1', DATE_SUB(NOW(), INTERVAL 39 MINUTE), '1', NOW(), b'0'),
    -- 已入池，待审核方案 910004（自建线 R103）
    (910601, CONCAT('TP', DATE_FORMAT(CURDATE(), '%Y%m%d'), 'D01'), 2, 1146, 107,
     TIMESTAMP(CURDATE(), '00:00:00'), TIMESTAMP(CURDATE(), '23:59:59'), 1, 9.00, @demo_uid,
     0, '1', DATE_SUB(NOW(), INTERVAL 33 MINUTE), '1', NOW(), b'0'),
    (910602, CONCAT('TP', DATE_FORMAT(CURDATE(), '%Y%m%d'), 'D02'), 2, 410, 420,
     TIMESTAMP(CURDATE(), '00:00:00'), TIMESTAMP(CURDATE(), '23:59:59'), 1, 11.00, @demo_uid,
     0, '1', DATE_SUB(NOW(), INTERVAL 29 MINUTE), '1', NOW(), b'0'),
    (910603, CONCAT('TP', DATE_FORMAT(CURDATE(), '%Y%m%d'), 'D03'), 2, 419, 421,
     TIMESTAMP(CURDATE(), '00:00:00'), TIMESTAMP(CURDATE(), '23:59:59'), 1, 12.50, @demo_uid,
     0, '1', DATE_SUB(NOW(), INTERVAL 24 MINUTE), '1', NOW(), b'0'),
    -- 刚下单待入池（订单池演示"归集"动作）
    (910604, CONCAT('TP', DATE_FORMAT(CURDATE(), '%Y%m%d'), 'E01'), 2, 410, 421,
     TIMESTAMP(CURDATE(), '00:00:00'), TIMESTAMP(CURDATE(), '23:59:59'), 8, 7.50, @demo_uid,
     0, '1', DATE_SUB(NOW(), INTERVAL 18 MINUTE), '1', NOW(), b'0'),
    (910605, CONCAT('TP', DATE_FORMAT(CURDATE(), '%Y%m%d'), 'E02'), 2, 448, 513,
     TIMESTAMP(CURDATE(), '00:00:00'), TIMESTAMP(CURDATE(), '23:59:59'), 8, 13.00, @demo_uid,
     0, '1', DATE_SUB(NOW(), INTERVAL 11 MINUTE), '1', NOW(), b'0')
ON DUPLICATE KEY UPDATE status = VALUES(status), deleted = b'0';

-- A2 货运子表
INSERT INTO transport_cargo_order
    (id, order_id, cargo_category, fresh_flag, item_count, weight_kg, volume_m3, goods_name, goods_note,
     audit_status, review_status, pickup_service_mode, delivery_service_mode,
     receiver_name, receiver_mobile, receiver_address, original_address, original_latitude, original_longitude,
     tenant_id, creator, create_time, updater, update_time, deleted)
VALUES
    (910501, 910501, '生鲜果蔬', b'1', 3, 12.00, 0.0600, '海棠溪农贸市场时令蔬菜', '早市鲜货，优先配送', 1, 1,
     'STATION_TO_STATION', 'STATION_TO_STATION', '刘先生', '13512340501', '南岸区四公里枢纽站',
     '南岸区海棠溪正街', 29.5425000, 106.5885000, 0, '1', TIMESTAMP(CURDATE(), '06:38:00'), '1', NOW(), b'0'),
    (910502, 910502, '日用品', b'0', 2, 6.50, 0.0320, '四公里日用百货', '', 1, 1,
     'STATION_TO_STATION', 'STATION_TO_STATION', '王女士', '13512340502', '南岸区五公里',
     '南岸区四公里', 29.5110660, 106.5745160, 0, '1', TIMESTAMP(CURDATE(), '06:47:00'), '1', NOW(), b'0'),
    (910503, 910503, '日用品', b'0', 2, 5.00, 0.0250, '办公文具箱', '', 1, 1,
     'STATION_TO_STATION', 'STATION_TO_STATION', '李老师', '13512340503', '南岸区轨道六公里站',
     '南岸区四公里', 29.5110660, 106.5745160, 0, '1', TIMESTAMP(CURDATE(), '07:02:00'), '1', NOW(), b'0'),
    (910511, 910511, '文件票据', b'0', 1, 2.00, 0.0080, '录取通知书（交大新生）', '重邮寄往重庆交通大学，347→303 联运', 1, 1,
     'STATION_TO_STATION', 'STATION_TO_STATION', '张同学', '13512340511', '重庆交通大学南岸校区（七公里）',
     '重庆邮电大学', 29.5319520, 106.6034080, 0, '1', TIMESTAMP(CURDATE(), '10:26:00'), '1', NOW(), b'0'),
    (910512, 910512, '日用品', b'0', 2, 4.00, 0.0200, '黄桷垭手工艺品', '346 路直达', 1, 1,
     'STATION_TO_STATION', 'STATION_TO_STATION', '陈老板', '13512340512', '南岸区上新街',
     '南岸区黄桷垭', 29.5368310, 106.6043790, 0, '1', TIMESTAMP(CURDATE(), '10:55:00'), '1', NOW(), b'0'),
    (910513, 910513, '农产品', b'0', 2, 4.50, 0.0220, '南山春茶', '', 1, 1,
     'STATION_TO_STATION', 'STATION_TO_STATION', '杨女士', '13512340513', '南岸区海棠溪',
     '重庆邮电大学', 29.5319520, 106.6034080, 0, '1', DATE_SUB(NOW(), INTERVAL 42 MINUTE), '1', NOW(), b'0'),
    (910514, 910514, '日用品', b'0', 1, 3.00, 0.0150, '日用百货', '', 1, 1,
     'STATION_TO_STATION', 'STATION_TO_STATION', '黄先生', '13512340514', '南岸区上海城',
     '南岸区海棠溪', 29.5425000, 106.5885000, 0, '1', DATE_SUB(NOW(), INTERVAL 39 MINUTE), '1', NOW(), b'0'),
    (910601, 910601, '农产品', b'0', 2, 5.00, 0.0250, '南山枇杷', '明志苑驿站寄出', 1, 1,
     'STATION_TO_STATION', 'STATION_TO_STATION', '周女士', '13512340601', '重邮南门货运站',
     '重庆邮电大学明志苑2舍', 29.5310000, 106.6055000, 0, '1', DATE_SUB(NOW(), INTERVAL 33 MINUTE), '1', NOW(), b'0'),
    (910602, 910602, '日用品', b'0', 3, 6.00, 0.0300, '校园快递统包', '', 1, 1,
     'STATION_TO_STATION', 'STATION_TO_STATION', '吴同学', '13512340602', '南岸区海棠溪',
     '重庆邮电大学', 29.5319520, 106.6034080, 0, '1', DATE_SUB(NOW(), INTERVAL 29 MINUTE), '1', NOW(), b'0'),
    (910603, 910603, '日用品', b'0', 4, 9.00, 0.0450, '小商品市场补货', '', 1, 1,
     'STATION_TO_STATION', 'STATION_TO_STATION', '郑老板', '13512340603', '南岸区南坪站',
     '南岸区上新街', 29.5476910, 106.5936620, 0, '1', DATE_SUB(NOW(), INTERVAL 24 MINUTE), '1', NOW(), b'0'),
    (910604, 910604, '农产品', b'1', 1, 3.50, 0.0180, '现摘草莓（冷链优先）', '', 1, 1,
     'STATION_TO_STATION', 'STATION_TO_STATION', '林女士', '13512340604', '南岸区南坪站',
     '重庆邮电大学', 29.5319520, 106.6034080, 0, '1', DATE_SUB(NOW(), INTERVAL 18 MINUTE), '1', NOW(), b'0'),
    (910605, 910605, '日用品', b'0', 3, 8.50, 0.0420, '龙洲湾商户补货', '', 1, 1,
     'STATION_TO_STATION', 'STATION_TO_STATION', '冯老板', '13512340605', '巴南区龙洲湾枢纽站',
     '南岸区四公里', 29.5194420, 106.5778430, 0, '1', DATE_SUB(NOW(), INTERVAL 11 MINUTE), '1', NOW(), b'0')
ON DUPLICATE KEY UPDATE goods_name = VALUES(goods_name), deleted = b'0';

-- ---------- B. 调度任务 + 方案（流水线全状态） ----------
INSERT INTO transport_dispatch_task
    (id, task_no, snapshot_id, planning_time, algorithm_job_id, scenario, status,
     batch_start, batch_end, tenant_id, creator, create_time, updater, update_time, deleted)
VALUES
    (910001, CONCAT('DT', DATE_FORMAT(CURDATE(), '%Y%m%d'), '01'), CONCAT('SNAP-', DATE_FORMAT(CURDATE(), '%Y%m%d'), '-01'),
     TIMESTAMP(CURDATE(), '06:55:00'), CONCAT('JOB-', DATE_FORMAT(CURDATE(), '%Y%m%d'), '-01'), 'DAILY', 1,
     TIMESTAMP(CURDATE(), '06:00:00'), TIMESTAMP(CURDATE(), '12:00:00'), 0, '1', TIMESTAMP(CURDATE(), '06:55:00'), '1', TIMESTAMP(CURDATE(), '06:55:00'), b'0'),
    (910002, CONCAT('DT', DATE_FORMAT(CURDATE(), '%Y%m%d'), '02'), CONCAT('SNAP-', DATE_FORMAT(CURDATE(), '%Y%m%d'), '-02'),
     DATE_SUB(NOW(), INTERVAL 15 MINUTE), CONCAT('JOB-', DATE_FORMAT(CURDATE(), '%Y%m%d'), '-02'), 'DAILY', 1,
     TIMESTAMP(CURDATE(), '12:00:00'), TIMESTAMP(CURDATE(), '21:00:00'), 0, '1', DATE_SUB(NOW(), INTERVAL 15 MINUTE), '1', DATE_SUB(NOW(), INTERVAL 15 MINUTE), b'0')
ON DUPLICATE KEY UPDATE status = VALUES(status);

-- 方案 910001：早班已完成（320 路，渝A·B5202 / 李伟民）
INSERT INTO transport_dispatch_plan
    (id, task_id, plan_no, plan_version, algorithm_version, parameter_version, score, status,
     approved_by, approved_time, task_window_start, task_window_end,
     mode, planning_mode, total_leg_count, transfer_count, plan_reason,
     estimated_start_time, estimated_arrival_time, actual_start_time, actual_arrival_time,
     total_distance, est_duration_minutes, est_revenue, est_cost, route_provider,
     tenant_id, creator, create_time, updater, update_time, deleted)
VALUES
    (910001, 910001, CONCAT('PL', DATE_FORMAT(CURDATE(), '%Y%m%d'), '01'), 1, 'HACO-CPS-L2.0', 'P-2026Q4', 92.4800, 3,
     1, TIMESTAMP(CURDATE(), '07:02:00'), TIMESTAMP(CURDATE(), '06:00:00'), TIMESTAMP(CURDATE(), '12:00:00'),
     1, 'DIRECT', 3, 0, '早班 320 路顺路捎带：海棠溪→四公里→五公里方向 3 单同车共载，无绕行。',
     TIMESTAMP(CURDATE(), '07:30:00'), TIMESTAMP(CURDATE(), '08:35:00'),
     TIMESTAMP(CURDATE(), '07:28:00'), TIMESTAMP(CURDATE(), '08:33:00'),
     11.40, 65, 22.50, 31.92, 'GRAPHHOPPER',
     0, '1', TIMESTAMP(CURDATE(), '06:55:00'), '1', TIMESTAMP(CURDATE(), '08:33:00'), b'0'),
    -- 方案 910002：执行中（347 路 → 303 路跨车联运 + 346 路直达）
    (910002, 910001, CONCAT('PL', DATE_FORMAT(CURDATE(), '%Y%m%d'), '02'), 2, 'HACO-CPS-L2.0', 'P-2026Q4', 88.3100, 2,
     1, TIMESTAMP(CURDATE(), '10:35:00'), TIMESTAMP(CURDATE(), '10:00:00'), TIMESTAMP(CURDATE(), '16:00:00'),
     1, 'MULTI_LEG', 3, 1, '录取通知书无直达：347 路承运 邮电大学→南坪站，南坪站交接 303 路续运至 七公里；黄桷垭手工艺品 346 路直达，同窗口并线。',
     DATE_SUB(NOW(), INTERVAL 75 MINUTE), DATE_ADD(NOW(), INTERVAL 20 MINUTE),
     DATE_SUB(NOW(), INTERVAL 75 MINUTE), NULL,
     14.80, 110, 23.00, 41.44, 'GRAPHHOPPER',
     0, '1', TIMESTAMP(CURDATE(), '10:32:00'), '1', NOW(), b'0'),
    -- 方案 910003：已下发（347 路区间，下午班）
    (910003, 910002, CONCAT('PL', DATE_FORMAT(CURDATE(), '%Y%m%d'), '03'), 1, 'HACO-CPS-L2.0', 'P-2026Q4', 90.1200, 1,
     1, DATE_SUB(NOW(), INTERVAL 15 MINUTE), TIMESTAMP(CURDATE(), '12:00:00'), TIMESTAMP(CURDATE(), '21:00:00'),
     1, 'DIRECT', 2, 0, '347 路区间下午班顺路单：邮电大学→海棠溪→上海城，2 单共载。',
     DATE_ADD(NOW(), INTERVAL 40 MINUTE), DATE_ADD(NOW(), INTERVAL 110 MINUTE), NULL, NULL,
     6.20, 70, 15.50, 17.36, 'GRAPHHOPPER',
     0, '1', DATE_SUB(NOW(), INTERVAL 20 MINUTE), '1', DATE_SUB(NOW(), INTERVAL 15 MINUTE), b'0'),
    -- 方案 910004：待审核（自建线 R103，算法刚出方案）
    (910004, 910002, CONCAT('PL', DATE_FORMAT(CURDATE(), '%Y%m%d'), '04'), 2, 'HACO-CPS-L2.0', 'P-2026Q4', 95.2600, 0,
     NULL, NULL, TIMESTAMP(CURDATE(), '12:00:00'), TIMESTAMP(CURDATE(), '21:00:00'),
     1, 'DIRECT', 3, 0, '自建线 R103 片区归集：明志苑驿站→南门货运站交接，叠加 邮电大学→海棠溪、上新街→南坪 2 单顺路捎带，满载率 78%。',
     DATE_ADD(NOW(), INTERVAL 30 MINUTE), DATE_ADD(NOW(), INTERVAL 110 MINUTE), NULL, NULL,
     9.80, 80, 32.50, 27.44, 'GRAPHHOPPER',
     0, '1', DATE_SUB(NOW(), INTERVAL 12 MINUTE), '1', DATE_SUB(NOW(), INTERVAL 12 MINUTE), b'0')
ON DUPLICATE KEY UPDATE status = VALUES(status);

-- ---------- B2. 方案经停明细 ----------
-- 早班方案 910001（全部已完成 status=7）
INSERT INTO transport_dispatch_plan_item
    (id, plan_id, vehicle_id, driver_id, shift_id, order_id, station_id, visit_sequence, action_type,
     estimated_arrival_time, planned_departure_time, segment_duration_seconds, segment_distance_km,
     service_duration_seconds, quantity, status, service_mode,
     tenant_id, creator, create_time, updater, update_time, deleted)
VALUES
    (911001, 910001, 2, 2, 402, NULL,   420, 1, 0, TIMESTAMP(CURDATE(), '07:30:00'), TIMESTAMP(CURDATE(), '07:30:00'), 0,     0.00, 60,  NULL, 7, 'STATION_TO_STATION', 0, '1', TIMESTAMP(CURDATE(), '06:55:00'), '1', TIMESTAMP(CURDATE(), '07:28:00'), b'0'),
    (911002, 910001, 2, 2, 402, 910501, 420, 2, 4, TIMESTAMP(CURDATE(), '07:32:00'), TIMESTAMP(CURDATE(), '07:30:00'), 120,   0.00, 180, 3,    7, 'STATION_TO_STATION', 0, '1', TIMESTAMP(CURDATE(), '06:55:00'), '1', TIMESTAMP(CURDATE(), '07:32:00'), b'0'),
    (911003, 910001, 2, 2, 402, 910501, 448, 3, 3, TIMESTAMP(CURDATE(), '07:52:00'), TIMESTAMP(CURDATE(), '07:34:00'), 1080,  3.10, 180, 3,    7, 'STATION_TO_STATION', 0, '1', TIMESTAMP(CURDATE(), '06:55:00'), '1', TIMESTAMP(CURDATE(), '07:51:00'), b'0'),
    (911004, 910001, 2, 2, 402, 910502, 448, 4, 4, TIMESTAMP(CURDATE(), '07:55:00'), TIMESTAMP(CURDATE(), '07:54:00'), 180,   0.00, 180, 2,    7, 'STATION_TO_STATION', 0, '1', TIMESTAMP(CURDATE(), '06:55:00'), '1', TIMESTAMP(CURDATE(), '07:55:00'), b'0'),
    (911005, 910001, 2, 2, 402, 910502, 450, 5, 3, TIMESTAMP(CURDATE(), '08:06:00'), TIMESTAMP(CURDATE(), '07:57:00'), 540,   1.80, 180, 2,    7, 'STATION_TO_STATION', 0, '1', TIMESTAMP(CURDATE(), '06:55:00'), '1', TIMESTAMP(CURDATE(), '08:05:00'), b'0'),
    (911006, 910001, 2, 2, 402, 910503, 449, 6, 4, TIMESTAMP(CURDATE(), '08:10:00'), TIMESTAMP(CURDATE(), '08:08:00'), 240,   0.40, 180, 2,    7, 'STATION_TO_STATION', 0, '1', TIMESTAMP(CURDATE(), '06:55:00'), '1', TIMESTAMP(CURDATE(), '08:10:00'), b'0'),
    (911007, 910001, 2, 2, 402, 910503, 451, 7, 3, TIMESTAMP(CURDATE(), '08:18:00'), TIMESTAMP(CURDATE(), '08:12:00'), 360,   1.10, 180, 2,    7, 'STATION_TO_STATION', 0, '1', TIMESTAMP(CURDATE(), '06:55:00'), '1', TIMESTAMP(CURDATE(), '08:18:00'), b'0'),
    (911008, 910001, 2, 2, 402, NULL,   448, 8, 5, TIMESTAMP(CURDATE(), '08:35:00'), TIMESTAMP(CURDATE(), '08:20:00'), 900,   4.20, 60,  NULL, 7, 'STATION_TO_STATION', 0, '1', TIMESTAMP(CURDATE(), '06:55:00'), '1', TIMESTAMP(CURDATE(), '08:33:00'), b'0'),
    -- 执行中方案 910002：渝A·B5205（347 路，赵德柱）已完成部分 + 渝A·B5204（346 路，刘明轩）+ 渝A·B5208（303 路，吴海涛）续运段
    (911011, 910002, 5, 4, 958, NULL,   417, 1, 0, DATE_SUB(NOW(), INTERVAL 72 MINUTE), DATE_SUB(NOW(), INTERVAL 72 MINUTE), 0,    0.00, 60,  NULL, 7, 'STATION_TO_STATION', 0, '1', TIMESTAMP(CURDATE(), '10:32:00'), '1', DATE_SUB(NOW(), INTERVAL 72 MINUTE), b'0'),
    (911012, 910002, 5, 4, 958, 910511, 410, 2, 4, DATE_SUB(NOW(), INTERVAL 40 MINUTE), DATE_SUB(NOW(), INTERVAL 70 MINUTE), 2280, 9.20, 180, 1,    7, 'STATION_TO_STATION', 0, '1', TIMESTAMP(CURDATE(), '10:32:00'), '1', DATE_SUB(NOW(), INTERVAL 40 MINUTE), b'0'),
    (911013, 910002, 5, 4, 958, 910511, 421, 3, 3, DATE_SUB(NOW(), INTERVAL 25 MINUTE), DATE_SUB(NOW(), INTERVAL 38 MINUTE), 1620, 4.10, 300, 1,    7, 'STATION_TO_STATION', 0, '1', TIMESTAMP(CURDATE(), '10:32:00'), '1', DATE_SUB(NOW(), INTERVAL 25 MINUTE), b'0'),
    (911014, 910002, 8, 9, NULL, 910511, 421, 4, 4, DATE_SUB(NOW(), INTERVAL 18 MINUTE), TIMESTAMP(CURDATE(), '12:00:00'), 300,  0.00, 300, 1,    1, 'STATION_TO_STATION', 0, '1', TIMESTAMP(CURDATE(), '10:32:00'), '1', NOW(), b'0'),
    (911015, 910002, 8, 9, NULL, 910511, 486, 5, 3, DATE_ADD(NOW(), INTERVAL 15 MINUTE),  DATE_SUB(NOW(), INTERVAL 15 MINUTE), 1620, 7.30, 180, 1,    0, 'STATION_TO_STATION', 0, '1', TIMESTAMP(CURDATE(), '10:32:00'), '1', NOW(), b'0'),
    (911016, 910002, 4, 6, 953, NULL,   467, 6, 0, DATE_SUB(NOW(), INTERVAL 58 MINUTE), DATE_SUB(NOW(), INTERVAL 58 MINUTE), 0,    0.00, 60,  NULL, 7, 'STATION_TO_STATION', 0, '1', TIMESTAMP(CURDATE(), '10:32:00'), '1', DATE_SUB(NOW(), INTERVAL 58 MINUTE), b'0'),
    (911017, 910002, 4, 6, 953, 910512, 409, 7, 4, DATE_SUB(NOW(), INTERVAL 46 MINUTE), DATE_SUB(NOW(), INTERVAL 56 MINUTE), 720,  2.10, 180, 2,    7, 'STATION_TO_STATION', 0, '1', TIMESTAMP(CURDATE(), '10:32:00'), '1', DATE_SUB(NOW(), INTERVAL 46 MINUTE), b'0'),
    (911018, 910002, 4, 6, 953, 910512, 419, 8, 3, DATE_ADD(NOW(), INTERVAL 12 MINUTE),  DATE_SUB(NOW(), INTERVAL 44 MINUTE), 1380, 3.40, 180, 2,    1, 'STATION_TO_STATION', 0, '1', TIMESTAMP(CURDATE(), '10:32:00'), '1', NOW(), b'0'),
    -- 已下发方案 910003（渝A·B5201，347 路区间，周建国）
    (911021, 910003, 1, 1, NULL, NULL,   417, 1, 0, DATE_ADD(NOW(), INTERVAL 40 MINUTE),  DATE_ADD(NOW(), INTERVAL 40 MINUTE),  0,    0.00, 60,  NULL, 0, 'STATION_TO_STATION', 0, '1', DATE_SUB(NOW(), INTERVAL 20 MINUTE), '1', DATE_SUB(NOW(), INTERVAL 15 MINUTE), b'0'),
    (911022, 910003, 1, 1, NULL, 910513, 410, 2, 4, DATE_ADD(NOW(), INTERVAL 58 MINUTE),  DATE_ADD(NOW(), INTERVAL 42 MINUTE),  1080, 3.20, 180, 2,    0, 'STATION_TO_STATION', 0, '1', DATE_SUB(NOW(), INTERVAL 20 MINUTE), '1', DATE_SUB(NOW(), INTERVAL 15 MINUTE), b'0'),
    (911023, 910003, 1, 1, NULL, 910513, 420, 3, 3, DATE_ADD(NOW(), INTERVAL 75 MINUTE),  DATE_ADD(NOW(), INTERVAL 60 MINUTE),  1020, 2.60, 180, 2,    0, 'STATION_TO_STATION', 0, '1', DATE_SUB(NOW(), INTERVAL 20 MINUTE), '1', DATE_SUB(NOW(), INTERVAL 15 MINUTE), b'0'),
    (911024, 910003, 1, 1, NULL, 910514, 420, 4, 4, DATE_ADD(NOW(), INTERVAL 78 MINUTE),  DATE_ADD(NOW(), INTERVAL 77 MINUTE),  180,  0.00, 180, 1,    0, 'STATION_TO_STATION', 0, '1', DATE_SUB(NOW(), INTERVAL 20 MINUTE), '1', DATE_SUB(NOW(), INTERVAL 15 MINUTE), b'0'),
    (911025, 910003, 1, 1, NULL, 910514, 400, 5, 3, DATE_ADD(NOW(), INTERVAL 95 MINUTE),  DATE_ADD(NOW(), INTERVAL 80 MINUTE),  1020, 1.40, 180, 1,    0, 'STATION_TO_STATION', 0, '1', DATE_SUB(NOW(), INTERVAL 20 MINUTE), '1', DATE_SUB(NOW(), INTERVAL 15 MINUTE), b'0'),
    (911026, 910003, 1, 1, NULL, NULL,   417, 6, 5, DATE_ADD(NOW(), INTERVAL 110 MINUTE), DATE_ADD(NOW(), INTERVAL 97 MINUTE),  780,  2.80, 60,  NULL, 0, 'STATION_TO_STATION', 0, '1', DATE_SUB(NOW(), INTERVAL 20 MINUTE), '1', DATE_SUB(NOW(), INTERVAL 15 MINUTE), b'0'),
    -- 待审核方案 910004（CQUPT01，自建线 R103，何伟）
    (911031, 910004, 101, 101, NULL, NULL,   1146, 1, 0, DATE_ADD(NOW(), INTERVAL 30 MINUTE),  DATE_ADD(NOW(), INTERVAL 30 MINUTE),  0,    0.00, 60,  NULL, 0, 'STATION_TO_STATION', 0, '1', DATE_SUB(NOW(), INTERVAL 12 MINUTE), '1', DATE_SUB(NOW(), INTERVAL 12 MINUTE), b'0'),
    (911032, 910004, 101, 101, NULL, 910601, 1146, 2, 4, DATE_ADD(NOW(), INTERVAL 32 MINUTE),  DATE_ADD(NOW(), INTERVAL 31 MINUTE),  120,  0.00, 180, 2,    0, 'STATION_TO_STATION', 0, '1', DATE_SUB(NOW(), INTERVAL 12 MINUTE), '1', DATE_SUB(NOW(), INTERVAL 12 MINUTE), b'0'),
    (911033, 910004, 101, 101, NULL, 910601, 107,  3, 3, DATE_ADD(NOW(), INTERVAL 38 MINUTE),  DATE_ADD(NOW(), INTERVAL 34 MINUTE),  360,  0.60, 180, 2,    0, 'STATION_TO_STATION', 0, '1', DATE_SUB(NOW(), INTERVAL 12 MINUTE), '1', DATE_SUB(NOW(), INTERVAL 12 MINUTE), b'0'),
    (911034, 910004, 101, 101, NULL, 910602, 410,  4, 4, DATE_ADD(NOW(), INTERVAL 46 MINUTE),  DATE_ADD(NOW(), INTERVAL 40 MINUTE),  480,  1.30, 180, 3,    0, 'STATION_TO_STATION', 0, '1', DATE_SUB(NOW(), INTERVAL 12 MINUTE), '1', DATE_SUB(NOW(), INTERVAL 12 MINUTE), b'0'),
    (911035, 910004, 101, 101, NULL, 910602, 420,  5, 3, DATE_ADD(NOW(), INTERVAL 68 MINUTE),  DATE_ADD(NOW(), INTERVAL 48 MINUTE),  1320, 3.30, 180, 3,    0, 'STATION_TO_STATION', 0, '1', DATE_SUB(NOW(), INTERVAL 12 MINUTE), '1', DATE_SUB(NOW(), INTERVAL 12 MINUTE), b'0'),
    (911036, 910004, 101, 101, NULL, 910603, 419,  6, 4, DATE_ADD(NOW(), INTERVAL 74 MINUTE),  DATE_ADD(NOW(), INTERVAL 70 MINUTE),  360,  0.90, 180, 4,    0, 'STATION_TO_STATION', 0, '1', DATE_SUB(NOW(), INTERVAL 12 MINUTE), '1', DATE_SUB(NOW(), INTERVAL 12 MINUTE), b'0'),
    (911037, 910004, 101, 101, NULL, 910603, 421,  7, 3, DATE_ADD(NOW(), INTERVAL 95 MINUTE),  DATE_ADD(NOW(), INTERVAL 76 MINUTE),  1260, 2.90, 180, 4,    0, 'STATION_TO_STATION', 0, '1', DATE_SUB(NOW(), INTERVAL 12 MINUTE), '1', DATE_SUB(NOW(), INTERVAL 12 MINUTE), b'0'),
    (911038, 910004, 101, 101, NULL, NULL,   1146, 8, 5, DATE_ADD(NOW(), INTERVAL 110 MINUTE), DATE_ADD(NOW(), INTERVAL 97 MINUTE),  900,  3.80, 60,  NULL, 0, 'STATION_TO_STATION', 0, '1', DATE_SUB(NOW(), INTERVAL 12 MINUTE), '1', DATE_SUB(NOW(), INTERVAL 12 MINUTE), b'0')
ON DUPLICATE KEY UPDATE status = VALUES(status);

-- ---------- B3. 运输段（leg） ----------
INSERT INTO transport_leg
    (id, order_id, leg_sequence, from_station_id, to_station_id,
     vehicle_id, driver_id, shift_id, plan_item_id, plan_id, route_id, status,
     estimated_departure, estimated_arrival, actual_departure, actual_arrival,
     distance_km, duration_minutes, cargo_count, cargo_weight, handover_required,
     tenant_id, creator, create_time, updater, update_time, deleted)
VALUES
    (912001, 910501, 1, 420, 448, 2, 2, 402, 911002, 910001, 403, 11,
     TIMESTAMP(CURDATE(), '07:32:00'), TIMESTAMP(CURDATE(), '07:52:00'), TIMESTAMP(CURDATE(), '07:32:00'), TIMESTAMP(CURDATE(), '07:51:00'),
     3.10, 19, 3, 12.00, b'0', 0, '1', TIMESTAMP(CURDATE(), '06:55:00'), '1', TIMESTAMP(CURDATE(), '07:51:00'), b'0'),
    (912002, 910502, 1, 448, 450, 2, 2, 402, 911004, 910001, 403, 11,
     TIMESTAMP(CURDATE(), '07:55:00'), TIMESTAMP(CURDATE(), '08:06:00'), TIMESTAMP(CURDATE(), '07:55:00'), TIMESTAMP(CURDATE(), '08:05:00'),
     1.80, 10, 2, 6.50, b'0', 0, '1', TIMESTAMP(CURDATE(), '06:55:00'), '1', TIMESTAMP(CURDATE(), '08:05:00'), b'0'),
    (912003, 910503, 1, 449, 451, 2, 2, 402, 911006, 910001, 403, 11,
     TIMESTAMP(CURDATE(), '08:10:00'), TIMESTAMP(CURDATE(), '08:18:00'), TIMESTAMP(CURDATE(), '08:10:00'), TIMESTAMP(CURDATE(), '08:18:00'),
     1.50, 8, 2, 5.00, b'0', 0, '1', TIMESTAMP(CURDATE(), '06:55:00'), '1', TIMESTAMP(CURDATE(), '08:18:00'), b'0'),
    -- 910511 跨车联运：347 路段已完成 → 303 路段运输中（时间相对执行时刻，保证任意拍摄时段都在途）
    (912011, 910511, 1, 410, 421, 5, 4, 958, 911012, 910002, 402, 11,
     DATE_SUB(NOW(), INTERVAL 40 MINUTE), DATE_SUB(NOW(), INTERVAL 25 MINUTE), DATE_SUB(NOW(), INTERVAL 40 MINUTE), DATE_SUB(NOW(), INTERVAL 25 MINUTE),
     4.10, 27, 1, 2.00, b'1', 0, '1', TIMESTAMP(CURDATE(), '10:32:00'), '1', DATE_SUB(NOW(), INTERVAL 25 MINUTE), b'0'),
    (912012, 910511, 2, 421, 486, 8, 9, NULL, 911014, 910002, 407, 7,
     DATE_SUB(NOW(), INTERVAL 18 MINUTE), DATE_ADD(NOW(), INTERVAL 15 MINUTE), DATE_SUB(NOW(), INTERVAL 18 MINUTE), NULL,
     7.30, 30, 1, 2.00, b'0', 0, '1', TIMESTAMP(CURDATE(), '10:32:00'), '1', NOW(), b'0'),
    (912013, 910512, 1, 409, 419, 4, 6, 953, 911017, 910002, 405, 7,
     DATE_SUB(NOW(), INTERVAL 46 MINUTE), DATE_ADD(NOW(), INTERVAL 12 MINUTE), DATE_SUB(NOW(), INTERVAL 46 MINUTE), NULL,
     3.40, 23, 2, 4.00, b'0', 0, '1', TIMESTAMP(CURDATE(), '10:32:00'), '1', NOW(), b'0'),
    -- 910513/910514 已分配待执行
    (912021, 910513, 1, 410, 420, 1, 1, NULL, 911022, 910003, 401, 1,
     DATE_ADD(NOW(), INTERVAL 58 MINUTE), DATE_ADD(NOW(), INTERVAL 75 MINUTE), NULL, NULL,
     2.60, 17, 2, 4.50, b'0', 0, '1', DATE_SUB(NOW(), INTERVAL 20 MINUTE), '1', DATE_SUB(NOW(), INTERVAL 15 MINUTE), b'0'),
    (912022, 910514, 1, 420, 400, 1, 1, NULL, 911024, 910003, 401, 1,
     DATE_ADD(NOW(), INTERVAL 78 MINUTE), DATE_ADD(NOW(), INTERVAL 95 MINUTE), NULL, NULL,
     1.40, 15, 1, 3.00, b'0', 0, '1', DATE_SUB(NOW(), INTERVAL 20 MINUTE), '1', DATE_SUB(NOW(), INTERVAL 15 MINUTE), b'0'),
    -- 910601~910603 已规划（待审核方案）
    (912031, 910601, 1, 1146, 107, NULL, NULL, NULL, NULL, 910004, 103, 0,
     DATE_ADD(NOW(), INTERVAL 32 MINUTE), DATE_ADD(NOW(), INTERVAL 38 MINUTE), NULL, NULL,
     0.60, 6, 2, 5.00, b'0', 0, '1', DATE_SUB(NOW(), INTERVAL 12 MINUTE), '1', DATE_SUB(NOW(), INTERVAL 12 MINUTE), b'0'),
    (912032, 910602, 1, 410, 420, NULL, NULL, NULL, NULL, 910004, 103, 0,
     DATE_ADD(NOW(), INTERVAL 46 MINUTE), DATE_ADD(NOW(), INTERVAL 68 MINUTE), NULL, NULL,
     3.30, 22, 3, 6.00, b'0', 0, '1', DATE_SUB(NOW(), INTERVAL 12 MINUTE), '1', DATE_SUB(NOW(), INTERVAL 12 MINUTE), b'0'),
    (912033, 910603, 1, 419, 421, NULL, NULL, NULL, NULL, 910004, 103, 0,
     DATE_ADD(NOW(), INTERVAL 74 MINUTE), DATE_ADD(NOW(), INTERVAL 95 MINUTE), NULL, NULL,
     2.90, 21, 4, 9.00, b'0', 0, '1', DATE_SUB(NOW(), INTERVAL 12 MINUTE), '1', DATE_SUB(NOW(), INTERVAL 12 MINUTE), b'0')
ON DUPLICATE KEY UPDATE status = VALUES(status);

-- ---------- B4. 交接记录（910511 在南坪站完成跨车交接） ----------
INSERT INTO transport_handover
    (id, order_id, plan_id, leg_from_id, leg_to_id, station_id,
     from_driver_id, to_driver_id, from_vehicle_id, to_vehicle_id,
     item_count, weight_kg, status,
     arrived_at, handover_started_at, handover_completed_at, handover_time, confirm_time, confirmed_by, remark,
     tenant_id, creator, create_time, updater, update_time, deleted)
VALUES
    (912501, 910511, 910002, 912011, 912012, 421, 4, 9, 5, 8, 1, 2.00, 4,
     DATE_SUB(NOW(), INTERVAL 25 MINUTE), DATE_SUB(NOW(), INTERVAL 24 MINUTE), DATE_SUB(NOW(), INTERVAL 21 MINUTE),
     DATE_SUB(NOW(), INTERVAL 21 MINUTE), DATE_SUB(NOW(), INTERVAL 20 MINUTE), 9, '南坪站同站台交接，包装完好',
     0, '1', TIMESTAMP(CURDATE(), '10:32:00'), '1', DATE_SUB(NOW(), INTERVAL 20 MINUTE), b'0')
ON DUPLICATE KEY UPDATE status = VALUES(status);

-- ---------- C1. 今日班次执行（按各班计划时刻自动落成 在途/已完成） ----------
-- 口径：计划发车时间早于 now → 有执行记录；窗口内=在途(0)，窗口已过=已完成(1)。
-- 每条运营线路取一台绑定车（driver_vehicle 里该线路 id 最小的一条绑定）。
INSERT INTO transport_shift_execution
    (id, shift_id, driver_id, vehicle_id, exec_date, depart_time, arrive_time, current_station_id, loaded_count,
     status, tenant_id, creator, updater, deleted)
SELECT
    910000 + s.id,
    s.id, dv.driver_id, dv.vehicle_id, CURDATE(),
    TIMESTAMP(CURDATE(), s.planned_departure_time),
    IF(TIMESTAMP(CURDATE(), s.planned_departure_time) < DATE_SUB(NOW(), INTERVAL (s.planned_duration_minutes + 20) MINUTE),
       DATE_ADD(TIMESTAMP(CURDATE(), s.planned_departure_time), INTERVAL s.planned_duration_minutes MINUTE), NULL),
    IF(TIMESTAMP(CURDATE(), s.planned_departure_time) >= DATE_SUB(NOW(), INTERVAL (s.planned_duration_minutes + 20) MINUTE),
       (SELECT rs.station_id FROM transport_route_station rs
         WHERE rs.route_id = s.route_id AND rs.deleted = b'0' ORDER BY rs.sequence_no LIMIT 1 OFFSET 1), NULL),
    2 + (s.id % 7),
    IF(TIMESTAMP(CURDATE(), s.planned_departure_time) < DATE_SUB(NOW(), INTERVAL (s.planned_duration_minutes + 20) MINUTE), 1, 0),
    0, '1', '1', b'0'
FROM transport_shift s
JOIN transport_driver_vehicle dv
  ON dv.route_id = s.route_id AND dv.status = 1 AND dv.deleted = b'0'
 AND dv.id = (SELECT MIN(d2.id) FROM transport_driver_vehicle d2
               WHERE d2.route_id = s.route_id AND d2.status = 1 AND d2.deleted = b'0')
WHERE s.status = 0 AND s.deleted = b'0'
  AND s.planned_departure_time <= CURTIME();

-- ---------- C2. 在途车辆实时位置（监控地图 15 分钟窗口；同一车辆多条在途执行时取最新一班） ----------
INSERT INTO transport_vehicle_location
    (id, vehicle_id, shift_id, longitude, latitude, speed_kmh, report_time, tenant_id, creator, updater, deleted)
SELECT
    915000 + e.vehicle_id,
    e.vehicle_id, e.shift_id,
    st.longitude + 0.00045, st.latitude + 0.00032,
    18 + (e.vehicle_id % 18),
    DATE_SUB(NOW(), INTERVAL (e.vehicle_id % 3) MINUTE),
    0, '1', '1', b'0'
FROM (
    SELECT e.*, ROW_NUMBER() OVER (PARTITION BY e.vehicle_id ORDER BY e.depart_time DESC) AS rn
    FROM transport_shift_execution e
    WHERE e.exec_date = CURDATE() AND e.status = 0 AND e.deleted = b'0'
) e
JOIN transport_station st ON st.id = e.current_station_id
WHERE e.rn = 1;

-- ---------- C3. 在途车辆当日轨迹（沿线路站序插值到"现在"） ----------
INSERT INTO transport_vehicle_location_track
    (id, vehicle_id, shift_id, longitude, latitude, speed_kmh, report_time, tenant_id, creator, updater, deleted)
SELECT
    916000 + (@trk := COALESCE(@trk, 0) + 1),
    e.vehicle_id, e.shift_id,
    st.longitude, st.latitude,
    20 + ((e.vehicle_id + rs.sequence_no) % 15),
    DATE_ADD(e.depart_time, INTERVAL rs.planned_minutes MINUTE),
    0, '1', '1', b'0'
FROM transport_shift_execution e
JOIN transport_shift s ON s.id = e.shift_id
JOIN transport_route_station rs ON rs.route_id = s.route_id AND rs.deleted = b'0'
JOIN transport_station st ON st.id = rs.station_id
CROSS JOIN (SELECT @trk := 916000) init
WHERE e.exec_date = CURDATE() AND e.status = 0 AND e.deleted = b'0'
  AND DATE_ADD(e.depart_time, INTERVAL rs.planned_minutes MINUTE) <= NOW()
ORDER BY e.vehicle_id, rs.sequence_no;

-- ---------- C4. 司机在线状态 ----------
INSERT INTO transport_driver_status
    (id, driver_id, online_status, current_vehicle_id, current_plan_id, last_heartbeat, last_latitude, last_longitude,
     tenant_id, create_time, update_time)
SELECT
    917000 + dv.driver_id,
    dv.driver_id, 1, dv.vehicle_id,
    CASE WHEN dv.vehicle_id IN (4, 5, 8) THEN 910002 ELSE NULL END,
    DATE_SUB(NOW(), INTERVAL (dv.driver_id % 4) MINUTE),
    st.latitude, st.longitude,
    0, NOW(), NOW()
FROM transport_driver_vehicle dv
JOIN transport_route r ON r.id = dv.route_id
JOIN transport_station st ON st.id = r.start_station_id
WHERE dv.status = 1 AND dv.deleted = b'0';

-- ---------- D1. 订单事件（剧情订单全链路时间线） ----------
INSERT INTO transport_order_event (id, order_id, event_type, event_time, operator, detail, tenant_id, create_time)
SELECT 913000 + o.id - 910500, o.id, 'ORDER_CREATED', o.create_time, '用户小程序', '用户提交寄货订单', 0, o.create_time
FROM transport_order o WHERE o.id BETWEEN 910501 AND 910605;
INSERT INTO transport_order_event (id, order_id, event_type, event_time, operator, detail, tenant_id, create_time)
SELECT 913200 + o.id - 910500, o.id, 'REVIEW_PASSED', DATE_ADD(o.create_time, INTERVAL 3 MINUTE), '系统', '承运审核通过', 0, o.create_time
FROM transport_order o WHERE o.id BETWEEN 910501 AND 910605;
INSERT INTO transport_order_event (id, order_id, event_type, event_time, operator, detail, tenant_id, create_time) VALUES
    (913401, 910501, 'PLAN_ISSUED', TIMESTAMP(CURDATE(), '07:02:00'), '调度员', '早班方案已下发，渝A·B5202 承运', 0, TIMESTAMP(CURDATE(), '07:02:00')),
    (913402, 910501, 'DEPARTED',    TIMESTAMP(CURDATE(), '07:32:00'), '司机 李伟民', '已从海棠溪揽收发车', 0, TIMESTAMP(CURDATE(), '07:32:00')),
    (913403, 910501, 'COMPLETED',   TIMESTAMP(CURDATE(), '07:51:00'), '司机 李伟民', '已送达四公里枢纽站', 0, TIMESTAMP(CURDATE(), '07:51:00')),
    (913404, 910511, 'PLAN_ISSUED', DATE_SUB(NOW(), INTERVAL 80 MINUTE), '调度员', '联运方案已下发：347 路 → 南坪站交接 → 303 路', 0, DATE_SUB(NOW(), INTERVAL 80 MINUTE)),
    (913405, 910511, 'DEPARTED',    DATE_SUB(NOW(), INTERVAL 40 MINUTE), '司机 赵德柱', '已从邮电大学揽收发车（347 路）', 0, DATE_SUB(NOW(), INTERVAL 40 MINUTE)),
    (913406, 910511, 'HANDOVER_COMPLETED', DATE_SUB(NOW(), INTERVAL 21 MINUTE), '系统', '南坪站交接完成，303 路续运中', 0, DATE_SUB(NOW(), INTERVAL 21 MINUTE)),
    (913407, 910512, 'DEPARTED',    DATE_SUB(NOW(), INTERVAL 46 MINUTE), '司机 刘明轩', '已从黄桷垭揽收发车（346 路）', 0, DATE_SUB(NOW(), INTERVAL 46 MINUTE)),
    (913408, 910513, 'PLAN_ISSUED', DATE_SUB(NOW(), INTERVAL 15 MINUTE), '调度员', '下午班方案已下发，渝A·B5201 承运', 0, NOW()),
    (913409, 910601, 'ORDER_POOLED', DATE_SUB(NOW(), INTERVAL 30 MINUTE), '调度员', '归集入池，等待智能调度', 0, NOW());

-- ---------- D2. 用户/司机通知 ----------
INSERT INTO transport_user_notification
    (id, user_id, recipient_type, event_id, event_type, title, content, order_id, plan_id, level, action_required, read_status, read_time, tenant_id, create_time)
VALUES
    (913501, @demo_uid, 'USER', CONCAT('PROMO-', CURDATE(), '-N01'), 'REVIEW_PASSED', '承运审核通过',
     '您的寄货订单（录取通知书）已通过承运审核，将为您安排最近班次。', 910511, NULL, 'INFO', b'0', 1, TIMESTAMP(CURDATE(), '10:30:00'), 0, TIMESTAMP(CURDATE(), '10:29:00')),
    (913502, @demo_uid, 'USER', CONCAT('PROMO-', CURDATE(), '-N02'), 'DEPARTED', '您的包裹已发车',
     '渝A·B5205（347 路）已从 邮电大学 揽收发车，预计 25 分钟后到达 南坪站 交接。', 910511, 910002, 'SUCCESS', b'0', 1, DATE_SUB(NOW(), INTERVAL 38 MINUTE), 0, DATE_SUB(NOW(), INTERVAL 40 MINUTE)),
    (913503, @demo_uid, 'USER', CONCAT('PROMO-', CURDATE(), '-N03'), 'HANDOVER_COMPLETED', '包裹已完成中转交接',
     '您的包裹已在 南坪站 完成交接，现由 渝A·B5208（303 路）续运至 七公里。', 910511, 910002, 'INFO', b'0', 0, NULL, 0, DATE_SUB(NOW(), INTERVAL 21 MINUTE)),
    (913504, @demo_uid, 'USER', CONCAT('PROMO-', CURDATE(), '-N04'), 'PLAN_ISSUED', '已为您安排运力',
     '订单（南山春茶）已分配 渝A·B5201（347 路区间），预计 1 小时内揽收。', 910513, 910003, 'INFO', b'0', 0, NULL, 0, DATE_SUB(NOW(), INTERVAL 15 MINUTE)),
    (913505, 4, 'DRIVER', CONCAT('PROMO-', CURDATE(), '-N05'), 'PLAN_ISSUED', '新调度任务',
     '您有新的运输任务：347 路 邮电大学→南坪站 段，请及时查看并发车。', 910511, 910002, 'ACTION_REQUIRED', b'1', 1, DATE_SUB(NOW(), INTERVAL 78 MINUTE), 0, DATE_SUB(NOW(), INTERVAL 80 MINUTE)),
    (913506, 1, 'DRIVER', CONCAT('PROMO-', CURDATE(), '-N06'), 'PLAN_ISSUED', '新调度任务',
     '下午班任务已下发：347 路区间 2 单顺路捎带，请按经停计划执行。', NULL, 910003, 'ACTION_REQUIRED', b'1', 0, NULL, 0, DATE_SUB(NOW(), INTERVAL 15 MINUTE))
ON DUPLICATE KEY UPDATE read_status = VALUES(read_status);

-- ---------- D3. 算法请求记录 ----------
INSERT INTO transport_algorithm_request
    (id, request_id, snapshot_hash, request_json, response_json, status, algorithm_version, parameter_version,
     tenant_id, creator, create_time, updater, update_time, deleted)
VALUES
    (918001, CONCAT('REQ-', DATE_FORMAT(CURDATE(), '%Y%m%d'), '-01'), CONCAT('sha1-', DATE_FORMAT(CURDATE(), '%Y%m%d'), '-a1'),
     '{"orders":9,"vehicles":6,"window":"06:00-12:00"}', '{"score":92.48,"plans":2,"assigned":9}', 1,
     'HACO-CPS-L2.0', 'P-2026Q4', 0, '1', TIMESTAMP(CURDATE(), '06:55:00'), '1', TIMESTAMP(CURDATE(), '06:55:00'), b'0'),
    (918002, CONCAT('REQ-', DATE_FORMAT(CURDATE(), '%Y%m%d'), '-02'), CONCAT('sha1-', DATE_FORMAT(CURDATE(), '%Y%m%d'), '-b2'),
     '{"orders":5,"vehicles":4,"window":"12:00-21:00"}', '{"score":95.26,"plans":2,"assigned":5}', 1,
     'HACO-CPS-L2.0', 'P-2026Q4', 0, '1', DATE_SUB(NOW(), INTERVAL 15 MINUTE), '1', DATE_SUB(NOW(), INTERVAL 15 MINUTE), b'0')
ON DUPLICATE KEY UPDATE status = VALUES(status);

-- ---------- 只读校验 ----------
SELECT '今日订单' AS info, status, COUNT(*) AS cnt FROM transport_order
WHERE create_time >= CURDATE() GROUP BY status ORDER BY status;
SELECT '方案' AS info, id, status, planning_mode, est_revenue FROM transport_dispatch_plan WHERE id >= 910000 ORDER BY id;
SELECT '班次执行' AS info, status, COUNT(*) AS cnt FROM transport_shift_execution WHERE exec_date = CURDATE() GROUP BY status;
SELECT '在途车辆位置' AS info, COUNT(*) AS cnt FROM transport_vehicle_location WHERE report_time >= DATE_SUB(NOW(), INTERVAL 15 MINUTE);
