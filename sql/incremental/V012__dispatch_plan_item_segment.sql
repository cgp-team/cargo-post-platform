-- V012：调度方案明细新增分段路网时长/里程（P1-001 segmentDuration 落库）。
-- 非破坏性 DDL 唯一维护在 sql/mysql/transport-schema.sql，本文件只做人工执行入口。
-- 已有库执行本文件后，若 transport_dispatch_plan_item 表已存在（老库），请人工执行文件内注释的 ALTER：
-- ALTER TABLE `transport_dispatch_plan_item`
--   ADD COLUMN `segment_duration_seconds` int DEFAULT NULL COMMENT '分段路网行驶秒数(上一站→本站；高德真实时长或直线÷均速估算)' AFTER `estimated_arrival_time`,
--   ADD COLUMN `segment_distance_km` decimal(12,3) DEFAULT NULL COMMENT '分段里程(km；高德路网公里或 Haversine 直线公里)' AFTER `segment_duration_seconds`;
SOURCE sql/mysql/transport-schema.sql;
