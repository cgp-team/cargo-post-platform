-- ============================================
-- 客货邮模块 - 演示数据
-- 在云服务器 ruoyi-vue-pro 数据库执行：
--   mysql -u root -p ruoyi-vue-pro < transport-demo-data.sql
-- 数据使用 IGNORE 避免重复插入时报错
-- ============================================

-- ---------- 车辆数据 ----------
INSERT IGNORE INTO transport_vehicle (id, plate_no, vehicle_type, passenger_capacity, cargo_capacity_kg, status, tenant_id, creator, create_time, updater, update_time, deleted) VALUES
(1, '川A·B5201', 1, 30, 500.00, 0, 0, '1', NOW(), '1', NOW(), b'0'),
(2, '川A·B5202', 1, 25, 400.00, 0, 0, '1', NOW(), '1', NOW(), b'0'),
(3, '川A·B5203', 2, 15, 800.00, 0, 0, '1', NOW(), '1', NOW(), b'0'),
(4, '川A·B5204', 1, 35, 600.00, 1, 0, '1', NOW(), '1', NOW(), b'0'),
(5, '川A·B5205', 3, 20, 1200.00, 0, 0, '1', NOW(), '1', NOW(), b'0');

-- ---------- 司机数据 ----------
INSERT IGNORE INTO transport_driver (id, name, mobile, license_no, license_expire_date, status, tenant_id, creator, create_time, updater, update_time, deleted) VALUES
(1, '张建国', '13800138001', '510100198001150011', '2027-06-15', 0, 0, '1', NOW(), '1', NOW(), b'0'),
(2, '李伟民', '13800138002', '510100198203220012', '2028-03-20', 0, 0, '1', NOW(), '1', NOW(), b'0'),
(3, '王守义', '13800138003', '510100197911080013', '2026-12-01', 0, 0, '1', NOW(), '1', NOW(), b'0'),
(4, '赵德柱', '13800138004', '510100198508140014', '2029-09-10', 0, 0, '1', NOW(), '1', NOW(), b'0'),
(5, '陈永发', '13800138005', '510100199012250015', '2030-01-05', 1, 0, '1', NOW(), '1', NOW(), b'0');

-- ---------- 司机车辆绑定 ----------
INSERT IGNORE INTO transport_driver_vehicle (id, driver_id, vehicle_id, bind_time, unbind_time, status, tenant_id, creator, create_time, updater, update_time, deleted) VALUES
(1, 1, 1, '2026-01-01 08:00:00', NULL, 1, 0, '1', NOW(), '1', NOW(), b'0'),
(2, 2, 2, '2026-02-01 08:00:00', NULL, 1, 0, '1', NOW(), '1', NOW(), b'0'),
(3, 3, 3, '2026-03-01 08:00:00', NULL, 1, 0, '1', NOW(), '1', NOW(), b'0'),
(4, 4, 5, '2026-04-01 08:00:00', NULL, 1, 0, '1', NOW(), '1', NOW(), b'0');

-- ---------- 站点数据 ----------
INSERT IGNORE INTO transport_station (id, station_code, station_name, station_level, longitude, latitude, address, status, tenant_id, creator, create_time, updater, update_time, deleted) VALUES
(1, 'ST001', '县城客运中心', 1, 104.0657, 30.5723, '成都市中心客运站', 0, 0, '1', NOW(), '1', NOW(), b'0'),
(2, 'ST002', '红花村站', 2, 104.1234, 30.6012, '红花村村委会旁', 0, 0, '1', NOW(), '1', NOW(), b'0'),
(3, 'ST003', '绿水村站', 2, 104.0890, 30.5890, '绿水村小学门口', 0, 0, '1', NOW(), '1', NOW(), b'0'),
(4, 'ST004', '青山镇站', 1, 104.2345, 30.6234, '青山镇客运站', 0, 0, '1', NOW(), '1', NOW(), b'0'),
(5, 'ST005', '大湾村站', 3, 104.2567, 30.6456, '大湾村便民服务中心', 0, 0, '1', NOW(), '1', NOW(), b'0'),
(6, 'ST006', '石桥村站', 3, 104.2789, 30.6678, '石桥村口公交站', 0, 0, '1', NOW(), '1', NOW(), b'0'),
(7, 'ST007', '龙泉镇站', 1, 104.3123, 30.6890, '龙泉镇综合运输服务站', 0, 0, '1', NOW(), '1', NOW(), b'0'),
(8, 'ST008', '桃花村站', 2, 104.3456, 30.7012, '桃花村党群服务中心', 0, 0, '1', NOW(), '1', NOW(), b'0');

