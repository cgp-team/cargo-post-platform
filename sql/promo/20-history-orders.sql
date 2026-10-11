-- ============================================
-- 宣传片演示数据 · 20 历史运营订单（近 45 天）
--
-- 用途：数据大屏的订单总量/金额/类型分布/状态分布、管理端订单列表，
--       看起来是"已经稳定运营一个半月"的平台，而不是只有今天的演示单。
--
-- 口径：
--   · 全部历史订单 status=4 已完成（少量 5 已取消，约 3%，更真实）；
--   · create_time 分散在每天 07:00~19:00，单量随时间缓慢增长、周末略高（趋势图好看）；
--   · 起终点全部用重庆真实站点/自建站点 id（南岸/渝中/沙坪坝/巴南走廊）；
--   · member_user_id 分散到演示会员（10-members-products.sql 创建）；
--   · 每单配套子表（货运/客运/邮快件），后台详情页不会出现"缺子表"的空页面。
--
-- 幂等：先删 id ∈ [900000, 909999] 再重建，可重复执行（不会碰当日实况 910000+ 与演示池单）。
-- 依赖：01~10 已执行（站点、会员存在）。
-- ============================================

DELETE FROM transport_cargo_order     WHERE id BETWEEN 900000 AND 909999;
DELETE FROM transport_postal_order    WHERE id BETWEEN 900000 AND 909999;
DELETE FROM transport_passenger_order WHERE id BETWEEN 900000 AND 909999;
DELETE FROM transport_order           WHERE id BETWEEN 900000 AND 909999;

-- ---------- 主表 ----------
-- 模板表：24 条真实走向（类型/起点/终点/基准金额），每天取前 N 条，N 随日期增长
INSERT INTO transport_order
    (id, order_no, order_type, pickup_station_id, delivery_station_id,
     earliest_pickup_time, latest_delivery_time, status, total_amount, member_user_id,
     tenant_id, creator, create_time, updater, update_time, deleted)
