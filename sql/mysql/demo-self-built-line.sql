-- ??????????????????????
-- ???????6~12km / 12~22 ????????????PROJECT ?????/???????????????
-- ????????????????????????????????????????

DELETE FROM transport_route_station WHERE route_id IN (103, 201);
DELETE FROM transport_shift WHERE shift_code LIKE 'SH-SELF-%';

INSERT INTO transport_station
    (id, station_code, station_name, station_level, longitude, latitude, address, status, source_type, station_type, user_access, vehicle_access, dispatch_enabled, tenant_id, creator, updater, deleted)
VALUES
    (2011, 'SELF2011', '????????', 2, 106.6041, 29.5327, '????????', 0, 'PROJECT', 'CARGO_STATION', b'1', b'1', b'1', 0, '1', '1', b'0'),
    (2012, 'SELF2012', '??????', 2, 106.60994, 29.534785, '??????', 0, 'PROJECT', 'CARGO_STATION', b'1', b'1', b'1', 0, '1', '1', b'0'),
    (2013, 'SELF2013', '?????', 2, 106.604546, 29.53261, '?????', 0, 'PROJECT', 'CARGO_STATION', b'1', b'1', b'1', 0, '1', '1', b'0'),
    (2014, 'SELF2014', '??????', 2, 106.606415, 29.535632, '??????', 0, 'PROJECT', 'CARGO_STATION', b'1', b'1', b'1', 0, '1', '1', b'0'),
    (2015, 'SELF2015', '?????', 2, 106.614823, 29.541359, '?????', 0, 'PROJECT', 'CARGO_STATION', b'1', b'1', b'1', 0, '1', '1', b'0'),
    (2016, 'SELF2016', '??????', 2, 106.611858, 29.542706, '??????', 0, 'PROJECT', 'CARGO_STATION', b'1', b'1', b'1', 0, '1', '1', b'0'),
    (2017, 'SELF2017', '?????', 2, 106.609786, 29.539894, '?????', 0, 'PROJECT', 'CARGO_STATION', b'1', b'1', b'1', 0, '1', '1', b'0'),
    (2018, 'SELF2018', '?????', 2, 106.618629, 29.539946, '?????', 0, 'PROJECT', 'CARGO_STATION', b'1', b'1', b'1', 0, '1', '1', b'0'),
    (2019, 'SELF2019', '?????', 2, 106.619523, 29.54513, '?????', 0, 'PROJECT', 'CARGO_STATION', b'1', b'1', b'1', 0, '1', '1', b'0'),
    (2020, 'SELF2020', '???????', 2, 106.614423, 29.549359, '???????', 0, 'PROJECT', 'CARGO_STATION', b'1', b'1', b'1', 0, '1', '1', b'0'),
    (2021, 'SELF2021', '??????', 2, 106.620869, 29.548178, '??????', 0, 'PROJECT', 'CARGO_STATION', b'1', b'1', b'1', 0, '1', '1', b'0'),
    (2022, 'SELF2022', '??????', 2, 106.625535, 29.546242, '??????', 0, 'PROJECT', 'CARGO_STATION', b'1', b'1', b'1', 0, '1', '1', b'0'),
    (2023, 'SELF2023', '??????', 2, 106.619669, 29.549563, '??????', 0, 'PROJECT', 'CARGO_STATION', b'1', b'1', b'1', 0, '1', '1', b'0'),
    (2024, 'SELF2024', '?????', 2, 106.622714, 29.555186, '?????', 0, 'PROJECT', 'CARGO_STATION', b'1', b'1', b'1', 0, '1', '1', b'0'),
    (2025, 'SELF2025', '??????', 2, 106.63076, 29.5564, '??????', 0, 'PROJECT', 'CARGO_STATION', b'1', b'1', b'1', 0, '1', '1', b'0'),
    (2026, 'SELF2026', '??????', 2, 106.628, 29.5554, '??????', 0, 'PROJECT', 'CARGO_STATION', b'1', b'1', b'1', 0, '1', '1', b'0'),
    (2041, 'SELF2041', '??????', 2, 106.628, 29.5554, '??????', 0, 'PROJECT', 'CARGO_STATION', b'1', b'1', b'1', 0, '1', '1', b'0'),
    (2042, 'SELF2042', '???????', 2, 106.626584, 29.552481, '???????', 0, 'PROJECT', 'CARGO_STATION', b'1', b'1', b'1', 0, '1', '1', b'0'),
    (2043, 'SELF2043', '?????', 2, 106.617009, 29.546468, '?????', 0, 'PROJECT', 'CARGO_STATION', b'1', b'1', b'1', 0, '1', '1', b'0'),
    (2044, 'SELF2044', '??????', 2, 106.612459, 29.544511, '??????', 0, 'PROJECT', 'CARGO_STATION', b'1', b'1', b'1', 0, '1', '1', b'0'),
    (2045, 'SELF2045', '??????', 2, 106.612584, 29.544598, '??????', 0, 'PROJECT', 'CARGO_STATION', b'1', b'1', b'1', 0, '1', '1', b'0'),
    (2046, 'SELF2046', '????????', 2, 106.604396, 29.541543, '????????', 0, 'PROJECT', 'CARGO_STATION', b'1', b'1', b'1', 0, '1', '1', b'0'),
    (2047, 'SELF2047', '??????', 2, 106.597222, 29.535068, '??????', 0, 'PROJECT', 'CARGO_STATION', b'1', b'1', b'1', 0, '1', '1', b'0'),
    (2048, 'SELF2048', '??????', 2, 106.597974, 29.530672, '??????', 0, 'PROJECT', 'CARGO_STATION', b'1', b'1', b'1', 0, '1', '1', b'0'),
    (2049, 'SELF2049', '??????', 2, 106.592948, 29.530008, '??????', 0, 'PROJECT', 'CARGO_STATION', b'1', b'1', b'1', 0, '1', '1', b'0'),
    (2050, 'SELF2050', '?????', 2, 106.583704, 29.528557, '?????', 0, 'PROJECT', 'CARGO_STATION', b'1', b'1', b'1', 0, '1', '1', b'0'),
    (2051, 'SELF2051', '??????', 2, 106.582943, 29.523103, '??????', 0, 'PROJECT', 'CARGO_STATION', b'1', b'1', b'1', 0, '1', '1', b'0'),
    (2052, 'SELF2052', '?????????', 2, 106.578, 29.5194, '?????????', 0, 'PROJECT', 'CARGO_STATION', b'1', b'1', b'1', 0, '1', '1', b'0')
