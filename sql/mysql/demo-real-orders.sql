-- 演示订单改为"高德真实公交站点之间"的真实行程（配合 demo-real-bus-network.sql）
-- 版本：2026-09-11（含跨区订单 TPDEMO4 邮电大学→重大A区、TPDEMO5 邮电大学→七公里/重庆交通大学门口）
-- 时间窗口径（2026-09-13 调整）：按"同一任务段时间内调度"给窗口——最早取货时间已过（立即可取），
--   最晚送达 = NOW() + 2 小时（原先 8~10 小时会让订单被排成一整天的任务，与"一车一时段任务段"不符）。
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
     DATE_SUB(NOW(), INTERVAL 1 HOUR), DATE_ADD(NOW(), INTERVAL 2 HOUR), 8, 26.00, 0, '1', NOW(), '1', NOW(), b'0')
ON DUPLICATE KEY UPDATE
    pickup_station_id = VALUES(pickup_station_id),
    delivery_station_id = VALUES(delivery_station_id),
    earliest_pickup_time = VALUES(earliest_pickup_time),
    latest_delivery_time = VALUES(latest_delivery_time),
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
     DATE_SUB(NOW(), INTERVAL 30 MINUTE), DATE_ADD(NOW(), INTERVAL 2 HOUR), 8, 18.00, 0, '1', NOW(), '1', NOW(), b'0')
ON DUPLICATE KEY UPDATE
    pickup_station_id = VALUES(pickup_station_id),
    delivery_station_id = VALUES(delivery_station_id),
    earliest_pickup_time = VALUES(earliest_pickup_time),
    latest_delivery_time = VALUES(latest_delivery_time),
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
     DATE_SUB(NOW(), INTERVAL 30 MINUTE), DATE_ADD(NOW(), INTERVAL 2 HOUR), 8, 22.00, 0, '1', NOW(), '1', NOW(), b'0')
ON DUPLICATE KEY UPDATE
    order_no = VALUES(order_no),
    pickup_station_id = VALUES(pickup_station_id),
    delivery_station_id = VALUES(delivery_station_id),
    earliest_pickup_time = VALUES(earliest_pickup_time),
    latest_delivery_time = VALUES(latest_delivery_time),
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
     DATE_SUB(NOW(), INTERVAL 30 MINUTE), DATE_ADD(NOW(), INTERVAL 2 HOUR), 8, 12.00, 0, '1', NOW(), '1', NOW(), b'0'),
    (213, 'TPDEMO8', 2,
     (SELECT id FROM (SELECT id FROM transport_station WHERE station_name = '邮电大学' ORDER BY id LIMIT 1) a),
     (SELECT id FROM (SELECT id FROM transport_station WHERE station_name = '重邮南门货运站' ORDER BY id LIMIT 1) b),
     DATE_SUB(NOW(), INTERVAL 30 MINUTE), DATE_ADD(NOW(), INTERVAL 2 HOUR), 8, 12.00, 0, '1', NOW(), '1', NOW(), b'0')
ON DUPLICATE KEY UPDATE
    order_no = VALUES(order_no),
    pickup_station_id = VALUES(pickup_station_id),
    delivery_station_id = VALUES(delivery_station_id),
    earliest_pickup_time = VALUES(earliest_pickup_time),
    latest_delivery_time = VALUES(latest_delivery_time),
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

-- ============================================
-- 订单池补充：把"符合可运条件（承运审核通过 → 待入池）"的各种情形都放进来，便于一键调度演示
--   214 邮快件：邮电大学 → 四公里交通换乘枢纽站（邮包，件数计价）
--   215 货运：南坪站 → 龙洲湾枢纽站（远端，通常需要换乘联运）
--   216 货运：海棠溪 → 南坪站（短途直达）
--   217 货运：四公里交通换乘枢纽站 → 南山站（返程方向）
--   218 货运：重邮明志苑驿站（自建站）→ 四公里交通换乘枢纽站（自建线路直达）
--   219 货运：南山 → 海棠溪（景区线，顺路带走）
-- 均为 status=8 待入池 + review_status=1 审核通过，可直接"一键归集 → 智能调度"。
-- ============================================
INSERT INTO transport_order
    (id, order_no, order_type, pickup_station_id, delivery_station_id, earliest_pickup_time, latest_delivery_time,
     status, total_amount, tenant_id, creator, create_time, updater, update_time, deleted)
