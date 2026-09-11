-- V019：站点可达性三维模型 + 运输方案/运输段/交接/通知扩展列（幂等，可重复执行）
--
-- 背景：transport-schema.sql 用 CREATE TABLE IF NOT EXISTS 维护 DDL，不会给已有表补列；
-- 本文件用 information_schema 守卫 + 动态 SQL 补齐新列，重复执行安全。
-- 新增/扩展：
--   transport_station             用户可达 / 车辆可达 / 可调度 / 来源 / 类型（站点启用 ≠ 车辆可进入 ≠ 可用于调度）
--   transport_vehicle             实时运营状态（AVAILABLE / IN_SERVICE）
--   transport_dispatch_plan       运输方案：planNo / 组织方式 / 段数 / 换乘数 / 方案解释 / 预计与实际时间
--   transport_leg                 方案归属 / 线路 / 里程 / 时长 / 导航来源 / 货物与换乘标记
--   transport_handover            交接双方车辆 / 体积 / 状态机时间点 / 确认人 / 异常原因
--   transport_user_notification   幂等 eventId / 接收方类型 / 级别 / 需操作 / 方案与段

DROP PROCEDURE IF EXISTS tr_add_col_if_missing;
DELIMITER $$
CREATE PROCEDURE tr_add_col_if_missing(IN p_table VARCHAR(64), IN p_col VARCHAR(64), IN p_ddl TEXT)
BEGIN
  IF (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table AND COLUMN_NAME = p_col) = 0 THEN
    SET @tr_ddl = p_ddl;
    PREPARE tr_stmt FROM @tr_ddl;
    EXECUTE tr_stmt;
    DEALLOCATE PREPARE tr_stmt;
  END IF;
END$$
DELIMITER ;

-- ---------- transport_station：站点可达性三维模型 ----------
CALL tr_add_col_if_missing('transport_station','source_type',
  'ALTER TABLE `transport_station` ADD COLUMN `source_type` varchar(20) NOT NULL DEFAULT ''PROJECT'' COMMENT ''数据来源：REAL/PROJECT/SIMULATION'' AFTER `status`');
CALL tr_add_col_if_missing('transport_station','station_type',
  'ALTER TABLE `transport_station` ADD COLUMN `station_type` varchar(20) NOT NULL DEFAULT ''CARGO_STATION'' COMMENT ''站点类型：BUS_STOP/CARGO_STATION/MIXED'' AFTER `source_type`');
CALL tr_add_col_if_missing('transport_station','user_access',
  'ALTER TABLE `transport_station` ADD COLUMN `user_access` bit(1) NOT NULL DEFAULT b''1'' COMMENT ''用户可达（能否推荐给用户送/取）'' AFTER `station_type`');
CALL tr_add_col_if_missing('transport_station','vehicle_access',
  'ALTER TABLE `transport_station` ADD COLUMN `vehicle_access` bit(1) NOT NULL DEFAULT b''1'' COMMENT ''车辆可达（车辆能否进入装卸货；校园禁行区置 0）'' AFTER `user_access`');
CALL tr_add_col_if_missing('transport_station','dispatch_enabled',
  'ALTER TABLE `transport_station` ADD COLUMN `dispatch_enabled` bit(1) NOT NULL DEFAULT b''1'' COMMENT ''是否可用于调度（作场站/换乘站）'' AFTER `vehicle_access`');
CALL tr_add_col_if_missing('transport_station','sort',
  'ALTER TABLE `transport_station` ADD COLUMN `sort` int NOT NULL DEFAULT 0 COMMENT ''排序'' AFTER `dispatch_enabled`');
CALL tr_add_col_if_missing('transport_station','remark',
  'ALTER TABLE `transport_station` ADD COLUMN `remark` varchar(255) NOT NULL DEFAULT '''' COMMENT ''备注'' AFTER `sort`');

-- ---------- transport_vehicle：实时运营状态 ----------
CALL tr_add_col_if_missing('transport_vehicle','realtime_status',
  'ALTER TABLE `transport_vehicle` ADD COLUMN `realtime_status` tinyint NOT NULL DEFAULT 0 COMMENT ''实时状态：0空闲 1在途 2故障 3离线'' AFTER `status`');

-- ---------- transport_dispatch_plan：运输方案（TransportPlan） ----------
CALL tr_add_col_if_missing('transport_dispatch_plan','plan_no',
  'ALTER TABLE `transport_dispatch_plan` ADD COLUMN `plan_no` varchar(64) NOT NULL DEFAULT '''' COMMENT ''方案号（人可读）'' AFTER `task_id`');
