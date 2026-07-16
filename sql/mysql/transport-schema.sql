-- 客货邮运营管理平台首批表结构草案（MySQL 8.4）。
-- 非破坏性：不包含 DROP，不会自动执行；上线前需完成字段、索引和容量评审。

CREATE TABLE IF NOT EXISTS `transport_vehicle` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '车辆编号',
  `plate_no` varchar(20) NOT NULL COMMENT '车牌号',
  `vehicle_type` tinyint NOT NULL COMMENT '车辆类型',
  `passenger_capacity` int NOT NULL DEFAULT 0 COMMENT '核定载客数',
  `cargo_capacity_kg` decimal(12,2) NOT NULL DEFAULT 0 COMMENT '载货重量上限(kg)',
  `status` tinyint NOT NULL DEFAULT 0 COMMENT '车辆状态',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  `creator` varchar(64) DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_vehicle_plate_tenant` (`plate_no`, `tenant_id`),
  KEY `idx_vehicle_status` (`tenant_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='运输车辆表';

CREATE TABLE IF NOT EXISTS `transport_driver` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '司机编号',
  `name` varchar(64) NOT NULL COMMENT '姓名',
  `mobile` varchar(32) NOT NULL COMMENT '联系电话',
  `license_no` varchar(64) NOT NULL COMMENT '驾驶证号',
  `license_expire_date` date DEFAULT NULL COMMENT '驾驶证到期日',
  `status` tinyint NOT NULL DEFAULT 0 COMMENT '司机状态',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  `creator` varchar(64) DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_driver_license_tenant` (`license_no`, `tenant_id`),
  KEY `idx_driver_status` (`tenant_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='司机档案表';

CREATE TABLE IF NOT EXISTS `transport_driver_vehicle` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '绑定编号',
  `driver_id` bigint NOT NULL COMMENT '司机编号',
  `vehicle_id` bigint NOT NULL COMMENT '车辆编号',
  `bind_time` datetime NOT NULL COMMENT '绑定时间',
  `unbind_time` datetime DEFAULT NULL COMMENT '解绑时间',
  `status` tinyint NOT NULL DEFAULT 1 COMMENT '绑定状态',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  `creator` varchar(64) DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`),
  KEY `idx_driver_vehicle_driver` (`tenant_id`, `driver_id`),
  KEY `idx_driver_vehicle_vehicle` (`tenant_id`, `vehicle_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='司机车辆绑定表';

CREATE TABLE IF NOT EXISTS `transport_station` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '站点编号',
  `station_code` varchar(32) NOT NULL COMMENT '站点编码',
  `station_name` varchar(128) NOT NULL COMMENT '站点名称',
  `station_level` tinyint NOT NULL COMMENT '站点层级',
  `longitude` decimal(10,7) NOT NULL COMMENT '经度',
  `latitude` decimal(10,7) NOT NULL COMMENT '纬度',
  `address` varchar(255) DEFAULT '' COMMENT '地址',
  `status` tinyint NOT NULL DEFAULT 0 COMMENT '站点状态',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  `creator` varchar(64) DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_station_code_tenant` (`station_code`, `tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='运输站点表';

CREATE TABLE IF NOT EXISTS `transport_route` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '线路编号',
  `route_code` varchar(32) NOT NULL COMMENT '线路编码',
  `route_name` varchar(128) NOT NULL COMMENT '线路名称',
  `start_station_id` bigint NOT NULL COMMENT '起点站编号',
  `end_station_id` bigint NOT NULL COMMENT '终点站编号',
  `distance_km` decimal(10,2) DEFAULT NULL COMMENT '线路里程(km)',
  `status` tinyint NOT NULL DEFAULT 0 COMMENT '线路状态',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  `creator` varchar(64) DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_route_code_tenant` (`route_code`, `tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='运输线路表';

