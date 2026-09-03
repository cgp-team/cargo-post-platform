-- ============================================================
-- Simulation Database Verification Script
-- 在 MySQL 中执行，验证 V017 migration 结果
-- ============================================================

-- 1. 表存在性检查
SELECT '=== Table Existence ===' AS section;

SELECT TABLE_NAME, TABLE_COMMENT, ENGINE, TABLE_ROWS
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN ('simulation_run', 'simulation_event', 'simulation_scenario', 'simulation_scenario_event')
ORDER BY TABLE_NAME;

-- 2. simulation_run 列检查
SELECT '=== simulation_run Columns ===' AS section;

SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'simulation_run'
ORDER BY ORDINAL_POSITION;

-- 3. simulation_event 列检查
SELECT '=== simulation_event Columns ===' AS section;

SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'simulation_event'
ORDER BY ORDINAL_POSITION;

-- 4. simulation_scenario 列检查
SELECT '=== simulation_scenario Columns ===' AS section;

SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'simulation_scenario'
ORDER BY ORDINAL_POSITION;

-- 5. 索引检查
SELECT '=== Indexes ===' AS section;

SELECT TABLE_NAME, INDEX_NAME, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS columns, NON_UNIQUE
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN ('simulation_run', 'simulation_event', 'simulation_scenario', 'simulation_scenario_event')
GROUP BY TABLE_NAME, INDEX_NAME, NON_UNIQUE
ORDER BY TABLE_NAME, INDEX_NAME;

-- 6. 内置场景数据检查
SELECT '=== Scenario Seed Data ===' AS section;

SELECT id, name, scenario_type, severity, is_builtin, enabled
FROM simulation_scenario
WHERE deleted = 0
ORDER BY id;

-- 7. EXPLAIN 验证索引命中
SELECT '=== EXPLAIN: events by run ===' AS section;

EXPLAIN SELECT * FROM simulation_event
WHERE run_id = 1 AND deleted = 0
ORDER BY sim_seconds ASC;

SELECT '=== EXPLAIN: active run by vehicle ===' AS section;

EXPLAIN SELECT * FROM simulation_run
WHERE vehicle_id = 1 AND status IN (1, 2) AND deleted = 0
ORDER BY id DESC LIMIT 1;

SELECT '=== EXPLAIN: history by creator ===' AS section;

EXPLAIN SELECT * FROM simulation_run
WHERE deleted = 0
ORDER BY id DESC
LIMIT 20;

-- 8. 数据统计
SELECT '=== Data Statistics ===' AS section;

SELECT 'simulation_run' AS table_name, COUNT(*) AS row_count FROM simulation_run
UNION ALL
SELECT 'simulation_event', COUNT(*) FROM simulation_event
UNION ALL
SELECT 'simulation_scenario', COUNT(*) FROM simulation_scenario
UNION ALL
SELECT 'simulation_scenario_event', COUNT(*) FROM simulation_scenario_event;