VALUES
    (214, 'TPDEMO9', 3,
     (SELECT id FROM (SELECT id FROM transport_station WHERE station_name = '邮电大学' ORDER BY id LIMIT 1) a),
     (SELECT id FROM (SELECT id FROM transport_station WHERE station_name = '四公里交通换乘枢纽站' ORDER BY id LIMIT 1) b),
     DATE_SUB(NOW(), INTERVAL 20 MINUTE), DATE_ADD(NOW(), INTERVAL 2 HOUR), 8, 9.00, 0, '1', NOW(), '1', NOW(), b'0'),
    (215, 'TPDEMO10', 2,
     (SELECT id FROM (SELECT id FROM transport_station WHERE station_name = '南坪站' ORDER BY id LIMIT 1) a),
     (SELECT id FROM (SELECT id FROM transport_station WHERE station_name = '龙洲湾枢纽站' ORDER BY id LIMIT 1) b),
     DATE_SUB(NOW(), INTERVAL 20 MINUTE), DATE_ADD(NOW(), INTERVAL 2 HOUR), 8, 26.00, 0, '1', NOW(), '1', NOW(), b'0'),
    (216, 'TPDEMO11', 2,
     (SELECT id FROM (SELECT id FROM transport_station WHERE station_name = '海棠溪' ORDER BY id LIMIT 1) a),
     (SELECT id FROM (SELECT id FROM transport_station WHERE station_name = '南坪站' ORDER BY id LIMIT 1) b),
     DATE_SUB(NOW(), INTERVAL 20 MINUTE), DATE_ADD(NOW(), INTERVAL 2 HOUR), 8, 8.00, 0, '1', NOW(), '1', NOW(), b'0'),
    (217, 'TPDEMO12', 2,
     (SELECT id FROM (SELECT id FROM transport_station WHERE station_name = '四公里交通换乘枢纽站' ORDER BY id LIMIT 1) a),
     (SELECT id FROM (SELECT id FROM transport_station WHERE station_name = '南山站' ORDER BY id LIMIT 1) b),
     DATE_SUB(NOW(), INTERVAL 20 MINUTE), DATE_ADD(NOW(), INTERVAL 2 HOUR), 8, 10.00, 0, '1', NOW(), '1', NOW(), b'0'),
    (218, 'TPDEMO13', 2,
     (SELECT id FROM (SELECT id FROM transport_station WHERE station_name = '重邮明志苑驿站' ORDER BY id LIMIT 1) a),
     (SELECT id FROM (SELECT id FROM transport_station WHERE station_name = '四公里交通换乘枢纽站' ORDER BY id LIMIT 1) b),
     DATE_SUB(NOW(), INTERVAL 20 MINUTE), DATE_ADD(NOW(), INTERVAL 2 HOUR), 8, 11.00, 0, '1', NOW(), '1', NOW(), b'0'),
    (219, 'TPDEMO14', 2,
     (SELECT id FROM (SELECT id FROM transport_station WHERE station_name = '南山站' ORDER BY id LIMIT 1) a),
     (SELECT id FROM (SELECT id FROM transport_station WHERE station_name = '海棠溪' ORDER BY id LIMIT 1) b),
     DATE_SUB(NOW(), INTERVAL 20 MINUTE), DATE_ADD(NOW(), INTERVAL 2 HOUR), 8, 13.00, 0, '1', NOW(), '1', NOW(), b'0')
ON DUPLICATE KEY UPDATE
    order_no = VALUES(order_no),
    pickup_station_id = VALUES(pickup_station_id),
    delivery_station_id = VALUES(delivery_station_id),
    earliest_pickup_time = VALUES(earliest_pickup_time),
    latest_delivery_time = VALUES(latest_delivery_time),
    status = 8, deleted = b'0';

INSERT INTO transport_cargo_order
    (id, order_id, cargo_category, fresh_flag, item_count, weight_kg, volume_m3, goods_name, goods_note,
     audit_status, review_status, pickup_service_mode, delivery_service_mode,
     receiver_name, receiver_mobile, receiver_address, original_address, original_latitude, original_longitude,
     tenant_id, creator, create_time, updater, update_time, deleted)
VALUES
    (215, 215, '日用品', b'0', 2, 6.00, 0.0300, '南坪寄往龙洲湾的生活用品', '跨片区，预计需换乘联运',
     1, 1, 'STATION_TO_STATION', 'STATION_TO_STATION', '龙洲湾收件人', '13900000015', '重庆市巴南区龙洲湾',
     '重庆市南岸区南坪', 29.5292000, 106.5711000, 0, '1', NOW(), '1', NOW(), b'0'),
    (216, 216, '农产品', b'0', 1, 3.00, 0.0150, '海棠溪应季蔬菜', '短途直达',
     1, 1, 'STATION_TO_STATION', 'STATION_TO_STATION', '南坪收件人', '13900000016', '重庆市南岸区南坪',
     '重庆市南岸区海棠溪', 29.5425000, 106.5885000, 0, '1', NOW(), '1', NOW(), b'0'),
    (217, 217, '日用品', b'0', 3, 8.00, 0.0400, '四公里寄往南山的日用品', '返程方向（车从南山返场时捎带）',
     1, 1, 'STATION_TO_STATION', 'STATION_TO_STATION', '南山收件人', '13900000017', '重庆市南岸区南山',
     '重庆市南岸区四公里', 29.5194000, 106.5778000, 0, '1', NOW(), '1', NOW(), b'0'),
    (218, 218, '日用品', b'0', 1, 2.00, 0.0100, '明志苑驿站寄出的生活用品', '自建线路直达（客货邮自建站点）',
     1, 1, 'STATION_TO_STATION', 'STATION_TO_STATION', '四公里收件人', '13900000018', '重庆市南岸区四公里',
     '重庆邮电大学明志苑2舍', 29.5310000, 106.6055000, 0, '1', NOW(), '1', NOW(), b'0'),
    (219, 219, '生鲜果蔬', b'0', 1, 5.00, 0.0200, '南山新鲜果蔬', '景区线顺路带走（非冷链）',
     1, 1, 'STATION_TO_STATION', 'STATION_TO_STATION', '海棠溪收件人', '13900000019', '重庆市南岸区海棠溪',
     '重庆市南岸区南山', 29.5554000, 106.6280000, 0, '1', NOW(), '1', NOW(), b'0')
ON DUPLICATE KEY UPDATE
    goods_name = VALUES(goods_name), goods_note = VALUES(goods_note),
    item_count = VALUES(item_count), weight_kg = VALUES(weight_kg), deleted = b'0';

-- 邮包单（order_type=3）需要 postal 子表行，否则小程序/后台查不到快递单号与取件码
INSERT INTO transport_postal_order
    (id, order_id, mail_no, carrier_code, item_count, weight_kg, receiver_name, receiver_mobile,
     receiver_address, pickup_code, pickup_status, tenant_id, creator, create_time, updater, update_time, deleted)
VALUES
    (214, 214, 'SF2026091201', 'SF', 2, 3.00, '四公里收件人', '13900000014',
     '重庆市南岸区四公里', '8614', 0, 0, '1', NOW(), '1', NOW(), b'0')
ON DUPLICATE KEY UPDATE
    mail_no = VALUES(mail_no), item_count = VALUES(item_count), weight_kg = VALUES(weight_kg), deleted = b'0';