CREATE TABLE IF NOT EXISTS `transport_route_station` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '线路站点编号',
  `route_id` bigint NOT NULL COMMENT '线路编号',
  `station_id` bigint NOT NULL COMMENT '站点编号',
  `sequence_no` int NOT NULL COMMENT '访问顺序',
  `planned_minutes` int DEFAULT NULL COMMENT '从线路起点计划分钟数',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  `creator` varchar(64) DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_route_station_sequence` (`route_id`, `sequence_no`, `tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='线路站点表';

CREATE TABLE IF NOT EXISTS `transport_shift` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '班次编号',
  `shift_code` varchar(32) NOT NULL COMMENT '班次编码',
  `route_id` bigint NOT NULL COMMENT '线路编号',
  `planned_departure_time` time NOT NULL COMMENT '计划发车时间',
  `planned_duration_minutes` int DEFAULT NULL COMMENT '计划时长(分钟)',
  `status` tinyint NOT NULL DEFAULT 0 COMMENT '班次状态',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  `creator` varchar(64) DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_shift_code_tenant` (`shift_code`, `tenant_id`),
  KEY `idx_shift_route` (`tenant_id`, `route_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='运输班次表';

CREATE TABLE IF NOT EXISTS `transport_order` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '订单编号',
  `order_no` varchar(64) NOT NULL COMMENT '业务订单号',
  `order_type` tinyint NOT NULL COMMENT '订单类型',
  `pickup_station_id` bigint NOT NULL COMMENT '取货或上车站点',
  `delivery_station_id` bigint NOT NULL COMMENT '送达或下车站点',
  `earliest_pickup_time` datetime DEFAULT NULL COMMENT '最早取货时间',
  `latest_delivery_time` datetime DEFAULT NULL COMMENT '最迟送达时间',
  `status` tinyint NOT NULL DEFAULT 0 COMMENT '订单状态',
  `total_amount` decimal(12,2) NOT NULL DEFAULT 0 COMMENT '订单金额',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  `creator` varchar(64) DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_transport_order_no_tenant` (`order_no`, `tenant_id`),
  KEY `idx_transport_order_pool` (`tenant_id`, `status`, `earliest_pickup_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='运输订单主表';

