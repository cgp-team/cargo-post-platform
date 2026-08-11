# 数据库设计说明

当前默认数据库为 MySQL，多租户在 `application.yaml` 中启用，transport 表包含 `tenant_id`。审计字段遵循现有规范：`creator`、`create_time`、`updater`、`update_time`、`deleted`。

- `sql/mysql/transport-schema.sql`：transport 领域表 DDL 的唯一维护处（非破坏性 DDL，已涵盖算法请求留痕、调度闭环、车辆货仓件数等历次变更）。
- `sql/mysql/transport-menu.sql`：客货邮动态菜单与权限标识（已按前后端路由评审填写），另含 `member_user` 建表——司机端小程序登录依赖 member 模块。
- `sql/mysql/transport-demo-data.sql`：演示数据（车辆、司机、人车绑定、站点、线路、班次、三类订单及农产品商品），使用 `INSERT IGNORE` 可重复执行，禁止用于生产。

## 增量迁移

项目未启用自动数据库迁移。`sql/incremental/` 下各版本只做人工执行入口，统一 `SOURCE` 全量 schema：

- `V001__create_transport_tables.sql`：首批领域表。
- `V002__transport_algorithm_request.sql`：算法请求留痕表 `transport_algorithm_request`。
- `V003__transport_dispatch.sql`：调度闭环（订单池、派单、审核下发、发车核验）相关表结构。
- `V004__vehicle_cargo_capacity.sql`：车辆表新增货仓件数字段 `cargo_capacity`（算法容量约束按件数）；库中已有 `transport_vehicle` 表时需人工执行文件内注释的 `ALTER`。

正式实施前需确认编码规则、状态字典、金额单位、订单拆合单和历史表策略。迁移必须先备份并在预发布验证，禁止直接修改官方大 SQL 破坏升级路径。
