-- ============================================================
-- 生产数据库 Schema 只读检查脚本
-- 用途：确认线上数据库是否包含 Phase 0-14 新增列
-- 日期：2026-08-25
-- 注意：本文件仅包含只读查询，不修改任何数据
-- ============================================================

-- ============================================================
-- 1. transport_dispatch_plan 表结构全量检查
-- ============================================================
SHOW COLUMNS FROM transport_dispatch_plan;

-- 关键新增列检查
SHOW COLUMNS FROM transport_dispatch_plan LIKE 'task_window_start';
SHOW COLUMNS FROM transport_dispatch_plan LIKE 'task_window_end';
SHOW COLUMNS FROM transport_dispatch_plan LIKE 'est_duration_minutes';
SHOW COLUMNS FROM transport_dispatch_plan LIKE 'est_revenue';
SHOW COLUMNS FROM transport_dispatch_plan LIKE 'est_cost';
SHOW COLUMNS FROM transport_dispatch_plan LIKE 'routeProvider';

-- ============================================================
-- 2. transport_dispatch_plan_item 表结构全量检查
-- ============================================================
SHOW COLUMNS FROM transport_dispatch_plan_item;

-- 关键新增列检查
SHOW COLUMNS FROM transport_dispatch_plan_item LIKE 'segment_duration_seconds';
SHOW COLUMNS FROM transport_dispatch_plan_item LIKE 'segment_distance_km';
SHOW COLUMNS FROM transport_dispatch_plan_item LIKE 'planned_departure_time';
SHOW COLUMNS FROM transport_dispatch_plan_item LIKE 'service_duration_seconds';
SHOW COLUMNS FROM transport_dispatch_plan_item LIKE 'quantity';
SHOW COLUMNS FROM transport_dispatch_plan_item LIKE 'status';
SHOW COLUMNS FROM transport_dispatch_plan_item LIKE 'service_mode';
SHOW COLUMNS FROM transport_dispatch_plan_item LIKE 'service_point_station_id';
SHOW COLUMNS FROM transport_dispatch_plan_item LIKE 'detour_distance_km';
SHOW COLUMNS FROM transport_dispatch_plan_item LIKE 'detour_duration_seconds';
SHOW COLUMNS FROM transport_dispatch_plan_item LIKE 'reason_code';

-- ============================================================
-- 3. transport_cargo_order 表结构全量检查
-- ============================================================
SHOW COLUMNS FROM transport_cargo_order;

-- 关键新增列检查
SHOW COLUMNS FROM transport_cargo_order LIKE 'review_status';
SHOW COLUMNS FROM transport_cargo_order LIKE 'review_reason_codes';
SHOW COLUMNS FROM transport_cargo_order LIKE 'pickup_service_mode';
SHOW COLUMNS FROM transport_cargo_order LIKE 'delivery_service_mode';
SHOW COLUMNS FROM transport_cargo_order LIKE 'service_point_station_id';

-- ============================================================
-- 4. 辅助检查：确认表是否存在
-- ============================================================
SHOW TABLES LIKE 'transport_%';
