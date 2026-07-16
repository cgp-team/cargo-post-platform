# 增量 SQL

当前项目未启用自动数据库迁移。`V001__create_transport_tables.sql` 是人工执行入口，引用 `sql/mysql/transport-schema.sql`。执行前必须备份、确认目标库、在预发布演练并记录执行人和校验结果。禁止在启动时自动执行破坏性 DDL。
