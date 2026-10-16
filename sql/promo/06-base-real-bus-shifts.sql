-- 重庆片区真实公交线路的「运营班次」演示数据。
--
-- 目的：让"附近公交 / 实时公交"展示的是**真实公交线路**（347 路区间、347 路、320 路、349 路…）
-- 上正在运行的车辆，而不是只在演示线路上原地待发；配合 DeterministicScheduleSimulator
-- （按线路站序 + 计划分钟插值，且沿真实道路几何行驶）即可得到位置合理的 SIMULATED 车辆。
--
-- 幂等：INSERT ... ON DUPLICATE KEY UPDATE；同时把真实线路的 route_station.planned_minutes
-- 按线路里程 / 20km/h 均摊到各站（原生成脚本写的是 0，会导致模拟车辆一直停在终点站）。

-- 1) 真实线路站序计划分钟：按里程均摊（0 会让模拟器永远停在终点站）
UPDATE transport_route_station rs
    JOIN transport_route r ON r.id = rs.route_id
    JOIN (SELECT route_id,
                 MIN(sequence_no) AS min_seq,
                 MAX(sequence_no) AS max_seq
          FROM transport_route_station
          WHERE route_id BETWEEN 401 AND 415
          GROUP BY route_id) t ON t.route_id = rs.route_id
SET rs.planned_minutes = ROUND((COALESCE(r.distance_km, 10) / 20.0 * 60.0)
        * (rs.sequence_no - t.min_seq) / GREATEST(1, t.max_seq - t.min_seq))
WHERE rs.route_id BETWEEN 401 AND 415;

-- 2) 真实线路班次：06:30~22:30 每 30 分钟一班，在 4 条经过重邮/南坪的真实线路
--    （347 路区间 / 320 路 / 329 路 / 349 路）间轮转发车；
--    这样任何演示时间点都有 2~3 台车在真实线路上"在途"，而不是全趴在终点站。
--    （DeterministicScheduleSimulator 的轮转规则：班次按发车时间升序、车辆按 id 升序取模）
--    先按编码前缀清掉本脚本自己的历史班次，保证幂等（编码规则变更时不会撞唯一键）
DELETE FROM transport_shift WHERE shift_code LIKE 'SH-R%';

INSERT INTO transport_shift
    (id, shift_code, route_id, planned_departure_time, planned_duration_minutes, status,
     tenant_id, creator, updater, deleted)
VALUES
    (401, 'SH-R401-01', 401, '06:30:00', 41, 0, 0, '1', '1', b'0'),
    (402, 'SH-R403-01', 403, '07:00:00', 62, 0, 0, '1', '1', b'0'),
    (403, 'SH-R404-01', 404, '07:30:00', 35, 0, 0, '1', '1', b'0'),
    (404, 'SH-R408-01', 408, '08:00:00', 52, 0, 0, '1', '1', b'0'),
    (405, 'SH-R401-02', 401, '08:30:00', 41, 0, 0, '1', '1', b'0'),
    (406, 'SH-R403-02', 403, '09:00:00', 62, 0, 0, '1', '1', b'0'),
    (407, 'SH-R404-02', 404, '09:30:00', 35, 0, 0, '1', '1', b'0'),
    (408, 'SH-R408-02', 408, '10:00:00', 52, 0, 0, '1', '1', b'0'),
    (409, 'SH-R401-03', 401, '10:30:00', 41, 0, 0, '1', '1', b'0'),
    (410, 'SH-R403-03', 403, '11:00:00', 62, 0, 0, '1', '1', b'0'),
    (411, 'SH-R404-03', 404, '11:30:00', 35, 0, 0, '1', '1', b'0'),
    (412, 'SH-R408-03', 408, '12:00:00', 52, 0, 0, '1', '1', b'0'),
    (413, 'SH-R401-04', 401, '12:30:00', 41, 0, 0, '1', '1', b'0'),
    (414, 'SH-R403-04', 403, '13:00:00', 62, 0, 0, '1', '1', b'0'),
    (415, 'SH-R404-04', 404, '13:30:00', 35, 0, 0, '1', '1', b'0'),
    (416, 'SH-R408-04', 408, '14:00:00', 52, 0, 0, '1', '1', b'0'),
    (417, 'SH-R401-05', 401, '14:30:00', 41, 0, 0, '1', '1', b'0'),
    (418, 'SH-R403-05', 403, '15:00:00', 62, 0, 0, '1', '1', b'0'),
    (419, 'SH-R404-05', 404, '15:30:00', 35, 0, 0, '1', '1', b'0'),
    (420, 'SH-R408-05', 408, '16:00:00', 52, 0, 0, '1', '1', b'0'),
    (421, 'SH-R401-06', 401, '16:30:00', 41, 0, 0, '1', '1', b'0'),
    (422, 'SH-R403-06', 403, '17:00:00', 62, 0, 0, '1', '1', b'0'),
    (423, 'SH-R404-06', 404, '17:30:00', 35, 0, 0, '1', '1', b'0'),
    (424, 'SH-R408-06', 408, '18:00:00', 52, 0, 0, '1', '1', b'0'),
    (425, 'SH-R401-07', 401, '18:30:00', 41, 0, 0, '1', '1', b'0'),
    (426, 'SH-R403-07', 403, '19:00:00', 62, 0, 0, '1', '1', b'0'),
    (427, 'SH-R404-07', 404, '19:30:00', 35, 0, 0, '1', '1', b'0'),
    (428, 'SH-R408-07', 408, '20:00:00', 52, 0, 0, '1', '1', b'0'),
    (429, 'SH-R401-08', 401, '20:30:00', 41, 0, 0, '1', '1', b'0'),
    (430, 'SH-R403-08', 403, '21:00:00', 62, 0, 0, '1', '1', b'0'),
    (431, 'SH-R404-08', 404, '21:30:00', 35, 0, 0, '1', '1', b'0'),
    (432, 'SH-R408-08', 408, '22:00:00', 52, 0, 0, '1', '1', b'0'),
    (433, 'SH-R401-09', 401, '22:30:00', 41, 0, 0, '1', '1', b'0')
ON DUPLICATE KEY UPDATE
    shift_code = VALUES(shift_code),
    route_id = VALUES(route_id),
    planned_departure_time = VALUES(planned_departure_time),
    planned_duration_minutes = VALUES(planned_duration_minutes),
    status = VALUES(status);

-- 2.1) 班次时长 = 线路总计划分钟的 2 倍：班次窗口按**一个往返**计
--      （司机本职是按线路跑，去程到终点后返程逆向再跑一遍，途中同样取派货）。
--      放在 INSERT 之后，保证每次执行都以线路真实总分钟为准。
UPDATE transport_shift sh
    JOIN (SELECT route_id, MAX(planned_minutes) AS total_minutes
          FROM transport_route_station
          WHERE route_id BETWEEN 401 AND 415
          GROUP BY route_id) t ON t.route_id = sh.route_id
SET sh.planned_duration_minutes = GREATEST(30, t.total_minutes * 2)
WHERE sh.route_id BETWEEN 401 AND 415;

-- 3) 校验输出：真实线路班次数量 + 站序计划分钟是否已填充
SELECT '真实线路班次' AS info, COUNT(*) AS shift_count
FROM transport_shift WHERE route_id BETWEEN 401 AND 415 AND status = 0;
SELECT '站序计划分钟已填充' AS info, COUNT(*) AS station_rows
FROM transport_route_station WHERE route_id BETWEEN 401 AND 415 AND planned_minutes > 0;
