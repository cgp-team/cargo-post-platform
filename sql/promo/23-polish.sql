-- ============================================
-- 宣传片演示数据 · 23 细节打磨（在 21 之后执行；刷新时请一并重跑）
--
-- 补齐镜头扫到的"小角落"：
--   1. 发车核验记录（方案 910001/910002 已核验通过）
--   2. 方案流转日志（dispatch_plan_log：创建→审核→核验→发车→完成）
--   3. 车辆实时状态（执行中方案的车辆 = 在途）
--   4. 两张"待承运审核"的新订单（审核中心有活可干）
--   5. 订单时间线补全：今日逐小时订单 + 订单池 15 单都补 ORDER_CREATED/REVIEW_PASSED 事件
--   6. 后台首页系统公告（system_notice）
--   7. 商城订单再加几单（近 5 天）
-- 幂等：按各自 id 段自清理后重建；依赖 01~22 已执行。
-- ============================================

DELETE FROM transport_departure_check   WHERE id >= 914000;
DELETE FROM transport_dispatch_plan_log WHERE id >= 914100;
DELETE FROM transport_order_event       WHERE id >= 913700;
DELETE FROM transport_cargo_order       WHERE id BETWEEN 910700 AND 910799;
DELETE FROM transport_order             WHERE id BETWEEN 910700 AND 910799;
DELETE FROM system_notice               WHERE id >= 930000;
DELETE FROM transport_product_order_item WHERE id BETWEEN 920010 AND 920099;
DELETE FROM transport_product_order      WHERE id BETWEEN 920010 AND 920099;

-- ---------- 1. 发车核验（result: 1 通过） ----------
INSERT INTO transport_departure_check
    (id, plan_id, vehicle_id, result, remark, checker, tenant_id, creator, create_time, updater, update_time, deleted)
VALUES
    (914001, 910001, 2, 1, '早班车况良好，货仓已清舱', '调度员·王敏', 0, '1', TIMESTAMP(CURDATE(), '07:25:00'), '1', TIMESTAMP(CURDATE(), '07:25:00'), b'0'),
    (914002, 910002, 5, 1, '证照齐全，冷链箱已装车', '调度员·王敏', 0, '1', DATE_SUB(NOW(), INTERVAL 76 MINUTE), '1', DATE_SUB(NOW(), INTERVAL 76 MINUTE), b'0'),
    (914003, 910002, 4, 1, '正常发车', '调度员·王敏', 0, '1', DATE_SUB(NOW(), INTERVAL 60 MINUTE), '1', DATE_SUB(NOW(), INTERVAL 60 MINUTE), b'0');

-- ---------- 2. 方案流转日志 ----------
INSERT INTO transport_dispatch_plan_log
    (id, plan_id, from_status, to_status, operator, reason, tenant_id, creator, create_time, updater, update_time, deleted)
VALUES
    (914101, 910001, NULL, 0, 'HACO-CPS 算法', '规划成功，得分 92.48', 0, '1', TIMESTAMP(CURDATE(), '06:55:00'), '1', TIMESTAMP(CURDATE(), '06:55:00'), b'0'),
    (914102, 910001, 0, 1, '调度员·王敏', '审核通过并下发', 0, '1', TIMESTAMP(CURDATE(), '07:02:00'), '1', TIMESTAMP(CURDATE(), '07:02:00'), b'0'),
    (914103, 910001, 1, 2, '调度员·王敏', '发车核验通过', 0, '1', TIMESTAMP(CURDATE(), '07:25:00'), '1', TIMESTAMP(CURDATE(), '07:25:00'), b'0'),
    (914104, 910001, 2, 3, '系统', '全部经停完成，方案完结', 0, '1', TIMESTAMP(CURDATE(), '08:33:00'), '1', TIMESTAMP(CURDATE(), '08:33:00'), b'0'),
    (914105, 910002, NULL, 0, 'HACO-CPS 算法', '规划成功，得分 88.31（含 1 次跨车交接）', 0, '1', TIMESTAMP(CURDATE(), '10:32:00'), '1', TIMESTAMP(CURDATE(), '10:32:00'), b'0'),
    (914106, 910002, 0, 1, '调度员·王敏', '审核通过并下发', 0, '1', TIMESTAMP(CURDATE(), '10:35:00'), '1', TIMESTAMP(CURDATE(), '10:35:00'), b'0'),
    (914107, 910002, 1, 2, '调度员·王敏', '发车核验通过', 0, '1', DATE_SUB(NOW(), INTERVAL 76 MINUTE), '1', DATE_SUB(NOW(), INTERVAL 76 MINUTE), b'0'),
    (914108, 910003, NULL, 0, 'HACO-CPS 算法', '规划成功，得分 90.12', 0, '1', DATE_SUB(NOW(), INTERVAL 20 MINUTE), '1', DATE_SUB(NOW(), INTERVAL 20 MINUTE), b'0'),
    (914109, 910003, 0, 1, '调度员·王敏', '审核通过并下发', 0, '1', DATE_SUB(NOW(), INTERVAL 15 MINUTE), '1', DATE_SUB(NOW(), INTERVAL 15 MINUTE), b'0'),
    (914110, 910004, NULL, 0, 'HACO-CPS 算法', '规划成功，得分 95.26，等待人工审核', 0, '1', DATE_SUB(NOW(), INTERVAL 12 MINUTE), '1', DATE_SUB(NOW(), INTERVAL 12 MINUTE), b'0');

