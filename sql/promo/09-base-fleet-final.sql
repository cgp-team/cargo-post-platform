-- ============================================
-- 宣传片演示数据 · 09 车队终态（车牌/司机姓名/运营线路绑定的唯一权威口径）
--
-- 为什么单独一个文件：02~08 的历史脚本在不同演示期改过绑定（ CQUPT 车跑过沙坪坝走廊、
-- 渝A·B5205 跑过 329 路等），中间态互相覆盖。本文件最后执行，一锤定音：
--   · 渝A 车跑真实公交线路，CQUPT 车跑自建客货邮线路；
--   · 司机全部用真实感姓名（宣传片镜头里不出现 "346路司机A" 这种占位名）；
--   · 每台在营车辆恰好一条运营线路、一位绑定司机。
-- 幂等：纯 UPDATE，可重复执行。
-- ============================================

-- ---------- 车牌终态 ----------
UPDATE transport_vehicle SET plate_no = 'CQUPT01' WHERE id = 101;
UPDATE transport_vehicle SET plate_no = 'CQUPT02' WHERE id = 102;
UPDATE transport_vehicle SET plate_no = 'CQUPT03' WHERE id = 103;

-- ---------- 司机姓名终态（覆盖历史占位名） ----------
UPDATE transport_driver SET name = '刘明轩' WHERE id = 6;
UPDATE transport_driver SET name = '陈岗'   WHERE id = 7;
UPDATE transport_driver SET name = '张世平' WHERE id = 8;
UPDATE transport_driver SET name = '吴海涛' WHERE id = 9;
UPDATE transport_driver SET name = '何伟'   WHERE id = 101;
UPDATE transport_driver SET name = '唐军'   WHERE id = 102;
UPDATE transport_driver SET name = '冯亮'   WHERE id = 103;

-- 司机端登录账号昵称同步（member_user 由 07/10 建好，这里只改展示名）
UPDATE member_user SET nickname = '刘明轩', name = '刘明轩' WHERE mobile = '13800138006';
UPDATE member_user SET nickname = '陈岗',   name = '陈岗'   WHERE mobile = '13800138007';
UPDATE member_user SET nickname = '张世平', name = '张世平' WHERE mobile = '13800138008';
UPDATE member_user SET nickname = '吴海涛', name = '吴海涛' WHERE mobile = '13800138009';
UPDATE member_user SET nickname = '何伟',   name = '何伟'   WHERE mobile = '13900001001';
UPDATE member_user SET nickname = '唐军',   name = '唐军'   WHERE mobile = '13900001002';
UPDATE member_user SET nickname = '冯亮',   name = '冯亮'   WHERE mobile = '13900001003';

-- ---------- 车辆保险 / 司机证照到期日期（大屏「证照到期提醒」有数据可看） ----------
UPDATE transport_vehicle SET insurance_expire_date = '2027-03-12' WHERE id = 1;
UPDATE transport_vehicle SET insurance_expire_date = '2026-11-02' WHERE id = 2;  -- 临近到期
UPDATE transport_vehicle SET insurance_expire_date = '2027-08-20' WHERE id = 3;
UPDATE transport_vehicle SET insurance_expire_date = '2027-01-15' WHERE id = 4;
UPDATE transport_vehicle SET insurance_expire_date = '2026-10-28' WHERE id = 5;  -- 即将到期
UPDATE transport_vehicle SET insurance_expire_date = '2027-05-09' WHERE id = 6;
UPDATE transport_vehicle SET insurance_expire_date = '2027-02-14' WHERE id = 7;
UPDATE transport_vehicle SET insurance_expire_date = '2027-06-30' WHERE id = 8;
UPDATE transport_vehicle SET insurance_expire_date = '2027-04-18' WHERE id = 101;
UPDATE transport_vehicle SET insurance_expire_date = '2026-11-20' WHERE id = 102; -- 临近到期
UPDATE transport_vehicle SET insurance_expire_date = '2027-09-01' WHERE id = 103;

-- ---------- 运营线路终态（人车绑定 route_id = 运营范围） ----------
UPDATE transport_driver_vehicle SET route_id = 401, status = 1, deleted = b'0' WHERE vehicle_id = 1;   -- 渝A·B5201 → 347路区间
UPDATE transport_driver_vehicle SET route_id = 403, status = 1, deleted = b'0' WHERE vehicle_id = 2;   -- 渝A·B5202 → 320路
UPDATE transport_driver_vehicle SET route_id = 404, status = 1, deleted = b'0' WHERE vehicle_id = 3;   -- 渝A·B5203 → 329路
UPDATE transport_driver_vehicle SET route_id = 405, status = 1, deleted = b'0' WHERE vehicle_id = 4;   -- 渝A·B5204 → 346路
UPDATE transport_driver_vehicle SET route_id = 402, status = 1, deleted = b'0' WHERE vehicle_id = 5;   -- 渝A·B5205 → 347路
UPDATE transport_driver_vehicle SET route_id = 415, status = 1, deleted = b'0' WHERE vehicle_id = 6;   -- 渝A·B5206 → 220路区间
UPDATE transport_driver_vehicle SET route_id = 414, status = 1, deleted = b'0' WHERE vehicle_id = 7;   -- 渝A·B5207 → 318路短线
UPDATE transport_driver_vehicle SET route_id = 407, status = 1, deleted = b'0' WHERE vehicle_id = 8;   -- 渝A·B5208 → 303路
UPDATE transport_driver_vehicle SET route_id = 103, status = 1, deleted = b'0' WHERE vehicle_id = 101; -- CQUPT01 → R103
UPDATE transport_driver_vehicle SET route_id = 104, status = 1, deleted = b'0' WHERE vehicle_id = 102; -- CQUPT02 → R104
UPDATE transport_driver_vehicle SET route_id = 103, status = 1, deleted = b'0' WHERE vehicle_id = 103; -- CQUPT03 → R103

-- ---------- 只读校验 ----------
SELECT v.id, v.plate_no, d.name AS driver, d.mobile, dv.route_id, r.route_name, v.insurance_expire_date
FROM transport_driver_vehicle dv
JOIN transport_vehicle v ON v.id = dv.vehicle_id
JOIN transport_driver d ON d.id = dv.driver_id
LEFT JOIN transport_route r ON r.id = dv.route_id
WHERE dv.status = 1 AND dv.deleted = b'0'
ORDER BY v.id;
