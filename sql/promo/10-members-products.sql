-- ============================================
-- 宣传片演示数据 · 10 会员与商城
--   · 演示会员（小程序密码登录兜底：13800000000 / 123456）
--   · 一批真实感会员（历史订单/商城订单的下单人，注册时间分散在近 90 天）
--   · 收货地址、商城商品（status=1 上架，V026 新语义）、计价规则
-- 幂等：按唯一键（mobile / 商品 id）幂等覆盖。
-- ============================================

-- ---------- 演示会员 ----------
INSERT INTO member_user
    (nickname, name, sex, point, avatar, status, mobile, password, register_ip, creator, updater, tenant_id, deleted)
VALUES
    ('演示用户', '演示用户', 1, 0, '', 0, '13800000000',
     '$2a$10$b7Zw5dh0ldzG08ReZBVGZenwyyYUwS07iMM/KAZOP6cqjP7..RRzi', '127.0.0.1', '1', '1', 0, b'0')
ON DUPLICATE KEY UPDATE
    password = VALUES(password), nickname = VALUES(nickname), status = 0, deleted = b'0';

-- ---------- 真实感会员（手机号即登录账号，密码同 123456；注册时间分散） ----------
INSERT INTO member_user
    (nickname, name, sex, point, avatar, status, mobile, password, register_ip, create_time, creator, updater, tenant_id, deleted)
VALUES
    ('王雪梅', '王雪梅', 2, 120, '', 0, '13911002001', '$2a$10$b7Zw5dh0ldzG08ReZBVGZenwyyYUwS07iMM/KAZOP6cqjP7..RRzi', '112.20.9.31',  DATE_SUB(NOW(), INTERVAL 88 DAY), '1', '1', 0, b'0'),
    ('李长顺', '李长顺', 1, 86,  '', 0, '13911002002', '$2a$10$b7Zw5dh0ldzG08ReZBVGZenwyyYUwS07iMM/KAZOP6cqjP7..RRzi', '112.20.9.44',  DATE_SUB(NOW(), INTERVAL 79 DAY), '1', '1', 0, b'0'),
    ('张桂芳', '张桂芳', 2, 230, '', 0, '13911002003', '$2a$10$b7Zw5dh0ldzG08ReZBVGZenwyyYUwS07iMM/KAZOP6cqjP7..RRzi', '112.20.10.12', DATE_SUB(NOW(), INTERVAL 71 DAY), '1', '1', 0, b'0'),
    ('刘志强', '刘志强', 1, 45,  '', 0, '13911002004', '$2a$10$b7Zw5dh0ldzG08ReZBVGZenwyyYUwS07iMM/KAZOP6cqjP7..RRzi', '112.20.11.8',  DATE_SUB(NOW(), INTERVAL 64 DAY), '1', '1', 0, b'0'),
    ('陈小红', '陈小红', 2, 310, '', 0, '13911002005', '$2a$10$b7Zw5dh0ldzG08ReZBVGZenwyyYUwS07iMM/KAZOP6cqjP7..RRzi', '112.20.9.71',  DATE_SUB(NOW(), INTERVAL 52 DAY), '1', '1', 0, b'0'),
    ('杨国富', '杨国富', 1, 66,  '', 0, '13911002006', '$2a$10$b7Zw5dh0ldzG08ReZBVGZenwyyYUwS07iMM/KAZOP6cqjP7..RRzi', '112.20.12.19', DATE_SUB(NOW(), INTERVAL 43 DAY), '1', '1', 0, b'0'),
    ('黄丽华', '黄丽华', 2, 158, '', 0, '13911002007', '$2a$10$b7Zw5dh0ldzG08ReZBVGZenwyyYUwS07iMM/KAZOP6cqjP7..RRzi', '112.20.10.55', DATE_SUB(NOW(), INTERVAL 35 DAY), '1', '1', 0, b'0'),
    ('周文斌', '周文斌', 1, 92,  '', 0, '13911002008', '$2a$10$b7Zw5dh0ldzG08ReZBVGZenwyyYUwS07iMM/KAZOP6cqjP7..RRzi', '112.20.13.27', DATE_SUB(NOW(), INTERVAL 27 DAY), '1', '1', 0, b'0'),
    ('吴秀英', '吴秀英', 2, 204, '', 0, '13911002009', '$2a$10$b7Zw5dh0ldzG08ReZBVGZenwyyYUwS07iMM/KAZOP6cqjP7..RRzi', '112.20.9.103', DATE_SUB(NOW(), INTERVAL 19 DAY), '1', '1', 0, b'0'),
    ('郑永康', '郑永康', 1, 38,  '', 0, '13911002010', '$2a$10$b7Zw5dh0ldzG08ReZBVGZenwyyYUwS07iMM/KAZOP6cqjP7..RRzi', '112.20.14.60', DATE_SUB(NOW(), INTERVAL 12 DAY), '1', '1', 0, b'0'),
    ('陈晓风', '陈晓风', 1, 12,  '', 0, '13911002011', '$2a$10$b7Zw5dh0ldzG08ReZBVGZenwyyYUwS07iMM/KAZOP6cqjP7..RRzi', '112.20.15.22', DATE_SUB(NOW(), INTERVAL 6 DAY),  '1', '1', 0, b'0'),
    ('林嘉欣', '林嘉欣', 2, 55,  '', 0, '13911002012', '$2a$10$b7Zw5dh0ldzG08ReZBVGZenwyyYUwS07iMM/KAZOP6cqjP7..RRzi', '112.20.15.81', DATE_SUB(NOW(), INTERVAL 2 DAY),  '1', '1', 0, b'0')
