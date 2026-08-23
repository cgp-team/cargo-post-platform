-- V011：车辆表新增保险到期日字段 insurance_expire_date（证照/保险到期预警）。
-- 新库直接 SOURCE 全量 schema 即可；若库中已有 transport_vehicle 表（CREATE IF NOT EXISTS 不会加列），
-- 请人工执行文件末尾注释中的 ALTER。
SOURCE sql/mysql/transport-schema.sql;

-- ALTER TABLE `transport_vehicle`
--   ADD COLUMN `insurance_expire_date` date DEFAULT NULL COMMENT '保险到期日' AFTER `cargo_capacity`;