SELECT o.id, o.order_no, o.order_type, ps.station_name AS pickup, ds.station_name AS delivery
FROM transport_order o
JOIN transport_station ps ON ps.id = o.pickup_station_id
JOIN transport_station ds ON ds.id = o.delivery_station_id
WHERE o.id BETWEEN 214 AND 219
ORDER BY o.id;

-- ============================================================
-- 演示单时间窗统一成「当天 00:00 ~ 23:59」
--
-- 为什么：调度是**按任务窗口**派单的（调度中心工具栏可选，例：早上 08:40~12:40）。
-- 后端判定口径是"订单 [最早取货, 最晚送达] 与任务窗口有交集才可派"，
-- 而上面这些演示单的窗口是按"脚本执行时刻"算的（NOW()-1h ~ NOW()+2h）：
-- 只要演示时段与脚本执行时刻不重叠（例如上午跑脚本、下午选 08:40-12:40 的窗口），
-- 整批演示单都会被判"送达时限早于窗口开始"而不可派（现场就会看到"没有可派订单"）。
-- 这里统一改成"当天全天可服务"：任何当天的任务窗口都与订单时间窗有交集，
-- 演示不会再因为"几点跑的脚本"而整批失败；订单仍然有明确的可服务范围（仅当天）。
--
-- 放在 demo-real-orders.sql 末尾：本文件在部署迁移清单里，且排在
-- demo-cqupt-stations / demo-multi-leg 之后，能一次性覆盖各脚本定义的演示单（TPCQ*/TPDEMO*/TP346*/TPJTU*/TPCQUA*）。
-- 幂等：可重复执行。
-- ============================================================
UPDATE transport_order
SET earliest_pickup_time = TIMESTAMP(CURDATE(), '00:00:00'),
    latest_delivery_time = TIMESTAMP(CURDATE(), '23:59:59')
WHERE order_no LIKE 'TPCQ%'
   OR order_no LIKE 'TPDEMO%'
   OR order_no LIKE 'TP346%'
   OR order_no LIKE 'TPJTU%'
   OR order_no LIKE 'TPCQUA%';

-- 只读校验：演示单时间窗（应全部是当天 00:00:00 / 23:59:59）
SELECT order_no, status, earliest_pickup_time, latest_delivery_time
FROM transport_order
WHERE order_no LIKE 'TPCQ%' OR order_no LIKE 'TPDEMO%'
   OR order_no LIKE 'TP346%' OR order_no LIKE 'TPJTU%' OR order_no LIKE 'TPCQUA%'
ORDER BY order_no;

-- ============================================================
-- 演示订单池精简：默认只留 15 张核心演示单进「待入池」，其余演示单置为「已创建」（不进订单池）
--
-- 为什么：算法预检要求"本批件数 ≤ 实际选中车辆的货仓件数合计"，而单批最多 3 台车、每台 24 件
-- （≈72 件容量上限）。演示单全量进池（20+ 单）时，一件数就超过 3 台车的运力，
-- 会直接返回 OVER_CAPACITY（"运力不足（订单总需求超出可用车辆总容量）"）→ 出不了方案。
-- 留 15 单（约 37 件）既覆盖三条演示主线，又稳稳落在运力内：
--   346 主线（TP346A~E，各站顺路取派 + 跨区交接）、
--   跨片区联运（TPJTU1 重邮→重庆交通大学、TPCQ0004/0005 黄桷垭↔南山）、
--   重庆大学A区（TPCQUA1~3）、真实线路样例（TPDEMO1~4）。
-- 其余演示单仍是"已创建"状态留在库里（后台订单管理可见），需要时可在订单池页面单独归集入池。
-- 幂等：可重复执行；不会动本文件之外由用户自建/小程序下单的订单。
-- ============================================================
UPDATE transport_order
SET status = 0
WHERE (order_no LIKE 'TPCQ%' OR order_no LIKE 'TPDEMO%'
       OR order_no LIKE 'TP346%' OR order_no LIKE 'TPJTU%' OR order_no LIKE 'TPCQUA%')
  AND order_no NOT IN ('TP346A', 'TP346B', 'TP346C', 'TP346D', 'TP346E',
                       'TPJTU1', 'TPCQUA1', 'TPCQUA2', 'TPCQUA3',
                       'TPCQ0004', 'TPCQ0005', 'TPDEMO1', 'TPDEMO2', 'TPDEMO3', 'TPDEMO4');

-- 只读校验：演示单池应恰好 15 单
SELECT COUNT(*) AS demo_pool_count
FROM transport_order
WHERE status = 8
  AND (order_no LIKE 'TPCQ%' OR order_no LIKE 'TPDEMO%'
       OR order_no LIKE 'TP346%' OR order_no LIKE 'TPJTU%' OR order_no LIKE 'TPCQUA%');


