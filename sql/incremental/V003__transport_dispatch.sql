-- V003：调度闭环（订单池、派单、审核下发、发车核验）相关表结构变更。
-- 非破坏性 DDL 唯一维护在 sql/mysql/transport-schema.sql，本文件只做人工执行入口。
SOURCE sql/mysql/transport-schema.sql;
