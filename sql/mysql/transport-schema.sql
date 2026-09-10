-- 客货邮运营管理平台首批表结构草案（MySQL 8.4）。
-- 非破坏性：不包含 DROP，不会自动执行；上线前需完成字段、索引和容量评审。

CREATE TABLE IF NOT EXISTS `transport_vehicle` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '车辆编号',
  `plate_no` varchar(20) NOT NULL COMMENT '车牌号',
  `vehicle_type` tinyint NOT NULL COMMENT '车辆类型',
  `passenger_capacity` int NOT NULL DEFAULT 0 COMMENT '核定载客数',
  `cargo_capacity_kg` decimal(12,2) NOT NULL DEFAULT 0 COMMENT '载货重量上限(kg)',
  `cargo_capacity` int NOT NULL DEFAULT 4 COMMENT '货仓件数上限（算法容量约束按件数）',
  `insurance_expire_date` date DEFAULT NULL COMMENT '保险到期日',
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
  `member_user_id` bigint NOT NULL DEFAULT 0 COMMENT '下单会员编号(小程序寄货)',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  `creator` varchar(64) DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_transport_order_no_tenant` (`order_no`, `tenant_id`),
  KEY `idx_transport_order_pool` (`tenant_id`, `status`, `earliest_pickup_time`),
  KEY `idx_transport_order_member` (`tenant_id`, `member_user_id`, `status`)
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
  `goods_name` varchar(128) NOT NULL DEFAULT '' COMMENT '货物名称',
  `goods_note` varchar(255) NOT NULL DEFAULT '' COMMENT '货物备注',
  `photo_url` varchar(255) NOT NULL DEFAULT '' COMMENT '货物照片',
  `driver_photo_url` varchar(255) NOT NULL DEFAULT '' COMMENT '司机收件照片(装车强制拍，快递总站核对凭证)',
  `audit_status` tinyint NOT NULL DEFAULT 0 COMMENT '审核状态：0待审核 1已通过 2已拒绝',
  `reject_reason` varchar(255) NOT NULL DEFAULT '' COMMENT '拒绝原因(审核拒绝时)',
  `review_status` tinyint NOT NULL DEFAULT 0 COMMENT '承运审核结果(ReviewStatusEnum)：0待审 1通过 2需客户操作 3需人工 4拒运',
  `review_reason_codes` varchar(255) NOT NULL DEFAULT '' COMMENT '承运审核原因码(ReviewReasonCodeEnum，逗号分隔多个)',
  `pickup_service_mode` varchar(32) NOT NULL DEFAULT '' COMMENT '取货服务方式(ServiceModeEnum)',
  `delivery_service_mode` varchar(32) NOT NULL DEFAULT '' COMMENT '送达服务方式(ServiceModeEnum)',
  `service_point_station_id` bigint DEFAULT NULL COMMENT '建议服务站点编号(替代交接：客户送站/最近站点时推荐)',
  `receiver_name` varchar(64) NOT NULL DEFAULT '' COMMENT '收货人',
  `receiver_mobile` varchar(32) NOT NULL DEFAULT '' COMMENT '收货电话',
  `receiver_address` varchar(255) NOT NULL DEFAULT '' COMMENT '收货地址',
  `original_address` varchar(255) NOT NULL DEFAULT '' COMMENT '用户原始寄货地址(如 重庆邮电大学)',
  `original_latitude` decimal(12,7) DEFAULT NULL COMMENT '用户原始纬度(GCJ-02)',
  `original_longitude` decimal(12,7) DEFAULT NULL COMMENT '用户原始经度(GCJ-02)',
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
  `receiver_name` varchar(64) NOT NULL DEFAULT '' COMMENT '收件人',
  `receiver_mobile` varchar(32) NOT NULL DEFAULT '' COMMENT '收件电话',
  `receiver_address` varchar(255) NOT NULL DEFAULT '' COMMENT '收件地址',
  `pickup_code` varchar(32) NOT NULL DEFAULT '' COMMENT '取件码（6位数字，收件人凭码取件）',
  `pickup_status` tinyint NOT NULL DEFAULT 0 COMMENT '取件状态：0待取件 1已取件',
  `picked_up_time` datetime DEFAULT NULL COMMENT '取件时间',
  `picker_member_user_id` bigint DEFAULT NULL COMMENT '核销人会员编号',
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
  `batch_start` datetime DEFAULT NULL COMMENT '批次区间开始（半小时）',
  `batch_end` datetime DEFAULT NULL COMMENT '批次区间结束（半小时）',
  `algorithm_job_id` varchar(64) DEFAULT NULL COMMENT '算法任务编号',
  `scenario` varchar(32) DEFAULT NULL COMMENT '规划场景',
  `status` tinyint NOT NULL DEFAULT 0 COMMENT '任务状态',
  `error_message` varchar(512) DEFAULT NULL COMMENT '失败原因',
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
  `mode` tinyint NOT NULL DEFAULT 0 COMMENT '派单方式:0 手工 1 智能',
  `algorithm_version` varchar(64) DEFAULT NULL COMMENT '算法版本',
  `parameter_version` varchar(64) DEFAULT NULL COMMENT '参数版本',
  `score` decimal(12,4) DEFAULT NULL COMMENT '方案评分',
  `total_distance` decimal(12,3) DEFAULT NULL COMMENT '总里程(km，按经停坐标 Haversine 换算)',
  `est_duration_minutes` int DEFAULT NULL COMMENT '预计耗时(分钟，估算)',
  `est_revenue` decimal(12,2) DEFAULT NULL COMMENT '预计收入(元，按计价规则估算)',
  `est_cost` decimal(12,2) DEFAULT NULL COMMENT '预计成本(元，按计价规则估算)',
  `route_provider` varchar(32) DEFAULT NULL COMMENT 'ETA路网来源:AMAP=高德真实时长 EUCLIDEAN_FALLBACK=直线估算',
  `status` tinyint NOT NULL DEFAULT 0 COMMENT '审核下发状态',
  `approved_by` bigint DEFAULT NULL COMMENT '审核人',
  `approved_time` datetime DEFAULT NULL COMMENT '审核时间',
  `task_window_start` datetime DEFAULT NULL COMMENT '任务段窗口开始(该方案车辆运营起始时刻，默认=批次开始)',
  `task_window_end` datetime DEFAULT NULL COMMENT '任务段窗口结束(默认=开始+预计耗时，方案完成后回写实际终点时刻)',
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
  `order_id` bigint DEFAULT NULL COMMENT '订单编号（场站起止点无订单）',
  `station_id` bigint DEFAULT NULL COMMENT '经停站点编号',
  `visit_sequence` int NOT NULL COMMENT '访问顺序',
  `action_type` tinyint NOT NULL COMMENT '0 出发 1 接客 2 送客 3 派送 4 揽收 5 返回',
  `estimated_arrival_time` datetime DEFAULT NULL COMMENT '预计到达时间',
  `segment_duration_seconds` int DEFAULT NULL COMMENT '分段路网行驶秒数(上一站→本站；高德真实时长或直线÷均速估算)',
  `segment_distance_km` decimal(12,3) DEFAULT NULL COMMENT '分段里程(km；高德路网公里或 Haversine 直线公里)',
  `planned_departure_time` datetime DEFAULT NULL COMMENT '计划离站时间(=预计到达+本站作业时长)',
  `service_duration_seconds` int DEFAULT NULL COMMENT '本站作业时长(秒，接/送/派/揽计停站作业)',
  `quantity` int DEFAULT NULL COMMENT '数量(BOARD/ALIGHT=人数，PICKUP/DELIVERY=件数)',
  `status` tinyint NOT NULL DEFAULT 0 COMMENT '任务段明细状态(TaskItemStatusEnum)：0待执行 1行驶中 2已到站 3上车中 4下车中 5揽收中 6派送中 7已完成 8失败',
  `service_mode` varchar(32) NOT NULL DEFAULT '' COMMENT '算法解释-服务方式(ServiceModeEnum，仅货运/揽收经停)',
  `service_point_station_id` bigint DEFAULT NULL COMMENT '算法解释-服务点站点编号(替代交接时推荐)',
  `detour_distance_km` decimal(12,3) DEFAULT NULL COMMENT '算法解释-绕行距离(km，相对公交骨架，骨架站为0)',
  `detour_duration_seconds` int DEFAULT NULL COMMENT '算法解释-绕行时长(秒)',
  `passenger_impact_seconds` int DEFAULT NULL COMMENT '算法解释-乘客影响(秒，绕行对车上乘客额外乘车时长，空车为NULL)',
  `reason_code` varchar(64) DEFAULT NULL COMMENT '算法解释-未接受原因码(ReviewReasonCodeEnum；已接受为NULL)',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  `creator` varchar(64) DEFAULT '', `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updater` varchar(64) DEFAULT '', `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` bit(1) NOT NULL DEFAULT b'0',
  PRIMARY KEY (`id`), KEY `idx_plan_item_plan_sequence` (`plan_id`, `vehicle_id`, `visit_sequence`), KEY `idx_plan_item_order` (`tenant_id`, `order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='调度方案明细表';