-- ---------- 3. 车辆实时状态（执行中方案的三台车在途，其余空闲） ----------
UPDATE transport_vehicle SET realtime_status = 1 WHERE id IN (4, 5, 8) AND deleted = b'0';
UPDATE transport_vehicle SET realtime_status = 0 WHERE id NOT IN (4, 5, 8) AND deleted = b'0';

-- ---------- 4. 待承运审核的新订单（审核中心镜头） ----------
SET @demo_uid := (SELECT id FROM member_user WHERE mobile = '13800000000' ORDER BY id LIMIT 1);
SET @m7 := (SELECT id FROM member_user WHERE mobile = '13911002007' ORDER BY id LIMIT 1);

INSERT INTO transport_order
    (id, order_no, order_type, pickup_station_id, delivery_station_id,
     earliest_pickup_time, latest_delivery_time, status, total_amount, member_user_id,
     tenant_id, creator, create_time, updater, update_time, deleted)
VALUES
    (910701, CONCAT('TP', DATE_FORMAT(CURDATE(), '%Y%m%d'), 'R01'), 2, 103, 421,
     TIMESTAMP(CURDATE(), '00:00:00'), TIMESTAMP(CURDATE(), '23:59:59'), 6, 14.00, @m7,
     0, '1', DATE_SUB(NOW(), INTERVAL 22 MINUTE), '1', DATE_SUB(NOW(), INTERVAL 22 MINUTE), b'0'),
    (910702, CONCAT('TP', DATE_FORMAT(CURDATE(), '%Y%m%d'), 'R02'), 2, 410, 543,
     TIMESTAMP(CURDATE(), '00:00:00'), TIMESTAMP(CURDATE(), '23:59:59'), 6, 16.50, @demo_uid,
     0, '1', DATE_SUB(NOW(), INTERVAL 8 MINUTE), '1', DATE_SUB(NOW(), INTERVAL 8 MINUTE), b'0')
ON DUPLICATE KEY UPDATE status = VALUES(status);

INSERT INTO transport_cargo_order
    (id, order_id, cargo_category, fresh_flag, item_count, weight_kg, volume_m3, goods_name, goods_note,
     audit_status, review_status, pickup_service_mode, delivery_service_mode,
     receiver_name, receiver_mobile, receiver_address, original_address, original_latitude, original_longitude,
     tenant_id, creator, create_time, updater, update_time, deleted)
