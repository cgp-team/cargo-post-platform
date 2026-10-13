-- 多段联运演示数据（换乘站 + 更多订单 + 司机在线状态）
--
-- 用途：让"无直达线路 → 经换乘站拆分多段 → 司机在换乘站交接 → 订单部分完成/完成"
--       这条多段联运主链路有真实可演示的数据。
-- 前置：先执行 demo-cqupt-stations.sql（站点 101/102/103）与 demo-cqupt-vehicles.sql（司机 101/102）。
-- 时间窗：订单最晚送达 = NOW() + 2 小时（与 demo-real-orders.sql 同一口径：一个任务段时间内调度完）。
-- 幂等：INSERT ... ON DUPLICATE KEY UPDATE。
--
-- 执行（服务器仓库根目录）：
--   set -a; source /opt/cargo-post-platform/.env; set +a
--   docker compose --env-file /opt/cargo-post-platform/.env -f deploy/docker-compose.yml \
--     exec -T mysql mysql --default-character-set=utf8mb4 -u root -p"${DB_PASSWORD}" "${DB_NAME}" < sql/mysql/demo-multi-leg.sql

-- ========== 换乘站：南岸客运站（接入重邮线路，作为换乘候选枢纽） ==========
INSERT INTO transport_station
    (id, station_code, station_name, station_level, longitude, latitude, address, status, tenant_id, creator, updater, deleted)
VALUES
    (104, 'ST104', '四公里交通换乘枢纽站', 1, 106.5778000, 29.5194000, '南岸区四公里（320路/轨道3号线换乘）', 0, 0, '1', '1', b'0')
ON DUPLICATE KEY UPDATE
    station_name = VALUES(station_name),
    longitude = VALUES(longitude),
    latitude = VALUES(latitude),
    address = VALUES(address),
    status = 0,
    deleted = b'0';

-- 把换乘站接入重邮线路（route 101），成为该线路上的换乘枢纽节点
-- 清理本演示走廊的旧节点（复用 id 会与旧行唯一键冲突，先删再插）
DELETE FROM transport_route_station WHERE id BETWEEN 301 AND 320;

INSERT INTO transport_route_station
    (id, route_id, station_id, sequence_no, planned_minutes, tenant_id, creator, updater, deleted)
VALUES
    (203, 101, 104, 3, 8, 0, '1', '1', b'0')
ON DUPLICATE KEY UPDATE
    sequence_no = VALUES(sequence_no),
    planned_minutes = VALUES(planned_minutes),
    deleted = b'0';

-- ========== 更多多段联运演示订单（无直达线路，必须经换乘站中转） ==========
-- 南山站(103) 与 重邮站(101)/黄桷垭站(102) 之间没有同一线路覆盖 → needMultiLeg=true
INSERT INTO transport_order
    (id, order_no, order_type, pickup_station_id, delivery_station_id, earliest_pickup_time, latest_delivery_time,
     status, total_amount, tenant_id, creator, create_time, updater, update_time, deleted)
VALUES
    (204, 'TPCQ0004', 2, 102, 103, DATE_SUB(NOW(), INTERVAL 1 HOUR), DATE_ADD(NOW(), INTERVAL 2 HOUR), 8, 11.00, 0, '1', DATE_SUB(NOW(), INTERVAL 15 MINUTE), '1', NOW(), b'0'),
    (205, 'TPCQ0005', 2, 103, 102, DATE_SUB(NOW(), INTERVAL 1 HOUR), DATE_ADD(NOW(), INTERVAL 2 HOUR), 8, 10.50, 0, '1', DATE_SUB(NOW(), INTERVAL 10 MINUTE), '1', NOW(), b'0')
ON DUPLICATE KEY UPDATE
    pickup_station_id = VALUES(pickup_station_id),
    delivery_station_id = VALUES(delivery_station_id),
    earliest_pickup_time = VALUES(earliest_pickup_time),
    latest_delivery_time = VALUES(latest_delivery_time),
    status = VALUES(status),
    deleted = b'0';

