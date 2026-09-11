
-- ========== CQUPT 演示车辆（重庆邮电大学片区专用） ==========
INSERT INTO transport_vehicle
    (id, plate_no, vehicle_type, passenger_capacity, cargo_capacity_kg, cargo_capacity, status, tenant_id, creator, updater, deleted)
VALUES
    (101, '渝A·CQUPT01', 1, 20, 300.00, 8, 0, 0, '1', '1', b'0'),
    (102, '渝A·CQUPT02', 1, 15, 200.00, 6, 0, 0, '1', '1', b'0'),
    -- 第三台演示车辆：多段联运要"相邻段不同车"，至少需要 2 台空闲车；3 台可覆盖三段联运
    (103, '渝A·CQUPT03', 1, 15, 200.00, 6, 0, 0, '1', '1', b'0')
ON DUPLICATE KEY UPDATE
    plate_no = VALUES(plate_no),
    status = 0,
    deleted = b'0';

-- ========== CQUPT 演示司机 ==========
INSERT INTO transport_driver
    (id, name, mobile, license_no, license_expire_date, status, tenant_id, creator, updater, deleted)
VALUES
    (101, '重邮司机A', '13900001001', 'CQ510100199001010001', '2028-12-31', 0, 0, '1', '1', b'0'),
    (102, '重邮司机B', '13900001002', 'CQ510100199002020002', '2029-06-30', 0, 0, '1', '1', b'0'),
    (103, '重邮司机C', '13900001003', 'CQ510100199003030003', '2029-12-31', 0, 0, '1', '1', b'0')
ON DUPLICATE KEY UPDATE
    name = VALUES(name),
    status = 0,
    deleted = b'0';

-- ========== CQUPT 司机车辆绑定 ==========
INSERT INTO transport_driver_vehicle
    (id, driver_id, vehicle_id, bind_time, status, tenant_id, creator, updater, deleted)
VALUES
    (101, 101, 101, NOW(), 1, 0, '1', '1', b'0'),
    (102, 102, 102, NOW(), 1, 0, '1', '1', b'0'),
    (103, 103, 103, NOW(), 1, 0, '1', '1', b'0')
ON DUPLICATE KEY UPDATE
    driver_id = VALUES(driver_id),
    vehicle_id = VALUES(vehicle_id),
    status = 1,
    deleted = b'0';

-- 验证
SELECT 'CQUPT车辆' AS info, id, plate_no, status FROM transport_vehicle WHERE id IN (101, 102, 103);
SELECT 'CQUPT司机' AS info, id, name, mobile FROM transport_driver WHERE id IN (101, 102, 103);
SELECT 'CQUPT绑定' AS info, driver_id, vehicle_id, status FROM transport_driver_vehicle WHERE id IN (101, 102, 103);

-- ========== CQUPT 司机会员账号（司机端登录用：登录会员手机号 = 司机档案手机号）==========
-- 密码统一 123456（与 demo-member.sql 相同的 BCrypt 值），小程序用「密码登录」即可切到司机端
INSERT INTO member_user
    (nickname, name, sex, point, avatar, status, mobile, password, register_ip, creator, updater, tenant_id, deleted)
VALUES
    ('重邮司机A', '重邮司机A', 1, 0, '', 0, '13900001001', '$2a$10$b7Zw5dh0ldzG08ReZBVGZenwyyYUwS07iMM/KAZOP6cqjP7..RRzi', '127.0.0.1', '1', '1', 0, b'0'),
    ('重邮司机B', '重邮司机B', 1, 0, '', 0, '13900001002', '$2a$10$b7Zw5dh0ldzG08ReZBVGZenwyyYUwS07iMM/KAZOP6cqjP7..RRzi', '127.0.0.1', '1', '1', 0, b'0'),
    ('重邮司机C', '重邮司机C', 1, 0, '', 0, '13900001003', '$2a$10$b7Zw5dh0ldzG08ReZBVGZenwyyYUwS07iMM/KAZOP6cqjP7..RRzi', '127.0.0.1', '1', '1', 0, b'0')
ON DUPLICATE KEY UPDATE
    nickname = VALUES(nickname),
    name = VALUES(name),
    password = VALUES(password),
    status = 0,
    deleted = b'0';

SELECT 'CQUPT司机会员' AS info, id, mobile, nickname FROM member_user WHERE mobile IN ('13900001001','13900001002','13900001003');
