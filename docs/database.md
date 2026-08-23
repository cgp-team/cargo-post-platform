# 数据库设计说明

当前默认数据库为 MySQL。多租户已禁用（`application.yaml` 中 `yudao.tenant.enable: false`），transport 表仍保留 `tenant_id` 字段以便将来启用。审计字段遵循现有规范：`creator`、`create_time`、`updater`、`update_time`、`deleted`。

- `sql/mysql/transport-schema.sql`：transport 领域表 DDL 的唯一维护处（非破坏性 DDL，已涵盖算法请求留痕、调度闭环、车辆货仓件数等历次变更）。
- `sql/mysql/transport-menu.sql`：客货邮动态菜单与权限标识（已按前后端路由评审填写），另含 `member_user` 建表——司机端小程序登录依赖 member 模块。
- `sql/mysql/transport-demo-data.sql`：演示数据（车辆、司机、人车绑定、站点、线路、班次、三类订单及农产品商品），使用 `INSERT IGNORE` 可重复执行，禁止用于生产。

## 增量迁移

项目未启用自动数据库迁移。`sql/incremental/` 下各版本只做人工执行入口，统一 `SOURCE` 全量 schema：

- `V001__create_transport_tables.sql`：首批领域表。
- `V002__transport_algorithm_request.sql`：算法请求留痕表 `transport_algorithm_request`。
- `V003__transport_dispatch.sql`：调度闭环（订单池、派单、审核下发、发车核验）相关表结构。
- `V004__vehicle_cargo_capacity.sql`：车辆表新增货仓件数字段 `cargo_capacity`（算法容量约束按件数）；库中已有 `transport_vehicle` 表时需人工执行文件内注释的 `ALTER`。
- `V005__driver_execution.sql`：司机端写操作闭环（班次执行记录 `transport_shift_execution`、车辆最新位置 `transport_vehicle_location`）。
- `V006__driver_execution_loaded_count.sql`：班次执行表新增已装车件数 `loaded_count`（司机端装车/妥投运力落库）；老库已有 `transport_shift_execution` 表时需人工执行文件内注释的 `ALTER`。
- `V007__notice.sql`：平台公告表 `transport_notice`（管理端发布、小程序首页拉取）。
- `V008__feedback.sql`：意见反馈表 `transport_feedback`（小程序提交与查询、管理端处理）。
- `V009__vehicle_location_track.sql`：车辆位置历史轨迹表 `transport_vehicle_location_track`（轨迹回放与商品溯源）。
- `V010__dispatch_estimation.sql`：计价规则表 `transport_pricing_rule`；`transport_dispatch_plan` 新增估算摘要列 `est_duration_minutes` / `est_revenue` / `est_cost`；已有该表时需人工执行文件内注释的 `ALTER`。
- `V011__vehicle_insurance_expiry.sql`：车辆表新增保险到期日 `insurance_expire_date`；已有 `transport_vehicle` 表时需人工执行文件内注释的 `ALTER`。

另：`sql/mysql/transport-schema-incremental.sql` 为幂等的增量列补齐脚本（给已存在的老表补新列），已纳入 CI 迁移步骤，在 `transport-schema.sql` 之后自动执行。

正式实施前需确认编码规则、状态字典、金额单位、订单拆合单和历史表策略。迁移必须先备份并在预发布验证，禁止直接修改官方大 SQL 破坏升级路径。
