-- V015：调度方案明细算法解释字段（Phase 5 联合调度：服务方式/服务点/绕行/原因码）。
-- 非破坏性 DDL 唯一维护在 sql/mysql/transport-schema.sql，本文件只做人工执行入口。
-- 已有库执行本文件后，若表已存在（老库），请人工执行文件内注释的 ALTER：
-- ALTER TABLE `transport_dispatch_plan_item`
--   ADD COLUMN `service_mode` varchar(32) NOT NULL DEFAULT '' COMMENT '算法解释-服务方式(ServiceModeEnum，仅货运/揽收经停)' AFTER `status`,
--   ADD COLUMN `service_point_station_id` bigint DEFAULT NULL COMMENT '算法解释-服务点站点编号(替代交接时推荐)' AFTER `service_mode`,
--   ADD COLUMN `detour_distance_km` decimal(12,3) DEFAULT NULL COMMENT '算法解释-绕行距离(km，相对公交骨架，骨架站为0)' AFTER `service_point_station_id`,
--   ADD COLUMN `detour_duration_seconds` int DEFAULT NULL COMMENT '算法解释-绕行时长(秒)' AFTER `detour_distance_km`,
--   ADD COLUMN `reason_code` varchar(64) DEFAULT NULL COMMENT '算法解释-未接受原因码(ReviewReasonCodeEnum；已接受为NULL)' AFTER `detour_duration_seconds`;
SOURCE sql/mysql/transport-schema.sql;