WITH RECURSIVE days(d) AS (
    SELECT 1 UNION ALL SELECT d + 1 FROM days WHERE d < 45
),
tpl(slot, otype, from_id, to_id, amt) AS (
    SELECT * FROM (VALUES
        ROW( 1, 2, 410, 420,  9.50),   -- 邮电大学 → 海棠溪
        ROW( 2, 2, 420, 421,  8.00),   -- 海棠溪 → 南坪站
        ROW( 3, 3, 421, 448,  5.00),   -- 南坪站 → 四公里枢纽站（邮快件）
        ROW( 4, 1, 448, 450,  4.50),   -- 四公里枢纽站 → 五公里（客运）
        ROW( 5, 2, 409, 419,  7.50),   -- 黄桷垭 → 上新街
        ROW( 6, 2, 419, 425, 12.00),   -- 上新街 → 菜园坝火车站
        ROW( 7, 2, 101, 102,  6.50),   -- 重庆邮电大学站 → 黄桷垭站
        ROW( 8, 3, 410, 448,  6.00),   -- 邮电大学 → 四公里枢纽站（邮快件）
        ROW( 9, 2, 103, 102, 10.50),   -- 南山站 → 黄桷垭站
        ROW(10, 2, 410, 486, 14.00),   -- 邮电大学 → 七公里（重庆交通大学）
        ROW(11, 1, 421, 410,  5.00),   -- 南坪站 → 邮电大学（客运）
        ROW(12, 2, 421, 513, 22.00),   -- 南坪站 → 龙洲湾枢纽站
        ROW(13, 2, 1146, 448,  7.00),  -- 重邮明志苑驿站 → 四公里枢纽站
        ROW(14, 3, 448, 450,  4.00),   -- 四公里枢纽站 → 五公里（邮快件）
        ROW(15, 2, 623, 421, 18.00),   -- 重大A区 → 南坪站（跨区）
        ROW(16, 2, 101, 619, 26.00),   -- 重庆邮电大学站 → 沙坪坝火车站（跨区）
        ROW(17, 1, 410, 421,  4.50),   -- 邮电大学 → 南坪站（客运）
        ROW(18, 2, 410, 1150, 15.00),  -- 邮电大学 → 重庆工商大学站
        ROW(19, 3, 419, 543,  6.50),   -- 上新街 → 磁器街（邮快件）
        ROW(20, 2, 543, 410, 16.00),   -- 磁器街 → 邮电大学
        ROW(21, 2, 417, 400, 11.00),   -- 老厂 → 上海城
        ROW(22, 1, 448, 421,  4.00),   -- 四公里枢纽站 → 南坪站（客运）
        ROW(23, 2, 486, 410, 13.00),   -- 七公里 → 邮电大学（回程）
        ROW(24, 3, 101, 107,  3.50)    -- 重庆邮电大学站 → 重邮南门货运站（邮快件）
    ) AS v(slot, otype, from_id, to_id, amt)
)
SELECT
    900000 + d.d * 100 + t.slot,
    CONCAT('TP', DATE_FORMAT(DATE_SUB(CURDATE(), INTERVAL d.d DAY), '%Y%m%d'), LPAD(t.slot, 2, '0')),
    t.otype,
    t.from_id,
    t.to_id,
    TIMESTAMP(DATE_SUB(CURDATE(), INTERVAL d.d DAY),
              MAKETIME(7 + ((d.d * 31 + t.slot * 17) % 11), (t.slot * 13 + d.d * 7) % 60, 0)),
    TIMESTAMP(DATE_SUB(CURDATE(), INTERVAL d.d DAY),
              MAKETIME(19 + ((d.d * 31 + t.slot * 17) % 3), (t.slot * 13 + d.d * 7) % 60, 0)),
    IF((d.d * 31 + t.slot) % 37 = 0, 5, 4),
    ROUND(t.amt + ((d.d * 7 + t.slot * 3) % 5) * 0.5, 2),
    COALESCE((SELECT m.id FROM (
        SELECT id, ROW_NUMBER() OVER (ORDER BY id) AS rn FROM member_user
        WHERE deleted = b'0' AND (mobile = '13800000000' OR mobile LIKE '13911002%')
    ) m WHERE m.rn = 1 + MOD(d.d + t.slot, 13)), 0),
    0, '1',
    TIMESTAMP(DATE_SUB(CURDATE(), INTERVAL d.d DAY),
              MAKETIME(7 + ((d.d * 31 + t.slot * 17) % 11), (t.slot * 13 + d.d * 7) % 60, 0)),
    '1',
    TIMESTAMP(DATE_SUB(CURDATE(), INTERVAL d.d DAY),
              MAKETIME(19 + ((d.d * 31 + t.slot * 17) % 3), (t.slot * 29 + d.d * 11) % 60, 0)),
    b'0'
FROM days d
JOIN tpl t ON t.slot <= 9 + FLOOR((45 - d.d) / 4)              -- 单量缓慢增长：45天前≈9单/天 → 昨天≈20单/天
             + IF(DAYOFWEEK(DATE_SUB(CURDATE(), INTERVAL d.d DAY)) IN (1, 7), 3, 0)  -- 周末 +3
             + (d.d % 3);                                       -- 抖动，避免完全平滑

-- ---------- 货运子表 ----------
INSERT INTO transport_cargo_order
    (id, order_id, cargo_category, fresh_flag, item_count, weight_kg, volume_m3, goods_name, goods_note,
     audit_status, review_status, pickup_service_mode, delivery_service_mode,
     receiver_name, receiver_mobile, receiver_address,
     tenant_id, creator, create_time, updater, update_time, deleted)
