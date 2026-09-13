
-- ========== CQUPT 演示车辆（重庆邮电大学片区专用） ==========
INSERT INTO transport_vehicle
    (id, plate_no, vehicle_type, passenger_capacity, cargo_capacity_kg, cargo_capacity, status, tenant_id, creator, updater, deleted)
VALUES
    -- cargo_capacity = 货仓件数上限：公交/大巴利用空闲运力捎带，行李舱能放不少小件包裹，
    -- 演示批次（多单共载）件数较多，这里给 24 件，避免"容量越界"直接卡住一键调度
    (101, '渝A·CQUPT01', 1, 20, 300.00, 24, 0, 0, '1', '1', b'0'),
    (102, '渝A·CQUPT02', 1, 15, 200.00, 24, 0, 0, '1', '1', b'0'),
    -- 第三台演示车辆：多段联运要"相邻段不同车"，至少需要 2 台空闲车；3 台可覆盖三段联运
    (103, '渝A·CQUPT03', 1, 15, 200.00, 24, 0, 0, '1', '1', b'0')
ON DUPLICATE KEY UPDATE
    plate_no = VALUES(plate_no),
    cargo_capacity = VALUES(cargo_capacity),
    status = 0,
    deleted = b'0';

-- ========== CQUPT 演示司机 ==========
INSERT INTO transport_driver
    (id, name, mobile, license_no, license_expire_date, status, tenant_id, creator, updater, deleted)
VALUES
    (101, '重邮司机A', '13900001001', '500108199001010001', '2028-12-31', 0, 0, '1', '1', b'0'),
    (102, '重邮司机B', '13900001002', '500108199002020002', '2029-06-30', 0, 0, '1', '1', b'0'),
    (103, '重邮司机C', '13900001003', '500108199003030003', '2029-12-31', 0, 0, '1', '1', b'0')
ON DUPLICATE KEY UPDATE
    name = VALUES(name),
    license_no = VALUES(license_no),
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


-- ===========================================================================
-- 自建线路 R103/R104 线网（从 demo-cqu-a-link.sql 第 6 节并入本文件）
-- 原因：demo-cqu-a-link.sql 不在部署迁移清单里 → 线上一直缺 R103/R104，重邮/重大A区订单
-- 解不出本地换乘，只能退到高德建议（车辆会略微跑出自己的运营线路）。本文件在清单里，部署即补。
-- 幂等：先删这两条线的站点再重建，不影响人车绑定/其它订单。
-- ===========================================================================
-- 6) 自建线路：R103 普通线路（6~12km / 12~22 站，重邮—南坪片区）
--    与 R104 跨区线（沙坪坝火车站—重庆邮电大学站，用于"重大A区 ↔ 重邮"联运的末段）
--    背景：自建站点/线路（重庆邮电大学站、黄桷垭站、四公里枢纽站…）与真实公交线网
--    只在四公里有一个交点，"重大A区 ↔ 重邮"因此解不出本地三段联运（只能走高德兜底）。
--    本线按"自建站点 + 真实站点同站换乘"的口径修建：重庆邮电大学站(自建) → 邮电大学(真实)
--    → 黄桷垭(真实) → 黄桷垭站(自建) → … → 沙坪坝火车站(真实)，两端都与真实线路共站。
DELETE FROM transport_route_station WHERE route_id IN (103, 104);
INSERT INTO transport_route
    (id, route_code, route_name, start_station_id, end_station_id, distance_km, source_type, dispatch_enabled,
     status, tenant_id, creator, updater, deleted)
VALUES
    -- 普通自建线路：重邮—黄桷垭—南坪，约 9.8km、16 站（自建站点 4 个：重庆邮电大学站/黄桷垭站/南门货运站/明志苑驿站）
    (103, 'R103', '重庆邮电大学站—南坪站线', 101, 421, 9.80, 'PROJECT', b'1', 0, 0, '1', '1', b'0'),
    -- 跨区自建线：沙坪坝火车站—重庆邮电大学站（联运末段用，里程长于普通线，如实标注）
    (104, 'R104', '沙坪坝火车站—重庆邮电大学站线', 619, 101, 18.60, 'PROJECT', b'1', 0, 0, '1', '1', b'0')
ON DUPLICATE KEY UPDATE
    route_name = VALUES(route_name), start_station_id = VALUES(start_station_id),
    end_station_id = VALUES(end_station_id), distance_km = VALUES(distance_km),
    source_type = VALUES(source_type), dispatch_enabled = VALUES(dispatch_enabled),
    status = 0, deleted = b'0';

