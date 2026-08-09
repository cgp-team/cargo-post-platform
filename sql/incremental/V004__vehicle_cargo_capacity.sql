-- V004：车辆表新增货仓件数字段 cargo_capacity（算法容量约束按件数）。
-- 新库直接 SOURCE 全量 schema 即可；若库中已有 transport_vehicle 表（CREATE IF NOT EXISTS 不会加列），
-- 请人工执行文件末尾注释中的 ALTER。
SOURCE sql/mysql/transport-schema.sql;

-- ALTER TABLE `transport_vehicle`
--   ADD COLUMN `cargo_capacity` int NOT NULL DEFAULT 4 COMMENT '货仓件数上限（算法容量约束按件数）' AFTER `cargo_capacity_kg`;