ON DUPLICATE KEY UPDATE
    nickname = VALUES(nickname), status = 0, deleted = b'0';

-- ---------- 演示会员收货地址 ----------
INSERT INTO member_address (user_id, name, mobile, area_id, detail_address, default_status, creator, updater, deleted)
SELECT u.id, '演示用户', '13800000000', 500108, '南岸区崇文路2号 重庆邮电大学明志苑2舍', b'1', '1', '1', b'0'
FROM member_user u WHERE u.mobile = '13800000000'
  AND NOT EXISTS (SELECT 1 FROM member_address a WHERE a.user_id = u.id AND a.detail_address LIKE '%明志苑2舍%');

-- ---------- 计价规则 ----------
INSERT INTO transport_pricing_rule
    (id, passenger_price_per_km, cargo_price_per_item, postal_price_per_item,
     vehicle_cost_per_km, avg_speed_kmh, stop_service_minutes, tenant_id, creator, updater, deleted)
VALUES
    (1, 1.50, 5.00, 3.00, 2.80, 25.0, 3, 0, '1', '1', b'0')
ON DUPLICATE KEY UPDATE
    passenger_price_per_km = VALUES(passenger_price_per_km),
    cargo_price_per_item = VALUES(cargo_price_per_item),
    postal_price_per_item = VALUES(postal_price_per_item);

