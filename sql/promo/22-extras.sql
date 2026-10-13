-- ============================================
-- 宣传片演示数据 · 22 运营点缀（公告 / 意见反馈 / 商城订单）
-- 幂等：按 id 段自清理后重建，可重复执行。
-- ============================================

DELETE FROM transport_notice             WHERE id >= 920000;
DELETE FROM transport_feedback           WHERE id >= 920000;
DELETE FROM transport_product_order_item WHERE id >= 920000;
DELETE FROM transport_product_order      WHERE id >= 920000;

SET @demo_uid := (SELECT id FROM member_user WHERE mobile = '13800000000' ORDER BY id LIMIT 1);
SET @m3 := (SELECT id FROM member_user WHERE mobile = '13911002003' ORDER BY id LIMIT 1);
SET @m5 := (SELECT id FROM member_user WHERE mobile = '13911002005' ORDER BY id LIMIT 1);

-- ---------- 平台公告（小程序/后台公告栏；status=1 上架） ----------
INSERT INTO transport_notice (id, title, content, status, sort, tenant_id, creator, create_time, updater, update_time, deleted) VALUES
(920001, '国庆中秋双节期间班次调整公告', '双节期间（10月1日-10月8日）347 路区间、320 路班次加密至每 20 分钟一班；南山方向末班车延长至 22:30。请广大用户合理安排寄货时间。', 1, 1, 0, '1', DATE_SUB(NOW(), INTERVAL 12 DAY), '1', DATE_SUB(NOW(), INTERVAL 12 DAY), b'0'),
(920002, '农产品进城「绿色通道」上线', '针对生鲜果蔬类寄货订单，平台开通绿色通道：优先审核、优先配载、全程冷链提示。欢迎各村集体经济组织对接入驻。', 1, 2, 0, '1', DATE_SUB(NOW(), INTERVAL 20 DAY), '1', DATE_SUB(NOW(), INTERVAL 20 DAY), b'0'),
(920003, '重邮明志苑驿站正式启用', '重庆邮电大学明志苑驿站已启用，支持校园内寄取件、土特产代销。营业时间 8:00-20:00。', 1, 3, 0, '1', DATE_SUB(NOW(), INTERVAL 32 DAY), '1', DATE_SUB(NOW(), INTERVAL 32 DAY), b'0');

-- ---------- 意见反馈（部分已回复） ----------
INSERT INTO transport_feedback (id, user_id, name, mobile, content, status, reply, reply_time, tenant_id, creator, create_time, updater, update_time, deleted) VALUES
(920001, @demo_uid, '演示用户', '13800000000', '从邮电大学寄到四公里的包裹当天上午就到了，比以前跑镇上寄方便多了。', 1, '感谢您的认可，我们会继续保持时效。', DATE_SUB(NOW(), INTERVAL 3 DAY), 0, '1', DATE_SUB(NOW(), INTERVAL 4 DAY), '1', DATE_SUB(NOW(), INTERVAL 3 DAY), b'0'),
(920002, @m3, '张桂芳', '13911002003', '希望南山站下午能增加一个班次，卖枇杷回来赶不上 15 点的车。', 1, '已纳入 10 月班次优化计划，预计月底前加密南山方向下午班次。', DATE_SUB(NOW(), INTERVAL 2 DAY), 0, '1', DATE_SUB(NOW(), INTERVAL 5 DAY), '1', DATE_SUB(NOW(), INTERVAL 2 DAY), b'0'),
(920003, @m5, '陈小红', '13911002005', '土鸡蛋收到时有 2 枚破损，建议加强缓冲包装。', 1, '非常抱歉，已为您的账户补偿 10 积分；驿站已更换加厚蛋托。', DATE_SUB(NOW(), INTERVAL 1 DAY), 0, '1', DATE_SUB(NOW(), INTERVAL 6 DAY), '1', DATE_SUB(NOW(), INTERVAL 1 DAY), b'0'),
(920004, @demo_uid, '演示用户', '13800000000', '小程序上能看到车到哪里了，这个功能很实用，就是偶尔刷新慢。', 1, '已优化实时位置刷新频率，感谢反馈。', DATE_SUB(NOW(), INTERVAL 1 DAY), 0, '1', DATE_SUB(NOW(), INTERVAL 2 DAY), '1', DATE_SUB(NOW(), INTERVAL 1 DAY), b'0'),
(920005, @m3, '张桂芳', '13911002003', '想咨询一下大件（20 斤蜂蜜）能不能走大巴捎带？', 0, NULL, NULL, 0, '1', DATE_SUB(NOW(), INTERVAL 5 HOUR), '1', DATE_SUB(NOW(), INTERVAL 5 HOUR), b'0');

-- ---------- 商城订单（今日/昨日，含已发货在途） ----------
INSERT INTO transport_product_order
    (id, order_no, user_id, user_mobile, total_amount, status, vehicle_id, shift_id, driver_id, deliver_station_id,
     receiver_name, receiver_mobile, receiver_address, remark,
     load_time, deliver_time, tenant_id, creator, create_time, updater, update_time, deleted)
VALUES
    (920001, CONCAT('PO', DATE_FORMAT(CURDATE(), '%Y%m%d'), '01'), @demo_uid, '13800000000', 68.00, 2, 2, 402, 2, 410,
     '演示用户', '13800000000', '重庆邮电大学明志苑2舍', '高山脆李 1 斤',
     TIMESTAMP(CURDATE(), '07:35:00'), TIMESTAMP(CURDATE(), '08:20:00'), 0, '1', TIMESTAMP(CURDATE(), '06:50:00'), '1', TIMESTAMP(CURDATE(), '08:20:00'), b'0'),
    (920002, CONCAT('PO', DATE_FORMAT(CURDATE(), '%Y%m%d'), '02'), @m3, '13911002003', 90.00, 1, 5, 958, 4, 421,
     '张桂芳', '13911002003', '南岸区黄桷垭正街 12 号', '土鸡蛋30枚装 2 箱',
     DATE_SUB(NOW(), INTERVAL 35 MINUTE), NULL, 0, '1', DATE_SUB(NOW(), INTERVAL 70 MINUTE), '1', DATE_SUB(NOW(), INTERVAL 35 MINUTE), b'0'),
    (920003, CONCAT('PO', DATE_FORMAT(CURDATE(), '%Y%m%d'), '03'), @m5, '13911002005', 128.00, 0, NULL, NULL, NULL, NULL,
     '陈小红', '13911002005', '南岸区南坪东路 88 号', '明前龙井茶 1 盒',
     NULL, NULL, 0, '1', DATE_SUB(NOW(), INTERVAL 25 MINUTE), '1', DATE_SUB(NOW(), INTERVAL 25 MINUTE), b'0');

INSERT INTO transport_product_order_item
    (id, order_id, product_id, product_name, product_image, product_price, quantity, amount, tenant_id, creator, updater, deleted)
VALUES
    (920001, 920001, 1, '高山脆李', '🍑', 68.00, 1, 68.00, 0, '1', '1', b'0'),
    (920002, 920002, 2, '土鸡蛋30枚装', '🥚', 45.00, 2, 90.00, 0, '1', '1', b'0'),
    (920003, 920003, 5, '明前龙井茶', '🍵', 128.00, 1, 128.00, 0, '1', '1', b'0');

SELECT '公告/反馈/商城订单' AS info,
       (SELECT COUNT(*) FROM transport_notice WHERE deleted = b'0') AS notices,
       (SELECT COUNT(*) FROM transport_feedback WHERE deleted = b'0') AS feedbacks,
       (SELECT COUNT(*) FROM transport_product_order WHERE deleted = b'0') AS product_orders;