VALUES
    (910701, 910701, '生鲜果蔬', b'1', 4, 18.00, 0.0900, '南山鲜菌菇', '生鲜件，需审核冷链承运条件', 0, 0,
     'STATION_TO_STATION', 'STATION_TO_STATION', '黄丽华', '13911002007', '南岸区南坪站',
     '南山植物园', 29.5554000, 106.6280000, 0, '1', DATE_SUB(NOW(), INTERVAL 22 MINUTE), '1', NOW(), b'0'),
    (910702, 910702, '家电小件', b'0', 1, 4.20, 0.0600, '小型电饭煲', '易碎，需确认包装', 0, 0,
     'STATION_TO_STATION', 'STATION_TO_STATION', '演示用户', '13800000000', '渝中区磁器街',
     '重庆邮电大学', 29.5319520, 106.6034080, 0, '1', DATE_SUB(NOW(), INTERVAL 8 MINUTE), '1', NOW(), b'0')
ON DUPLICATE KEY UPDATE goods_name = VALUES(goods_name);

INSERT INTO transport_order_event (id, order_id, event_type, event_time, operator, detail, tenant_id, create_time)
VALUES
    (913701, 910701, 'ORDER_CREATED', DATE_SUB(NOW(), INTERVAL 22 MINUTE), '用户小程序', '用户提交寄货订单', 0, DATE_SUB(NOW(), INTERVAL 22 MINUTE)),
    (913702, 910702, 'ORDER_CREATED', DATE_SUB(NOW(), INTERVAL 8 MINUTE), '用户小程序', '用户提交寄货订单', 0, DATE_SUB(NOW(), INTERVAL 8 MINUTE));

-- ---------- 5. 订单时间线补全（今日逐小时单 + 订单池 15 单） ----------
-- 今日逐小时单：创建 + 审核通过（按各单 create_time）
INSERT INTO transport_order_event (id, order_id, event_type, event_time, operator, detail, tenant_id, create_time)
SELECT 920000 + (o.id - 910100), o.id, 'ORDER_CREATED', o.create_time, '用户小程序', '用户提交寄货订单', 0, o.create_time
FROM transport_order o WHERE o.id BETWEEN 910100 AND 910499;
INSERT INTO transport_order_event (id, order_id, event_type, event_time, operator, detail, tenant_id, create_time)
SELECT 920400 + (o.id - 910100), o.id, 'REVIEW_PASSED', DATE_ADD(o.create_time, INTERVAL 3 MINUTE), '系统', '承运审核通过', 0, o.create_time
FROM transport_order o WHERE o.id BETWEEN 910100 AND 910499;

-- 订单池 15 单（07 脚本生成，id 201~239）：补创建/审核事件
INSERT INTO transport_order_event (id, order_id, event_type, event_time, operator, detail, tenant_id, create_time)
SELECT 921000 + o.id, o.id, 'ORDER_CREATED', o.create_time, '用户小程序', '用户提交寄货订单', 0, o.create_time
FROM transport_order o
WHERE o.id BETWEEN 201 AND 239 AND o.deleted = b'0';
INSERT INTO transport_order_event (id, order_id, event_type, event_time, operator, detail, tenant_id, create_time)
SELECT 922000 + o.id, o.id, 'REVIEW_PASSED', DATE_ADD(o.create_time, INTERVAL 5 MINUTE), '系统', '承运审核通过', 0, o.create_time
FROM transport_order o
WHERE o.id BETWEEN 201 AND 239 AND o.deleted = b'0' AND o.status IN (8, 1, 2, 3, 10, 11, 12, 4);

-- ---------- 6. 后台首页系统公告 ----------
INSERT INTO system_notice (id, title, content, type, status, creator, create_time, updater, update_time, deleted, tenant_id)
VALUES
    (930001, '平台运营周报：本周订单量环比增长 12%', '本周客货邮融合订单稳步增长，跨片区联运占比提升至 31%。各线路请继续保持班次准点率。', 1, 0, '1', DATE_SUB(NOW(), INTERVAL 2 DAY), '1', DATE_SUB(NOW(), INTERVAL 2 DAY), b'0', 0),
    (930002, '关于做好生鲜件承运审核的通知', '生鲜果蔬类订单审核时需确认冷链/时效条件，不符合的引导客户选择次日达班次。', 2, 0, '1', DATE_SUB(NOW(), INTERVAL 5 DAY), '1', DATE_SUB(NOW(), INTERVAL 5 DAY), b'0', 0);