-- ---------- 商城商品（status=1 上架，V026 新语义；INSERT IGNORE 不覆盖运营改动） ----------
INSERT IGNORE INTO transport_product (id, name, from_village, price, unit, image, badge, description, stock, status, sort, tenant_id, creator, create_time, updater, update_time, deleted) VALUES
(1, '高山脆李', '云山村', 68.00, '斤', '🍑', '大巴直通车', '高山生态种植，皮薄肉厚，清甜多汁', 200, 1, 1, 0, '1', NOW(), '1', NOW(), b'0'),
(2, '土鸡蛋30枚装', '大湾村', 45.00, '箱', '🥚', '大巴直通车', '农家散养土鸡蛋，30枚装', 150, 1, 2, 0, '1', NOW(), '1', NOW(), b'0'),
(3, '有机红薯粉', '竹林乡', 28.00, '袋', '🍜', '', '传统手工制作，爽滑劲道', 300, 1, 3, 0, '1', NOW(), '1', NOW(), b'0'),
(4, '野生山核桃', '青山镇', 55.00, '斤', '🥜', '大巴直通车', '深山野生，自然晾晒', 120, 1, 4, 0, '1', NOW(), '1', NOW(), b'0'),
(5, '明前龙井茶', '云山村', 128.00, '盒', '🍵', '', '清明前采摘，鲜嫩甘醇', 80, 1, 5, 0, '1', NOW(), '1', NOW(), b'0'),
(6, '农家腊肉', '溪口村', 88.00, '斤', '🥩', '大巴直通车', '柴火熏制，肥而不腻', 60, 1, 6, 0, '1', NOW(), '1', NOW(), b'0'),
(7, '重庆老火锅底料（牛油）', '重庆·南岸', 45.00, '袋', 'https://www.themealdb.com/images/media/meals/uuqvwu1504629254.jpg', '重庆特产', '牛油熬制，麻辣鲜香，一袋可煮 3~4 人份', 200, 1, 7, 0, '1', NOW(), '1', NOW(), b'0'),
(8, '重庆小面麻辣调料包', '重庆·渝中', 32.80, '盒', 'https://www.themealdb.com/images/media/meals/pbzcrx1763765096.jpg', '重庆特产', '手工炒制辣椒油，还原街头小面的味道', 180, 1, 8, 0, '1', NOW(), '1', NOW(), b'0'),
(9, '涪陵榨菜（鲜脆榨菜丝）', '重庆·涪陵', 15.60, '袋', 'https://www.themealdb.com/images/media/meals/44pjrn1779814409.jpg', '重庆特产', '百年工艺，鲜脆爽口，佐餐下饭', 300, 1, 9, 0, '1', NOW(), '1', NOW(), b'0'),
(10, '合川桃片（核桃味）', '重庆·合川', 22.00, '盒', 'https://www.themealdb.com/images/media/meals/t3r3ka1560461972.jpg', '重庆特产', '糯米核桃薄片，甜而不腻，重庆老字号茶点', 160, 1, 10, 0, '1', NOW(), '1', NOW(), b'0'),
(11, '江津米花糖', '重庆·江津', 18.80, '袋', 'https://www.themealdb.com/images/media/meals/q47rkb1762324620.jpg', '重庆特产', '传统手工膨化，酥脆香甜', 220, 1, 11, 0, '1', NOW(), '1', NOW(), b'0'),
(12, '城口老腊肉（烟熏）', '重庆·城口', 68.00, '斤', 'https://www.themealdb.com/images/media/meals/t1hg8s1780087329.jpg', '重庆特产', '土猪后腿，柏树枝烟熏，肥而不腻', 90, 1, 12, 0, '1', NOW(), '1', NOW(), b'0');

-- 历史库修正：如果本库曾按旧语义（0=上架）写过商品，V026 迁移已统一翻转；
-- 这里只兜底把演示商品置为上架（不动后台手工下架的非演示商品）
UPDATE transport_product SET status = 1 WHERE id BETWEEN 1 AND 12 AND deleted = b'0' AND status = 0 AND updater = '1';

-- 商品实拍图（托管在本机 nginx：/opt/cargo-post-web/images/products/，拍摄不依赖外网）
UPDATE transport_product p
JOIN (SELECT 1 AS id, 'plum.jpg' AS img UNION ALL SELECT 2, 'eggs.jpg' UNION ALL SELECT 3, 'noodles.jpg'
      UNION ALL SELECT 4, 'walnuts.jpg' UNION ALL SELECT 5, 'tea.jpg' UNION ALL SELECT 6, 'bacon.jpg'
      UNION ALL SELECT 7, 'sp1.jpg' UNION ALL SELECT 8, 'sp2.jpg' UNION ALL SELECT 9, 'sp3.jpg'
      UNION ALL SELECT 10, 'sp4.jpg' UNION ALL SELECT 11, 'sp5.jpg' UNION ALL SELECT 12, 'sp6.jpg') m
  ON m.id = p.id
SET p.image_url = CONCAT('http://1.15.29.107/images/products/', m.img)
WHERE p.id BETWEEN 1 AND 12;

SELECT '会员' AS info, COUNT(*) AS cnt FROM member_user WHERE deleted = b'0';
SELECT '上架商品' AS info, COUNT(*) AS cnt FROM transport_product WHERE status = 1 AND deleted = b'0';