CALL tr_add_col_if_missing('transport_dispatch_plan','planning_mode',
  'ALTER TABLE `transport_dispatch_plan` ADD COLUMN `planning_mode` varchar(20) NOT NULL DEFAULT '''' COMMENT ''组织方式：DIRECT/MULTI_LEG'' AFTER `mode`');
CALL tr_add_col_if_missing('transport_dispatch_plan','total_leg_count',
  'ALTER TABLE `transport_dispatch_plan` ADD COLUMN `total_leg_count` int NOT NULL DEFAULT 0 COMMENT ''总运输段数'' AFTER `planning_mode`');
CALL tr_add_col_if_missing('transport_dispatch_plan','transfer_count',
  'ALTER TABLE `transport_dispatch_plan` ADD COLUMN `transfer_count` int NOT NULL DEFAULT 0 COMMENT ''换乘次数'' AFTER `total_leg_count`');
CALL tr_add_col_if_missing('transport_dispatch_plan','plan_reason',
  'ALTER TABLE `transport_dispatch_plan` ADD COLUMN `plan_reason` varchar(500) NOT NULL DEFAULT '''' COMMENT ''方案解释（为什么直达/为什么联运）'' AFTER `transfer_count`');
CALL tr_add_col_if_missing('transport_dispatch_plan','estimated_start_time',
  'ALTER TABLE `transport_dispatch_plan` ADD COLUMN `estimated_start_time` datetime DEFAULT NULL COMMENT ''预计开始时间'' AFTER `plan_reason`');
CALL tr_add_col_if_missing('transport_dispatch_plan','estimated_arrival_time',
  'ALTER TABLE `transport_dispatch_plan` ADD COLUMN `estimated_arrival_time` datetime DEFAULT NULL COMMENT ''预计到达时间'' AFTER `estimated_start_time`');
CALL tr_add_col_if_missing('transport_dispatch_plan','actual_start_time',
  'ALTER TABLE `transport_dispatch_plan` ADD COLUMN `actual_start_time` datetime DEFAULT NULL COMMENT ''实际开始时间'' AFTER `estimated_arrival_time`');
CALL tr_add_col_if_missing('transport_dispatch_plan','actual_arrival_time',
  'ALTER TABLE `transport_dispatch_plan` ADD COLUMN `actual_arrival_time` datetime DEFAULT NULL COMMENT ''实际到达时间'' AFTER `actual_start_time`');

-- ---------- transport_leg：多段联运运输段 ----------
CALL tr_add_col_if_missing('transport_leg','plan_id',
  'ALTER TABLE `transport_leg` ADD COLUMN `plan_id` bigint DEFAULT NULL COMMENT ''所属运输方案编号'' AFTER `order_id`');
CALL tr_add_col_if_missing('transport_leg','route_id',
  'ALTER TABLE `transport_leg` ADD COLUMN `route_id` bigint DEFAULT NULL COMMENT ''本段承运线路编号'' AFTER `shift_id`');
CALL tr_add_col_if_missing('transport_leg','distance_km',
  'ALTER TABLE `transport_leg` ADD COLUMN `distance_km` decimal(12,2) DEFAULT NULL COMMENT ''本段里程(km)'' AFTER `actual_arrival`');
CALL tr_add_col_if_missing('transport_leg','duration_minutes',
  'ALTER TABLE `transport_leg` ADD COLUMN `duration_minutes` int DEFAULT NULL COMMENT ''本段预计耗时(分钟)'' AFTER `distance_km`');
CALL tr_add_col_if_missing('transport_leg','navigation_source',
  'ALTER TABLE `transport_leg` ADD COLUMN `navigation_source` varchar(20) DEFAULT NULL COMMENT ''导航来源：AMAP/ESTIMATED/PROJECT'' AFTER `duration_minutes`');
CALL tr_add_col_if_missing('transport_leg','navigation_polyline',
  'ALTER TABLE `transport_leg` ADD COLUMN `navigation_polyline` text DEFAULT NULL COMMENT ''导航 polyline(JSON)'' AFTER `navigation_source`');
CALL tr_add_col_if_missing('transport_leg','cargo_count',
  'ALTER TABLE `transport_leg` ADD COLUMN `cargo_count` int DEFAULT NULL COMMENT ''本段货物件数'' AFTER `navigation_polyline`');
CALL tr_add_col_if_missing('transport_leg','cargo_weight',
  'ALTER TABLE `transport_leg` ADD COLUMN `cargo_weight` decimal(12,2) DEFAULT NULL COMMENT ''本段货物重量(kg)'' AFTER `cargo_count`');
CALL tr_add_col_if_missing('transport_leg','handover_required',
  'ALTER TABLE `transport_leg` ADD COLUMN `handover_required` bit(1) NOT NULL DEFAULT b''0'' COMMENT ''是否需要换乘交接（非最终段）'' AFTER `cargo_weight`');

-- ---------- transport_handover：换乘站交接 ----------
CALL tr_add_col_if_missing('transport_handover','plan_id',
  'ALTER TABLE `transport_handover` ADD COLUMN `plan_id` bigint DEFAULT NULL COMMENT ''所属运输方案编号'' AFTER `order_id`');
CALL tr_add_col_if_missing('transport_handover','from_vehicle_id',
  'ALTER TABLE `transport_handover` ADD COLUMN `from_vehicle_id` bigint DEFAULT NULL COMMENT ''交出车辆编号'' AFTER `to_driver_id`');
CALL tr_add_col_if_missing('transport_handover','to_vehicle_id',
  'ALTER TABLE `transport_handover` ADD COLUMN `to_vehicle_id` bigint DEFAULT NULL COMMENT ''接收车辆编号'' AFTER `from_vehicle_id`');