INSERT INTO transport_cargo_order
    (id, order_id, cargo_category, fresh_flag, item_count, weight_kg, volume_m3, goods_name, goods_note,
     audit_status, review_status, pickup_service_mode, delivery_service_mode, receiver_name, receiver_mobile,
     receiver_address, original_address, original_latitude, original_longitude,
     tenant_id, creator, create_time, updater, update_time, deleted)
VALUES
    (204, 204, '日用品', b'0', 3, 4.20, 0.0300, '南山商铺补货', '轻拿轻放', 1, 1, 'STATION_TO_STATION', 'STATION_TO_STATION',
     '南山便利店', '13800000004', '南山植物园路 8 号', '黄桷垭正街', 29.5382000, 106.6056000, 0, '1', DATE_SUB(NOW(), INTERVAL 15 MINUTE), '1', NOW(), b'0'),
    (205, 205, '农产品', b'0', 2, 6.00, 0.0400, '南山枇杷', '需换乘接力配送', 1, 1, 'STATION_TO_STATION', 'STATION_TO_STATION',
     '陈同学', '13800000005', '重庆邮电大学 5 教', '南山植物园', 29.5554000, 106.6280000, 0, '1', DATE_SUB(NOW(), INTERVAL 10 MINUTE), '1', NOW(), b'0')
ON DUPLICATE KEY UPDATE
    item_count = VALUES(item_count),
    weight_kg = VALUES(weight_kg),
    goods_name = VALUES(goods_name),
    goods_note = VALUES(goods_note),
    pickup_service_mode = VALUES(pickup_service_mode),
    delivery_service_mode = VALUES(delivery_service_mode),
    receiver_name = VALUES(receiver_name),
    receiver_mobile = VALUES(receiver_mobile),
    receiver_address = VALUES(receiver_address),
    original_address = VALUES(original_address),
    original_latitude = VALUES(original_latitude),
    original_longitude = VALUES(original_longitude),
    deleted = b'0';

-- ========== 司机在线状态（在线/离线判断） ==========
INSERT INTO transport_driver_status
    (id, driver_id, online_status, current_vehicle_id, last_heartbeat, last_latitude, last_longitude, tenant_id, create_time, update_time)
VALUES
    (101, 101, 1, 101, NOW(), 29.5325000, 106.5765000, 0, NOW(), NOW()),
    (102, 102, 1, 102, NOW(), 29.5370000, 106.5748000, 0, NOW(), NOW()),
    (103, 103, 1, 103, NOW(), 29.5325000, 106.5765000, 0, NOW(), NOW())
ON DUPLICATE KEY UPDATE
    online_status = VALUES(online_status),
    current_vehicle_id = VALUES(current_vehicle_id),
    last_heartbeat = VALUES(last_heartbeat),
    last_latitude = VALUES(last_latitude),
    last_longitude = VALUES(last_longitude);

-- ========== 班次执行：让模拟车辆呈现"正在运行"状态 ==========
-- 说明：确定性班次模拟按 transport_shift 的计划时刻插值；这里补当天执行记录，
--       司机端/监控端据此看到"今天这趟车已在途"（跑完的旧记录靠 ON DUPLICATE 覆盖）。
INSERT INTO transport_shift_execution
    (id, shift_id, driver_id, vehicle_id, exec_date, depart_time, current_station_id, loaded_count, status,
     tenant_id, creator, updater, deleted)
VALUES
    (101, 101, 101, 101, CURDATE(), CONCAT(CURDATE(), ' 08:00:00'), 101, 0, 0, 0, '1', '1', b'0'),
    (102, 102, 102, 102, CURDATE(), CONCAT(CURDATE(), ' 15:00:00'), 102, 0, 0, 0, '1', '1', b'0')
ON DUPLICATE KEY UPDATE
    vehicle_id = VALUES(vehicle_id),
    exec_date = VALUES(exec_date),
    current_station_id = VALUES(current_station_id),
    deleted = b'0';

-- 校验（预期：换乘站 1 行、线路节点 1 行、多段演示订单 2 行、在线司机 2 行）
SELECT '换乘站' AS info, id, station_name FROM transport_station WHERE id = 104;
SELECT '多段订单' AS info, id, order_no, pickup_station_id, delivery_station_id, status FROM transport_order WHERE id IN (204, 205);
SELECT '司机在线' AS info, driver_id, online_status, current_vehicle_id FROM transport_driver_status WHERE driver_id IN (101, 102);
SELECT '班次执行' AS info, shift_id, driver_id, vehicle_id, exec_date, status FROM transport_shift_execution WHERE id IN (101, 102);

