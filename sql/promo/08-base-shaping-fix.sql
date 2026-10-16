-- 沙坪坝 / 重庆大学A区 ↔ 南坪（重邮片区）换乘节点修复
--
-- 现象：真实线网生成时，同一个物理站点被两条线路各自生成了一份站点记录
--       （612/887 瑞天路铭依眼科、613/888 瑞天路中段、614/889 化龙桥、615/890 红岩村、
--         616/891 羊角堡、617/892 土湾、618/893 小龙坎立交、619/894 沙坪坝火车站），
--       导致换乘图在沙坪坝走廊断开：
--         · 220路区间（route 415）与 318路短线（route 414）只连到 617/619；
--         · 318路全程（route 513/530）只连到 892/894；
--       两条线路明明在同一站交汇，系统却认为没有共同站点 → 重大A区 到 南坪/重邮
--       只能退化成"本地线网未覆盖，直送兜底"，联运也就无从谈起（更谈不上在各自运营范围内交接）。
--
-- 处理：把重复站点合并到主记录（route_station 指回主站点，重复站点软删除），
--       线路交汇点恢复后，联运才能在"两条线路交汇站"由一台车交给另一台车/另一位司机。
--
-- 幂等：重复执行无副作用（重复站点已软删除时 UPDATE 不命中）。

-- 1) 线路站点指回主站点（唯一键是 route_id + sequence_no，改 station_id 不会冲突）
UPDATE transport_route_station rs
    JOIN (
        SELECT 612 AS keep_id, 887 AS dup_id UNION ALL
        SELECT 613, 888 UNION ALL
        SELECT 614, 889 UNION ALL
        SELECT 615, 890 UNION ALL
        SELECT 616, 891 UNION ALL
        SELECT 617, 892 UNION ALL
        SELECT 618, 893 UNION ALL
        SELECT 619, 894
    ) m ON m.dup_id = rs.station_id
SET rs.station_id = m.keep_id
WHERE rs.deleted = b'0';

-- 2) 重复站点软删除（保留行，避免历史订单/运输段外键指向不存在）
UPDATE transport_station
SET deleted = b'1', updater = '1'
WHERE id IN (887, 888, 889, 890, 891, 892, 893, 894);

-- 3) 沙坪坝走廊线路的计划分钟 + 班次：
--    没有班次就不会有车、站点也不会进「附近公交」（后端按"有班次的线路"出地图数据），
--    所以"覆盖沙坪坝重庆大学A区公交线路"必须同时补班次，否则页面上看不到这些线路。
--    计划分钟按线路里程 / 20km/h 均摊（与 demo-real-bus-shifts.sql 同一口径）。
UPDATE transport_route_station rs
    JOIN transport_route r ON r.id = rs.route_id
    JOIN (SELECT route_id, MIN(sequence_no) AS min_seq, MAX(sequence_no) AS max_seq
          FROM transport_route_station
          WHERE route_id IN (413, 414, 415, 513, 530)
          GROUP BY route_id) t ON t.route_id = rs.route_id
SET rs.planned_minutes = ROUND((COALESCE(r.distance_km, 10) / 20.0 * 60.0)
        * (rs.sequence_no - t.min_seq) / GREATEST(1, t.max_seq - t.min_seq))
WHERE rs.route_id IN (413, 414, 415, 513, 530);

DELETE FROM transport_shift WHERE shift_code LIKE 'SH-CQUA-%';

INSERT INTO transport_shift
    (id, shift_code, route_id, planned_departure_time, planned_duration_minutes, status,
     tenant_id, creator, updater, deleted)
VALUES
    -- 220路区间（土湾—学林雅园，经重大A区）：短程高频
    (901, 'SH-CQUA-415-01', 415, '07:00:00', 9, 0, 0, '1', '1', b'0'),
    (902, 'SH-CQUA-415-02', 415, '10:30:00', 9, 0, 0, '1', '1', b'0'),
    (903, 'SH-CQUA-415-03', 415, '15:00:00', 9, 0, 0, '1', '1', b'0'),
    (904, 'SH-CQUA-415-04', 415, '19:30:00', 9, 0, 0, '1', '1', b'0'),
    -- 318路短线（瑞祥路—沙坪坝火车站）
    (905, 'SH-CQUA-414-01', 414, '07:30:00', 82, 0, 0, '1', '1', b'0'),
    (906, 'SH-CQUA-414-02', 414, '13:00:00', 82, 0, 0, '1', '1', b'0'),
    (907, 'SH-CQUA-414-03', 414, '18:30:00', 82, 0, 0, '1', '1', b'0'),
    -- 318路全程（瑞祥路—沙坪坝火车站，南坪 ↔ 沙坪坝走廊）
    (908, 'SH-CQUA-513-01', 513, '06:45:00', 82, 0, 0, '1', '1', b'0'),
    (909, 'SH-CQUA-513-02', 513, '12:30:00', 82, 0, 0, '1', '1', b'0'),
    (910, 'SH-CQUA-513-03', 513, '17:45:00', 82, 0, 0, '1', '1', b'0'),
    -- 808路（融侨半岛—童家桥正街，与318路在沙坪坝走廊共站）
    (911, 'SH-CQUA-530-01', 530, '08:00:00', 69, 0, 0, '1', '1', b'0'),
    (912, 'SH-CQUA-530-02', 530, '14:00:00', 69, 0, 0, '1', '1', b'0'),
    (913, 'SH-CQUA-530-03', 530, '20:00:00', 69, 0, 0, '1', '1', b'0'),
    -- 181路（朝天门公交枢纽站—小杨公桥，经重大中门/沙中路）
    (914, 'SH-CQUA-413-01', 413, '09:00:00', 58, 0, 0, '1', '1', b'0'),
    (915, 'SH-CQUA-413-02', 413, '16:00:00', 58, 0, 0, '1', '1', b'0')