-- ============================================================================
-- 346 路主线演示数据（中研所/黄桷垭/上新街/邮电大学→重庆工商大学/返程 + 重邮→重庆交通大学
-- + 重庆大学A区订单 + 一条线路一辆车的人车绑定与班次）
--
-- 为什么并入本文件：本文件在部署迁移清单里（deploy-dev.yml），而独立的 demo-line346-orders.sql
-- 不在清单内 —— 并入后部署即自动生成这批"后台订单池"里的演示单，演示时在「调度中心 → 订单池」直接可见，
-- 不需要再手工执行脚本。内容与 demo-line346-orders.sql 完全一致（该文件已改为指向本文件的说明）。
-- ============================================================================
-- ============================================================================
-- 346 路主线演示订单 + 重庆大学A区订单（答辩主线数据）
-- ============================================================================
--
-- 演示故事（司机A 开 346 路跑本职线路，途中按调度一段接一段取派货）：
--   始发站 悠山路 ──> 中研所 ──> 邮电大学 ──> 黄桷垭 ──> 上新街 ──> 龙门浩 ──> 小什字 ──> 较场口（终点站）
--   订单A 中研所 → 上新街            （346 直达：车到站即取即送）
--   订单B 黄桷垭 → 小什字小商品      （346 直达）
--   订单C 上新街 → 小什字小商品      （346 直达，与订单A/B 共载，先后送达）
--   订单D 邮电大学 → 重庆工商大学站  （跨片区，不能绕行这么远 → 346 送到与 320 的交汇站
--                                     交给 320 的司机续运，完成后续订单）
--   司机A 按调度一路跑到终点站较场口；返程（较场口 → 悠山路）又收到系统调度：
--   订单E 上新街取货 → 重庆邮电大学  （返程揽收/派送，属于**另一次调度**）
--
--   另有沙坪坝片区（重庆大学A区）订单 3 单，用于演示"另一片区单独出方案 + 跨片区联运接力"。
--
-- 依赖（先执行）：
--   transport-demo-data.sql → demo-cqupt-stations.sql → demo-cqupt-vehicles.sql
--   → demo-real-bus-network.sql → demo-real-orders.sql → demo-cqu-a-link.sql
--   → demo-vehicle-system-split.sql →（本脚本）
--
-- 幂等：站点/线路站点/车辆/司机/人车绑定全部 ON DUPLICATE KEY UPDATE；
--       订单按 id 去重并刷新时间窗（隔天演示也能直接跑）。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1) 重庆工商大学站：320 路沿线（学府大道，南岸校区）
--    为什么必须补：跨片区订单"邮电大学 → 重庆工商大学"要能解出"346 + 320"两段联运，
--    送达站就得落在 320 路覆盖范围内；否则本地线网无解 → 退化成跨城直送（画面上就是一条直线）。
--    坐标取高德该公交站实际站位，与 320 路"五公里"站相邻（约 260m）。
-- ---------------------------------------------------------------------------
INSERT INTO transport_station
    (id, station_code, station_name, station_level, longitude, latitude, address, status,
     source_type, station_type, user_access, vehicle_access, dispatch_enabled,
     tenant_id, creator, updater, deleted)
VALUES
    (1150, 'AMAP1150', '重庆工商大学站', 2, 106.5739000, 29.5073000,
     '南岸区学府大道·重庆工商大学南岸校区', 0,
     'REAL', 'BUS_STOP', b'1', b'1', b'1', 0, '1', '1', b'0')
ON DUPLICATE KEY UPDATE
    station_name = VALUES(station_name),
    longitude = VALUES(longitude), latitude = VALUES(latitude),
    address = VALUES(address), status = 0,
    user_access = b'1', vehicle_access = b'1', dispatch_enabled = b'1',
    deleted = b'0';

-- 把重庆工商大学站按真实站序插进 320 路（403）：位于"五公里"(seq 28) 与"轨道六公里站"(seq 29) 之间。
-- 幂等：已插入过就不再整体后移站序（避免重复执行把站序越推越大）。
SET @cqu_gongshang_on_320 := (
    SELECT COUNT(*) FROM transport_route_station
    WHERE route_id = 403 AND station_id = 1150 AND deleted = b'0'
);

UPDATE transport_route_station
SET sequence_no = sequence_no + 1
WHERE route_id = 403 AND sequence_no >= 29 AND deleted = b'0'
  AND @cqu_gongshang_on_320 = 0
ORDER BY sequence_no DESC;

INSERT INTO transport_route_station
    (id, route_id, station_id, sequence_no, planned_minutes, tenant_id, creator, updater, deleted)
VALUES
    -- id 40901：40000 段（40100~40446）已被真实线网占满，这里另起一段给演示补站用。
    -- planned_minutes=50 夹在相邻两站（五公里≈49、轨道六公里≈51）之间，站序计划分钟保持单调。
    (40901, 403, 1150, 29, 50, 0, '1', '1', b'0')
ON DUPLICATE KEY UPDATE
    station_id = VALUES(station_id),
    sequence_no = VALUES(sequence_no),
    planned_minutes = VALUES(planned_minutes),
    deleted = b'0';

-- ---------------------------------------------------------------------------
-- 2) 346 路演示车辆 + 司机A
--    现状：346 路（route 405）没有任何人车绑定 → 一键调度选不到它，演示里就没有"346 司机本职跑线"。
--    这里复用闲置车辆 4（渝A·B5204，原 status=1 停用），司机新建"346路司机A"。
-- ---------------------------------------------------------------------------
UPDATE transport_vehicle
SET plate_no = '渝A·B5204', status = 0, deleted = b'0'
WHERE id = 4;

INSERT INTO transport_driver
    (id, name, mobile, license_no, license_expire_date, status, tenant_id, creator, updater, deleted)
VALUES
    (6, '346路司机A', '13800138006', '500108198604120016', '2029-12-31', 0, 0, '1', '1', b'0')
ON DUPLICATE KEY UPDATE
    name = VALUES(name), mobile = VALUES(mobile), status = 0, deleted = b'0';

-- 运营线路 = 346 路（悠山路—较场口）：跨片区时车不越界，在 346/320 的交汇站交接
INSERT INTO transport_driver_vehicle
    (id, driver_id, vehicle_id, route_id, bind_time, unbind_time, status, tenant_id, creator, updater, deleted)
VALUES
    (6, 6, 4, 405, NOW(), NULL, 1, 0, '1', '1', b'0')
ON DUPLICATE KEY UPDATE
    driver_id = VALUES(driver_id), vehicle_id = VALUES(vehicle_id),
    route_id = VALUES(route_id), status = 1, deleted = b'0';

-- 司机端登录账号（手机号 = 司机档案手机号，密码 123456，与其余演示账号一致）
INSERT INTO member_user
    (nickname, name, sex, point, avatar, status, mobile, password, register_ip, creator, updater, tenant_id, deleted)
VALUES
    ('346路司机A', '346路司机A', 1, 0, '', 0, '13800138006',
     '$2a$10$b7Zw5dh0ldzG08ReZBVGZenwyyYUwS07iMM/KAZOP6cqjP7..RRzi',
     '127.0.0.1', '1', '1', 0, b'0')
