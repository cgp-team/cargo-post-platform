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

-- 校验（预期 2 行站点）
SELECT id, station_code, station_name, station_level, longitude, latitude FROM transport_station WHERE id IN (101, 102);
