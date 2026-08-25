-- V013：货运明细承运审核列（Phase 2 承运审核：review_status/reason_codes/service_mode/建议站点）。
-- 非破坏性 DDL 唯一维护在 sql/mysql/transport-schema.sql，本文件只做人工执行入口。
-- 已有库执行本文件后，若 transport_cargo_order 表已存在（老库），请人工执行文件内注释的 ALTER：
-- ALTER TABLE `transport_cargo_order`
--   ADD COLUMN `review_status` tinyint NOT NULL DEFAULT 0 COMMENT '承运审核结果(ReviewStatusEnum)：0待审 1通过 2需客户操作 3需人工 4拒运' AFTER `reject_reason`,
--   ADD COLUMN `review_reason_codes` varchar(255) NOT NULL DEFAULT '' COMMENT '承运审核原因码(ReviewReasonCodeEnum，逗号分隔多个)' AFTER `review_status`,
--   ADD COLUMN `pickup_service_mode` varchar(32) NOT NULL DEFAULT '' COMMENT '取货服务方式(ServiceModeEnum)' AFTER `review_reason_codes`,
--   ADD COLUMN `delivery_service_mode` varchar(32) NOT NULL DEFAULT '' COMMENT '送达服务方式(ServiceModeEnum)' AFTER `pickup_service_mode`,
--   ADD COLUMN `service_point_station_id` bigint DEFAULT NULL COMMENT '建议服务站点编号(替代交接：客户送站/最近站点时推荐)' AFTER `delivery_service_mode`;
SOURCE sql/mysql/transport-schema.sql;
