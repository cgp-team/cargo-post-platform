-- V005：司机端写操作闭环（班次执行记录 transport_shift_execution、车辆最新位置 transport_vehicle_location）。
-- 非破坏性 DDL 唯一维护在 sql/mysql/transport-schema.sql，本文件只做人工执行入口。
SOURCE sql/mysql/transport-schema.sql;