-- ---------- 线路数据 ----------
INSERT IGNORE INTO transport_route (id, route_code, route_name, start_station_id, end_station_id, distance_km, status, tenant_id, creator, create_time, updater, update_time, deleted) VALUES
(1, 'R001', '县城—青山镇线', 1, 4, 25.50, 0, 0, '1', NOW(), '1', NOW(), b'0'),
(2, 'R002', '青山镇—龙泉镇线', 4, 7, 18.30, 0, 0, '1', NOW(), '1', NOW(), b'0'),
(3, 'R003', '县城—龙泉镇快线', 1, 7, 42.00, 0, 0, '1', NOW(), '1', NOW(), b'0');

-- ---------- 线路站点 ----------
INSERT IGNORE INTO transport_route_station (id, route_id, station_id, sequence_no, planned_minutes, tenant_id, creator, create_time, updater, update_time, deleted) VALUES
(1, 1, 1, 1, 0, 0, '1', NOW(), '1', NOW(), b'0'),
(2, 1, 2, 2, 15, 0, '1', NOW(), '1', NOW(), b'0'),
(3, 1, 3, 3, 30, 0, '1', NOW(), '1', NOW(), b'0'),
(4, 1, 4, 4, 50, 0, '1', NOW(), '1', NOW(), b'0'),
(5, 2, 4, 1, 0, 0, '1', NOW(), '1', NOW(), b'0'),
(6, 2, 5, 2, 10, 0, '1', NOW(), '1', NOW(), b'0'),
(7, 2, 6, 3, 25, 0, '1', NOW(), '1', NOW(), b'0'),
(8, 2, 7, 4, 40, 0, '1', NOW(), '1', NOW(), b'0'),
(9, 3, 1, 1, 0, 0, '1', NOW(), '1', NOW(), b'0'),
(10, 3, 4, 2, 25, 0, '1', NOW(), '1', NOW(), b'0'),
(11, 3, 7, 3, 45, 0, '1', NOW(), '1', NOW(), b'0');

-- ---------- 班次数据 ----------
INSERT IGNORE INTO transport_shift (id, shift_code, route_id, planned_departure_time, planned_duration_minutes, status, tenant_id, creator, create_time, updater, update_time, deleted) VALUES
(1, 'SH001', 1, '06:30:00', 50, 0, 0, '1', NOW(), '1', NOW(), b'0'),
(2, 'SH002', 1, '08:30:00', 50, 0, 0, '1', NOW(), '1', NOW(), b'0'),
(3, 'SH003', 1, '14:00:00', 50, 0, 0, '1', NOW(), '1', NOW(), b'0'),
(4, 'SH004', 2, '07:00:00', 40, 0, 0, '1', NOW(), '1', NOW(), b'0'),
(5, 'SH005', 2, '15:30:00', 40, 0, 0, '1', NOW(), '1', NOW(), b'0'),
(6, 'SH006', 3, '09:00:00', 50, 0, 0, '1', NOW(), '1', NOW(), b'0'),
-- 补充下午至晚间班次，覆盖全天各时段（车辆监控演示用）
(7, 'SH007', 1, '11:00:00', 50, 0, 0, '1', NOW(), '1', NOW(), b'0'),
(8, 'SH008', 2, '16:30:00', 40, 0, 0, '1', NOW(), '1', NOW(), b'0'),
(9, 'SH009', 1, '18:30:00', 50, 0, 0, '1', NOW(), '1', NOW(), b'0'),
(10, 'SH010', 2, '20:30:00', 40, 0, 0, '1', NOW(), '1', NOW(), b'0');

-- ---------- 订单数据 ----------
-- create_time 分散到近 7 日，供数据大盘趋势图演示
INSERT IGNORE INTO transport_order (id, order_no, order_type, pickup_station_id, delivery_station_id, earliest_pickup_time, latest_delivery_time, status, total_amount, tenant_id, creator, create_time, updater, update_time, deleted) VALUES
-- 客运订单
(1, 'TP20260701001', 1, 1, 4, '2026-07-01 08:00:00', '2026-07-01 10:00:00', 1, 15.00, 0, '1', DATE_SUB(NOW(), INTERVAL 6 DAY), '1', NOW(), b'0'),
(2, 'TP20260702001', 1, 4, 7, '2026-07-02 07:00:00', '2026-07-02 09:00:00', 1, 12.00, 0, '1', DATE_SUB(NOW(), INTERVAL 5 DAY), '1', NOW(), b'0'),
(3, 'TP20260703001', 1, 1, 7, '2026-07-03 09:00:00', '2026-07-03 11:00:00', 0, 20.00, 0, '1', DATE_SUB(NOW(), INTERVAL 3 DAY), '1', NOW(), b'0'),
-- 货运订单
(4, 'TP20260701002', 2, 2, 1, '2026-07-01 06:00:00', '2026-07-01 18:00:00', 2, 35.00, 0, '1', DATE_SUB(NOW(), INTERVAL 6 DAY), '1', NOW(), b'0'),
(5, 'TP20260702002', 2, 3, 4, '2026-07-02 07:00:00', '2026-07-02 14:00:00', 2, 28.00, 0, '1', DATE_SUB(NOW(), INTERVAL 4 DAY), '1', NOW(), b'0'),
(6, 'TP20260704001', 2, 5, 7, '2026-07-04 08:00:00', '2026-07-04 16:00:00', 0, 42.00, 0, '1', DATE_SUB(NOW(), INTERVAL 2 DAY), '1', NOW(), b'0'),
(7, 'TP20260705001', 2, 8, 1, '2026-07-05 07:00:00', '2026-07-05 18:00:00', 0, 55.00, 0, '1', DATE_SUB(NOW(), INTERVAL 1 DAY), '1', NOW(), b'0'),
-- 邮快件订单
(8, 'TP20260701003', 3, 1, 2, '2026-07-01 09:00:00', '2026-07-01 17:00:00', 2, 8.00, 0, '1', DATE_SUB(NOW(), INTERVAL 5 DAY), '1', NOW(), b'0'),
(9, 'TP20260703002', 3, 7, 8, '2026-07-03 10:00:00', '2026-07-03 16:00:00', 1, 6.00, 0, '1', DATE_SUB(NOW(), INTERVAL 2 DAY), '1', NOW(), b'0'),
(10, 'TP20260706001', 3, 4, 6, '2026-07-06 08:00:00', '2026-07-06 15:00:00', 0, 10.00, 0, '1', NOW(), '1', NOW(), b'0');