-- ---------- 7. 商城订单补充（近 5 天，多为已完成） ----------
SET @m1 := (SELECT id FROM member_user WHERE mobile = '13911002001' ORDER BY id LIMIT 1);
SET @m2 := (SELECT id FROM member_user WHERE mobile = '13911002002' ORDER BY id LIMIT 1);
SET @m9 := (SELECT id FROM member_user WHERE mobile = '13911002009' ORDER BY id LIMIT 1);

INSERT INTO transport_product_order
    (id, order_no, user_id, user_mobile, total_amount, status, vehicle_id, shift_id, driver_id, deliver_station_id,
     receiver_name, receiver_mobile, receiver_address, remark, load_time, deliver_time,
     tenant_id, creator, create_time, updater, update_time, deleted)
VALUES
    (920010, CONCAT('PO', DATE_FORMAT(DATE_SUB(CURDATE(), INTERVAL 4 DAY), '%Y%m%d'), '01'), @m1, '13911002001', 90.00, 2, 2, 402, 2, 448,
     '王雪梅', '13911002001', '南岸区四公里枢纽站旁小区', '土鸡蛋30枚装 2 箱',
     TIMESTAMP(DATE_SUB(CURDATE(), INTERVAL 4 DAY), '08:10:00'), TIMESTAMP(DATE_SUB(CURDATE(), INTERVAL 4 DAY), '09:20:00'),
     0, '1', TIMESTAMP(DATE_SUB(CURDATE(), INTERVAL 4 DAY), '07:40:00'), '1', TIMESTAMP(DATE_SUB(CURDATE(), INTERVAL 4 DAY), '09:20:00'), b'0'),
    (920011, CONCAT('PO', DATE_FORMAT(DATE_SUB(CURDATE(), INTERVAL 3 DAY), '%Y%m%d'), '02'), @m2, '13911002002', 45.60, 2, 5, 958, 4, 421,
     '李长顺', '13911002002', '南岸区南坪站', '涪陵榨菜 2 袋 + 米花糖 1 袋',
     TIMESTAMP(DATE_SUB(CURDATE(), INTERVAL 3 DAY), '11:05:00'), TIMESTAMP(DATE_SUB(CURDATE(), INTERVAL 3 DAY), '12:15:00'),
     0, '1', TIMESTAMP(DATE_SUB(CURDATE(), INTERVAL 3 DAY), '10:20:00'), '1', TIMESTAMP(DATE_SUB(CURDATE(), INTERVAL 3 DAY), '12:15:00'), b'0'),
    (920012, CONCAT('PO', DATE_FORMAT(DATE_SUB(CURDATE(), INTERVAL 2 DAY), '%Y%m%d'), '03'), @m9, '13911002009', 176.00, 2, 4, 953, 6, 419,
     '吴秀英', '13911002009', '南岸区上新街', '农家腊肉 2 斤',
     TIMESTAMP(DATE_SUB(CURDATE(), INTERVAL 2 DAY), '14:15:00'), TIMESTAMP(DATE_SUB(CURDATE(), INTERVAL 2 DAY), '15:30:00'),
     0, '1', TIMESTAMP(DATE_SUB(CURDATE(), INTERVAL 2 DAY), '13:45:00'), '1', TIMESTAMP(DATE_SUB(CURDATE(), INTERVAL 2 DAY), '15:30:00'), b'0'),
    (920013, CONCAT('PO', DATE_FORMAT(DATE_SUB(CURDATE(), INTERVAL 1 DAY), '%Y%m%d'), '04'), @m1, '13911002001', 55.00, 2, 101, 920, 101, 410,
     '王雪梅', '13911002001', '重庆邮电大学家属区', '野生山核桃 1 斤',
     TIMESTAMP(DATE_SUB(CURDATE(), INTERVAL 1 DAY), '07:20:00'), TIMESTAMP(DATE_SUB(CURDATE(), INTERVAL 1 DAY), '08:10:00'),
     0, '1', TIMESTAMP(DATE_SUB(CURDATE(), INTERVAL 1 DAY), '06:55:00'), '1', TIMESTAMP(DATE_SUB(CURDATE(), INTERVAL 1 DAY), '08:10:00'), b'0'),
    (920014, CONCAT('PO', DATE_FORMAT(DATE_SUB(CURDATE(), INTERVAL 1 DAY), '%Y%m%d'), '05'), @m2, '13911002002', 32.80, 1, 2, 414, 2, 448,
     '李长顺', '13911002002', '南岸区四公里', '重庆小面调料包 1 盒',
     DATE_SUB(NOW(), INTERVAL 26 HOUR), NULL,
     0, '1', DATE_SUB(NOW(), INTERVAL 27 HOUR), '1', DATE_SUB(NOW(), INTERVAL 26 HOUR), b'0');

