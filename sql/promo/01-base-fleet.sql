-- ============================================
-- 宣传片演示数据 · 01 基础车队（车辆/司机/人车绑定 + 自建示例线路）
-- 来源：历史 transport-demo-data.sql 改编（2026-09 比赛演示期在服务器实测可用）。
--   改编：去掉了原文件里的成都演示站点/线路/订单（宣传片全程在重庆，监控地图是全量站点，
--   成都的坐标会把地图视野带偏）；商品改到 10-members-products.sql（适配 V026 新状态语义）。
-- 幂等：INSERT IGNORE / ON DUPLICATE KEY UPDATE，可重复执行。
-- ============================================

-- ---------- 车辆（渝A 牌照，跑真实公交线路） ----------
INSERT IGNORE INTO transport_vehicle (id, plate_no, vehicle_type, passenger_capacity, cargo_capacity_kg, status, tenant_id, creator, create_time, updater, update_time, deleted) VALUES
(1, '渝A·B5201', 1, 30, 500.00, 0, 0, '1', NOW(), '1', NOW(), b'0'),
(2, '渝A·B5202', 1, 25, 400.00, 0, 0, '1', NOW(), '1', NOW(), b'0'),
(3, '渝A·B5203', 2, 15, 800.00, 0, 0, '1', NOW(), '1', NOW(), b'0'),
(4, '渝A·B5204', 1, 35, 600.00, 0, 0, '1', NOW(), '1', NOW(), b'0'),
(5, '渝A·B5205', 3, 20, 1200.00, 0, 0, '1', NOW(), '1', NOW(), b'0');

-- 货仓件数上限：多单共载场景件数容易顶到旧值（6~8）导致"容量越界"卡住一键调度；统一 24 件
UPDATE transport_vehicle SET cargo_capacity = 24
WHERE id BETWEEN 1 AND 5 AND (cargo_capacity IS NULL OR cargo_capacity < 24);

-- ---------- 司机（身份证重庆 5001xx 口径） ----------
INSERT IGNORE INTO transport_driver (id, name, mobile, license_no, license_expire_date, status, tenant_id, creator, create_time, updater, update_time, deleted) VALUES
(1, '周建国', '13800138001', '500108198001150011', '2027-06-15', 0, 0, '1', NOW(), '1', NOW(), b'0'),
(2, '李伟民', '13800138002', '500106198203220012', '2028-03-20', 0, 0, '1', NOW(), '1', NOW(), b'0'),
(3, '王守义', '13800138003', '500107197911080013', '2026-12-01', 0, 0, '1', NOW(), '1', NOW(), b'0'),
(4, '赵德柱', '13800138004', '500108198508140014', '2029-09-10', 0, 0, '1', NOW(), '1', NOW(), b'0'),
(5, '陈永发', '13800138005', '500109199012250015', '2030-01-05', 0, 0, '1', NOW(), '1', NOW(), b'0');

-- ---------- 司机车辆绑定 ----------
INSERT IGNORE INTO transport_driver_vehicle (id, driver_id, vehicle_id, bind_time, unbind_time, status, tenant_id, creator, create_time, updater, update_time, deleted) VALUES
(1, 1, 1, '2026-01-05 08:00:00', NULL, 1, 0, '1', NOW(), '1', NOW(), b'0'),
(2, 2, 2, '2026-02-10 08:00:00', NULL, 1, 0, '1', NOW(), '1', NOW(), b'0'),
(3, 3, 3, '2026-03-01 08:00:00', NULL, 1, 0, '1', NOW(), '1', NOW(), b'0'),
(4, 4, 5, '2026-04-01 08:00:00', NULL, 1, 0, '1', NOW(), '1', NOW(), b'0'),
(5, 5, 3, '2026-05-12 08:00:00', NULL, 1, 0, '1', NOW(), '1', NOW(), b'0');

-- ============================================
-- 自建站点 / 自建客货邮线路示例（重邮明志苑驿站 → 四公里集散）
-- ============================================
INSERT INTO transport_station
    (id, station_code, station_name, station_level, longitude, latitude, address, status,
     source_type, station_type, user_access, vehicle_access, dispatch_enabled,
     sort, remark, tenant_id, creator, updater, deleted)
VALUES
    (1146, 'CQ-MZY-01', '重邮明志苑驿站', 2, 106.6055000, 29.5310000,
     '重庆邮电大学明志苑2舍', 0, 'PROJECT', 'CARGO_STATION', b'1', b'1', b'1',
     1, '校园快递驿站，车辆可达、开放调度', 0, '1', '1', b'0')
ON DUPLICATE KEY UPDATE
    station_name = VALUES(station_name),
    longitude = VALUES(longitude), latitude = VALUES(latitude),
    address = VALUES(address),
    user_access = VALUES(user_access), vehicle_access = VALUES(vehicle_access),
    dispatch_enabled = VALUES(dispatch_enabled),
    status = 0, deleted = b'0';

INSERT INTO transport_route
    (id, route_code, route_name, start_station_id, end_station_id, distance_km, status,
     source_type, service_type, dispatch_enabled, tenant_id, creator, updater, deleted)
VALUES
    (545, 'CQ-MZY-LINE', '重邮明志苑—四公里集散中心', 1146, 104, 2.98, 0,
     'PROJECT', 'MIXED', b'1', 0, '1', '1', b'0')
ON DUPLICATE KEY UPDATE
    route_name = VALUES(route_name), start_station_id = VALUES(start_station_id),
    end_station_id = VALUES(end_station_id), distance_km = VALUES(distance_km),
    dispatch_enabled = VALUES(dispatch_enabled), status = 0, deleted = b'0';

INSERT INTO transport_route_station
    (id, route_id, station_id, sequence_no, planned_minutes, tenant_id, creator, updater, deleted)
VALUES
    (905451, 545, 1146, 1, 0, 0, '1', '1', b'0'),
    (905452, 545, 107, 2, 1, 0, '1', '1', b'0'),
    (905453, 545, 104, 3, 9, 0, '1', '1', b'0')
ON DUPLICATE KEY UPDATE
    station_id = VALUES(station_id), sequence_no = VALUES(sequence_no),
    planned_minutes = VALUES(planned_minutes), deleted = b'0';

INSERT INTO transport_shift
    (id, shift_code, route_id, planned_departure_time, planned_duration_minutes, status,
     tenant_id, creator, updater, deleted)
VALUES
    (461, 'SH-MZY-01', 545, '08:20:00', 30, 0, 0, '1', '1', b'0'),
    (462, 'SH-MZY-02', 545, '16:40:00', 30, 0, 0, '1', '1', b'0')
ON DUPLICATE KEY UPDATE
    route_id = VALUES(route_id), planned_departure_time = VALUES(planned_departure_time),
    planned_duration_minutes = VALUES(planned_duration_minutes), status = 0, deleted = b'0';

SELECT '基础车队' AS info,
       (SELECT COUNT(*) FROM transport_vehicle WHERE deleted = b'0') AS vehicles,
       (SELECT COUNT(*) FROM transport_driver WHERE deleted = b'0') AS drivers;