SELECT
    o.id, o.id,
    ELT(1 + (o.id % 6), '农产品', '日用品', '生鲜果蔬', '文件票据', '服饰鞋帽', '家电小件'),
    IF(o.id % 6 = 3, b'1', b'0'),
    1 + (o.id % 5),
    ROUND(2 + (o.id % 17) * 1.8, 2),
    ROUND(0.01 + (o.id % 9) * 0.008, 4),
    ELT(1 + (o.id % 10),
        '南山土鸡蛋', '高山脆李', '农家蜂蜜', '手工红薯粉', '腊肉礼盒',
        '日用百货箱', '学生书籍', '应季蔬菜', '服饰包裹', '小家电'),
    ELT(1 + (o.id % 4), '轻拿轻放', '勿压', '', '到站自提'),
    1, 1, 'STATION_TO_STATION', 'STATION_TO_STATION',
    ELT(1 + (o.id % 8), '刘先生', '王女士', '李老师', '张同学', '陈老板', '杨女士', '黄先生', '周女士'),
    CONCAT('139', LPAD(20000000 + (o.id % 89999999), 8, '0')),
    ELT(1 + (o.id % 6),
        '南岸区海棠溪正街', '南岸区南坪东路', '南岸区黄桷垭正街', '南岸区四公里枢纽站旁',
        '巴南区龙洲湾商圈', '渝中区磁器街'),
    0, '1', o.create_time, '1', o.update_time, b'0'
FROM transport_order o
WHERE o.id BETWEEN 900000 AND 909999 AND o.order_type = 2;

-- ---------- 客运子表 ----------
INSERT INTO transport_passenger_order
    (id, order_id, passenger_count, contact_name, contact_mobile,
     tenant_id, creator, create_time, updater, update_time, deleted)
SELECT
    o.id, o.id, 1 + (o.id % 3),
    ELT(1 + (o.id % 8), '刘先生', '王女士', '李老师', '张同学', '陈老板', '杨女士', '黄先生', '周女士'),
    CONCAT('138', LPAD(30000000 + (o.id % 89999999), 8, '0')),
    0, '1', o.create_time, '1', o.update_time, b'0'
FROM transport_order o
WHERE o.id BETWEEN 900000 AND 909999 AND o.order_type = 1;

-- ---------- 邮快件子表 ----------
INSERT INTO transport_postal_order
    (id, order_id, mail_no, carrier_code, item_count, weight_kg,
     receiver_name, receiver_mobile, receiver_address, pickup_code, pickup_status, picked_up_time,
     tenant_id, creator, create_time, updater, update_time, deleted)
SELECT
    o.id, o.id,
    CONCAT(ELT(1 + (o.id % 3), 'SF', 'YT', 'ZT'), DATE_FORMAT(o.create_time, '%Y%m%d'), LPAD(o.id % 100, 3, '0')),
    ELT(1 + (o.id % 3), 'SF', 'YTO', 'ZTO'),
    1 + (o.id % 2),
    ROUND(0.5 + (o.id % 6) * 0.8, 2),
    ELT(1 + (o.id % 8), '刘先生', '王女士', '李老师', '张同学', '陈老板', '杨女士', '黄先生', '周女士'),
    CONCAT('137', LPAD(40000000 + (o.id % 89999999), 8, '0')),
    ELT(1 + (o.id % 4), '南岸区四公里枢纽站自取', '南岸区南坪站自取', '南岸区邮电大学站自取', '渝中区磁器街自取'),
    LPAD(1000 + (o.id % 9000), 4, '0'),
    1, o.update_time,
    0, '1', o.create_time, '1', o.update_time, b'0'
FROM transport_order o
WHERE o.id BETWEEN 900000 AND 909999 AND o.order_type = 3;

-- ---------- 只读校验 ----------
SELECT '历史订单' AS info, COUNT(*) AS cnt, SUM(total_amount) AS amount,
       MIN(create_time) AS first_day, MAX(create_time) AS last_day
FROM transport_order WHERE id BETWEEN 900000 AND 909999;
SELECT order_type, COUNT(*) AS cnt FROM transport_order
WHERE id BETWEEN 900000 AND 909999 GROUP BY order_type ORDER BY order_type;