ON DUPLICATE KEY UPDATE
    nickname = VALUES(nickname), name = VALUES(name),
    password = VALUES(password), status = 0, deleted = b'0';

-- 346 路班次：司机端工作台按"今天实际发车的那条班次执行记录"装车/妥投，
-- 商城订单发货也要选班次；346 路此前没有班次，司机端就没有可执行记录。
-- 班次时长按"一个往返"计（往返 ≈ 线路总计划分钟 × 2，346 路 11.96km ≈ 36 分钟单程）。
DELETE FROM transport_shift WHERE shift_code LIKE 'SH-L346-%';

INSERT INTO transport_shift
    (id, shift_code, route_id, planned_departure_time, planned_duration_minutes, status,
     tenant_id, creator, updater, deleted)
VALUES
    (951, 'SH-L346-01', 405, '06:40:00', 72, 0, 0, '1', '1', b'0'),
    (952, 'SH-L346-02', 405, '09:10:00', 72, 0, 0, '1', '1', b'0'),
    (953, 'SH-L346-03', 405, '11:40:00', 72, 0, 0, '1', '1', b'0'),
    (954, 'SH-L346-04', 405, '14:10:00', 72, 0, 0, '1', '1', b'0'),
    (955, 'SH-L346-05', 405, '16:40:00', 72, 0, 0, '1', '1', b'0'),
    (956, 'SH-L346-06', 405, '19:10:00', 72, 0, 0, '1', '1', b'0'),
    -- 347 路（老厂—菜园坝火车站，17.09km）：承运"重邮 → 南坪站"这一段
    (957, 'SH-L347-01', 402, '07:00:00', 102, 0, 0, '1', '1', b'0'),
    (958, 'SH-L347-02', 402, '11:00:00', 102, 0, 0, '1', '1', b'0'),
    (959, 'SH-L347-03', 402, '15:00:00', 102, 0, 0, '1', '1', b'0'),
    -- 303 路（南坪站—龙洲湾枢纽站，21.92km）：承运"南坪站 → 七公里（重庆交通大学门口）"这一段
    (960, 'SH-L303-01', 407, '07:20:00', 132, 0, 0, '1', '1', b'0'),
    (961, 'SH-L303-02', 407, '12:00:00', 132, 0, 0, '1', '1', b'0'),
    (962, 'SH-L303-03', 407, '16:00:00', 132, 0, 0, '1', '1', b'0')
ON DUPLICATE KEY UPDATE
    shift_code = VALUES(shift_code),
    route_id = VALUES(route_id),
    planned_departure_time = VALUES(planned_departure_time),
    planned_duration_minutes = VALUES(planned_duration_minutes),
    status = 0, deleted = b'0';

-- ---------------------------------------------------------------------------
-- 3) 沙坪坝（重庆大学A区）演示车辆 + 司机
--    220 路区间（route 415，经重大A区）与 318 路短线（route 414，沙坪坝走廊）此前都没有车
--    → 重大A区订单只能被跨片区车辆"直送"。这里各配一台车，让"重大A区 ↔ 沙坪坝火车站"
--    这类订单在走廊交汇站（土湾/小龙坎立交）由本片区车辆接力，而不是一辆车跨城跑。
--    另把 CQUPT02 的运营线路改到自建跨区线 R104（沙坪坝火车站—重庆邮电大学站），
--    由它承担"沙坪坝 ↔ 重邮"联运的末段（此前 R104 没有车，末段只能靠兜底派车）。
-- ---------------------------------------------------------------------------
INSERT INTO transport_vehicle
    (id, plate_no, vehicle_type, passenger_capacity, cargo_capacity_kg, cargo_capacity,
     status, tenant_id, creator, updater, deleted)
VALUES
    (6, '渝A·B5206', 1, 30, 500.00, 24, 0, 0, '1', '1', b'0'),
    (7, '渝A·B5207', 1, 28, 480.00, 24, 0, 0, '1', '1', b'0')
ON DUPLICATE KEY UPDATE
    plate_no = VALUES(plate_no), cargo_capacity = VALUES(cargo_capacity),
    status = 0, deleted = b'0';

INSERT INTO transport_driver
    (id, name, mobile, license_no, license_expire_date, status, tenant_id, creator, updater, deleted)
VALUES
    (7, '沙坪坝司机', '13800138007', '500106198709090017', '2029-06-30', 0, 0, '1', '1', b'0'),
    (8, '沙坪坝司机B', '13800138008', '500106198811110018', '2029-09-30', 0, 0, '1', '1', b'0')
ON DUPLICATE KEY UPDATE
    name = VALUES(name), mobile = VALUES(mobile), status = 0, deleted = b'0';

INSERT INTO transport_driver_vehicle
    (id, driver_id, vehicle_id, route_id, bind_time, unbind_time, status, tenant_id, creator, updater, deleted)
VALUES
    (7, 7, 6, 415, NOW(), NULL, 1, 0, '1', '1', b'0'),
    (8, 8, 7, 414, NOW(), NULL, 1, 0, '1', '1', b'0')
ON DUPLICATE KEY UPDATE
    driver_id = VALUES(driver_id), vehicle_id = VALUES(vehicle_id),
    route_id = VALUES(route_id), status = 1, deleted = b'0';

-- CQUPT02 → 自建跨区线 R104（沙坪坝火车站—重庆邮电大学站）：重邮 ↔ 沙坪坝联运的末段由它承运
UPDATE transport_driver_vehicle
SET route_id = 104, status = 1, deleted = b'0'
WHERE vehicle_id = 102;

