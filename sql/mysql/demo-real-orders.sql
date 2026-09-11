-- 演示订单改为"高德真实公交站点之间"的真实行程（配合 demo-real-bus-network.sql）
-- 版本：2026-09-11（含跨区订单 TPDEMO4 邮电大学→重大A区、TPDEMO5 邮电大学→七公里/重庆交通大学门口）
--
-- 真实线网（由 tools/gen_bus_network_sql.py 从高德抓取）：
--   347路区间（老厂—上海城）：邮电大学 → 海棠溪 → …
--   320路（市五院新院区—渝南公交站场）：海棠溪 → 四公里 → 五公里 → …
--   轨道交通3号线（鱼洞—江北机场T2航站楼）：南坪 → 四公里 → 重庆工商大学 → …
--
-- 订单与真实可行路径（纯公交，均可用高德"不乘地铁"策略复核）：
--   201 邮电大学 → 海棠溪            （347路区间 直达）
--   202 海棠溪  → 五公里             （320路 直达）
--   203 五公里  → 龙洲湾枢纽站        （303路 直达）
--   204 邮电大学 → 五公里            （347路区间 + 320路，海棠溪换乘 = 两段）
--   205 南坪站  → 龙洲湾枢纽站        （303路 直达）
--   206 邮电大学 → 海棠溪            （Demo1 直达）
--   207 邮电大学 → 五公里            （Demo2 两段联运：海棠溪换乘）
--   208 邮电大学 → 磁器街            （Demo3 三段联运：本地线网无"一次换乘"枢纽，需两次换乘）
--
-- 幂等：全部按"站名"解析站点 id，不硬编码 id。

-- 清理第一版自造走廊（201-205 站、301-304 线路），避免与真实线网重复
DELETE FROM transport_route_station WHERE route_id BETWEEN 301 AND 304 OR id BETWEEN 301 AND 320;
DELETE FROM transport_route WHERE id BETWEEN 301 AND 304;
DELETE FROM transport_station WHERE id BETWEEN 201 AND 205;

-- 按站名把演示订单指到真实站点
UPDATE transport_order SET
    pickup_station_id = (SELECT id FROM (SELECT id FROM transport_station WHERE station_name = '邮电大学' ORDER BY id LIMIT 1) a),
    delivery_station_id = (SELECT id FROM (SELECT id FROM transport_station WHERE station_name = '海棠溪' ORDER BY id LIMIT 1) b)
WHERE id IN (201, 206);

UPDATE transport_order SET
    pickup_station_id = (SELECT id FROM (SELECT id FROM transport_station WHERE station_name = '海棠溪' ORDER BY id LIMIT 1) a),
    delivery_station_id = (SELECT id FROM (SELECT id FROM transport_station WHERE station_name = '五公里' ORDER BY id LIMIT 1) b)
WHERE id = 202;

UPDATE transport_order SET
    pickup_station_id = (SELECT id FROM (SELECT id FROM transport_station WHERE station_name = '五公里' ORDER BY id LIMIT 1) a),
    delivery_station_id = (SELECT id FROM (SELECT id FROM transport_station WHERE station_name = '龙洲湾枢纽站' ORDER BY id LIMIT 1) b)
WHERE id = 203;

UPDATE transport_order SET
    pickup_station_id = (SELECT id FROM (SELECT id FROM transport_station WHERE station_name = '邮电大学' ORDER BY id LIMIT 1) a),
    delivery_station_id = (SELECT id FROM (SELECT id FROM transport_station WHERE station_name = '五公里' ORDER BY id LIMIT 1) b)
WHERE id IN (204, 207);

UPDATE transport_order SET
    pickup_station_id = (SELECT id FROM (SELECT id FROM transport_station WHERE station_name = '南坪站' ORDER BY id LIMIT 1) a),
    delivery_station_id = (SELECT id FROM (SELECT id FROM transport_station WHERE station_name = '龙洲湾枢纽站' ORDER BY id LIMIT 1) b)
WHERE id = 205;

UPDATE transport_order SET
    pickup_station_id = (SELECT id FROM (SELECT id FROM transport_station WHERE station_name = '邮电大学' ORDER BY id LIMIT 1) a),
    delivery_station_id = (SELECT id FROM (SELECT id FROM transport_station WHERE station_name = '磁器街' ORDER BY id LIMIT 1) b)
WHERE id = 208;