CALL tr_add_col_if_missing('transport_handover','cargo_volume_m3',
  'ALTER TABLE `transport_handover` ADD COLUMN `cargo_volume_m3` decimal(12,4) DEFAULT NULL COMMENT ''交接体积(m³)'' AFTER `weight_kg`');
CALL tr_add_col_if_missing('transport_handover','arrived_at',
  'ALTER TABLE `transport_handover` ADD COLUMN `arrived_at` datetime DEFAULT NULL COMMENT ''前序司机到达换乘站时间'' AFTER `remark`');
CALL tr_add_col_if_missing('transport_handover','handover_started_at',
  'ALTER TABLE `transport_handover` ADD COLUMN `handover_started_at` datetime DEFAULT NULL COMMENT ''开始交接时间'' AFTER `arrived_at`');
CALL tr_add_col_if_missing('transport_handover','handover_completed_at',
  'ALTER TABLE `transport_handover` ADD COLUMN `handover_completed_at` datetime DEFAULT NULL COMMENT ''交接完成时间'' AFTER `handover_started_at`');
CALL tr_add_col_if_missing('transport_handover','confirmed_by',
  'ALTER TABLE `transport_handover` ADD COLUMN `confirmed_by` bigint DEFAULT NULL COMMENT ''确认人（司机编号）'' AFTER `handover_completed_at`');
CALL tr_add_col_if_missing('transport_handover','exception_reason',
  'ALTER TABLE `transport_handover` ADD COLUMN `exception_reason` varchar(255) DEFAULT NULL COMMENT ''超时/异常原因'' AFTER `confirmed_by`');

-- ---------- transport_user_notification：通知幂等与角色/级别 ----------
CALL tr_add_col_if_missing('transport_user_notification','recipient_type',
  'ALTER TABLE `transport_user_notification` ADD COLUMN `recipient_type` varchar(20) NOT NULL DEFAULT ''USER'' COMMENT ''接收方类型：USER/DRIVER/ADMIN'' AFTER `user_id`');
CALL tr_add_col_if_missing('transport_user_notification','event_id',
  'ALTER TABLE `transport_user_notification` ADD COLUMN `event_id` varchar(128) NOT NULL DEFAULT '''' COMMENT ''业务事件唯一标识(幂等键)'' AFTER `recipient_type`');
CALL tr_add_col_if_missing('transport_user_notification','plan_id',
  'ALTER TABLE `transport_user_notification` ADD COLUMN `plan_id` bigint DEFAULT NULL COMMENT ''所属方案编号'' AFTER `order_id`');
CALL tr_add_col_if_missing('transport_user_notification','leg_id',
  'ALTER TABLE `transport_user_notification` ADD COLUMN `leg_id` bigint DEFAULT NULL COMMENT ''关联运输段编号'' AFTER `plan_id`');
CALL tr_add_col_if_missing('transport_user_notification','level',
  'ALTER TABLE `transport_user_notification` ADD COLUMN `level` varchar(20) NOT NULL DEFAULT ''INFO'' COMMENT ''级别：INFO/SUCCESS/ACTION_REQUIRED/WARNING/EXCEPTION'' AFTER `leg_id`');
CALL tr_add_col_if_missing('transport_user_notification','action_required',
  'ALTER TABLE `transport_user_notification` ADD COLUMN `action_required` bit(1) NOT NULL DEFAULT b''0'' COMMENT ''是否需要接收方操作'' AFTER `level`');

DROP PROCEDURE IF EXISTS tr_add_col_if_missing;

-- ---------- 索引（幂等：已存在则忽略）----------
SET @idx_exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'transport_leg' AND INDEX_NAME = 'idx_leg_plan');
SET @ddl := IF(@idx_exists = 0, 'ALTER TABLE `transport_leg` ADD KEY `idx_leg_plan` (`plan_id`, `leg_sequence`)', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx_exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'transport_handover' AND INDEX_NAME = 'idx_handover_plan');
SET @ddl := IF(@idx_exists = 0, 'ALTER TABLE `transport_handover` ADD KEY `idx_handover_plan` (`plan_id`)', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx_exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'transport_user_notification' AND INDEX_NAME = 'idx_notification_event');
-- 仅当历史数据没有重复事件时才加唯一键（避免老库有重复通知导致迁移失败）
SET @dup_event := (SELECT COUNT(*) FROM (
  SELECT event_id, user_id, event_type FROM `transport_user_notification`
  GROUP BY event_id, user_id, event_type HAVING COUNT(*) > 1) t);
SET @ddl := IF(@idx_exists = 0 AND @dup_event = 0,
  'ALTER TABLE `transport_user_notification` ADD UNIQUE KEY `idx_notification_event` (`event_id`, `user_id`, `event_type`)',
  IF(@idx_exists = 0 AND @dup_event > 0,
     'ALTER TABLE `transport_user_notification` ADD KEY `idx_notification_event` (`event_id`, `user_id`, `event_type`)',
     'SELECT 1'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SELECT 'V019 applied' AS info;