CREATE TABLE IF NOT EXISTS `transport_dispatch_plan_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '方案日志编号',
  `plan_id` bigint NOT NULL COMMENT '调度方案编号',
  `from_status` tinyint DEFAULT NULL COMMENT '变更前状态',
  `to_status` tinyint NOT NULL COMMENT '变更后状态',
  `operator` varchar(64) DEFAULT NULL COMMENT '操作人',
  `reason` varchar(512) DEFAULT NULL COMMENT '操作原因',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  `creator` varchar(64) DEFAULT '', `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updater` varchar(64) DEFAULT '', `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` bit(1) NOT NULL DEFAULT b'0',
  PRIMARY KEY (`id`), KEY `idx_plan_log_plan` (`tenant_id`, `plan_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='调度方案状态日志表';

CREATE TABLE IF NOT EXISTS `transport_pricing_rule` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '计价规则编号',
  `passenger_price_per_km` decimal(10,2) NOT NULL DEFAULT 1.00 COMMENT '客运人公里单价(元)',
  `cargo_price_per_item` decimal(10,2) NOT NULL DEFAULT 5.00 COMMENT '货运件单价(元)',
  `postal_price_per_item` decimal(10,2) NOT NULL DEFAULT 3.00 COMMENT '邮快件件单价(元)',
  `vehicle_cost_per_km` decimal(10,2) NOT NULL DEFAULT 2.50 COMMENT '车辆公里成本(元)',
  `avg_speed_kmh` decimal(5,1) NOT NULL DEFAULT 25.0 COMMENT '班线平均时速(km/h，ETA 与耗时估算口径)',
  `stop_service_minutes` int NOT NULL DEFAULT 3 COMMENT '作业站停站分钟（接/送/派/揽）',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  `creator` varchar(64) DEFAULT '', `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updater` varchar(64) DEFAULT '', `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` bit(1) NOT NULL DEFAULT b'0',
  PRIMARY KEY (`id`), KEY `idx_pricing_rule_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='运输计价规则表（单行配置，无记录时后端用默认值兜底）';

CREATE TABLE IF NOT EXISTS `transport_departure_check` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '发车核验编号',
  `plan_id` bigint NOT NULL COMMENT '调度方案编号',
  `vehicle_id` bigint NOT NULL COMMENT '车辆编号',
  `result` tinyint NOT NULL COMMENT '0 不通过 1 通过',
  `remark` varchar(512) DEFAULT NULL COMMENT '核验备注',
  `checker` varchar(64) DEFAULT NULL COMMENT '核验人',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  `creator` varchar(64) DEFAULT '', `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updater` varchar(64) DEFAULT '', `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` bit(1) NOT NULL DEFAULT b'0',
  PRIMARY KEY (`id`), KEY `idx_departure_check_plan` (`tenant_id`, `plan_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='发车核验表';

