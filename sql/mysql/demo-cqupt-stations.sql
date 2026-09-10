-- 校园/真实场景演示站点（重庆邮电大学）
--
-- 用途：让"用户在重庆邮电大学附近寄货 → 车辆无法直接进入 → 推荐最近服务站点"这条主链路
--       有真实可用的站点数据（项目原有演示站点都在成都附近，距离几百公里，演示不真实）。
--
-- 说明：这里只是**补两行真实站点/线路数据**，代码里没有任何"重庆邮电大学"硬编码；
--       换城市只需换这段数据。坐标均为 GCJ-02（与地图/高德/小程序一致）。
-- 幂等：INSERT ... ON DUPLICATE KEY UPDATE。
--
-- 执行（服务器仓库根目录）：
--   set -a; source /opt/cargo-post-platform/.env; set +a
--   docker compose --env-file /opt/cargo-post-platform/.env -f deploy/docker-compose.yml \
--     exec -T mysql mysql --default-character-set=utf8mb4 -u root -p"${DB_PASSWORD}" "${DB_NAME}" < sql/mysql/demo-cqupt-stations.sql

INSERT INTO transport_station
    (id, station_code, station_name, station_level, longitude, latitude, address, status, tenant_id, creator, updater, deleted)
VALUES
    -- 校园内取货点（村级站点，用户步行可达）
    (101, 'ST101', '重庆邮电大学站', 2, 106.5765000, 29.5325000, '重庆邮电大学崇文门', 0, 0, '1', '1', b'0'),
    -- 附近场站（可作调度场站/接驳点）
    (102, 'ST102', '黄桷垭站', 1, 106.5748000, 29.5370000, '南岸区黄桷垭正街', 0, 0, '1', '1', b'0')
ON DUPLICATE KEY UPDATE
    station_name = VALUES(station_name),
    longitude = VALUES(longitude),
    latitude = VALUES(latitude),
    address = VALUES(address),
    status = 0,
    deleted = b'0';

INSERT INTO transport_route
    (id, route_code, route_name, start_station_id, end_station_id, distance_km, status, tenant_id, creator, updater, deleted)
VALUES
    (101, 'R101', '重庆邮电大学—黄桷垭线', 101, 102, 0.60, 0, 0, '1', '1', b'0')
ON DUPLICATE KEY UPDATE
    route_name = VALUES(route_name),
    start_station_id = VALUES(start_station_id),
    end_station_id = VALUES(end_station_id),
    deleted = b'0';

INSERT INTO transport_route_station
    (id, route_id, station_id, sequence_no, planned_minutes, tenant_id, creator, updater, deleted)
VALUES
    (201, 101, 101, 1, 0, 0, '1', '1', b'0'),
    (202, 101, 102, 2, 5, 0, '1', '1', b'0')
ON DUPLICATE KEY UPDATE
    sequence_no = VALUES(sequence_no),
    planned_minutes = VALUES(planned_minutes),
    deleted = b'0';

-- 班次（让该线路进入"确定性班次模拟"，实时公交与车来提醒都能跑）
INSERT INTO transport_shift
    (id, shift_code, route_id, planned_departure_time, planned_duration_minutes, status, tenant_id, creator, updater, deleted)
VALUES
    (101, 'SH101', 101, '08:00:00', 20, 0, 0, '1', '1', b'0'),
    (102, 'SH102', 101, '15:00:00', 20, 0, 0, '1', '1', b'0')
ON DUPLICATE KEY UPDATE
    route_id = VALUES(route_id),
    planned_departure_time = VALUES(planned_departure_time),
    status = 0,
    deleted = b'0';

-- ---------- 校园片区演示订单 ----------
-- 用途：调度工作台"一键调度"时，重庆邮电大学片区不止用户刚寄的那一单 —— 还有 3 单同片区的
--       模拟订单（全部 待入池=8，会被「一键演示」的"归集全部待入池"一起收进订单池），
--       这样调度结果可视化里能看到"我的那一单 + 同片区其他模拟订单"的完整线路；
--       成都片区订单（transport-demo-data.sql 的 TP2026…）仍在订单池里，第二次点「一键调度」
--       会自动生成第二套方案（按片区分别成方案，避免跨城混批导致算法无解）。
--
-- 时间窗用 NOW() 相对值：算法按时间窗判可行，写死的历史日期会判不可行。
INSERT INTO transport_station
    (id, station_code, station_name, station_level, longitude, latitude, address, status, tenant_id, creator, updater, deleted)
VALUES
    (103, 'ST103', '南山站', 2, 106.5830000, 29.5230000, '南岸区南山植物园路', 0, 0, '1', '1', b'0')