-- ---------------------------------------------------------------------------
-- 3.1) 一条真实线路只跑一辆公交车（演示口径，避免同线路多车把画面搞乱）
--      · 渝A·B5205 原来和 渝A·B5203 同跑 329 路 → 改跑 347 路（402，老厂—菜园坝火车站）
--        这样"重庆邮电大学 → 南坪站"这一段有本线路的车承运；
--      · 新增 渝A·B5208 跑 303 路（407，南坪站—龙洲湾枢纽站）
--        → "南坪站 → 七公里（重庆交通大学门口）"这一段有本线路的车承运。
--      两段在南坪站交接，正是"重邮 → 重庆交通大学（七公里）"的联运路径。
-- ---------------------------------------------------------------------------
UPDATE transport_driver_vehicle
SET route_id = 402, status = 1, deleted = b'0'
WHERE vehicle_id = 5;

INSERT INTO transport_vehicle
    (id, plate_no, vehicle_type, passenger_capacity, cargo_capacity_kg, cargo_capacity,
     status, tenant_id, creator, updater, deleted)
VALUES
    (8, '渝A·B5208', 1, 26, 460.00, 24, 0, 0, '1', '1', b'0')
ON DUPLICATE KEY UPDATE
    plate_no = VALUES(plate_no), cargo_capacity = VALUES(cargo_capacity),
    status = 0, deleted = b'0';

INSERT INTO transport_driver
    (id, name, mobile, license_no, license_expire_date, status, tenant_id, creator, updater, deleted)
VALUES
    (9, '303路司机', '13800138009', '500108198812120019', '2030-03-31', 0, 0, '1', '1', b'0')
ON DUPLICATE KEY UPDATE
    name = VALUES(name), mobile = VALUES(mobile), status = 0, deleted = b'0';

INSERT INTO transport_driver_vehicle
    (id, driver_id, vehicle_id, route_id, bind_time, unbind_time, status, tenant_id, creator, updater, deleted)
VALUES
    (9, 9, 8, 407, NOW(), NULL, 1, 0, '1', '1', b'0')
ON DUPLICATE KEY UPDATE
    driver_id = VALUES(driver_id), vehicle_id = VALUES(vehicle_id),
    route_id = VALUES(route_id), status = 1, deleted = b'0';

INSERT INTO member_user
    (nickname, name, sex, point, avatar, status, mobile, password, register_ip, creator, updater, tenant_id, deleted)
VALUES
    ('303路司机', '303路司机', 1, 0, '', 0, '13800138009',
     '$2a$10$b7Zw5dh0ldzG08ReZBVGZenwyyYUwS07iMM/KAZOP6cqjP7..RRzi',
     '127.0.0.1', '1', '1', 0, b'0')
ON DUPLICATE KEY UPDATE
    nickname = VALUES(nickname), name = VALUES(name),
    password = VALUES(password), status = 0, deleted = b'0';

INSERT INTO member_user
    (nickname, name, sex, point, avatar, status, mobile, password, register_ip, creator, updater, tenant_id, deleted)
VALUES
    ('沙坪坝司机', '沙坪坝司机', 1, 0, '', 0, '13800138007',
     '$2a$10$b7Zw5dh0ldzG08ReZBVGZenwyyYUwS07iMM/KAZOP6cqjP7..RRzi',
     '127.0.0.1', '1', '1', 0, b'0'),
    ('沙坪坝司机B', '沙坪坝司机B', 1, 0, '', 0, '13800138008',
     '$2a$10$b7Zw5dh0ldzG08ReZBVGZenwyyYUwS07iMM/KAZOP6cqjP7..RRzi',
     '127.0.0.1', '1', '1', 0, b'0')
ON DUPLICATE KEY UPDATE
    nickname = VALUES(nickname), name = VALUES(name),
    password = VALUES(password), status = 0, deleted = b'0';

-- ---------------------------------------------------------------------------
-- 4) 演示订单（status = 8 待入池：后台「归集入池 → 一键演示/一键调度」即可出方案）
--    时间窗用 NOW() 相对值，并在 ON DUPLICATE KEY UPDATE 里刷新（隔天演示不用改脚本）。
--    站名解析成站点 id，不硬编码站点 id（站点表可能被别的脚本重建）。
-- ---------------------------------------------------------------------------
INSERT INTO transport_order
    (id, order_no, order_type, pickup_station_id, delivery_station_id,
     earliest_pickup_time, latest_delivery_time, status, total_amount,
     tenant_id, creator, create_time, updater, update_time, deleted)
