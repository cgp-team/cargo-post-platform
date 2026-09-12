-- 车辆体系区分：真实公交线路车辆（渝A 车牌） / 自建线路车辆（CQUPT+编号）
--
-- 口径（业务约定）：
--   · 真实线路车辆：车牌以「渝A」开头，只跑真实公交线路（如 347路区间/320路/329路/318路…）
--   · 自建线路车辆：车牌为「CQUPT+编号」（CQUPT01/CQUPT02/CQUPT03），只跑自建客货邮线路（R103/R201）
-- 运营范围（transport_driver_vehicle.route_id）与车牌体系保持一致，调度按"同体系分工"分配：
-- 自建订单由自建车辆承运，真实线路订单由渝A车辆承运，联运换乘在共站枢纽交接。
--
-- 幂等：可重复执行。

-- 1) 自建车辆车牌：去掉「渝A·」前缀，改为 CQUPT+编号
UPDATE transport_vehicle SET plate_no = 'CQUPT01' WHERE id = 101;
UPDATE transport_vehicle SET plate_no = 'CQUPT02' WHERE id = 102;
UPDATE transport_vehicle SET plate_no = 'CQUPT03' WHERE id = 103;

-- 2) 运营范围（运营线路）与体系对齐：真实车辆跑真实线路，自建车辆跑自建线路
UPDATE transport_driver_vehicle SET route_id = 401 WHERE vehicle_id = 1;   -- 渝A·B5201 → 347路区间（真实）
UPDATE transport_driver_vehicle SET route_id = 403 WHERE vehicle_id = 2;   -- 渝A·B5202 → 320路（真实）
UPDATE transport_driver_vehicle SET route_id = 404 WHERE vehicle_id = 3;   -- 渝A·B5203 → 329路（真实）
UPDATE transport_driver_vehicle SET route_id = 404 WHERE vehicle_id = 5;   -- 渝A·B5205 → 329路（真实）
UPDATE transport_driver_vehicle SET route_id = 103 WHERE vehicle_id = 101; -- CQUPT01 → R103（自建）
UPDATE transport_driver_vehicle SET route_id = 201 WHERE vehicle_id = 102; -- CQUPT02 → R201（自建）
UPDATE transport_driver_vehicle SET route_id = 103 WHERE vehicle_id = 103; -- CQUPT03 → R103（自建）

-- 3) 自建车辆/司机档案命名与体系一致（司机端登录手机号不变）
UPDATE transport_driver SET name = '自建线司机A' WHERE id = 101;
UPDATE transport_driver SET name = '自建线司机B' WHERE id = 102;
UPDATE transport_driver SET name = '自建线司机C' WHERE id = 103;

-- 4) 只读校验：两套体系的车牌与运营线路
SELECT v.id, v.plate_no, dv.route_id, r.route_name, r.source_type
FROM transport_vehicle v
    LEFT JOIN transport_driver_vehicle dv ON dv.vehicle_id = v.id AND dv.deleted = b'0'
    LEFT JOIN transport_route r ON r.id = dv.route_id
WHERE v.deleted = b'0'
ORDER BY v.id;
