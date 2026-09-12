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
  AND @cqu_gongshang_on_320 = 0;

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

-- 时间窗统一成"当天 06:00~22:00"：演示时不管几点跑、任务窗口选哪一段（例如早上 8-10 点），
-- 这些订单都落在窗口内可派；隔天重跑脚本自动刷新，不依赖脚本执行时刻。
UPDATE transport_order
SET earliest_pickup_time = TIMESTAMP(CURDATE(), '06:00:00'),
    latest_delivery_time = TIMESTAMP(CURDATE(), '22:00:00')
WHERE id BETWEEN 231 AND 239;

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