INSERT INTO transport_route_station
    (route_id, station_id, sequence_no, planned_minutes, tenant_id, creator, updater, deleted)
VALUES
    -- R103：自建站点与真实公交站同站排布，两端均可与真实线路换乘
    (103, 101, 1, 0, 0, '1', '1', b'0'),   -- 重庆邮电大学站（自建）
    (103, 410, 2, 2, 0, '1', '1', b'0'),   -- 邮电大学（真实）
    (103, 409, 3, 5, 0, '1', '1', b'0'),   -- 黄桷垭（真实）
    (103, 102, 4, 7, 0, '1', '1', b'0'),   -- 黄桷垭站（自建）
    (103, 107, 5, 9, 0, '1', '1', b'0'),   -- 重邮南门货运站（自建）
    (103, 1146, 6, 11, 0, '1', '1', b'0'), -- 重邮明志苑驿站（自建）
    (103, 408, 7, 14, 0, '1', '1', b'0'),  -- 崇文路口（真实）
    (103, 407, 8, 16, 0, '1', '1', b'0'),  -- 上新街站（真实）
    (103, 419, 9, 18, 0, '1', '1', b'0'),  -- 上新街（真实）
    (103, 420, 10, 20, 0, '1', '1', b'0'), -- 海棠溪（真实）
    (103, 406, 11, 22, 0, '1', '1', b'0'), -- 海棠溪新街（真实）
    (103, 405, 12, 24, 0, '1', '1', b'0'), -- 海棠溪站（真实）
    (103, 404, 13, 26, 0, '1', '1', b'0'), -- 海棠晓月（真实）
    (103, 403, 14, 28, 0, '1', '1', b'0'), -- 福利社·小米熊儿童医院（真实）
    (103, 402, 15, 30, 0, '1', '1', b'0'), -- 南坪东路（真实）
    (103, 421, 16, 33, 0, '1', '1', b'0'), -- 南坪站（真实）
    -- R104：沙坪坝走廊 ↔ 重邮（跨区）
    (104, 619, 1, 0, 0, '1', '1', b'0'),   -- 沙坪坝火车站（真实，与 414/513 共站换乘）
    (104, 618, 2, 6, 0, '1', '1', b'0'),   -- 小龙坎立交（真实）
    (104, 617, 3, 10, 0, '1', '1', b'0'),  -- 土湾（真实，与 220路区间 共站换乘）
    (104, 421, 4, 42, 0, '1', '1', b'0'),  -- 南坪站（真实）
    (104, 410, 5, 62, 0, '1', '1', b'0'),  -- 邮电大学（真实）
    (104, 409, 6, 66, 0, '1', '1', b'0'),  -- 黄桷垭（真实）
    (104, 101, 7, 70, 0, '1', '1', b'0')   -- 重庆邮电大学站（自建）
ON DUPLICATE KEY UPDATE
    station_id = VALUES(station_id), planned_minutes = VALUES(planned_minutes), deleted = b'0';

-- ===========================================================================
-- 人车绑定补全：线上这几台车没绑运营线路 → 规划时这些线路被判成"没有车在跑"，
-- 联运通道（401/403/404/103/104）整体缺失，只能退到高德建议。此处按演示口径补齐（幂等）。
-- ===========================================================================
UPDATE transport_driver_vehicle SET route_id = 401 WHERE vehicle_id = 1   AND deleted = b'0';
UPDATE transport_driver_vehicle SET route_id = 403 WHERE vehicle_id = 2   AND deleted = b'0';
UPDATE transport_driver_vehicle SET route_id = 404 WHERE vehicle_id = 3   AND deleted = b'0';
UPDATE transport_driver_vehicle SET route_id = 402 WHERE vehicle_id = 5   AND deleted = b'0';
UPDATE transport_driver_vehicle SET route_id = 405 WHERE vehicle_id = 4   AND deleted = b'0';
UPDATE transport_driver_vehicle SET route_id = 415 WHERE vehicle_id = 6   AND deleted = b'0';
UPDATE transport_driver_vehicle SET route_id = 414 WHERE vehicle_id = 7   AND deleted = b'0';
UPDATE transport_driver_vehicle SET route_id = 407 WHERE vehicle_id = 8   AND deleted = b'0';
UPDATE transport_driver_vehicle SET route_id = 103 WHERE vehicle_id = 101 AND deleted = b'0';
UPDATE transport_driver_vehicle SET route_id = 104 WHERE vehicle_id = 102 AND deleted = b'0';
UPDATE transport_driver_vehicle SET route_id = 103 WHERE vehicle_id = 103 AND deleted = b'0';