ON DUPLICATE KEY UPDATE station_name=VALUES(station_name), longitude=VALUES(longitude), latitude=VALUES(latitude), source_type='PROJECT', station_type='CARGO_STATION', user_access=b'1', vehicle_access=b'1', dispatch_enabled=b'1', status=0, deleted=b'0';

INSERT INTO transport_route
    (id, route_code, route_name, start_station_id, end_station_id, distance_km, source_type, dispatch_enabled, status, tenant_id, creator, updater, deleted)
VALUES
    (103, 'R103', '?????????????????', 2011, 2026, 9.0, 'PROJECT', b'1', 0, 0, '1', '1', b'0'),
    (201, 'R201', '?????????????', 2041, 2052, 7.0, 'PROJECT', b'1', 0, 0, '1', '1', b'0')
ON DUPLICATE KEY UPDATE route_name=VALUES(route_name), start_station_id=VALUES(start_station_id), end_station_id=VALUES(end_station_id), distance_km=VALUES(distance_km), source_type='PROJECT', dispatch_enabled=b'1', status=0, deleted=b'0';

INSERT INTO transport_route_station
    (route_id, station_id, sequence_no, planned_minutes, tenant_id, creator, updater, deleted)
VALUES
    (103, 2011, 1, 0, 0, '1', '1', b'0'),
    (103, 2012, 2, 2, 0, '1', '1', b'0'),
    (103, 2013, 3, 4, 0, '1', '1', b'0'),
    (103, 2014, 4, 5, 0, '1', '1', b'0'),
    (103, 2015, 5, 8, 0, '1', '1', b'0'),
    (103, 2016, 6, 9, 0, '1', '1', b'0'),
    (103, 2017, 7, 10, 0, '1', '1', b'0'),
    (103, 2018, 8, 12, 0, '1', '1', b'0'),
    (103, 2019, 9, 14, 0, '1', '1', b'0'),
    (103, 2020, 10, 16, 0, '1', '1', b'0'),
    (103, 2021, 11, 18, 0, '1', '1', b'0'),
    (103, 2022, 12, 20, 0, '1', '1', b'0'),
    (103, 2023, 13, 22, 0, '1', '1', b'0'),
    (103, 2024, 14, 24, 0, '1', '1', b'0'),
    (103, 2025, 15, 26, 0, '1', '1', b'0'),
    (103, 2026, 16, 27, 0, '1', '1', b'0'),
    (201, 2041, 1, 0, 0, '1', '1', b'0'),
    (201, 2042, 2, 1, 0, '1', '1', b'0'),
    (201, 2043, 3, 4, 0, '1', '1', b'0'),
    (201, 2044, 4, 6, 0, '1', '1', b'0'),
    (201, 2045, 5, 6, 0, '1', '1', b'0'),
    (201, 2046, 6, 9, 0, '1', '1', b'0'),
    (201, 2047, 7, 12, 0, '1', '1', b'0'),
    (201, 2048, 8, 13, 0, '1', '1', b'0'),
    (201, 2049, 9, 15, 0, '1', '1', b'0'),
    (201, 2050, 10, 17, 0, '1', '1', b'0'),
    (201, 2051, 11, 19, 0, '1', '1', b'0'),
    (201, 2052, 12, 21, 0, '1', '1', b'0')
