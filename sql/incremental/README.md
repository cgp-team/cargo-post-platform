# 增量 SQL

当前项目未启用自动数据库迁移。`V001__create_transport_tables.sql` 是人工执行入口，引用 `sql/mysql/transport-schema.sql`。执行前必须备份、确认目标库、在预发布演练并记录执行人和校验结果。禁止在启动时自动执行破坏性 DDL。

`V005__driver_execution.sql`：司机端写操作闭环，新增 `transport_shift_execution`（班次执行记录）与 `transport_vehicle_location`（车辆最新位置，每车一行 upsert）。同为人工执行入口，DDL 维护在 `sql/mysql/transport-schema.sql`。

`V006__driver_execution_loaded_count.sql`：班次执行记录补已装车数量列（见文件头注释）。

`V007__notice.sql`：平台公告 `transport_notice`（管理端发布、小程序首页展示）。

`V008__feedback.sql`：意见反馈 `transport_feedback`（小程序提交、管理端回复处理）。

`V009__vehicle_location_track.sql`：车辆位置历史轨迹 `transport_vehicle_location_track`（班次在途时按上报落库，供商品溯源轨迹查询）。
