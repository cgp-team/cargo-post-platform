-- V010：调度估算层——计价规则表 transport_pricing_rule；transport_dispatch_plan 加估算摘要列。
-- 新库直接 SOURCE 全量 schema 即可；若库中已有 transport_dispatch_plan 表（CREATE IF NOT EXISTS 不会加列），
-- 请人工执行文件末尾注释中的 ALTER。
SOURCE sql/mysql/transport-schema.sql;

-- ALTER TABLE `transport_dispatch_plan`
--   ADD COLUMN `est_duration_minutes` int DEFAULT NULL COMMENT '预计耗时(分钟，估算)' AFTER `total_distance`,
--   ADD COLUMN `est_revenue` decimal(12,2) DEFAULT NULL COMMENT '预计收入(元，按计价规则估算)' AFTER `est_duration_minutes`,
--   ADD COLUMN `est_cost` decimal(12,2) DEFAULT NULL COMMENT '预计成本(元，按计价规则估算)' AFTER `est_revenue`;