-- 校验：订单起终点必须都是真实线网里的站点，且落在高德线路上
-- 跨区订单（南岸→沙坪坝）：高德"不乘地铁"给出 2 段（346+181，小什字换乘）
-- 或 3 段（347区间+318+220区间，福利社、小龙坎换乘）；用于验证本地线网+算法能否复现
INSERT INTO transport_order
    (id, order_no, order_type, pickup_station_id, delivery_station_id, earliest_pickup_time, latest_delivery_time,
     status, total_amount, tenant_id, creator, create_time, updater, update_time, deleted)
VALUES
    (209, 'TPDEMO4', 2,
     (SELECT id FROM (SELECT id FROM transport_station WHERE station_name = '邮电大学' ORDER BY id LIMIT 1) a),
     (SELECT id FROM (SELECT id FROM transport_station WHERE station_name = '重大A区' ORDER BY id LIMIT 1) b),
     DATE_SUB(NOW(), INTERVAL 1 HOUR), DATE_ADD(NOW(), INTERVAL 10 HOUR), 8, 26.00, 0, '1', NOW(), '1', NOW(), b'0')
ON DUPLICATE KEY UPDATE
    pickup_station_id = VALUES(pickup_station_id),
    delivery_station_id = VALUES(delivery_station_id),
    status = 8, deleted = b'0';

INSERT INTO transport_cargo_order
    (id, order_id, cargo_category, fresh_flag, item_count, weight_kg, volume_m3, goods_name, goods_note,
     audit_status, review_status, pickup_service_mode, delivery_service_mode, receiver_name, receiver_mobile,
     receiver_address, original_address, original_latitude, original_longitude,
     tenant_id, creator, create_time, updater, update_time, deleted)
VALUES
    (209, 209, '日用品', b'0', 1, 3.00, 0.0150, '跨区联运演示货物', '南岸→沙坪坝', 1, 1,
     'STATION_TO_STATION', 'STATION_TO_STATION', '重大收件人', '13900000009', '重庆大学A区',
     '重庆邮电大学', 29.5326000, 106.6038000, 0, '1', NOW(), '1', NOW(), b'0')
ON DUPLICATE KEY UPDATE
    item_count = VALUES(item_count), weight_kg = VALUES(weight_kg), goods_name = VALUES(goods_name), deleted = b'0';

-- 用户实际场景：重庆邮电大学明志苑2舍 → 重庆交通大学门口（书本）
--   明志苑2舍在重邮校内 → 车辆进不去，按可达性规则取最近可服务站点「邮电大学」公交站；
--   重庆交通大学南岸校区门口的公交站是「七公里」
INSERT INTO transport_order
    (id, order_no, order_type, pickup_station_id, delivery_station_id, earliest_pickup_time, latest_delivery_time,
     status, total_amount, tenant_id, creator, create_time, updater, update_time, deleted)
VALUES
    (210, 'TPDEMO5', 2,
     (SELECT id FROM (SELECT id FROM transport_station WHERE station_name = '邮电大学' ORDER BY id LIMIT 1) a),
     (SELECT id FROM (SELECT id FROM transport_station WHERE station_name = '七公里' ORDER BY id LIMIT 1) b),
     DATE_SUB(NOW(), INTERVAL 30 MINUTE), DATE_ADD(NOW(), INTERVAL 10 HOUR), 8, 18.00, 0, '1', NOW(), '1', NOW(), b'0')
ON DUPLICATE KEY UPDATE
    pickup_station_id = VALUES(pickup_station_id),
    delivery_station_id = VALUES(delivery_station_id),
    status = 8, deleted = b'0';

INSERT INTO transport_cargo_order
    (id, order_id, cargo_category, fresh_flag, item_count, weight_kg, volume_m3, goods_name, goods_note,
     audit_status, review_status, pickup_service_mode, delivery_service_mode, receiver_name, receiver_mobile,
     receiver_address, original_address, original_latitude, original_longitude,
     tenant_id, creator, create_time, updater, update_time, deleted)
VALUES
    (210, 210, '文件票据', b'0', 1, 2.00, 0.0080, '书本', '重邮明志苑2舍寄出', 1, 1,
     'NEAREST_STATION', 'STATION_TO_STATION', '交大收件人', '13900000010', '重庆交通大学门口',
     '重庆邮电大学明志苑2舍', 29.5310000, 106.6043000, 0, '1', NOW(), '1', NOW(), b'0')