-- ========== 站点可达性三维模型（需求 §30~§34）==========
-- 核心：站点启用 ≠ 车辆可进入 ≠ 可用于调度。
-- 重庆邮电大学校内站(101)：用户能到，但运输车辆进不去 → vehicle_access=0、dispatch_enabled=0；
-- 重邮南门货运站(107)：校内用户最近的可服务站点（约 500m），车辆可进、可调度。
UPDATE transport_station SET user_access = b'1', vehicle_access = b'0', dispatch_enabled = b'0',
    station_type = 'CARGO_STATION', source_type = 'PROJECT'
  WHERE id = 101;
UPDATE transport_station SET user_access = b'1', vehicle_access = b'1', dispatch_enabled = b'1'
  WHERE id IN (102, 103, 104);

INSERT INTO transport_station
    (id, station_code, station_name, station_level, longitude, latitude, address, status,
     source_type, station_type, user_access, vehicle_access, dispatch_enabled,
     tenant_id, creator, updater, deleted)
VALUES
    (107, 'ST107', '重邮南门货运站', 1, 106.6020000, 29.5290000, '重庆邮电大学南门（崇文路）', 0,
     'PROJECT', 'CARGO_STATION', b'1', b'1', b'1', 0, '1', '1', b'0')
ON DUPLICATE KEY UPDATE
    station_name = VALUES(station_name), longitude = VALUES(longitude), latitude = VALUES(latitude),
    address = VALUES(address), status = 0,
    source_type = VALUES(source_type), station_type = VALUES(station_type),
    user_access = VALUES(user_access), vehicle_access = VALUES(vehicle_access),
    dispatch_enabled = VALUES(dispatch_enabled), deleted = b'0';

-- 南山站接入黄桷垭线路：让"南山站 ↔ 重邮站"可通过黄桷垭换乘（2 段联运）
INSERT INTO transport_route
    (id, route_code, route_name, start_station_id, end_station_id, distance_km, status, tenant_id, creator, updater, deleted)
VALUES
    (102, 'R102', '南山—黄桷垭线', 103, 102, 2.20, 0, 0, '1', '1', b'0')
ON DUPLICATE KEY UPDATE
    route_name = VALUES(route_name), start_station_id = VALUES(start_station_id),
    end_station_id = VALUES(end_station_id), deleted = b'0';

INSERT INTO transport_route_station
    (id, route_id, station_id, sequence_no, planned_minutes, tenant_id, creator, updater, deleted)
VALUES
    (204, 102, 103, 1, 0, 0, '1', '1', b'0'),
    (205, 102, 102, 2, 8, 0, '1', '1', b'0')
ON DUPLICATE KEY UPDATE
    route_id = VALUES(route_id), station_id = VALUES(station_id),
    sequence_no = VALUES(sequence_no), planned_minutes = VALUES(planned_minutes), deleted = b'0';

-- ========== Demo 场景站点/线路（需求 §104~§107：直达 / 两段 / 三段）==========
-- 线路拓扑：301 南门—上新街；302 上新街—学堂湾；303 学堂湾—工商大学；304 南门—南坪
INSERT INTO transport_station
    (id, station_code, station_name, station_level, longitude, latitude, address, status,
     source_type, station_type, user_access, vehicle_access, dispatch_enabled,
     tenant_id, creator, updater, deleted)
