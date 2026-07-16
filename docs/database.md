# 数据库设计说明

当前默认数据库为 MySQL，多租户在 `application.yaml` 中启用，因此 transport 表草案包含 `tenant_id`。审计字段遵循现有规范：`creator`、`create_time`、`updater`、`update_time`、`deleted`。

- `sql/mysql/transport-schema.sql`：首批领域表 DDL 草案，不自动执行。
- `sql/mysql/transport-menu.sql`：动态菜单和权限标识占位，需前后端路由评审后填写。
- `sql/mysql/transport-demo-data.sql`：演示数据占位，禁止用于生产。
- `sql/incremental/V001__create_transport_tables.sql`：指向首批迁移范围的人工执行说明。

正式实施前需确认编码规则、状态字典、金额单位、车辆/司机证照字段、站点层级、线路方向、订单拆合单、调度方案版本和历史表策略。迁移必须先备份并在预发布验证，禁止直接修改官方大 SQL 破坏升级路径。
