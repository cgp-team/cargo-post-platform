-- 演示订单改为"高德真实公交站点之间"的真实行程（配合 demo-real-bus-network.sql）
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
SELECT o.id, o.order_no, ps.station_name AS pickup, ps.source_type AS pickup_src,
       ds.station_name AS delivery, ds.source_type AS delivery_src
FROM transport_order o
JOIN transport_station ps ON ps.id = o.pickup_station_id
JOIN transport_station ds ON ds.id = o.delivery_station_id
WHERE o.id BETWEEN 201 AND 208
ORDER BY o.id;
