-- V018: 多段联运框架表（Leg / Handover / OrderEvent / UserNotification / DriverStatus）

-- ========== 运输段表 ==========
CREATE TABLE IF NOT EXISTS `transport_leg` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '运输段编号',
  `order_id` bigint NOT NULL COMMENT '运输订单编号',
  `leg_sequence` int NOT NULL COMMENT '段序号（1=第一段，2=第二段…）',
  `from_station_id` bigint NOT NULL COMMENT '起始站点编号',
  `to_station_id` bigint NOT NULL COMMENT '目的站点编号',
  `vehicle_id` bigint DEFAULT NULL COMMENT '承运车辆编号',
  `driver_id` bigint DEFAULT NULL COMMENT '承运司机编号',
  `shift_id` bigint DEFAULT NULL COMMENT '承运班次编号',
  `plan_item_id` bigint DEFAULT NULL COMMENT '关联调度方案明细编号',
  `status` tinyint NOT NULL DEFAULT 0 COMMENT '状态：0待分配 1已分配 2运输中 3已到达 4已交接 5异常',
  `estimated_departure` datetime DEFAULT NULL COMMENT '预计出发时间',
  `estimated_arrival` datetime DEFAULT NULL COMMENT '预计到达时间',
  `actual_departure` datetime DEFAULT NULL COMMENT '实际出发时间',
  `actual_arrival` datetime DEFAULT NULL COMMENT '实际到达时间',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  `creator` varchar(64) DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_leg_order_sequence` (`order_id`, `leg_sequence`, `tenant_id`),
  KEY `idx_leg_vehicle` (`tenant_id`, `vehicle_id`),
  KEY `idx_leg_driver` (`tenant_id`, `driver_id`),
  KEY `idx_leg_status` (`tenant_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='运输段表（支持多段联运）';

-- ========== 货物交接记录表 ==========
CREATE TABLE IF NOT EXISTS `transport_handover` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '交接编号',
  `order_id` bigint NOT NULL COMMENT '运输订单编号',
  `leg_from_id` bigint DEFAULT NULL COMMENT '来源运输段编号',
  `leg_to_id` bigint DEFAULT NULL COMMENT '目的运输段编号',
  `station_id` bigint NOT NULL COMMENT '交接站点编号',
  `from_driver_id` bigint DEFAULT NULL COMMENT '交出司机编号',
  `to_driver_id` bigint DEFAULT NULL COMMENT '接收司机编号',
  `item_count` int NOT NULL DEFAULT 0 COMMENT '交接件数',
  `weight_kg` decimal(12,2) DEFAULT NULL COMMENT '交接重量(kg)',
  `photo_url` varchar(255) DEFAULT '' COMMENT '交接照片URL',
  `status` tinyint NOT NULL DEFAULT 0 COMMENT '状态：0待确认 1已确认 2有争议',
  `handover_time` datetime DEFAULT NULL COMMENT '交接时间',
  `confirm_time` datetime DEFAULT NULL COMMENT '确认时间',
  `remark` varchar(255) DEFAULT '' COMMENT '备注',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  `creator` varchar(64) DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`),
  KEY `idx_handover_order` (`tenant_id`, `order_id`),
  KEY `idx_handover_station` (`tenant_id`, `station_id`),
  KEY `idx_handover_from_driver` (`tenant_id`, `from_driver_id`),
  KEY `idx_handover_to_driver` (`tenant_id`, `to_driver_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='货物交接记录表';

-- ========== 订单事件时间线表 ==========
CREATE TABLE IF NOT EXISTS `transport_order_event` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '事件编号',
  `order_id` bigint NOT NULL COMMENT '运输订单编号',
  `event_type` varchar(64) NOT NULL COMMENT '事件类型（ORDER_CREATED/REVIEW_PASSED/DISPATCHED/DEPARTED/ARRIVED/HANDOVER/COMPLETED/EXCEPTION/...）',
  `event_time` datetime NOT NULL COMMENT '事件时间',
  `operator` varchar(64) DEFAULT '' COMMENT '操作人（系统/司机ID/管理员名）',
  `detail` varchar(500) DEFAULT '' COMMENT '事件详情',
  `extra_data` text DEFAULT NULL COMMENT '扩展数据JSON',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_order_event_order` (`order_id`, `event_time`),
  KEY `idx_order_event_type` (`tenant_id`, `event_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单事件时间线表';

-- ========== 用户通知表 ==========
CREATE TABLE IF NOT EXISTS `transport_user_notification` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '通知编号',
  `user_id` bigint NOT NULL COMMENT '用户编号（会员ID）',
  `event_type` varchar(64) NOT NULL COMMENT '事件类型',
  `title` varchar(128) NOT NULL COMMENT '通知标题',
  `content` varchar(500) NOT NULL DEFAULT '' COMMENT '通知内容',
  `order_id` bigint DEFAULT NULL COMMENT '关联订单编号',
  `read_status` tinyint NOT NULL DEFAULT 0 COMMENT '阅读状态：0未读 1已读',
  `read_time` datetime DEFAULT NULL COMMENT '阅读时间',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`),
  KEY `idx_notification_user` (`user_id`, `read_status`),
  KEY `idx_notification_order` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户通知表';

-- ========== 司机实时状态表 ==========
CREATE TABLE IF NOT EXISTS `transport_driver_status` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '状态编号',
  `driver_id` bigint NOT NULL COMMENT '司机编号',
  `online_status` tinyint NOT NULL DEFAULT 0 COMMENT '在线状态：0离线 1在线 2忙碌',
  `current_vehicle_id` bigint DEFAULT NULL COMMENT '当前绑定车辆编号',
  `current_plan_id` bigint DEFAULT NULL COMMENT '当前执行方案编号',
  `last_heartbeat` datetime DEFAULT NULL COMMENT '最后心跳时间',
  `last_latitude` decimal(10,7) DEFAULT NULL COMMENT '最后纬度',
  `last_longitude` decimal(10,7) DEFAULT NULL COMMENT '最后经度',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_driver_status` (`driver_id`, `tenant_id`),
  KEY `idx_driver_status_online` (`tenant_id`, `online_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='司机实时状态表';
