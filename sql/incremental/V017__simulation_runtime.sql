-- ---------- V017：模拟运营运行时表（simulation_run / simulation_event / simulation_scenario / simulation_scenario_event） ----------

-- 模拟运行记录
CREATE TABLE IF NOT EXISTS `simulation_run` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  `plan_id` bigint DEFAULT NULL COMMENT '调度方案ID',
  `vehicle_id` bigint DEFAULT NULL COMMENT '车辆ID',
  `driver_id` bigint DEFAULT NULL COMMENT '司机ID',
  `scenario_id` bigint DEFAULT NULL COMMENT '场景ID',
  `multiplier` double DEFAULT 10 COMMENT '模拟倍速',
  `status` tinyint NOT NULL DEFAULT 0 COMMENT '状态：0已创建 1运行中 2已暂停 3已完成 4已终止',
  `start_time` datetime DEFAULT NULL COMMENT '模拟开始时间',
  `end_time` datetime DEFAULT NULL COMMENT '模拟结束时间',
  `total_sim_seconds` bigint DEFAULT 0 COMMENT '总模拟秒数',
  `actual_sim_seconds` bigint DEFAULT 0 COMMENT '实际模拟秒数',
  `total_distance_km` double DEFAULT NULL COMMENT '总里程(km)',
  `actual_distance_km` double DEFAULT NULL COMMENT '实际行驶里程(km)',
  `station_count` int DEFAULT 0 COMMENT '总站点数',
  `completed_station_count` int DEFAULT 0 COMMENT '已完成站点数',
  `order_count` int DEFAULT 0 COMMENT '总订单数',
  `completed_order_count` int DEFAULT 0 COMMENT '完成订单数',
  `exception_count` int DEFAULT 0 COMMENT '异常事件数',
  `result_summary` text COMMENT '结果摘要(JSON)',
  `creator` varchar(64) DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`),
  KEY `idx_plan_id` (`plan_id`),
  KEY `idx_vehicle_id` (`vehicle_id`),
  KEY `idx_status` (`status`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='模拟运行记录';

-- 模拟事件
CREATE TABLE IF NOT EXISTS `simulation_event` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  `run_id` bigint NOT NULL COMMENT '模拟运行ID',
  `vehicle_id` bigint DEFAULT NULL COMMENT '车辆ID',
  `event_type` varchar(50) NOT NULL COMMENT '事件类型：START/STOP/PAUSE/RESUME/ARRIVE/DEPART/FAULT/GPS_LOST/GPS_RECOVER/DRIVER_OFFLINE/DRIVER_ONLINE/ORDER_ADDED/ORDER_CANCELLED/CONGESTION/DELAY/COMPLETED',
  `severity` tinyint NOT NULL DEFAULT 0 COMMENT '严重级别：0信息 1警告 2严重',
  `title` varchar(200) NOT NULL COMMENT '事件标题',
  `content` varchar(1000) DEFAULT NULL COMMENT '事件内容',
  `sim_seconds` bigint DEFAULT NULL COMMENT '模拟时刻(秒)',
  `station_id` bigint DEFAULT NULL COMMENT '相关站点ID',
  `station_name` varchar(100) DEFAULT NULL COMMENT '相关站点名',
  `longitude` decimal(10,7) DEFAULT NULL COMMENT '经度',
  `latitude` decimal(10,7) DEFAULT NULL COMMENT '纬度',
  `extra_data` text COMMENT '扩展数据(JSON)',
  `creator` varchar(64) DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`),
  KEY `idx_run_id` (`run_id`),
  KEY `idx_vehicle_id` (`vehicle_id`),
  KEY `idx_event_type` (`event_type`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='模拟事件';

-- 模拟场景定义
CREATE TABLE IF NOT EXISTS `simulation_scenario` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  `name` varchar(100) NOT NULL COMMENT '场景名称',
  `description` varchar(500) DEFAULT NULL COMMENT '场景描述',
  `scenario_type` varchar(50) NOT NULL COMMENT '场景类型：NORMAL/CONGESTION/FAULT/GPS_LOST/DRIVER_OFFLINE/ORDER_SURGE/ORDER_CANCEL/ROAD_BLOCK',
  `severity` tinyint NOT NULL DEFAULT 0 COMMENT '严重级别：0正常 1警告 2严重',
  `is_builtin` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否内置',
  `enabled` bit(1) NOT NULL DEFAULT b'1' COMMENT '是否启用',
  `config_json` text COMMENT '场景配置(JSON)',
  `creator` varchar(64) DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`),
  KEY `idx_scenario_type` (`scenario_type`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='模拟场景定义';

-- 模拟场景事件（场景触发的具体事件）
CREATE TABLE IF NOT EXISTS `simulation_scenario_event` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  `scenario_id` bigint NOT NULL COMMENT '场景ID',
  `event_type` varchar(50) NOT NULL COMMENT '事件类型',
  `trigger_seconds` bigint DEFAULT NULL COMMENT '触发时刻(模拟秒)',
  `duration_seconds` bigint DEFAULT NULL COMMENT '持续时长(秒)',
  `title` varchar(200) NOT NULL COMMENT '事件标题',
  `content` varchar(1000) DEFAULT NULL COMMENT '事件内容',
  `severity` tinyint NOT NULL DEFAULT 0 COMMENT '严重级别：0信息 1警告 2严重',
  `parameters` text COMMENT '参数(JSON)',
  `creator` varchar(64) DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`),
  KEY `idx_scenario_id` (`scenario_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='模拟场景事件';

-- 内置场景数据
INSERT IGNORE INTO `simulation_scenario` (`id`, `tenant_id`, `name`, `description`, `scenario_type`, `severity`, `is_builtin`, `enabled`, `config_json`, `creator`, `create_time`, `updater`, `update_time`, `deleted`) VALUES
(1, 0, '正常运营', '标准运营场景，无异常事件', 'NORMAL', 0, b'1', b'1', '{}', '1', NOW(), '1', NOW(), b'0'),
(2, 0, '高峰拥堵', '模拟高峰时段道路拥堵，车辆速度降低', 'CONGESTION', 1, b'1', b'1', '{"speedFactor":0.4,"triggerAtPercent":30,"durationPercent":20}', '1', NOW(), '1', NOW(), b'0'),
(3, 0, '车辆晚点', '模拟车辆因故晚点到达', 'DELAY', 1, b'1', b'1', '{"delaySeconds":300,"triggerAtPercent":50}', '1', NOW(), '1', NOW(), b'0'),
(4, 0, '车辆故障', '模拟车辆发生故障停驶', 'FAULT', 2, b'1', b'1', '{"triggerAtPercent":40,"durationSeconds":600}', '1', NOW(), '1', NOW(), b'0'),
(5, 0, 'GPS 丢失', '模拟GPS信号丢失', 'GPS_LOST', 1, b'1', b'1', '{"triggerAtPercent":20,"durationSeconds":120}', '1', NOW(), '1', NOW(), b'0'),
(6, 0, '司机离线', '模拟司机离线无法操作', 'DRIVER_OFFLINE', 1, b'1', b'1', '{"triggerAtPercent":35,"durationSeconds":300}', '1', NOW(), '1', NOW(), b'0'),
(7, 0, '临时订单增加', '模拟运行中新增临时订单', 'ORDER_SURGE', 0, b'1', b'1', '{"orderCount":5,"triggerAtPercent":25}', '1', NOW(), '1', NOW(), b'0'),
(8, 0, '临时订单取消', '模拟运行中取消部分订单', 'ORDER_CANCEL', 0, b'1', b'1', '{"cancelCount":3,"triggerAtPercent":60}', '1', NOW(), '1', NOW(), b'0'),
(9, 0, '道路异常', '模拟道路施工或事故导致路线变更', 'ROAD_BLOCK', 2, b'1', b'1', '{"triggerAtPercent":45,"durationPercent":10}', '1', NOW(), '1', NOW(), b'0');
