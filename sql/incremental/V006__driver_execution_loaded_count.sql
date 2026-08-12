-- V006：班次执行表新增已装车件数 loaded_count（司机端装车/妥投运力落库）。
-- 非破坏性 DDL 唯一维护在 sql/mysql/transport-schema.sql，本文件只做人工执行入口。
-- 已有库执行本文件后，若 transport_shift_execution 表已存在（老库），请人工执行文件内注释的 ALTER：
-- ALTER TABLE `transport_shift_execution`
--   ADD COLUMN `loaded_count` int NOT NULL DEFAULT 0 COMMENT '已装车件数(行李舱运力,受 vehicle.cargo_capacity 约束)' AFTER `current_station_id`;
SOURCE sql/mysql/transport-schema.sql;