ON DUPLICATE KEY UPDATE
    item_count = VALUES(item_count), weight_kg = VALUES(weight_kg), goods_name = VALUES(goods_name),
    original_address = VALUES(original_address), deleted = b'0';

SELECT o.id, o.order_no, ps.station_name AS pickup, ps.source_type AS pickup_src,
       ds.station_name AS delivery, ds.source_type AS delivery_src
FROM transport_order o
JOIN transport_station ps ON ps.id = o.pickup_station_id
JOIN transport_station ds ON ds.id = o.delivery_station_id
WHERE o.id BETWEEN 201 AND 210
ORDER BY o.id;

-- ============================================
-- 回程揽收/派送示例：巴南龙洲湾 → 重庆邮电大学（与 TPDEMO4/5 方向相反）
--
-- 用途：验证"线路返场时也收货派货" —— 车从南岸跑到巴南，回来（返场）途中把巴南的货捎回重邮；
-- 同时验证跨片区订单由**不同车辆在换乘站接驳**（不再一台车跨城往返）。
-- 幂等：按站名解析站点 id。
-- ============================================
INSERT INTO transport_order
    (id, order_no, order_type, pickup_station_id, delivery_station_id, earliest_pickup_time, latest_delivery_time,
     status, total_amount, tenant_id, creator, create_time, updater, update_time, deleted)
VALUES
    (211, 'TPDEMO6', 2,
     (SELECT id FROM (SELECT id FROM transport_station WHERE station_name = '龙洲湾枢纽站' ORDER BY id LIMIT 1) a),
     (SELECT id FROM (SELECT id FROM transport_station WHERE station_name = '重邮南门货运站' ORDER BY id LIMIT 1) b),
     DATE_SUB(NOW(), INTERVAL 30 MINUTE), DATE_ADD(NOW(), INTERVAL 10 HOUR), 8, 22.00, 0, '1', NOW(), '1', NOW(), b'0')
ON DUPLICATE KEY UPDATE
    order_no = VALUES(order_no),
    pickup_station_id = VALUES(pickup_station_id),
    delivery_station_id = VALUES(delivery_station_id),
    status = 8, deleted = b'0';

INSERT INTO transport_cargo_order
    (id, order_id, cargo_category, fresh_flag, item_count, weight_kg, volume_m3, goods_name, goods_note,
     audit_status, review_status, pickup_service_mode, delivery_service_mode, receiver_name, receiver_mobile,
     receiver_address, original_address, original_latitude, original_longitude,
     tenant_id, creator, create_time, updater, update_time, deleted)
VALUES
    (211, 211, '日用品', b'0', 2, 4.00, 0.0200, '巴南土特产（回程捎带）', '返场途中揽收，带回重邮驿站',
     1, 1, 'STATION_TO_STATION', 'STATION_TO_STATION', '重邮收件人', '13900000011', '重庆邮电大学明志苑2舍',
     '重庆市巴南区龙洲湾', 29.3767650, 106.5420550, 0, '1', NOW(), '1', NOW(), b'0')
ON DUPLICATE KEY UPDATE
    item_count = VALUES(item_count), weight_kg = VALUES(weight_kg), goods_name = VALUES(goods_name),
    goods_note = VALUES(goods_note), deleted = b'0';

SELECT o.id, o.order_no, ps.station_name AS pickup, ds.station_name AS delivery
FROM transport_order o
JOIN transport_station ps ON ps.id = o.pickup_station_id
JOIN transport_station ds ON ds.id = o.delivery_station_id
WHERE o.id = 211;

-- ============================================
-- 无站点场景示例：用户在**路口/路边**（自填地址或地图选点）寄取，体现"小范围绕行"
--
-- 业务前提：车辆本职按线路跑、每站都停，只允许按订单做**小范围绕行**。
--   距离最近可服务站点 ≤300m → 服务方式 DOOR_PICKUP（司机就近绕行交接）；
--   300m~1km            → SAFE_ROADSIDE（在安全路边点交接）；
--   更远                → NEAREST_STATION（客户送到最近站点交接）。
-- 下面两单分别演示"路边取货"与"路边送达"，起终点仍是真实站点/真实路网，
-- 用户原始地址与坐标单独留痕（original_address / original_latitude / original_longitude）。
-- ============================================
INSERT INTO transport_order
    (id, order_no, order_type, pickup_station_id, delivery_station_id, earliest_pickup_time, latest_delivery_time,
     status, total_amount, tenant_id, creator, create_time, updater, update_time, deleted)