ON DUPLICATE KEY UPDATE station_id=VALUES(station_id), planned_minutes=VALUES(planned_minutes), deleted=b'0';

INSERT INTO transport_shift
    (id, shift_code, route_id, planned_departure_time, planned_duration_minutes, status, tenant_id, creator, updater, deleted)
VALUES
    (930, 'SH-SELF-103-0650', 103, '06:50:00', 27, 0, 0, '1', '1', b'0'),
    (931, 'SH-SELF-103-1020', 103, '10:20:00', 27, 0, 0, '1', '1', b'0'),
    (932, 'SH-SELF-103-1540', 103, '15:40:00', 27, 0, 0, '1', '1', b'0'),
    (933, 'SH-SELF-103-1950', 103, '19:50:00', 27, 0, 0, '1', '1', b'0'),
    (934, 'SH-SELF-201-0650', 201, '06:50:00', 21, 0, 0, '1', '1', b'0'),
    (935, 'SH-SELF-201-1020', 201, '10:20:00', 21, 0, 0, '1', '1', b'0'),
    (936, 'SH-SELF-201-1540', 201, '15:40:00', 21, 0, 0, '1', '1', b'0'),
    (937, 'SH-SELF-201-1950', 201, '19:50:00', 21, 0, 0, '1', '1', b'0')
ON DUPLICATE KEY UPDATE route_id=VALUES(route_id), planned_departure_time=VALUES(planned_departure_time), planned_duration_minutes=VALUES(planned_duration_minutes), status=0, deleted=b'0';

UPDATE transport_driver_vehicle SET route_id=103 WHERE vehicle_id=101;
UPDATE transport_driver_vehicle SET route_id=201 WHERE vehicle_id=102;

SELECT r.id,r.route_name,r.distance_km,COUNT(rs.id) stops,SUM(s.source_type='PROJECT') self_stations FROM transport_route r JOIN transport_route_station rs ON rs.route_id=r.id JOIN transport_station s ON s.id=rs.station_id WHERE r.id IN (103,201) GROUP BY r.id,r.route_name,r.distance_km ORDER BY r.id;