INSERT INTO transport_product_order_item
    (id, order_id, product_id, product_name, product_image, product_price, quantity, amount, tenant_id, creator, updater, deleted)
VALUES
    (920010, 920010, 2, '土鸡蛋30枚装', '🥚', 45.00, 2, 90.00, 0, '1', '1', b'0'),
    (920011, 920011, 9, '涪陵榨菜（鲜脆榨菜丝）', '', 15.60, 2, 31.20, 0, '1', '1', b'0'),
    (920012, 920011, 11, '江津米花糖', '', 18.80, 1, 18.80, 0, '1', '1', b'0'),
    (920013, 920012, 6, '农家腊肉', '🥩', 88.00, 2, 176.00, 0, '1', '1', b'0'),
    (920014, 920013, 4, '野生山核桃', '🥜', 55.00, 1, 55.00, 0, '1', '1', b'0'),
    (920015, 920014, 8, '重庆小面麻辣调料包', '', 32.80, 1, 32.80, 0, '1', '1', b'0');

-- ---------- 只读校验 ----------
SELECT '发车核验' AS info, COUNT(*) AS cnt FROM transport_departure_check WHERE id >= 914000;
SELECT '方案日志' AS info, COUNT(*) AS cnt FROM transport_dispatch_plan_log WHERE id >= 914100;
SELECT '待审核订单' AS info, COUNT(*) AS cnt FROM transport_order WHERE status = 6 AND deleted = b'0';
SELECT '车辆实时状态' AS info, realtime_status, COUNT(*) AS cnt FROM transport_vehicle WHERE deleted = b'0' GROUP BY realtime_status;

-- ---------- 8. 去演示化（订单池单号/货物信息里的演示痕迹） ----------
-- 订单池 15 单（07 脚本生成）的单号 TPCQ*/TPDEMO*/TP346*/TPJTU*/TPCQUA* → 规范日期单号
UPDATE transport_order o
JOIN (SELECT id, ROW_NUMBER() OVER (ORDER BY id) AS rn FROM transport_order
      WHERE id BETWEEN 201 AND 239 AND deleted = b'0') s ON s.id = o.id
SET o.order_no = CONCAT('TP', DATE_FORMAT(CURDATE(), '%Y%m%d'), 'P', LPAD(s.rn, 2, '0'))
WHERE o.id BETWEEN 201 AND 239;

-- 货物信息里的演示字样 → 真实货物口径
UPDATE transport_cargo_order SET goods_name = '南山蜂蜜礼盒',  goods_note = '' WHERE id = 206;
UPDATE transport_cargo_order SET goods_name = '手工竹编收纳篮', goods_note = '海棠溪换乘' WHERE id = 207;
UPDATE transport_cargo_order SET goods_name = '校园教材包裹',  goods_note = '四公里中转' WHERE id = 208;
UPDATE transport_cargo_order SET goods_name = '沙坪坝干货礼包', goods_note = '南岸→沙坪坝' WHERE id = 209;
UPDATE transport_cargo_order SET goods_name = '考研复习资料',  goods_note = '明志苑2舍寄出' WHERE id = 210;
UPDATE transport_cargo_order SET goods_note = '346 路直达' WHERE id = 232;
UPDATE transport_cargo_order SET goods_note = '' WHERE id IN (231, 233, 236, 237, 238);
UPDATE transport_cargo_order SET goods_note = '跨片区联运，交汇站换乘续运' WHERE id = 234;
UPDATE transport_cargo_order SET goods_note = '返程捎带' WHERE id = 235;
UPDATE transport_cargo_order SET goods_note = '南坪站换乘，两段联运' WHERE id = 239;
