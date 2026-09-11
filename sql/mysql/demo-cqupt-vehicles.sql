
-- ========== CQUPT 演示车辆（重庆邮电大学片区专用） ==========
INSERT INTO transport_vehicle
    (id, plate_no, vehicle_type, passenger_capacity, cargo_capacity_kg, cargo_capacity, status, tenant_id, creator, updater, deleted)
VALUES
    (101, '渝A·CQUPT01', 1, 20, 300.00, 8, 0, 0, '1', '1', b'0'),
    (102, '渝A·CQUPT02', 1, 15, 200.00, 6, 0, 0, '1', '1', b'0')
ON DUPLICATE KEY UPDATE
    plate_no = VALUES(plate_no),
    status = 0,
    deleted = b'0';

-- ========== CQUPT 演示司机 ==========
INSERT INTO transport_driver
    (id, name, mobile, license_no, license_expire_date, status, tenant_id, creator, updater, deleted)
VALUES
    (101, '重邮司机A', '13900001001', 'CQ510100199001010001', '2028-12-31', 0, 0, '1', '1', b'0'),
    (102, '重邮司机B', '13900001002', 'CQ510100199002020002', '2029-06-30', 0, 0, '1', '1', b'0')
ON DUPLICATE KEY UPDATE
    name = VALUES(name),
    status = 0,
    deleted = b'0';

-- ========== CQUPT 司机车辆绑定 ==========
INSERT INTO transport_driver_vehicle
    (id, driver_id, vehicle_id, bind_time, status, tenant_id, creator, updater, deleted)
VALUES
    (101, 101, 101, NOW(), 1, 0, '1', '1', b'0'),
    (102, 102, 102, NOW(), 1, 0, '1', '1', b'0')
ON DUPLICATE KEY UPDATE
    driver_id = VALUES(driver_id),
    vehicle_id = VALUES(vehicle_id),
    status = 1,
    deleted = b'0';

-- 验证
SELECT 'CQUPT车辆' AS info, id, plate_no, status FROM transport_vehicle WHERE id IN (101, 102);
SELECT 'CQUPT司机' AS info, id, name, mobile FROM transport_driver WHERE id IN (101, 102);
SELECT 'CQUPT绑定' AS info, driver_id, vehicle_id, status FROM transport_driver_vehicle WHERE id IN (101, 102);