ON DUPLICATE KEY UPDATE
    route_id = VALUES(route_id),
    planned_departure_time = VALUES(planned_departure_time),
    planned_duration_minutes = VALUES(planned_duration_minutes),
    status = 0,
    deleted = b'0';

-- 4) 人车绑定的「运营线路」= 运营范围（联运时车不越界，跨片区在交汇站交接）
-- 3.1) 自建站点参与调度：PROJECT 站点默认 vehicle_access=0/dispatch_enabled=0，
--      会导致"寄货选重庆邮电大学站 → 所选站点车辆无法进入"，自建线网也就无法用于调度。
--      这里把重邮/沙坪坝片区的自建站点开放为可调度站点（与真实站点同一套调度口径）。
UPDATE transport_station
SET vehicle_access = b'1', dispatch_enabled = b'1', updater = '1'
WHERE id IN (101, 102, 103, 104, 107, 1146);

--    重邮片区（347路区间/320路/329路）由主演示车辆运营；
--    沙坪坝走廊（220路区间 经重大A区 / 318路 南坪↔沙坪坝 / 329路）由 CQUPT 演示车辆运营。
UPDATE transport_driver_vehicle SET route_id = 401 WHERE vehicle_id = 1;   -- 渝A·B5201 → 347路区间（上海城—老厂）
UPDATE transport_driver_vehicle SET route_id = 403 WHERE vehicle_id = 2;   -- 渝A·B5202 → 320路（市五院—渝南公交站场）
UPDATE transport_driver_vehicle SET route_id = 404 WHERE vehicle_id = 3;   -- 渝A·B5203 → 329路（南山加勒比—白鹤枢纽站）
UPDATE transport_driver_vehicle SET route_id = 404 WHERE vehicle_id = 5;   -- 渝A·B5205 → 329路
UPDATE transport_driver_vehicle SET route_id = 415 WHERE vehicle_id = 101; -- 渝A·CQUPT01 → 220路区间（经重大A区）
UPDATE transport_driver_vehicle SET route_id = 513 WHERE vehicle_id = 102; -- 渝A·CQUPT02 → 318路（南坪 ↔ 沙坪坝走廊）
UPDATE transport_driver_vehicle SET route_id = 403 WHERE vehicle_id = 103; -- 渝A·CQUPT03 → 320路

-- 5) 只读校验：沙坪坝走廊的换乘节点应至少被 2 条线路共用
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

DELETE FROM transport_shift WHERE shift_code LIKE 'SH-CQUA-10%';
INSERT INTO transport_shift
    (id, shift_code, route_id, planned_departure_time, planned_duration_minutes, status,
     tenant_id, creator, updater, deleted)
VALUES
    (920, 'SH-CQUA-103-01', 103, '07:15:00', 33, 0, 0, '1', '1', b'0'),
    (921, 'SH-CQUA-103-02', 103, '11:45:00', 33, 0, 0, '1', '1', b'0'),
    (922, 'SH-CQUA-103-03', 103, '16:30:00', 33, 0, 0, '1', '1', b'0'),
    (923, 'SH-CQUA-103-04', 103, '20:30:00', 33, 0, 0, '1', '1', b'0'),
    (924, 'SH-CQUA-104-01', 104, '07:20:00', 70, 0, 0, '1', '1', b'0'),
    (925, 'SH-CQUA-104-02', 104, '17:10:00', 70, 0, 0, '1', '1', b'0')
ON DUPLICATE KEY UPDATE
    route_id = VALUES(route_id), planned_departure_time = VALUES(planned_departure_time),
    planned_duration_minutes = VALUES(planned_duration_minutes), status = 0, deleted = b'0';

SELECT rs.station_id, s.station_name,
       COUNT(DISTINCT rs.route_id) AS route_count,
       GROUP_CONCAT(DISTINCT r.route_name ORDER BY r.id SEPARATOR ' | ') AS routes
FROM transport_route_station rs
    JOIN transport_route r ON r.id = rs.route_id
    JOIN transport_station s ON s.id = rs.station_id
WHERE rs.station_id IN (612, 613, 614, 615, 616, 617, 618, 619)
GROUP BY rs.station_id, s.station_name
ORDER BY route_count DESC, rs.station_id;