CREATE TABLE IF NOT EXISTS `transport_algorithm_request` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '算法请求编号',
  `request_id` varchar(64) NOT NULL COMMENT '全局唯一请求标识（算法侧幂等键）',
  `snapshot_hash` varchar(64) NOT NULL COMMENT '请求快照（不含 requestId）的 SHA-256',
  `request_json` text NOT NULL COMMENT '原始请求 JSON',
  `response_json` text DEFAULT NULL COMMENT '校验通过的响应 JSON；调用失败为空',
  `status` tinyint NOT NULL DEFAULT 0 COMMENT '状态：0 处理中 1 可行 2 无解 3 失败',
  `error_code` varchar(32) DEFAULT NULL COMMENT '失败时的业务错误码',
  `error_message` varchar(512) DEFAULT NULL COMMENT '失败时的错误信息',
  `algorithm_version` varchar(64) DEFAULT NULL COMMENT '算法版本，随镜像管理',
  `parameter_version` varchar(64) DEFAULT NULL COMMENT '默认参数版本',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  `creator` varchar(64) DEFAULT '', `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updater` varchar(64) DEFAULT '', `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` bit(1) NOT NULL DEFAULT b'0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_algorithm_request_id_tenant` (`request_id`, `tenant_id`),
  KEY `idx_algorithm_request_snapshot` (`tenant_id`, `snapshot_hash`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='算法请求留痕表';

-- ---------- 农产品商品表 ----------
CREATE TABLE IF NOT EXISTS `transport_product` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '商品编号',
  `name` varchar(128) NOT NULL COMMENT '商品名称',
  `from_village` varchar(64) NOT NULL DEFAULT '' COMMENT '产地村庄',
  `price` decimal(12,2) NOT NULL DEFAULT 0 COMMENT '售价',
  `unit` varchar(16) NOT NULL DEFAULT '斤' COMMENT '计价单位',
  `image` varchar(32) NOT NULL DEFAULT '' COMMENT '商品图(emoji)',
  `badge` varchar(64) NOT NULL DEFAULT '' COMMENT '角标文案',
  `description` varchar(512) NOT NULL DEFAULT '' COMMENT '商品描述',
  `stock` int NOT NULL DEFAULT 0 COMMENT '库存',
  `status` tinyint NOT NULL DEFAULT 0 COMMENT '状态(0上架 1下架)',
  `sort` int NOT NULL DEFAULT 0 COMMENT '排序值(越小越靠前)',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  `creator` varchar(64) DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_product_name_tenant` (`name`, `tenant_id`),
  KEY `idx_product_status_sort` (`tenant_id`, `status`, `sort`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='农产品商品表';

-- ---------- 农产品商城订单表 ----------
CREATE TABLE IF NOT EXISTS `transport_product_order` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '订单编号',
  `order_no` varchar(64) NOT NULL COMMENT '业务订单号',
  `user_id` bigint NOT NULL COMMENT '购买会员编号',
  `user_mobile` varchar(32) NOT NULL DEFAULT '' COMMENT '购买会员手机号',
  `total_amount` decimal(12,2) NOT NULL DEFAULT 0 COMMENT '订单总额',
  `status` tinyint NOT NULL DEFAULT 0 COMMENT '订单状态(0待发货 1已发货 2已完成 3已取消)',
  `vehicle_id` bigint DEFAULT NULL COMMENT '承运车辆编号(发货时关联,溯源用)',
  `shift_id` bigint DEFAULT NULL COMMENT '承运班次编号(发货时关联,溯源用)',
  `driver_id` bigint DEFAULT NULL COMMENT '承运司机编号(发货时按车辆绑定推导,司机端任务归属)',
  `deliver_station_id` bigint DEFAULT NULL COMMENT '交付/自提站点编号(发货时=班次线路终点站,司机到站提醒用)',
  `load_photo_url` varchar(255) NOT NULL DEFAULT '' COMMENT '司机装车照片URL(装车核验凭证)',
  `load_time` datetime DEFAULT NULL COMMENT '司机装车确认时间',
  `deliver_photo_url` varchar(255) NOT NULL DEFAULT '' COMMENT '司机妥投照片URL(交付凭证)',
  `deliver_time` datetime DEFAULT NULL COMMENT '司机妥投完成时间',
  `receiver_name` varchar(64) NOT NULL DEFAULT '' COMMENT '收货人',
  `receiver_mobile` varchar(32) NOT NULL DEFAULT '' COMMENT '收货电话',
  `receiver_address` varchar(255) NOT NULL DEFAULT '' COMMENT '收货地址',
  `remark` varchar(255) NOT NULL DEFAULT '' COMMENT '订单备注',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  `creator` varchar(64) DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_product_order_no_tenant` (`order_no`, `tenant_id`),
  KEY `idx_product_order_user` (`tenant_id`, `user_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='农产品商城订单表';

CREATE TABLE IF NOT EXISTS `transport_product_order_item` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '明细编号',
  `order_id` bigint NOT NULL COMMENT '订单编号',
  `product_id` bigint NOT NULL COMMENT '商品编号',
  `product_name` varchar(128) NOT NULL COMMENT '商品名称',
  `product_image` varchar(32) NOT NULL DEFAULT '' COMMENT '商品图(emoji)',
  `product_price` decimal(12,2) NOT NULL DEFAULT 0 COMMENT '下单单价',
  `quantity` int NOT NULL DEFAULT 1 COMMENT '购买数量',
  `amount` decimal(12,2) NOT NULL DEFAULT 0 COMMENT '小计金额',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  `creator` varchar(64) DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`),
  KEY `idx_product_order_item_order` (`tenant_id`, `order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='农产品商城订单明细表';

-- ---------- 司机端写操作闭环 ----------
CREATE TABLE IF NOT EXISTS `transport_shift_execution` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '执行编号',
  `shift_id` bigint NOT NULL COMMENT '班次编号',
  `driver_id` bigint NOT NULL COMMENT '司机编号',
  `vehicle_id` bigint DEFAULT NULL COMMENT '车辆编号',
  `exec_date` date NOT NULL COMMENT '执行日期',
  `depart_time` datetime DEFAULT NULL COMMENT '实际发车时间',
  `arrive_time` datetime DEFAULT NULL COMMENT '到达终点时间',
  `current_station_id` bigint DEFAULT NULL COMMENT '当前所在站点编号',
  `loaded_count` int NOT NULL DEFAULT 0 COMMENT '已装车件数(行李舱运力,受 vehicle.cargo_capacity 约束)',
  `status` tinyint NOT NULL DEFAULT 0 COMMENT '执行状态(0在途 1已完成)',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  `creator` varchar(64) DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_shift_execution` (`shift_id`, `driver_id`, `exec_date`, `tenant_id`),
  KEY `idx_shift_execution_date` (`tenant_id`, `exec_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='班次执行表';

CREATE TABLE IF NOT EXISTS `transport_vehicle_location` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '位置编号',
  `vehicle_id` bigint NOT NULL COMMENT '车辆编号',
  `shift_id` bigint DEFAULT NULL COMMENT '班次编号',
  `longitude` decimal(10,7) NOT NULL COMMENT '经度',
  `latitude` decimal(10,7) NOT NULL COMMENT '纬度',
  `speed_kmh` decimal(6,1) DEFAULT NULL COMMENT '速度(km/h)',
  `report_time` datetime NOT NULL COMMENT '上报时间',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  `creator` varchar(64) DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_vehicle_location_vehicle` (`vehicle_id`, `tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='车辆最新位置表（每车一行，司机端上报 upsert）';

-- ---------- 车辆位置历史轨迹表 ----------
CREATE TABLE IF NOT EXISTS `transport_vehicle_location_track` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '轨迹编号',
  `vehicle_id` bigint NOT NULL COMMENT '车辆编号',
  `shift_id` bigint DEFAULT NULL COMMENT '班次编号',
  `longitude` decimal(10,7) NOT NULL COMMENT '经度',
  `latitude` decimal(10,7) NOT NULL COMMENT '纬度',
  `speed_kmh` decimal(6,1) DEFAULT NULL COMMENT '速度(km/h)',
  `report_time` datetime NOT NULL COMMENT '上报时间',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  `creator` varchar(64) DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`),
  KEY `idx_vehicle_time` (`vehicle_id`, `report_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='车辆位置历史轨迹表（班次在途时按上报落库）';

-- ---------- 平台公告表 ----------
CREATE TABLE IF NOT EXISTS `transport_notice` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '公告编号',
  `title` varchar(128) NOT NULL DEFAULT '' COMMENT '公告标题',
  `content` text COMMENT '公告内容',
  `status` tinyint NOT NULL DEFAULT 1 COMMENT '状态(0下架 1上架)',
  `sort` int NOT NULL DEFAULT 0 COMMENT '排序(小的在前)',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  `creator` varchar(64) DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`),
  KEY `idx_notice_status_sort` (`tenant_id`, `status`, `sort`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='平台公告表';

-- ---------- 意见反馈表 ----------
CREATE TABLE IF NOT EXISTS `transport_feedback` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '反馈编号',
  `user_id` bigint NOT NULL DEFAULT 0 COMMENT '会员编号',
  `name` varchar(30) NOT NULL DEFAULT '' COMMENT '联系人姓名',
  `mobile` varchar(11) NOT NULL DEFAULT '' COMMENT '联系电话',
  `content` varchar(500) NOT NULL DEFAULT '' COMMENT '反馈内容',
  `status` tinyint NOT NULL DEFAULT 0 COMMENT '状态(0待处理 1已回复)',
  `reply` varchar(500) DEFAULT NULL COMMENT '回复内容',
  `reply_time` datetime DEFAULT NULL COMMENT '回复时间',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  `creator` varchar(64) DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='意见反馈表';

-- 已有库人工执行（CREATE IF NOT EXISTS 不会给已有表加列，升级请执行以下 ALTER）：
-- ALTER TABLE `transport_order`
--   ADD COLUMN `member_user_id` bigint NOT NULL DEFAULT 0 COMMENT '下单会员编号(小程序寄货)' AFTER `total_amount`,
--   ADD KEY `idx_transport_order_member` (`tenant_id`, `member_user_id`, `status`);
-- ALTER TABLE `transport_cargo_order`
--   ADD COLUMN `goods_name` varchar(128) NOT NULL DEFAULT '' COMMENT '货物名称' AFTER `volume_m3`,
--   ADD COLUMN `goods_note` varchar(255) NOT NULL DEFAULT '' COMMENT '货物备注' AFTER `goods_name`,
--   ADD COLUMN `photo_url` varchar(255) NOT NULL DEFAULT '' COMMENT '货物照片' AFTER `goods_note`,
--   ADD COLUMN `receiver_name` varchar(64) NOT NULL DEFAULT '' COMMENT '收货人' AFTER `photo_url`,
--   ADD COLUMN `receiver_mobile` varchar(32) NOT NULL DEFAULT '' COMMENT '收货电话' AFTER `receiver_name`,
--   ADD COLUMN `receiver_address` varchar(255) NOT NULL DEFAULT '' COMMENT '收货地址' AFTER `receiver_mobile`;
-- ALTER TABLE `transport_shift_execution`
--   ADD COLUMN `loaded_count` int NOT NULL DEFAULT 0 COMMENT '已装车件数(行李舱运力,受 vehicle.cargo_capacity 约束)' AFTER `current_station_id`;