VALUES
    -- 订单A：中研所 → 上新街（346 直达）
    (231, 'TP346A', 2,
     (SELECT id FROM (SELECT id FROM transport_station WHERE station_name = '中研所' ORDER BY id LIMIT 1) a),
     (SELECT id FROM (SELECT id FROM transport_station WHERE station_name = '上新街' ORDER BY id LIMIT 1) b),
     DATE_SUB(NOW(), INTERVAL 40 MINUTE), DATE_ADD(NOW(), INTERVAL 9 HOUR), 8, 8.00,
     0, '1', DATE_SUB(NOW(), INTERVAL 28 MINUTE), '1', NOW(), b'0'),
    -- 订单B：黄桷垭 → 小什字小商品（346 直达）
    (232, 'TP346B', 2,
     (SELECT id FROM (SELECT id FROM transport_station WHERE station_name = '黄桷垭' ORDER BY id LIMIT 1) a),
     (SELECT id FROM (SELECT id FROM transport_station WHERE station_name = '小什字小商品' ORDER BY id LIMIT 1) b),
     DATE_SUB(NOW(), INTERVAL 40 MINUTE), DATE_ADD(NOW(), INTERVAL 9 HOUR), 8, 9.00,
     0, '1', DATE_SUB(NOW(), INTERVAL 26 MINUTE), '1', NOW(), b'0'),
    -- 订单C：上新街 → 小什字小商品（346 直达，与 A/B 同车共载）
    (233, 'TP346C', 2,
     (SELECT id FROM (SELECT id FROM transport_station WHERE station_name = '上新街' ORDER BY id LIMIT 1) a),
     (SELECT id FROM (SELECT id FROM transport_station WHERE station_name = '小什字小商品' ORDER BY id LIMIT 1) b),
     DATE_SUB(NOW(), INTERVAL 35 MINUTE), DATE_ADD(NOW(), INTERVAL 9 HOUR), 8, 7.50,
     0, '1', DATE_SUB(NOW(), INTERVAL 24 MINUTE), '1', NOW(), b'0'),
    -- 订单D：邮电大学 → 重庆工商大学站（跨片区：346 送到交汇站交 320 续运）
    (234, 'TP346D', 2,
     (SELECT id FROM (SELECT id FROM transport_station WHERE station_name = '邮电大学' ORDER BY id LIMIT 1) a),
     (SELECT id FROM (SELECT id FROM transport_station WHERE station_name = '重庆工商大学站' ORDER BY id LIMIT 1) b),
     DATE_SUB(NOW(), INTERVAL 35 MINUTE), DATE_ADD(NOW(), INTERVAL 9 HOUR), 8, 16.00,
     0, '1', DATE_SUB(NOW(), INTERVAL 22 MINUTE), '1', NOW(), b'0'),
    -- 订单E：上新街 → 邮电大学（返程揽收派送：**另一次调度**）
    (235, 'TP346E', 2,
     (SELECT id FROM (SELECT id FROM transport_station WHERE station_name = '上新街' ORDER BY id LIMIT 1) a),
     (SELECT id FROM (SELECT id FROM transport_station WHERE station_name = '邮电大学' ORDER BY id LIMIT 1) b),
     DATE_SUB(NOW(), INTERVAL 30 MINUTE), DATE_ADD(NOW(), INTERVAL 9 HOUR), 8, 6.50,
     0, '1', DATE_SUB(NOW(), INTERVAL 20 MINUTE), '1', NOW(), b'0'),
    -- 重庆大学A区：片区直达 + 跨片区联运（丰富演示）
    (236, 'TPCQUA1', 2,
     (SELECT id FROM (SELECT id FROM transport_station WHERE station_name = '重大A区' ORDER BY id LIMIT 1) a),
     (SELECT id FROM (SELECT id FROM transport_station WHERE station_name = '沙坪坝火车站' ORDER BY id LIMIT 1) b),
     DATE_SUB(NOW(), INTERVAL 45 MINUTE), DATE_ADD(NOW(), INTERVAL 9 HOUR), 8, 6.00,
     0, '1', DATE_SUB(NOW(), INTERVAL 18 MINUTE), '1', NOW(), b'0'),
    (237, 'TPCQUA2', 2,
     (SELECT id FROM (SELECT id FROM transport_station WHERE station_name = '重大A区' ORDER BY id LIMIT 1) a),
     (SELECT id FROM (SELECT id FROM transport_station WHERE station_name = '重庆邮电大学站' ORDER BY id LIMIT 1) b),
     DATE_SUB(NOW(), INTERVAL 45 MINUTE), DATE_ADD(NOW(), INTERVAL 9 HOUR), 8, 14.00,
     0, '1', DATE_SUB(NOW(), INTERVAL 16 MINUTE), '1', NOW(), b'0'),
    (238, 'TPCQUA3', 2,
     (SELECT id FROM (SELECT id FROM transport_station WHERE station_name = '重庆邮电大学站' ORDER BY id LIMIT 1) a),
     (SELECT id FROM (SELECT id FROM transport_station WHERE station_name = '重大A区' ORDER BY id LIMIT 1) b),
     DATE_SUB(NOW(), INTERVAL 45 MINUTE), DATE_ADD(NOW(), INTERVAL 9 HOUR), 8, 12.00,
     0, '1', DATE_SUB(NOW(), INTERVAL 14 MINUTE), '1', NOW(), b'0'),
    -- 跨线路联运（演示"用户自己下单、算法自己解出来"）：重邮 → 重庆交通大学（七公里）
    --   本地线网无直达 → 347 路（402）邮电大学→南坪站，换乘 303 路（407）南坪站→七公里，两段接力
    (239, 'TPJTU1', 2,
     (SELECT id FROM (SELECT id FROM transport_station WHERE station_name = '邮电大学' ORDER BY id LIMIT 1) a),
     (SELECT id FROM (SELECT id FROM transport_station WHERE station_name = '七公里' ORDER BY id LIMIT 1) b),
     DATE_SUB(NOW(), INTERVAL 50 MINUTE), DATE_ADD(NOW(), INTERVAL 9 HOUR), 8, 15.00,
     0, '1', DATE_SUB(NOW(), INTERVAL 12 MINUTE), '1', NOW(), b'0')
ON DUPLICATE KEY UPDATE
    order_no = VALUES(order_no),
    pickup_station_id = VALUES(pickup_station_id),
    delivery_station_id = VALUES(delivery_station_id),
    earliest_pickup_time = VALUES(earliest_pickup_time),
    latest_delivery_time = VALUES(latest_delivery_time),
    status = VALUES(status),
    deleted = b'0';

INSERT INTO transport_cargo_order
    (id, order_id, cargo_category, fresh_flag, item_count, weight_kg, volume_m3, goods_name, goods_note,
     audit_status, review_status, pickup_service_mode, delivery_service_mode,
     receiver_name, receiver_mobile, receiver_address, original_address, original_latitude, original_longitude,
     tenant_id, creator, create_time, updater, update_time, deleted)