ON DUPLICATE KEY UPDATE
    station_name = VALUES(station_name),
    longitude = VALUES(longitude),
    latitude = VALUES(latitude),
    status = 0,
    deleted = b'0';

INSERT INTO transport_order
    (id, order_no, order_type, pickup_station_id, delivery_station_id, earliest_pickup_time, latest_delivery_time,
     status, total_amount, tenant_id, creator, create_time, updater, update_time, deleted)
VALUES
    (201, 'TPCQ0001', 2, 101, 102, DATE_SUB(NOW(), INTERVAL 1 HOUR), DATE_ADD(NOW(), INTERVAL 8 HOUR), 8, 12.00, 0, '1', DATE_SUB(NOW(), INTERVAL 40 MINUTE), '1', NOW(), b'0'),
    (202, 'TPCQ0002', 2, 103, 101, DATE_SUB(NOW(), INTERVAL 1 HOUR), DATE_ADD(NOW(), INTERVAL 8 HOUR), 8, 9.50, 0, '1', DATE_SUB(NOW(), INTERVAL 30 MINUTE), '1', NOW(), b'0'),
    (203, 'TPCQ0003', 2, 101, 103, DATE_SUB(NOW(), INTERVAL 1 HOUR), DATE_ADD(NOW(), INTERVAL 8 HOUR), 8, 15.00, 0, '1', DATE_SUB(NOW(), INTERVAL 20 MINUTE), '1', NOW(), b'0')
ON DUPLICATE KEY UPDATE
    pickup_station_id = VALUES(pickup_station_id),
    delivery_station_id = VALUES(delivery_station_id),
    earliest_pickup_time = VALUES(earliest_pickup_time),
    latest_delivery_time = VALUES(latest_delivery_time),
    status = VALUES(status),
    deleted = b'0';

-- 货物子表（件数/重量/体积/物品信息，后台"物品信息"列与算法载荷都读这里）
INSERT INTO transport_cargo_order
    (id, order_id, cargo_category, fresh_flag, item_count, weight_kg, volume_m3, goods_name, goods_note,
     audit_status, review_status, pickup_service_mode, delivery_service_mode, receiver_name, receiver_mobile,
     receiver_address, original_address, original_latitude, original_longitude,
     tenant_id, creator, create_time, updater, update_time, deleted)
VALUES
    (201, 201, '日用品', b'0', 2, 3.50, 0.0200, '宿舍日用品箱', '崇文门交接', 1, 1, 'NEAREST_STATION', 'STATION_TO_STATION',
     '李同学', '13800000001', '黄桷垭正街 12 号', '重庆邮电大学明志苑 6 栋', 29.5302000, 106.5781000, 0, '1', DATE_SUB(NOW(), INTERVAL 40 MINUTE), '1', NOW(), b'0'),
    (202, 202, '农产品', b'0', 1, 5.00, 0.0300, '南山土鸡蛋', '轻拿轻放', 1, 1, 'STATION_TO_STATION', 'STATION_TO_STATION',
     '王老师', '13800000002', '重庆邮电大学 8 教', '南山植物园', 29.5240000, 106.5825000, 0, '1', DATE_SUB(NOW(), INTERVAL 30 MINUTE), '1', NOW(), b'0'),
    (203, 203, '文件票据', b'0', 1, 0.20, 0.0010, '录取通知书材料', '轻拿轻放', 1, 1, 'NEAREST_STATION', 'STATION_TO_STATION',
     '赵同学', '13800000003', '南山站自取', '重庆邮电大学明志苑 3 栋', 29.5310000, 106.5772000, 0, '1', DATE_SUB(NOW(), INTERVAL 20 MINUTE), '1', NOW(), b'0')
ON DUPLICATE KEY UPDATE
    cargo_category = VALUES(cargo_category),
    fresh_flag = VALUES(fresh_flag),
    item_count = VALUES(item_count),
    weight_kg = VALUES(weight_kg),
    volume_m3 = VALUES(volume_m3),
    goods_name = VALUES(goods_name),
    audit_status = VALUES(audit_status),
    review_status = VALUES(review_status),
    pickup_service_mode = VALUES(pickup_service_mode),
    original_address = VALUES(original_address),
    deleted = b'0';

-- 校验（预期：3 行站点、3 行重邮片区待入池订单）
SELECT id, station_code, station_name, station_level, longitude, latitude FROM transport_station WHERE id IN (101, 102, 103);
SELECT id, order_no, pickup_station_id, delivery_station_id, status FROM transport_order WHERE id IN (201, 202, 203);