VALUES
    -- 以下坐标全部取自高德 POI/公交站点（347路、320路、轨道3号线真实站名）
    (201, 'ST201', '邮电大学站（347路）', 1, 106.6038000, 29.5326000, '南岸区邮电大学公交站', 0, 'PROJECT', 'CARGO_STATION', b'1', b'1', b'1', 0, '1', '1', b'0'),
    (202, 'ST202', '海棠溪站（347×320换乘）', 1, 106.5885000, 29.5425000, '南岸区海棠溪', 0, 'PROJECT', 'CARGO_STATION', b'1', b'1', b'1', 0, '1', '1', b'0'),
    (203, 'ST203', '五公里站（320路）', 1, 106.5740000, 29.5050000, '南岸区五公里', 0, 'PROJECT', 'CARGO_STATION', b'1', b'1', b'1', 0, '1', '1', b'0'),
    (204, 'ST204', '重庆工商大学站（轨道3号线）', 1, 106.5739000, 29.5073000, '重庆工商大学（南岸校区）', 0, 'PROJECT', 'CARGO_STATION', b'1', b'1', b'1', 0, '1', '1', b'0'),
    (205, 'ST205', '南坪站（347路）', 1, 106.5711000, 29.5292000, '南岸区南坪', 0, 'PROJECT', 'CARGO_STATION', b'1', b'1', b'1', 0, '1', '1', b'0')
ON DUPLICATE KEY UPDATE
    station_name = VALUES(station_name), longitude = VALUES(longitude), latitude = VALUES(latitude),
    address = VALUES(address), status = 0, user_access = VALUES(user_access),
    vehicle_access = VALUES(vehicle_access), dispatch_enabled = VALUES(dispatch_enabled), deleted = b'0';

INSERT INTO transport_route
    (id, route_code, route_name, start_station_id, end_station_id, distance_km, status, tenant_id, creator, updater, deleted)
VALUES
    -- 真实公交线路区段（高德鉴别：347路区间 → 320路 → 轨道3号线）
    -- 里程按高德路网实测回填（347 段 5.58km / 320 段 5.14km / 3号线段 2.49km）
    (301, 'R301', '347路区间（邮电大学—海棠溪）', 201, 202, 5.58, 0, 0, '1', '1', b'0'),
    (302, 'R302', '320路（海棠溪—四公里—五公里）', 202, 203, 5.14, 0, 0, '1', '1', b'0'),
    (303, 'R303', '轨道3号线（四公里—重庆工商大学）', 104, 204, 2.49, 0, 0, '1', '1', b'0')
ON DUPLICATE KEY UPDATE
    route_name = VALUES(route_name), start_station_id = VALUES(start_station_id),
    end_station_id = VALUES(end_station_id), distance_km = VALUES(distance_km), deleted = b'0';

INSERT INTO transport_route_station
    (id, route_id, station_id, sequence_no, planned_minutes, tenant_id, creator, updater, deleted)
VALUES
    -- 347路区间：邮电大学 → 海棠溪
    (301, 301, 201, 1, 0, 0, '1', '1', b'0'),
    (302, 301, 202, 2, 12, 0, '1', '1', b'0'),
    -- 320路：海棠溪 → 四公里 → 五公里
    (303, 302, 202, 1, 0, 0, '1', '1', b'0'),
    (304, 302, 104, 2, 22, 0, '1', '1', b'0'),
    (305, 302, 203, 3, 30, 0, '1', '1', b'0'),
    -- 轨道3号线：四公里 → 重庆工商大学
    (306, 303, 104, 1, 0, 0, '1', '1', b'0'),
    (307, 303, 204, 2, 6, 0, '1', '1', b'0')
ON DUPLICATE KEY UPDATE
    sequence_no = VALUES(sequence_no), planned_minutes = VALUES(planned_minutes), deleted = b'0';

-- 三个 Demo 订单：206 直达（同一线路）/ 207 两段联运 / 208 三段联运
INSERT INTO transport_order
    (id, order_no, order_type, pickup_station_id, delivery_station_id, earliest_pickup_time, latest_delivery_time,
     status, total_amount, tenant_id, creator, create_time, updater, update_time, deleted)