VALUES
    (212, 'TPDEMO7', 2,
     (SELECT id FROM (SELECT id FROM transport_station WHERE station_name = '重邮南门货运站' ORDER BY id LIMIT 1) a),
     (SELECT id FROM (SELECT id FROM transport_station WHERE station_name = '四公里交通换乘枢纽站' ORDER BY id LIMIT 1) b),
     DATE_SUB(NOW(), INTERVAL 30 MINUTE), DATE_ADD(NOW(), INTERVAL 10 HOUR), 8, 12.00, 0, '1', NOW(), '1', NOW(), b'0'),
    (213, 'TPDEMO8', 2,
     (SELECT id FROM (SELECT id FROM transport_station WHERE station_name = '邮电大学' ORDER BY id LIMIT 1) a),
     (SELECT id FROM (SELECT id FROM transport_station WHERE station_name = '重邮南门货运站' ORDER BY id LIMIT 1) b),
     DATE_SUB(NOW(), INTERVAL 30 MINUTE), DATE_ADD(NOW(), INTERVAL 10 HOUR), 8, 12.00, 0, '1', NOW(), '1', NOW(), b'0')
ON DUPLICATE KEY UPDATE
    order_no = VALUES(order_no),
    pickup_station_id = VALUES(pickup_station_id),
    delivery_station_id = VALUES(delivery_station_id),
    status = 8, deleted = b'0';

INSERT INTO transport_cargo_order
    (id, order_id, cargo_category, fresh_flag, item_count, weight_kg, volume_m3, goods_name, goods_note,
     audit_status, review_status, pickup_service_mode, delivery_service_mode, service_point_station_id,
     receiver_name, receiver_mobile, receiver_address, original_address, original_latitude, original_longitude,
     tenant_id, creator, create_time, updater, update_time, deleted)
VALUES
    -- 212：用户在明志苑门口（路边，非站点）寄件 → 司机在最近站点附近小范围绕行取货（DOOR_PICKUP）
    (212, 212, '日用品', b'0', 1, 2.00, 0.0100, '明志苑门口的快递纸箱', '路边取货（非站点），司机就近绕行',
     1, 1, 'DOOR_PICKUP', 'STATION_TO_STATION',
     (SELECT id FROM (SELECT id FROM transport_station WHERE station_name = '重邮南门货运站' ORDER BY id LIMIT 1) c),
     '袁同学', '13900000012', '重庆邮电大学明志苑2舍门口（路边）',
     '重庆邮电大学明志苑2舍门口（崇文路路边）', 29.5312000, 106.6052000, 0, '1', NOW(), '1', NOW(), b'0'),
    -- 213：寄到学生公寓路口（路边，非站点）→ 司机在安全路边点交接（SAFE_ROADSIDE）
    (213, 213, '文件票据', b'0', 1, 1.00, 0.0040, '录取通知书', '路边送达（非站点），司机在安全点交接',
     1, 1, 'STATION_TO_STATION', 'SAFE_ROADSIDE',
     (SELECT id FROM (SELECT id FROM transport_station WHERE station_name = '重邮南门货运站' ORDER BY id LIMIT 1) c),
     '王同学', '13900000013', '重庆邮电大学学生公寓路口（南山路辅路路边）',
     '重庆邮电大学学生公寓路口（南山路辅路路边）', 29.5305000, 106.6065000, 0, '1', NOW(), '1', NOW(), b'0')
ON DUPLICATE KEY UPDATE
    goods_name = VALUES(goods_name),
    goods_note = VALUES(goods_note),
    pickup_service_mode = VALUES(pickup_service_mode),
    delivery_service_mode = VALUES(delivery_service_mode),
    service_point_station_id = VALUES(service_point_station_id),
    original_address = VALUES(original_address),
    original_latitude = VALUES(original_latitude),
    original_longitude = VALUES(original_longitude),
    deleted = b'0';

SELECT o.id, o.order_no, ps.station_name AS pickup_station, ds.station_name AS delivery_station,
       c.pickup_service_mode, c.delivery_service_mode, c.original_address
FROM transport_order o
JOIN transport_station ps ON ps.id = o.pickup_station_id
JOIN transport_station ds ON ds.id = o.delivery_station_id
JOIN transport_cargo_order c ON c.order_id = o.id
WHERE o.id BETWEEN 212 AND 213
ORDER BY o.id;
