-- V020：扩展 plan_reason 字段长度，解决 Data too long 问题
-- 非破坏性 DDL 唯一维护在 sql/mysql/transport-schema.sql，本文件只做人工执行入口。
-- 已有库执行本文件后，若表已存在（老库），请人工执行文件内注释的 ALTER：
-- ALTER TABLE transport_dispatch_plan
--   MODIFY COLUMN plan_reason varchar(2000) NOT NULL DEFAULT '' COMMENT '方案解释（为什么直达/为什么联运）';
SOURCE sql/mysql/transport-schema.sql;
