-- V023: settlement 相关关键列 schema 检查（幂等；部署前/运维 checklist 执行）
-- 背景：settlement-500 根因是生产库缺 V012–V015 列导致 SELECT * 映射失败。
-- 本脚本只读校验关键列是否存在；缺失时请执行 V012–V015/V019 中的 ALTER。

-- 1) dispatch_plan 关键列
SELECT
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'transport_dispatch_plan'
      AND COLUMN_NAME = 'total_distance') AS plan_total_distance,
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'transport_dispatch_plan'
      AND COLUMN_NAME = 'est_duration_minutes') AS plan_est_duration_minutes,
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'transport_dispatch_plan'
      AND COLUMN_NAME = 'task_window_start') AS plan_task_window_start;

-- 2) dispatch_plan_item 关键列（V012/V014/V015）
SELECT
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'transport_dispatch_plan_item'
      AND COLUMN_NAME = 'segment_duration_seconds') AS item_segment_duration_seconds,
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'transport_dispatch_plan_item'
      AND COLUMN_NAME = 'segment_distance_km') AS item_segment_distance_km,
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'transport_dispatch_plan_item'
      AND COLUMN_NAME = 'service_mode') AS item_service_mode;

-- 3) cargo_order 审核列（V013）
SELECT
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'transport_cargo_order'
      AND COLUMN_NAME = 'review_status') AS cargo_review_status,
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'transport_cargo_order'
      AND COLUMN_NAME = 'pickup_service_mode') AS cargo_pickup_service_mode;

-- 4) 结算用索引建议（缺则补）
SELECT
  (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'transport_dispatch_plan'
      AND INDEX_NAME = 'idx_plan_status_create') AS idx_plan_status_create;