CREATE TABLE IF NOT EXISTS `transport_passenger_order` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '客运明细编号',
  `order_id` bigint NOT NULL COMMENT '运输订单编号',
  `passenger_count` int NOT NULL DEFAULT 1 COMMENT '乘客人数',
  `contact_name` varchar(64) NOT NULL COMMENT '联系人',
  `contact_mobile` varchar(32) NOT NULL COMMENT '联系电话',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  `creator` varchar(64) DEFAULT '', `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updater` varchar(64) DEFAULT '', `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` bit(1) NOT NULL DEFAULT b'0',
  PRIMARY KEY (`id`), UNIQUE KEY `uk_passenger_order` (`order_id`, `tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='客运订单明细表';

CREATE TABLE IF NOT EXISTS `transport_cargo_order` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '货运明细编号',
  `order_id` bigint NOT NULL COMMENT '运输订单编号',
  `cargo_category` varchar(64) NOT NULL COMMENT '货物类别',
  `fresh_flag` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否生鲜',
  `item_count` int NOT NULL DEFAULT 1 COMMENT '件数',
  `weight_kg` decimal(12,2) NOT NULL DEFAULT 0 COMMENT '重量(kg)',
  `volume_m3` decimal(12,4) NOT NULL DEFAULT 0 COMMENT '体积(m3)',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  `creator` varchar(64) DEFAULT '', `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updater` varchar(64) DEFAULT '', `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` bit(1) NOT NULL DEFAULT b'0',
  PRIMARY KEY (`id`), UNIQUE KEY `uk_cargo_order` (`order_id`, `tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='货运和生鲜订单明细表';

CREATE TABLE IF NOT EXISTS `transport_postal_order` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '邮快件明细编号',
  `order_id` bigint NOT NULL COMMENT '运输订单编号',
  `mail_no` varchar(64) NOT NULL COMMENT '邮件或快递单号',
  `carrier_code` varchar(32) DEFAULT '' COMMENT '承运商编码',
  `item_count` int NOT NULL DEFAULT 1 COMMENT '件数',
  `weight_kg` decimal(12,2) NOT NULL DEFAULT 0 COMMENT '重量(kg)',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  `creator` varchar(64) DEFAULT '', `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updater` varchar(64) DEFAULT '', `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` bit(1) NOT NULL DEFAULT b'0',
  PRIMARY KEY (`id`), UNIQUE KEY `uk_postal_mail_tenant` (`mail_no`, `tenant_id`), KEY `idx_postal_order` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='邮快件订单明细表';

CREATE TABLE IF NOT EXISTS `transport_dispatch_task` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '调度任务编号',
  `task_no` varchar(64) NOT NULL COMMENT '调度任务号',
  `snapshot_id` varchar(64) NOT NULL COMMENT '规划快照编号',
  `planning_time` datetime NOT NULL COMMENT '规划时间',
  `algorithm_job_id` varchar(64) DEFAULT NULL COMMENT '算法任务编号',
  `scenario` varchar(32) DEFAULT NULL COMMENT '规划场景',
  `status` tinyint NOT NULL DEFAULT 0 COMMENT '任务状态',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  `creator` varchar(64) DEFAULT '', `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updater` varchar(64) DEFAULT '', `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` bit(1) NOT NULL DEFAULT b'0',
  PRIMARY KEY (`id`), UNIQUE KEY `uk_dispatch_task_no_tenant` (`task_no`, `tenant_id`), UNIQUE KEY `uk_snapshot_tenant` (`snapshot_id`, `tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='调度任务表';

CREATE TABLE IF NOT EXISTS `transport_dispatch_plan` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '调度方案编号',
  `task_id` bigint NOT NULL COMMENT '调度任务编号',
  `plan_version` int NOT NULL DEFAULT 1 COMMENT '方案版本',
  `algorithm_version` varchar(64) DEFAULT NULL COMMENT '算法版本',
  `parameter_version` varchar(64) DEFAULT NULL COMMENT '参数版本',
  `score` decimal(12,4) DEFAULT NULL COMMENT '方案评分',
  `status` tinyint NOT NULL DEFAULT 0 COMMENT '审核下发状态',
  `approved_by` bigint DEFAULT NULL COMMENT '审核人',
  `approved_time` datetime DEFAULT NULL COMMENT '审核时间',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  `creator` varchar(64) DEFAULT '', `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updater` varchar(64) DEFAULT '', `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` bit(1) NOT NULL DEFAULT b'0',
  PRIMARY KEY (`id`), UNIQUE KEY `uk_dispatch_plan_version` (`task_id`, `plan_version`, `tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='调度方案表';

CREATE TABLE IF NOT EXISTS `transport_dispatch_plan_item` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '方案明细编号',
  `plan_id` bigint NOT NULL COMMENT '调度方案编号',
  `vehicle_id` bigint NOT NULL COMMENT '车辆编号',
  `driver_id` bigint DEFAULT NULL COMMENT '司机编号',
  `shift_id` bigint DEFAULT NULL COMMENT '班次编号',
  `order_id` bigint NOT NULL COMMENT '订单编号',
  `visit_sequence` int NOT NULL COMMENT '访问顺序',
  `action_type` tinyint NOT NULL COMMENT '取货/送达/上下客动作',
  `estimated_arrival_time` datetime DEFAULT NULL COMMENT '预计到达时间',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  `creator` varchar(64) DEFAULT '', `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updater` varchar(64) DEFAULT '', `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` bit(1) NOT NULL DEFAULT b'0',
  PRIMARY KEY (`id`), KEY `idx_plan_item_plan_sequence` (`plan_id`, `vehicle_id`, `visit_sequence`), KEY `idx_plan_item_order` (`tenant_id`, `order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='调度方案明细表';
