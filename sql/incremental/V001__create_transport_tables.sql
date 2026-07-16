-- V001：创建 transport 首批业务表。
-- 当前仓库未启用 Flyway；请从仓库根目录使用 MySQL 客户端执行以下 SOURCE 命令。
-- 该命令引用唯一维护的非破坏性 DDL，避免两份表结构漂移。
SOURCE sql/mysql/transport-schema.sql;