VALUES
    (231, 231, '农产品', b'0', 3, 5.00, 0.0300, '南山春茶', '345 路沿线捎带（订单A）', 1, 1,
     'STATION_TO_STATION', 'STATION_TO_STATION', '上新街收货人', '13900000231', '南岸区上新街',
     '重庆市南岸区中研所', 29.5280002, 106.6031520, 0, '1', NOW(), '1', NOW(), b'0'),
    (232, 232, '日用品', b'0', 2, 4.00, 0.0200, '黄桷垭手工艺品', '346 直达（订单B）', 1, 1,
     'STATION_TO_STATION', 'STATION_TO_STATION', '小什字商户', '13900000232', '渝中区小什字小商品市场',
     '南岸区黄桷垭', 29.5368310, 106.6043790, 0, '1', NOW(), '1', NOW(), b'0'),
    (233, 233, '日用品', b'0', 4, 8.00, 0.0400, '南坪日用百货', '与订单A/B 同车共载（订单C）', 1, 1,
     'STATION_TO_STATION', 'STATION_TO_STATION', '小什字商户', '13900000233', '渝中区小什字小商品市场',
     '南岸区上新街', 29.5476910, 106.5936620, 0, '1', NOW(), '1', NOW(), b'0'),
    (234, 234, '文件票据', b'0', 6, 12.00, 0.0600, '重邮教材', '跨片区：346 送到交汇站交 320 续运（订单D）', 1, 1,
     'STATION_TO_STATION', 'STATION_TO_STATION', '工商大学收货人', '13900000234', '重庆工商大学南岸校区',
     '重庆邮电大学', 29.5319520, 106.6034080, 0, '1', NOW(), '1', NOW(), b'0'),
    (235, 235, '农产品', b'0', 2, 4.00, 0.0200, '返程捎带的巴南土特产', '返程揽收 → 邮电大学（订单E，另一次调度）', 1, 1,
     'STATION_TO_STATION', 'STATION_TO_STATION', '重邮收货人', '13900000235', '重庆邮电大学',
     '南岸区上新街', 29.5476910, 106.5936620, 0, '1', NOW(), '1', NOW(), b'0'),
    (236, 236, '日用品', b'0', 5, 10.00, 0.0500, '校园快递统包', '沙坪坝片区直达（重大A区 → 沙坪坝火车站）', 1, 1,
     'STATION_TO_STATION', 'STATION_TO_STATION', '沙坪坝收货人', '13900000236', '沙坪坝火车站',
     '重庆大学A区', 29.5662410, 106.4636460, 0, '1', NOW(), '1', NOW(), b'0'),
    (237, 237, '日用品', b'0', 3, 9.00, 0.0450, '学生行李', '跨片区联运：沙坪坝走廊 → 南坪/重邮', 1, 1,
     'STATION_TO_STATION', 'STATION_TO_STATION', '重邮收货人', '13900000237', '重庆邮电大学',
     '重庆大学A区', 29.5662410, 106.4636460, 0, '1', NOW(), '1', NOW(), b'0'),
    (238, 238, '农产品', b'0', 2, 6.00, 0.0300, '重邮特产', '反向联运：重邮 → 重大A区', 1, 1,
     'STATION_TO_STATION', 'STATION_TO_STATION', '重大收件人', '13900000238', '重庆大学A区',
     '重庆邮电大学', 29.5319520, 106.6034080, 0, '1', NOW(), '1', NOW(), b'0'),
    (239, 239, '文件票据', b'0', 1, 3.00, 0.0100, '录取通知书', '重邮 → 重庆交通大学（七公里），无直达 → 两段联运',
     1, 1, 'STATION_TO_STATION', 'STATION_TO_STATION', '交大收件人', '13900000239', '重庆交通大学南岸校区',
     '重庆邮电大学', 29.5319520, 106.6034080, 0, '1', NOW(), '1', NOW(), b'0')
ON DUPLICATE KEY UPDATE
    item_count = VALUES(item_count),
    weight_kg = VALUES(weight_kg),
    volume_m3 = VALUES(volume_m3),
    goods_name = VALUES(goods_name),
    goods_note = VALUES(goods_note),
    audit_status = VALUES(audit_status),
    review_status = VALUES(review_status),
    deleted = b'0';

-- 时间窗统一成"当天全天 00:00~23:59"：调度按任务窗口派单（例：早上 08:40~12:40），
-- 订单时间窗必须与它有交集才可派；按脚本执行时刻算窗口会导致"几点跑的脚本"决定能不能派，
-- 现场会出现整批"送达时限早于窗口开始"。统一成当天全天，隔天重跑自动刷新（幂等）。
UPDATE transport_order
SET earliest_pickup_time = TIMESTAMP(CURDATE(), '00:00:00'),
    latest_delivery_time = TIMESTAMP(CURDATE(), '23:59:59')
WHERE order_no LIKE 'TPCQ%'
   OR order_no LIKE 'TPDEMO%'
   OR order_no LIKE 'TP346%'
   OR order_no LIKE 'TPJTU%'
   OR order_no LIKE 'TPCQUA%';

-- ---------------------------------------------------------------------------
-- 5) 只读校验：订单起终点 + 346 路车辆绑定 + 320 路站序
-- ---------------------------------------------------------------------------
SELECT o.id, o.order_no, o.status, ps.station_name AS pickup, ds.station_name AS delivery
FROM transport_order o
    JOIN transport_station ps ON ps.id = o.pickup_station_id
    JOIN transport_station ds ON ds.id = o.delivery_station_id
WHERE o.id BETWEEN 231 AND 239
ORDER BY o.id;

SELECT '演示人车绑定（本脚本负责的线路）' AS info, v.plate_no, d.name AS driver, d.mobile, r.route_name
FROM transport_driver_vehicle dv
    JOIN transport_vehicle v ON v.id = dv.vehicle_id
    JOIN transport_driver d ON d.id = dv.driver_id
    JOIN transport_route r ON r.id = dv.route_id
WHERE dv.route_id IN (405, 402, 407, 415, 414, 104) AND dv.deleted = b'0'
ORDER BY dv.route_id, dv.id;

SELECT '320路站序（含重庆工商大学站）' AS info, rs.sequence_no, s.id AS station_id, s.station_name
FROM transport_route_station rs
    JOIN transport_station s ON s.id = rs.station_id
WHERE rs.route_id = 403 AND rs.deleted = b'0'
ORDER BY rs.sequence_no;