-- ---------- 客运订单明细 ----------
INSERT IGNORE INTO transport_passenger_order (id, order_id, passenger_count, contact_name, contact_mobile, tenant_id, creator, create_time, updater, update_time, deleted) VALUES
(1, 1, 2, '张三', '13900139001', 0, '1', NOW(), '1', NOW(), b'0'),
(2, 2, 1, '李四', '13900139002', 0, '1', NOW(), '1', NOW(), b'0'),
(3, 3, 3, '王五', '13900139003', 0, '1', NOW(), '1', NOW(), b'0');

-- ---------- 货运订单明细 ----------
INSERT IGNORE INTO transport_cargo_order (id, order_id, cargo_category, fresh_flag, item_count, weight_kg, volume_m3, tenant_id, creator, create_time, updater, update_time, deleted) VALUES
(1, 4, '蔬菜水果', b'1', 5, 50.00, 0.2000, 0, '1', NOW(), '1', NOW(), b'0'),
(2, 5, '日用品', b'0', 10, 80.00, 0.5000, 0, '1', NOW(), '1', NOW(), b'0'),
(3, 6, '禽蛋肉类', b'1', 8, 60.00, 0.3000, 0, '1', NOW(), '1', NOW(), b'0'),
(4, 7, '土特产', b'0', 15, 120.00, 0.8000, 0, '1', NOW(), '1', NOW(), b'0');

-- ---------- 邮快件订单明细 ----------
INSERT IGNORE INTO transport_postal_order (id, order_id, mail_no, carrier_code, item_count, weight_kg, tenant_id, creator, create_time, updater, update_time, deleted) VALUES
(1, 8, 'SF1234567890', 'SF', 2, 3.50, 0, '1', NOW(), '1', NOW(), b'0'),
(2, 9, 'YT0987654321', 'YTO', 1, 1.20, 0, '1', NOW(), '1', NOW(), b'0'),
(3, 10, 'ZT5678901234', 'ZTO', 3, 5.00, 0, '1', NOW(), '1', NOW(), b'0');

-- ---------- 农产品商品 ----------
INSERT IGNORE INTO transport_product (id, name, from_village, price, unit, image, badge, description, stock, status, sort, tenant_id, creator, create_time, updater, update_time, deleted) VALUES
(1, '高山脆李', '云山村', 68.00, '斤', '🍑', '大巴直通车', '高山生态种植，皮薄肉厚，清甜多汁', 200, 0, 1, 0, '1', NOW(), '1', NOW(), b'0'),
(2, '土鸡蛋30枚装', '大湾村', 45.00, '箱', '🥚', '大巴直通车', '农家散养土鸡蛋，30枚装', 150, 0, 2, 0, '1', NOW(), '1', NOW(), b'0'),
(3, '有机红薯粉', '竹林乡', 28.00, '袋', '🍜', '', '传统手工制作，爽滑劲道', 300, 0, 3, 0, '1', NOW(), '1', NOW(), b'0'),
(4, '野生山核桃', '青山镇', 55.00, '斤', '🥜', '大巴直通车', '深山野生，自然晾晒', 120, 0, 4, 0, '1', NOW(), '1', NOW(), b'0'),
(5, '明前龙井茶', '云山村', 128.00, '盒', '🍵', '', '清明前采摘，鲜嫩甘醇', 80, 0, 5, 0, '1', NOW(), '1', NOW(), b'0'),
(6, '农家腊肉', '溪口村', 88.00, '斤', '🥩', '大巴直通车', '柴火熏制，肥而不腻', 60, 0, 6, 0, '1', NOW(), '1', NOW(), b'0');