VALUES
    -- 206 直达（347路同线）/ 207 两段（347→320，海棠溪换乘）/ 208 三段（347→320→轨道3号线）
    (206, 'TPDEMO1', 2, 201, 202, DATE_SUB(NOW(), INTERVAL 1 HOUR), DATE_ADD(NOW(), INTERVAL 2 HOUR), 8, 12.00, 0, '1', DATE_SUB(NOW(), INTERVAL 9 MINUTE), '1', NOW(), b'0'),
    (207, 'TPDEMO2', 2, 201, 203, DATE_SUB(NOW(), INTERVAL 1 HOUR), DATE_ADD(NOW(), INTERVAL 2 HOUR), 8, 16.00, 0, '1', DATE_SUB(NOW(), INTERVAL 8 MINUTE), '1', NOW(), b'0'),
    (208, 'TPDEMO3', 2, 201, 204, DATE_SUB(NOW(), INTERVAL 1 HOUR), DATE_ADD(NOW(), INTERVAL 2 HOUR), 8, 21.00, 0, '1', DATE_SUB(NOW(), INTERVAL 7 MINUTE), '1', NOW(), b'0')
ON DUPLICATE KEY UPDATE
    pickup_station_id = VALUES(pickup_station_id), delivery_station_id = VALUES(delivery_station_id),
    earliest_pickup_time = VALUES(earliest_pickup_time), latest_delivery_time = VALUES(latest_delivery_time),
    status = VALUES(status), deleted = b'0';

INSERT INTO transport_cargo_order
    (id, order_id, cargo_category, fresh_flag, item_count, weight_kg, volume_m3, goods_name, goods_note,
     audit_status, review_status, pickup_service_mode, delivery_service_mode, receiver_name, receiver_mobile,
     receiver_address, original_address, original_latitude, original_longitude,
     tenant_id, creator, create_time, updater, update_time, deleted)
VALUES
    (206, 206, '日用品', b'0', 1, 2.00, 0.0100, '直达演示货物', 'Demo1 直达', 1, 1, 'STATION_TO_STATION', 'STATION_TO_STATION',
     '直达收件人', '13900000001', '南坪货运站', '重邮南门', 29.5325000, 106.5765000, 0, '1', DATE_SUB(NOW(), INTERVAL 9 MINUTE), '1', NOW(), b'0'),
    (207, 207, '日用品', b'0', 1, 2.50, 0.0120, '两段联运演示货物', 'Demo2 两段联运', 1, 1, 'STATION_TO_STATION', 'STATION_TO_STATION',
     '两段收件人', '13900000002', '学堂湾货运站', '重邮南门', 29.5325000, 106.5765000, 0, '1', DATE_SUB(NOW(), INTERVAL 8 MINUTE), '1', NOW(), b'0'),
    (208, 208, '日用品', b'0', 1, 3.00, 0.0150, '三段联运演示货物', 'Demo3 三段联运', 1, 1, 'STATION_TO_STATION', 'STATION_TO_STATION',
     '三段收件人', '13900000003', '重庆工商大学站', '重邮南门', 29.5325000, 106.5765000, 0, '1', DATE_SUB(NOW(), INTERVAL 7 MINUTE), '1', NOW(), b'0')
ON DUPLICATE KEY UPDATE
    item_count = VALUES(item_count), weight_kg = VALUES(weight_kg), goods_name = VALUES(goods_name),
    goods_note = VALUES(goods_note), receiver_name = VALUES(receiver_name),
    receiver_mobile = VALUES(receiver_mobile), receiver_address = VALUES(receiver_address), deleted = b'0';

-- 校验：Demo 站点/线路/订单
SELECT id, station_name, user_access, vehicle_access, dispatch_enabled FROM transport_station WHERE id IN (101, 107);
SELECT id, order_no, pickup_station_id, delivery_station_id FROM transport_order WHERE id IN (206, 207, 208);

-- 清理旧演示走廊残留（第一版自造坐标的 304 线路与其站点引用）
DELETE FROM transport_route_station WHERE route_id = 304;
DELETE FROM transport_route WHERE id = 304;

-- ========== 演示订单归属演示会员（小程序端才能查看运输拓扑并收到通知）==========
-- demo-member.sql 预置演示会员 13800000000（member_user.id 通常为 3，这里按手机号动态取）
UPDATE transport_order
SET member_user_id = (SELECT id FROM member_user WHERE mobile = '13800000000' ORDER BY id LIMIT 1)
WHERE id BETWEEN 201 AND 208
  AND EXISTS (SELECT 1 FROM member_user WHERE mobile = '13800000000');

SELECT id, order_no, member_user_id FROM transport_order WHERE id BETWEEN 201 AND 208 ORDER BY id;
