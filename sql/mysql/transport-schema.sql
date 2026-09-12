-- 瀹㈣揣閭�杩愯惀绠＄悊骞冲彴棣栨壒琛ㄧ粨鏋勮崏妗堬紙MySQL 8.4锛夈�?
-- 闈炵牬鍧忔�э細涓嶅寘鍚?DROP锛屼笉浼氳嚜鍔ㄦ墽琛岋紱涓婄嚎鍓嶉渶瀹屾垚瀛楁�点�佺储寮曞拰瀹归噺璇勫�°�?

CREATE TABLE IF NOT EXISTS `transport_vehicle` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '杞﹁締缂栧彿',
  `plate_no` varchar(20) NOT NULL COMMENT '杞︾墝鍙?,
  `vehicle_type` tinyint NOT NULL COMMENT '杞﹁締绫诲瀷',
  `passenger_capacity` int NOT NULL DEFAULT 0 COMMENT '鏍稿畾杞藉�㈡�?,
  `cargo_capacity_kg` decimal(12,2) NOT NULL DEFAULT 0 COMMENT '杞借揣閲嶉噺涓婇檺(kg)',
  `cargo_capacity` int NOT NULL DEFAULT 4 COMMENT '璐т粨浠舵暟涓婇檺锛堢畻娉曞�归噺绾︽潫鎸変欢鏁帮�?,
  `insurance_expire_date` date DEFAULT NULL COMMENT '淇濋櫓鍒版湡鏃?,
  `status` tinyint NOT NULL DEFAULT 0 COMMENT '杞﹁締鐘舵�?,
  `realtime_status` tinyint NOT NULL DEFAULT 0 COMMENT '瀹炴椂鐘舵�侊細0绌洪棽 1鍦ㄩ�?2鏁呴殰 3绂荤嚎',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '绉熸埛缂栧彿',
  `creator` varchar(64) DEFAULT '' COMMENT '鍒涘缓鑰?,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '鍒涘缓鏃堕棿',
  `updater` varchar(64) DEFAULT '' COMMENT '鏇存柊鑰?,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '鏇存柊鏃堕棿',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '鏄�鍚﹀垹闄�',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_vehicle_plate_tenant` (`plate_no`, `tenant_id`),
  KEY `idx_vehicle_status` (`tenant_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='杩愯緭杞﹁締琛?;

CREATE TABLE IF NOT EXISTS `transport_driver` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '鍙告満缂栧彿',
  `name` varchar(64) NOT NULL COMMENT '濮撳悕',
  `mobile` varchar(32) NOT NULL COMMENT '鑱旂郴鐢佃瘽',
  `license_no` varchar(64) NOT NULL COMMENT '椹鹃┒璇佸彿',
  `license_expire_date` date DEFAULT NULL COMMENT '椹鹃┒璇佸埌鏈熸棩',
  `status` tinyint NOT NULL DEFAULT 0 COMMENT '鍙告満鐘舵�?,
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '绉熸埛缂栧彿',
  `creator` varchar(64) DEFAULT '' COMMENT '鍒涘缓鑰?,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '鍒涘缓鏃堕棿',
  `updater` varchar(64) DEFAULT '' COMMENT '鏇存柊鑰?,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '鏇存柊鏃堕棿',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '鏄�鍚﹀垹闄�',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_driver_license_tenant` (`license_no`, `tenant_id`),
  KEY `idx_driver_status` (`tenant_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='鍙告満妗ｆ�堣�?;

CREATE TABLE IF NOT EXISTS `transport_driver_vehicle` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '缁戝畾缂栧彿',
  `driver_id` bigint NOT NULL COMMENT '鍙告満缂栧彿',
  `vehicle_id` bigint NOT NULL COMMENT '杞﹁締缂栧彿',
  `bind_time` datetime NOT NULL COMMENT '缁戝畾鏃堕棿',
  `unbind_time` datetime DEFAULT NULL COMMENT '瑙ｇ粦鏃堕棿',
  `status` tinyint NOT NULL DEFAULT 1 COMMENT '缁戝畾鐘舵�?,
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '绉熸埛缂栧彿',
  `creator` varchar(64) DEFAULT '' COMMENT '鍒涘缓鑰?,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '鍒涘缓鏃堕棿',
  `updater` varchar(64) DEFAULT '' COMMENT '鏇存柊鑰?,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '鏇存柊鏃堕棿',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '鏄�鍚﹀垹闄�',
  PRIMARY KEY (`id`),
  KEY `idx_driver_vehicle_driver` (`tenant_id`, `driver_id`),
  KEY `idx_driver_vehicle_vehicle` (`tenant_id`, `vehicle_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='鍙告満杞﹁締缁戝畾琛?;

CREATE TABLE IF NOT EXISTS `transport_station` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '绔欑偣缂栧彿',
  `station_code` varchar(32) NOT NULL COMMENT '绔欑偣缂栫爜',
  `station_name` varchar(128) NOT NULL COMMENT '绔欑偣鍚嶇О',
  `station_level` tinyint NOT NULL COMMENT '绔欑偣灞傜骇',
  `longitude` decimal(10,7) NOT NULL COMMENT '缁忓害',
  `latitude` decimal(10,7) NOT NULL COMMENT '绾�搴�',
  `address` varchar(255) DEFAULT '' COMMENT '鍦板潃',
  `status` tinyint NOT NULL DEFAULT 0 COMMENT '绔欑偣鐘舵�?,
  `source_type` varchar(20) NOT NULL DEFAULT 'PROJECT' COMMENT '鏁版嵁鏉ユ簮锛歊EAL/PROJECT/SIMULATION',
  `station_type` varchar(20) NOT NULL DEFAULT 'CARGO_STATION' COMMENT '绔欑偣绫诲瀷锛欱US_STOP/CARGO_STATION/MIXED',
  `user_access` bit(1) NOT NULL DEFAULT b'1' COMMENT '鐢ㄦ埛鍙�杈撅紙鑳藉惁鎺ㄨ崘缁欑敤鎴烽�?鍙栵級',
  `vehicle_access` bit(1) NOT NULL DEFAULT b'0' COMMENT '杞﹁締鍙�杈撅紙杞﹁締鑳藉惁杩涘叆瑁呭嵏璐э紱榛樿�ゅ惁锛岄渶绠＄悊鍛樻樉寮忓紑鏀撅級',
  `dispatch_enabled` bit(1) NOT NULL DEFAULT b'0' COMMENT '鏄�鍚﹀彲鐢ㄤ簬璋冨害锛堥粯璁ゅ惁锛屾柊澧炵珯鐐逛笉浼氳嚜鍔ㄦ垚涓哄満绔�/鎹�涔樼珯锛�',
  `sort` int NOT NULL DEFAULT 0 COMMENT '鎺掑簭',
  `remark` varchar(255) NOT NULL DEFAULT '' COMMENT '澶囨敞',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '绉熸埛缂栧彿',
  `creator` varchar(64) DEFAULT '' COMMENT '鍒涘缓鑰?,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '鍒涘缓鏃堕棿',
  `updater` varchar(64) DEFAULT '' COMMENT '鏇存柊鑰?,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '鏇存柊鏃堕棿',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '鏄�鍚﹀垹闄�',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_station_code_tenant` (`station_code`, `tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='杩愯緭绔欑偣琛?;

CREATE TABLE IF NOT EXISTS `transport_route` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '绾胯矾缂栧彿',
  `route_code` varchar(32) NOT NULL COMMENT '绾胯矾缂栫爜',
  `route_name` varchar(128) NOT NULL COMMENT '绾胯矾鍚嶇О',
  `start_station_id` bigint NOT NULL COMMENT '璧风偣绔欑紪鍙?,
  `end_station_id` bigint NOT NULL COMMENT '缁堢偣绔欑紪鍙?,
  `distance_km` decimal(10,2) DEFAULT NULL COMMENT '绾胯矾閲岀▼(km)',
  `status` tinyint NOT NULL DEFAULT 0 COMMENT '绾胯矾鐘舵�?,
  `source_type` varchar(20) NOT NULL DEFAULT 'PROJECT' COMMENT '鏁版嵁鏉ユ簮锛歊EAL/PROJECT',
  `service_type` varchar(20) NOT NULL DEFAULT 'CARGO' COMMENT '鏈嶅姟绫诲瀷锛歅ASSENGER/CARGO/MIXED',
  `dispatch_enabled` bit(1) NOT NULL DEFAULT b'1' COMMENT '鏄�鍚﹀彲鐢ㄤ簬璋冨�?,
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '绉熸埛缂栧彿',
  `creator` varchar(64) DEFAULT '' COMMENT '鍒涘缓鑰?,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '鍒涘缓鏃堕棿',
  `updater` varchar(64) DEFAULT '' COMMENT '鏇存柊鑰?,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '鏇存柊鏃堕棿',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '鏄�鍚﹀垹闄�',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_route_code_tenant` (`route_code`, `tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='杩愯緭绾胯矾琛?;

CREATE TABLE IF NOT EXISTS `transport_route_station` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '绾胯矾绔欑偣缂栧彿',
  `route_id` bigint NOT NULL COMMENT '绾胯矾缂栧彿',
  `station_id` bigint NOT NULL COMMENT '绔欑偣缂栧彿',
  `sequence_no` int NOT NULL COMMENT '璁块棶椤哄簭',
  `planned_minutes` int DEFAULT NULL COMMENT '浠庣嚎璺�璧风偣璁″垝鍒嗛挓鏁�',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '绉熸埛缂栧彿',
  `creator` varchar(64) DEFAULT '' COMMENT '鍒涘缓鑰?,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '鍒涘缓鏃堕棿',
  `updater` varchar(64) DEFAULT '' COMMENT '鏇存柊鑰?,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '鏇存柊鏃堕棿',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '鏄�鍚﹀垹闄�',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_route_station_sequence` (`route_id`, `sequence_no`, `tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='绾胯矾绔欑偣琛?;

CREATE TABLE IF NOT EXISTS `transport_shift` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '鐝�娆＄紪鍙�',
  `shift_code` varchar(32) NOT NULL COMMENT '鐝�娆＄紪鐮�',
  `route_id` bigint NOT NULL COMMENT '绾胯矾缂栧彿',
  `planned_departure_time` time NOT NULL COMMENT '璁″垝鍙戣溅鏃堕棿',
  `planned_duration_minutes` int DEFAULT NULL COMMENT '璁″垝鏃堕暱(鍒嗛挓)',
  `status` tinyint NOT NULL DEFAULT 0 COMMENT '鐝�娆＄姸鎬?,
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '绉熸埛缂栧彿',
  `creator` varchar(64) DEFAULT '' COMMENT '鍒涘缓鑰?,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '鍒涘缓鏃堕棿',
  `updater` varchar(64) DEFAULT '' COMMENT '鏇存柊鑰?,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '鏇存柊鏃堕棿',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '鏄�鍚﹀垹闄�',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_shift_code_tenant` (`shift_code`, `tenant_id`),
  KEY `idx_shift_route` (`tenant_id`, `route_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='杩愯緭鐝�娆¤�?;

CREATE TABLE IF NOT EXISTS `transport_order` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '璁㈠崟缂栧彿',
  `order_no` varchar(64) NOT NULL COMMENT '涓氬姟璁㈠崟鍙?,
  `order_type` tinyint NOT NULL COMMENT '璁㈠崟绫诲瀷',
  `pickup_station_id` bigint NOT NULL COMMENT '鍙栬揣鎴栦笂杞︾珯鐐?,
  `delivery_station_id` bigint NOT NULL COMMENT '閫佽揪鎴栦笅杞︾珯鐐?,
  `earliest_pickup_time` datetime DEFAULT NULL COMMENT '鏈�鏃╁彇璐ф椂闂?,
  `latest_delivery_time` datetime DEFAULT NULL COMMENT '鏈�杩熼�佽揪鏃堕棿',
  `status` tinyint NOT NULL DEFAULT 0 COMMENT '璁㈠崟鐘舵�?,
  `total_amount` decimal(12,2) NOT NULL DEFAULT 0 COMMENT '璁㈠崟閲戦��',
  `member_user_id` bigint NOT NULL DEFAULT 0 COMMENT '涓嬪崟浼氬憳缂栧彿(灏忕▼搴忓瘎璐?',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '绉熸埛缂栧彿',
  `creator` varchar(64) DEFAULT '' COMMENT '鍒涘缓鑰?,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '鍒涘缓鏃堕棿',
  `updater` varchar(64) DEFAULT '' COMMENT '鏇存柊鑰?,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '鏇存柊鏃堕棿',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '鏄�鍚﹀垹闄�',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_transport_order_no_tenant` (`order_no`, `tenant_id`),
  KEY `idx_transport_order_pool` (`tenant_id`, `status`, `earliest_pickup_time`),
  KEY `idx_transport_order_member` (`tenant_id`, `member_user_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='杩愯緭璁㈠崟涓昏〃';

CREATE TABLE IF NOT EXISTS `transport_passenger_order` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '瀹㈣繍鏄庣粏缂栧彿',
  `order_id` bigint NOT NULL COMMENT '杩愯緭璁㈠崟缂栧彿',
  `passenger_count` int NOT NULL DEFAULT 1 COMMENT '涔樺��浜烘暟',
  `contact_name` varchar(64) NOT NULL COMMENT '鑱旂郴浜?,
  `contact_mobile` varchar(32) NOT NULL COMMENT '鑱旂郴鐢佃瘽',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '绉熸埛缂栧彿',
  `creator` varchar(64) DEFAULT '', `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updater` varchar(64) DEFAULT '', `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` bit(1) NOT NULL DEFAULT b'0',
  PRIMARY KEY (`id`), UNIQUE KEY `uk_passenger_order` (`order_id`, `tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='瀹㈣繍璁㈠崟鏄庣粏琛?;

CREATE TABLE IF NOT EXISTS `transport_cargo_order` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '璐ц繍鏄庣粏缂栧彿',
  `order_id` bigint NOT NULL COMMENT '杩愯緭璁㈠崟缂栧彿',
  `cargo_category` varchar(64) NOT NULL COMMENT '璐х墿绫诲埆',
  `fresh_flag` bit(1) NOT NULL DEFAULT b'0' COMMENT '鏄�鍚︾敓椴�',
  `item_count` int NOT NULL DEFAULT 1 COMMENT '浠舵暟',
  `weight_kg` decimal(12,2) NOT NULL DEFAULT 0 COMMENT '閲嶉噺(kg)',
  `volume_m3` decimal(12,4) NOT NULL DEFAULT 0 COMMENT '浣撶Н(m3)',
  `goods_name` varchar(128) NOT NULL DEFAULT '' COMMENT '璐х墿鍚嶇О',
  `goods_note` varchar(255) NOT NULL DEFAULT '' COMMENT '璐х墿澶囨敞',
  `photo_url` varchar(255) NOT NULL DEFAULT '' COMMENT '璐х墿鐓х墖',
  `driver_photo_url` varchar(255) NOT NULL DEFAULT '' COMMENT '鍙告満鏀朵欢鐓х墖(瑁呰溅寮哄埗鎷嶏紝蹇�閫掓�荤珯鏍稿�瑰嚟璇�)',
  `audit_status` tinyint NOT NULL DEFAULT 0 COMMENT '瀹℃牳鐘舵�侊細0寰呭�℃�?1宸查�氳繃 2宸叉嫆缁?,
  `reject_reason` varchar(255) NOT NULL DEFAULT '' COMMENT '鎷掔粷鍘熷洜(瀹℃牳鎷掔粷鏃?',
  `review_status` tinyint NOT NULL DEFAULT 0 COMMENT '鎵胯繍瀹℃牳缁撴灉(ReviewStatusEnum)锛?寰呭�� 1閫氳繃 2闇�瀹㈡埛鎿嶄綔 3闇�浜哄伐 4鎷掕繍',
  `review_reason_codes` varchar(255) NOT NULL DEFAULT '' COMMENT '鎵胯繍瀹℃牳鍘熷洜鐮?ReviewReasonCodeEnum锛岄�楀彿鍒嗛殧澶氫釜)',
  `pickup_service_mode` varchar(32) NOT NULL DEFAULT '' COMMENT '鍙栬揣鏈嶅姟鏂瑰紡(ServiceModeEnum)',
  `delivery_service_mode` varchar(32) NOT NULL DEFAULT '' COMMENT '閫佽揪鏈嶅姟鏂瑰紡(ServiceModeEnum)',
  `service_point_station_id` bigint DEFAULT NULL COMMENT '寤鸿��鏈嶅姟绔欑偣缂栧彿(鏇夸唬浜ゆ帴锛氬�㈡埛閫佺珯/鏈�杩戠珯鐐规椂鎺ㄨ崘)',
  `receiver_name` varchar(64) NOT NULL DEFAULT '' COMMENT '鏀惰揣浜?,
  `receiver_mobile` varchar(32) NOT NULL DEFAULT '' COMMENT '鏀惰揣鐢佃瘽',
  `receiver_address` varchar(255) NOT NULL DEFAULT '' COMMENT '鏀惰揣鍦板潃',
  `original_address` varchar(255) NOT NULL DEFAULT '' COMMENT '鐢ㄦ埛鍘熷�嬪瘎璐у湴鍧�(濡?閲嶅簡閭�鐢靛ぇ瀛�)',
  `original_latitude` decimal(12,7) DEFAULT NULL COMMENT '鐢ㄦ埛鍘熷�嬬含搴�(GCJ-02)',
  `original_longitude` decimal(12,7) DEFAULT NULL COMMENT '鐢ㄦ埛鍘熷�嬬粡搴�(GCJ-02)',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '绉熸埛缂栧彿',
  `creator` varchar(64) DEFAULT '', `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updater` varchar(64) DEFAULT '', `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` bit(1) NOT NULL DEFAULT b'0',
  PRIMARY KEY (`id`), UNIQUE KEY `uk_cargo_order` (`order_id`, `tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='璐ц繍鍜岀敓椴滆�㈠崟鏄庣粏琛�';

CREATE TABLE IF NOT EXISTS `transport_postal_order` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '閭�蹇�浠舵槑缁嗙紪鍙?,
  `order_id` bigint NOT NULL COMMENT '杩愯緭璁㈠崟缂栧彿',
  `mail_no` varchar(64) NOT NULL COMMENT '閭�浠舵垨蹇�閫掑崟鍙?,
  `carrier_code` varchar(32) DEFAULT '' COMMENT '鎵胯繍鍟嗙紪鐮?,
  `item_count` int NOT NULL DEFAULT 1 COMMENT '浠舵暟',
  `weight_kg` decimal(12,2) NOT NULL DEFAULT 0 COMMENT '閲嶉噺(kg)',
  `receiver_name` varchar(64) NOT NULL DEFAULT '' COMMENT '鏀朵欢浜?,
  `receiver_mobile` varchar(32) NOT NULL DEFAULT '' COMMENT '鏀朵欢鐢佃瘽',
  `receiver_address` varchar(255) NOT NULL DEFAULT '' COMMENT '鏀朵欢鍦板潃',
  `pickup_code` varchar(32) NOT NULL DEFAULT '' COMMENT '鍙栦欢鐮侊紙6浣嶆暟瀛楋紝鏀朵欢浜哄嚟鐮佸彇浠讹級',
  `pickup_status` tinyint NOT NULL DEFAULT 0 COMMENT '鍙栦欢鐘舵�侊細0寰呭彇浠?1宸插彇浠?,
  `picked_up_time` datetime DEFAULT NULL COMMENT '鍙栦欢鏃堕棿',
  `picker_member_user_id` bigint DEFAULT NULL COMMENT '鏍搁攢浜轰細鍛樼紪鍙?,
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '绉熸埛缂栧彿',
  `creator` varchar(64) DEFAULT '', `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updater` varchar(64) DEFAULT '', `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` bit(1) NOT NULL DEFAULT b'0',
  PRIMARY KEY (`id`), UNIQUE KEY `uk_postal_mail_tenant` (`mail_no`, `tenant_id`), KEY `idx_postal_order` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='閭�蹇�浠惰�㈠崟鏄庣粏琛�';

CREATE TABLE IF NOT EXISTS `transport_dispatch_task` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '璋冨害浠诲姟缂栧彿',
  `task_no` varchar(64) NOT NULL COMMENT '璋冨害浠诲姟鍙?,
  `snapshot_id` varchar(64) NOT NULL COMMENT '瑙勫垝蹇�鐓х紪鍙�',
  `planning_time` datetime NOT NULL COMMENT '瑙勫垝鏃堕棿',
  `batch_start` datetime DEFAULT NULL COMMENT '鎵规�″尯闂村紑濮嬶紙鍗婂皬鏃讹級',
  `batch_end` datetime DEFAULT NULL COMMENT '鎵规�″尯闂寸粨鏉燂紙鍗婂皬鏃讹�?,
  `algorithm_job_id` varchar(64) DEFAULT NULL COMMENT '绠楁硶浠诲姟缂栧彿',
  `scenario` varchar(32) DEFAULT NULL COMMENT '瑙勫垝鍦烘櫙',
  `status` tinyint NOT NULL DEFAULT 0 COMMENT '浠诲姟鐘舵�?,
  `error_message` varchar(512) DEFAULT NULL COMMENT '澶辫触鍘熷洜',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '绉熸埛缂栧彿',
  `creator` varchar(64) DEFAULT '', `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updater` varchar(64) DEFAULT '', `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` bit(1) NOT NULL DEFAULT b'0',
  PRIMARY KEY (`id`), UNIQUE KEY `uk_dispatch_task_no_tenant` (`task_no`, `tenant_id`), UNIQUE KEY `uk_snapshot_tenant` (`snapshot_id`, `tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='璋冨害浠诲姟琛?;

CREATE TABLE IF NOT EXISTS `transport_dispatch_plan` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '璋冨害鏂规�堢紪鍙�',
  `task_id` bigint NOT NULL COMMENT '璋冨害浠诲姟缂栧彿',
  `plan_version` int NOT NULL DEFAULT 1 COMMENT '鏂规�堢増鏈�',
  `mode` tinyint NOT NULL DEFAULT 0 COMMENT '娲惧崟鏂瑰紡:0 鎵嬪伐 1 鏅鸿兘',
  `algorithm_version` varchar(64) DEFAULT NULL COMMENT '绠楁硶鐗堟湰',
  `parameter_version` varchar(64) DEFAULT NULL COMMENT '鍙傛暟鐗堟湰',
  `score` decimal(12,4) DEFAULT NULL COMMENT '鏂规�堣瘎鍒�',
  `total_distance` decimal(12,3) DEFAULT NULL COMMENT '鎬婚噷绋?km锛屾寜缁忓仠鍧愭爣 Haversine 鎹㈢畻)',
  `est_duration_minutes` int DEFAULT NULL COMMENT '棰勮�¤�楁椂(鍒嗛挓锛屼及绠?',
  `est_revenue` decimal(12,2) DEFAULT NULL COMMENT '棰勮�℃敹鍏�(鍏冿紝鎸夎�′环瑙勫垯浼扮�?',
  `est_cost` decimal(12,2) DEFAULT NULL COMMENT '棰勮�℃垚鏈�(鍏冿紝鎸夎�′环瑙勫垯浼扮�?',
  `route_provider` varchar(32) DEFAULT NULL COMMENT 'ETA璺�缃戞潵婧�:AMAP=楂樺痉鐪熷疄鏃堕暱 EUCLIDEAN_FALLBACK=鐩寸嚎浼扮畻',
  `status` tinyint NOT NULL DEFAULT 0 COMMENT '瀹℃牳涓嬪彂鐘舵�?,
  `approved_by` bigint DEFAULT NULL COMMENT '瀹℃牳浜?,
  `approved_time` datetime DEFAULT NULL COMMENT '瀹℃牳鏃堕棿',
  `task_window_start` datetime DEFAULT NULL COMMENT '浠诲姟娈电獥鍙ｅ紑濮?璇ユ柟妗堣溅杈嗚繍钀ヨ捣濮嬫椂鍒伙紝榛樿��=鎵规�″紑濮?',
  `task_window_end` datetime DEFAULT NULL COMMENT '浠诲姟娈电獥鍙ｇ粨鏉?榛樿��=寮�濮?棰勮�¤�楁椂锛屾柟妗堝畬鎴愬悗鍥炲啓瀹為檯缁堢偣鏃跺埢)',
  `plan_no` varchar(64) NOT NULL DEFAULT '' COMMENT '鏂规�堝彿锛堜汉鍙�璇伙級',
  `planning_mode` varchar(20) NOT NULL DEFAULT '' COMMENT '缁勭粐鏂瑰紡锛欴IRECT/MULTI_LEG',
  `total_leg_count` int NOT NULL DEFAULT 0 COMMENT '鎬昏繍杈撴�垫�?,
  `transfer_count` int NOT NULL DEFAULT 0 COMMENT '鎹�涔樻�℃暟',
  `plan_reason` varchar(2000) NOT NULL DEFAULT '' COMMENT '鏂规�堣В閲婏紙涓轰粈涔堢洿杈?涓轰粈涔堣仈杩愶級',
  `estimated_start_time` datetime DEFAULT NULL COMMENT '棰勮�″紑濮嬫椂闂?,
  `estimated_arrival_time` datetime DEFAULT NULL COMMENT '棰勮�″埌杈炬椂闂�',
  `actual_start_time` datetime DEFAULT NULL COMMENT '瀹為檯寮�濮嬫椂闂?,
  `actual_arrival_time` datetime DEFAULT NULL COMMENT '瀹為檯鍒拌揪鏃堕棿',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '绉熸埛缂栧彿',
  `creator` varchar(64) DEFAULT '', `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updater` varchar(64) DEFAULT '', `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` bit(1) NOT NULL DEFAULT b'0',
  PRIMARY KEY (`id`), UNIQUE KEY `uk_dispatch_plan_version` (`task_id`, `plan_version`, `tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='璋冨害鏂规�堣�?;

CREATE TABLE IF NOT EXISTS `transport_dispatch_plan_item` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '鏂规�堟槑缁嗙紪鍙�',
  `plan_id` bigint NOT NULL COMMENT '璋冨害鏂规�堢紪鍙�',
  `vehicle_id` bigint NOT NULL COMMENT '杞﹁締缂栧彿',
  `driver_id` bigint DEFAULT NULL COMMENT '鍙告満缂栧彿',
  `shift_id` bigint DEFAULT NULL COMMENT '鐝�娆＄紪鍙�',
  `order_id` bigint DEFAULT NULL COMMENT '璁㈠崟缂栧彿锛堝満绔欒捣姝㈢偣鏃犺�㈠崟锛�',
  `station_id` bigint DEFAULT NULL COMMENT '缁忓仠绔欑偣缂栧彿',
  `visit_sequence` int NOT NULL COMMENT '璁块棶椤哄簭',
  `action_type` tinyint NOT NULL COMMENT '0 鍑哄彂 1 鎺ュ�� 2 閫佸�� 3 娲鹃�?4 鎻芥敹 5 杩斿洖',
  `estimated_arrival_time` datetime DEFAULT NULL COMMENT '棰勮�″埌杈炬椂闂�',
  `segment_duration_seconds` int DEFAULT NULL COMMENT '鍒嗘�佃矾缃戣�岄┒绉掓暟(涓婁竴绔欌啋鏈�绔欙紱楂樺痉鐪熷疄鏃堕暱鎴栫洿绾棵峰潎閫熶及绠?',
  `segment_distance_km` decimal(12,3) DEFAULT NULL COMMENT '鍒嗘�甸噷绋�(km锛涢珮寰疯矾缃戝叕閲屾垨 Haversine 鐩寸嚎鍏�閲�)',
  `planned_departure_time` datetime DEFAULT NULL COMMENT '璁″垝绂荤珯鏃堕棿(=棰勮�″埌杈�+鏈�绔欎綔涓氭椂闀�)',
  `service_duration_seconds` int DEFAULT NULL COMMENT '鏈�绔欎綔涓氭椂闀�(绉掞紝鎺?閫?娲?鎻借�″仠绔欎綔涓�)',
  `quantity` int DEFAULT NULL COMMENT '鏁伴噺(BOARD/ALIGHT=浜烘暟锛孭ICKUP/DELIVERY=浠舵暟)',
  `status` tinyint NOT NULL DEFAULT 0 COMMENT '浠诲姟娈垫槑缁嗙姸鎬?TaskItemStatusEnum)锛?寰呮墽琛?1琛岄┒涓?2宸插埌绔?3涓婅溅涓?4涓嬭溅涓?5鎻芥敹涓?6娲鹃�佷腑 7宸插畬鎴?8澶辫触',
  `service_mode` varchar(32) NOT NULL DEFAULT '' COMMENT '绠楁硶瑙ｉ噴-鏈嶅姟鏂瑰紡(ServiceModeEnum锛屼粎璐ц繍/鎻芥敹缁忓仠)',
  `service_point_station_id` bigint DEFAULT NULL COMMENT '绠楁硶瑙ｉ噴-鏈嶅姟鐐圭珯鐐圭紪鍙?鏇夸唬浜ゆ帴鏃舵帹鑽?',
  `detour_distance_km` decimal(12,3) DEFAULT NULL COMMENT '绠楁硶瑙ｉ噴-缁曡�岃窛绂�(km锛岀浉瀵瑰叕浜ら�ㄦ灦锛岄�ㄦ灦绔欎负0)',
  `detour_duration_seconds` int DEFAULT NULL COMMENT '绠楁硶瑙ｉ噴-缁曡�屾椂闀�(绉?',
  `passenger_impact_seconds` int DEFAULT NULL COMMENT '绠楁硶瑙ｉ噴-涔樺�㈠奖鍝�(绉掞紝缁曡�屽�硅溅涓婁箻瀹㈤�濆�栦箻杞︽椂闀匡紝绌鸿溅涓篘ULL)',
  `reason_code` varchar(64) DEFAULT NULL COMMENT '绠楁硶瑙ｉ噴-鏈�鎺ュ彈鍘熷洜鐮�(ReviewReasonCodeEnum锛涘凡鎺ュ彈涓篘ULL)',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '绉熸埛缂栧彿',
  `creator` varchar(64) DEFAULT '', `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updater` varchar(64) DEFAULT '', `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` bit(1) NOT NULL DEFAULT b'0',
  PRIMARY KEY (`id`), KEY `idx_plan_item_plan_sequence` (`plan_id`, `vehicle_id`, `visit_sequence`), KEY `idx_plan_item_order` (`tenant_id`, `order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='璋冨害鏂规�堟槑缁嗚�?;

CREATE TABLE IF NOT EXISTS `transport_dispatch_plan_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '鏂规�堟棩蹇楃紪鍙�',
  `plan_id` bigint NOT NULL COMMENT '璋冨害鏂规�堢紪鍙�',
  `from_status` tinyint DEFAULT NULL COMMENT '鍙樻洿鍓嶇姸鎬?,
  `to_status` tinyint NOT NULL COMMENT '鍙樻洿鍚庣姸鎬?,
  `operator` varchar(64) DEFAULT NULL COMMENT '鎿嶄綔浜?,
  `reason` varchar(512) DEFAULT NULL COMMENT '鎿嶄綔鍘熷洜',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '绉熸埛缂栧彿',
  `creator` varchar(64) DEFAULT '', `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updater` varchar(64) DEFAULT '', `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` bit(1) NOT NULL DEFAULT b'0',
  PRIMARY KEY (`id`), KEY `idx_plan_log_plan` (`tenant_id`, `plan_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='璋冨害鏂规�堢姸鎬佹棩蹇楄〃';

CREATE TABLE IF NOT EXISTS `transport_pricing_rule` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '璁′环瑙勫垯缂栧彿',
  `passenger_price_per_km` decimal(10,2) NOT NULL DEFAULT 1.00 COMMENT '瀹㈣繍浜哄叕閲屽崟浠?鍏?',
  `cargo_price_per_item` decimal(10,2) NOT NULL DEFAULT 5.00 COMMENT '璐ц繍浠跺崟浠?鍏?',
  `postal_price_per_item` decimal(10,2) NOT NULL DEFAULT 3.00 COMMENT '閭�蹇�浠朵欢鍗曚环(鍏?',
  `vehicle_cost_per_km` decimal(10,2) NOT NULL DEFAULT 2.50 COMMENT '杞﹁締鍏�閲屾垚鏈�(鍏?',
  `avg_speed_kmh` decimal(5,1) NOT NULL DEFAULT 25.0 COMMENT '鐝�绾垮钩鍧囨椂閫?km/h锛孍TA 涓庤�楁椂浼扮畻鍙ｅ緞)',
  `stop_service_minutes` int NOT NULL DEFAULT 3 COMMENT '浣滀笟绔欏仠绔欏垎閽燂紙鎺?閫?娲?鎻斤級',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '绉熸埛缂栧彿',
  `creator` varchar(64) DEFAULT '', `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updater` varchar(64) DEFAULT '', `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` bit(1) NOT NULL DEFAULT b'0',
  PRIMARY KEY (`id`), KEY `idx_pricing_rule_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='杩愯緭璁′环瑙勫垯琛�锛堝崟琛岄厤缃�锛屾棤璁板綍鏃跺悗绔�鐢ㄩ粯璁ゅ�煎厹搴曪級';

CREATE TABLE IF NOT EXISTS `transport_departure_check` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '鍙戣溅鏍搁獙缂栧彿',
  `plan_id` bigint NOT NULL COMMENT '璋冨害鏂规�堢紪鍙�',
  `vehicle_id` bigint NOT NULL COMMENT '杞﹁締缂栧彿',
  `result` tinyint NOT NULL COMMENT '0 涓嶉�氳繃 1 閫氳繃',
  `remark` varchar(512) DEFAULT NULL COMMENT '鏍搁獙澶囨敞',
  `checker` varchar(64) DEFAULT NULL COMMENT '鏍搁獙浜?,
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '绉熸埛缂栧彿',
  `creator` varchar(64) DEFAULT '', `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updater` varchar(64) DEFAULT '', `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` bit(1) NOT NULL DEFAULT b'0',
  PRIMARY KEY (`id`), KEY `idx_departure_check_plan` (`tenant_id`, `plan_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='鍙戣溅鏍搁獙琛?;

CREATE TABLE IF NOT EXISTS `transport_algorithm_request` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '绠楁硶璇锋眰缂栧彿',
  `request_id` varchar(64) NOT NULL COMMENT '鍏ㄥ眬鍞�涓�璇锋眰鏍囪瘑锛堢畻娉曚晶骞傜瓑閿�锛�',
  `snapshot_hash` varchar(64) NOT NULL COMMENT '璇锋眰蹇�鐓э紙涓嶅�?requestId锛夌殑 SHA-256',
  `request_json` text NOT NULL COMMENT '鍘熷�嬭�锋眰 JSON',
  `response_json` text DEFAULT NULL COMMENT '鏍￠獙閫氳繃鐨勫搷搴?JSON锛涜皟鐢ㄥけ璐ヤ负绌?,
  `status` tinyint NOT NULL DEFAULT 0 COMMENT '鐘舵�侊細0 澶勭悊涓?1 鍙�琛� 2 鏃犺В 3 澶辫触',
  `error_code` varchar(32) DEFAULT NULL COMMENT '澶辫触鏃剁殑涓氬姟閿欒��鐮?,
  `error_message` varchar(512) DEFAULT NULL COMMENT '澶辫触鏃剁殑閿欒��淇℃伅',
  `algorithm_version` varchar(64) DEFAULT NULL COMMENT '绠楁硶鐗堟湰锛岄殢闀滃儚绠＄悊',
  `parameter_version` varchar(64) DEFAULT NULL COMMENT '榛樿�ゅ弬鏁扮増鏈�',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '绉熸埛缂栧彿',
  `creator` varchar(64) DEFAULT '', `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updater` varchar(64) DEFAULT '', `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` bit(1) NOT NULL DEFAULT b'0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_algorithm_request_id_tenant` (`request_id`, `tenant_id`),
  KEY `idx_algorithm_request_snapshot` (`tenant_id`, `snapshot_hash`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='绠楁硶璇锋眰鐣欑棔琛?;

-- ---------- 鍐滀骇鍝佸晢鍝佽〃 ----------
CREATE TABLE IF NOT EXISTS `transport_product` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '鍟嗗搧缂栧彿',
  `name` varchar(128) NOT NULL COMMENT '鍟嗗搧鍚嶇О',
  `from_village` varchar(64) NOT NULL DEFAULT '' COMMENT '浜у湴鏉戝簞',
  `price` decimal(12,2) NOT NULL DEFAULT 0 COMMENT '鍞�浠�',
  `unit` varchar(16) NOT NULL DEFAULT '鏂? COMMENT '璁′环鍗曚綅',
  `image` varchar(32) NOT NULL DEFAULT '' COMMENT '鍟嗗搧鍥?emoji)',
  `badge` varchar(64) NOT NULL DEFAULT '' COMMENT '瑙掓爣鏂囨��',
  `description` varchar(512) NOT NULL DEFAULT '' COMMENT '鍟嗗搧鎻忚堪',
  `stock` int NOT NULL DEFAULT 0 COMMENT '搴撳瓨',
  `status` tinyint NOT NULL DEFAULT 0 COMMENT '鐘舵�?0涓婃灦 1涓嬫灦)',
  `sort` int NOT NULL DEFAULT 0 COMMENT '鎺掑簭鍊?瓒婂皬瓒婇潬鍓?',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '绉熸埛缂栧彿',
  `creator` varchar(64) DEFAULT '' COMMENT '鍒涘缓鑰?,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '鍒涘缓鏃堕棿',
  `updater` varchar(64) DEFAULT '' COMMENT '鏇存柊鑰?,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '鏇存柊鏃堕棿',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '鏄�鍚﹀垹闄�',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_product_name_tenant` (`name`, `tenant_id`),
  KEY `idx_product_status_sort` (`tenant_id`, `status`, `sort`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='鍐滀骇鍝佸晢鍝佽〃';

-- ---------- 鍐滀骇鍝佸晢鍩庤�㈠崟琛� ----------
CREATE TABLE IF NOT EXISTS `transport_product_order` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '璁㈠崟缂栧彿',
  `order_no` varchar(64) NOT NULL COMMENT '涓氬姟璁㈠崟鍙?,
  `user_id` bigint NOT NULL COMMENT '璐�涔颁細鍛樼紪鍙�',
  `user_mobile` varchar(32) NOT NULL DEFAULT '' COMMENT '璐�涔颁細鍛樻墜鏈哄�?,
  `total_amount` decimal(12,2) NOT NULL DEFAULT 0 COMMENT '璁㈠崟鎬婚��',
  `status` tinyint NOT NULL DEFAULT 0 COMMENT '璁㈠崟鐘舵�?0寰呭彂璐?1宸插彂璐?2宸插畬鎴?3宸插彇娑?',
  `vehicle_id` bigint DEFAULT NULL COMMENT '鎵胯繍杞﹁締缂栧彿(鍙戣揣鏃跺叧鑱?婧�婧愮�?',
  `shift_id` bigint DEFAULT NULL COMMENT '鎵胯繍鐝�娆＄紪鍙�(鍙戣揣鏃跺叧鑱?婧�婧愮�?',
  `driver_id` bigint DEFAULT NULL COMMENT '鎵胯繍鍙告満缂栧彿(鍙戣揣鏃舵寜杞﹁締缁戝畾鎺ㄥ��,鍙告満绔�浠诲姟褰掑�?',
  `deliver_station_id` bigint DEFAULT NULL COMMENT '浜や粯/鑷�鎻愮珯鐐圭紪鍙�(鍙戣揣鏃?鐝�娆＄嚎璺�缁堢偣绔?鍙告満鍒扮珯鎻愰啋鐢?',
  `load_photo_url` varchar(255) NOT NULL DEFAULT '' COMMENT '鍙告満瑁呰溅鐓х墖URL(瑁呰溅鏍搁獙鍑�璇�)',
  `load_time` datetime DEFAULT NULL COMMENT '鍙告満瑁呰溅纭�璁ゆ椂闂�',
  `deliver_photo_url` varchar(255) NOT NULL DEFAULT '' COMMENT '鍙告満濡ユ姇鐓х墖URL(浜や粯鍑�璇�)',
  `deliver_time` datetime DEFAULT NULL COMMENT '鍙告満濡ユ姇瀹屾垚鏃堕棿',
  `receiver_name` varchar(64) NOT NULL DEFAULT '' COMMENT '鏀惰揣浜?,
  `receiver_mobile` varchar(32) NOT NULL DEFAULT '' COMMENT '鏀惰揣鐢佃瘽',
  `receiver_address` varchar(255) NOT NULL DEFAULT '' COMMENT '鏀惰揣鍦板潃',
  `remark` varchar(255) NOT NULL DEFAULT '' COMMENT '璁㈠崟澶囨敞',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '绉熸埛缂栧彿',
  `creator` varchar(64) DEFAULT '' COMMENT '鍒涘缓鑰?,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '鍒涘缓鏃堕棿',
  `updater` varchar(64) DEFAULT '' COMMENT '鏇存柊鑰?,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '鏇存柊鏃堕棿',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '鏄�鍚﹀垹闄�',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_product_order_no_tenant` (`order_no`, `tenant_id`),
  KEY `idx_product_order_user` (`tenant_id`, `user_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='鍐滀骇鍝佸晢鍩庤�㈠崟琛�';

CREATE TABLE IF NOT EXISTS `transport_product_order_item` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '鏄庣粏缂栧彿',
  `order_id` bigint NOT NULL COMMENT '璁㈠崟缂栧彿',
  `product_id` bigint NOT NULL COMMENT '鍟嗗搧缂栧彿',
  `product_name` varchar(128) NOT NULL COMMENT '鍟嗗搧鍚嶇О',
  `product_image` varchar(32) NOT NULL DEFAULT '' COMMENT '鍟嗗搧鍥?emoji)',
  `product_price` decimal(12,2) NOT NULL DEFAULT 0 COMMENT '涓嬪崟鍗曚环',
  `quantity` int NOT NULL DEFAULT 1 COMMENT '璐�涔版暟閲�',
  `amount` decimal(12,2) NOT NULL DEFAULT 0 COMMENT '灏忚�￠噾棰�',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '绉熸埛缂栧彿',
  `creator` varchar(64) DEFAULT '' COMMENT '鍒涘缓鑰?,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '鍒涘缓鏃堕棿',
  `updater` varchar(64) DEFAULT '' COMMENT '鏇存柊鑰?,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '鏇存柊鏃堕棿',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '鏄�鍚﹀垹闄�',
  PRIMARY KEY (`id`),
  KEY `idx_product_order_item_order` (`tenant_id`, `order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='鍐滀骇鍝佸晢鍩庤�㈠崟鏄庣粏琛�';

-- ---------- 鍙告満绔�鍐欐搷浣滈棴鐜� ----------
CREATE TABLE IF NOT EXISTS `transport_shift_execution` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '鎵ц�岀紪鍙�',
  `shift_id` bigint NOT NULL COMMENT '鐝�娆＄紪鍙�',
  `driver_id` bigint NOT NULL COMMENT '鍙告満缂栧彿',
  `vehicle_id` bigint DEFAULT NULL COMMENT '杞﹁締缂栧彿',
  `exec_date` date NOT NULL COMMENT '鎵ц�屾棩鏈�',
  `depart_time` datetime DEFAULT NULL COMMENT '瀹為檯鍙戣溅鏃堕棿',
  `arrive_time` datetime DEFAULT NULL COMMENT '鍒拌揪缁堢偣鏃堕棿',
  `current_station_id` bigint DEFAULT NULL COMMENT '褰撳墠鎵�鍦ㄧ珯鐐圭紪鍙?,
  `loaded_count` int NOT NULL DEFAULT 0 COMMENT '宸茶�呰溅浠舵�?琛屾潕鑸辫繍鍔?鍙?vehicle.cargo_capacity 绾︽潫)',
  `status` tinyint NOT NULL DEFAULT 0 COMMENT '鎵ц�岀姸鎬?0鍦ㄩ�?1宸插畬鎴?',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '绉熸埛缂栧彿',
  `creator` varchar(64) DEFAULT '' COMMENT '鍒涘缓鑰?,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '鍒涘缓鏃堕棿',
  `updater` varchar(64) DEFAULT '' COMMENT '鏇存柊鑰?,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '鏇存柊鏃堕棿',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '鏄�鍚﹀垹闄�',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_shift_execution` (`shift_id`, `driver_id`, `exec_date`, `tenant_id`),
  KEY `idx_shift_execution_date` (`tenant_id`, `exec_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='鐝�娆℃墽琛岃�?;

CREATE TABLE IF NOT EXISTS `transport_vehicle_location` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '浣嶇疆缂栧彿',
  `vehicle_id` bigint NOT NULL COMMENT '杞﹁締缂栧彿',
  `shift_id` bigint DEFAULT NULL COMMENT '鐝�娆＄紪鍙�',
  `longitude` decimal(10,7) NOT NULL COMMENT '缁忓害',
  `latitude` decimal(10,7) NOT NULL COMMENT '绾�搴�',
  `speed_kmh` decimal(6,1) DEFAULT NULL COMMENT '閫熷害(km/h)',
  `report_time` datetime NOT NULL COMMENT '涓婃姤鏃堕棿',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '绉熸埛缂栧彿',
  `creator` varchar(64) DEFAULT '' COMMENT '鍒涘缓鑰?,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '鍒涘缓鏃堕棿',
  `updater` varchar(64) DEFAULT '' COMMENT '鏇存柊鑰?,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '鏇存柊鏃堕棿',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '鏄�鍚﹀垹闄�',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_vehicle_location_vehicle` (`vehicle_id`, `tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='杞﹁締鏈�鏂颁綅缃�琛�锛堟瘡杞︿竴琛岋紝鍙告満绔�涓婃�?upsert锛?;

-- ---------- 杞﹁締浣嶇疆鍘嗗彶杞ㄨ抗琛?----------
CREATE TABLE IF NOT EXISTS `transport_vehicle_location_track` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '杞ㄨ抗缂栧彿',
  `vehicle_id` bigint NOT NULL COMMENT '杞﹁締缂栧彿',
  `shift_id` bigint DEFAULT NULL COMMENT '鐝�娆＄紪鍙�',
  `longitude` decimal(10,7) NOT NULL COMMENT '缁忓害',
  `latitude` decimal(10,7) NOT NULL COMMENT '绾�搴�',
  `speed_kmh` decimal(6,1) DEFAULT NULL COMMENT '閫熷害(km/h)',
  `report_time` datetime NOT NULL COMMENT '涓婃姤鏃堕棿',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '绉熸埛缂栧彿',
  `creator` varchar(64) DEFAULT '' COMMENT '鍒涘缓鑰?,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '鍒涘缓鏃堕棿',
  `updater` varchar(64) DEFAULT '' COMMENT '鏇存柊鑰?,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '鏇存柊鏃堕棿',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '鏄�鍚﹀垹闄�',
  PRIMARY KEY (`id`),
  KEY `idx_vehicle_time` (`vehicle_id`, `report_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='杞﹁締浣嶇疆鍘嗗彶杞ㄨ抗琛�锛堢彮娆″湪閫旀椂鎸変笂鎶ヨ惤搴擄級';

-- ---------- 骞冲彴鍏�鍛婅�?----------
CREATE TABLE IF NOT EXISTS `transport_notice` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '鍏�鍛婄紪鍙�',
  `title` varchar(128) NOT NULL DEFAULT '' COMMENT '鍏�鍛婃爣棰�',
  `content` text COMMENT '鍏�鍛婂唴瀹�',
  `status` tinyint NOT NULL DEFAULT 1 COMMENT '鐘舵�?0涓嬫灦 1涓婃灦)',
  `sort` int NOT NULL DEFAULT 0 COMMENT '鎺掑簭(灏忕殑鍦ㄥ墠)',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '绉熸埛缂栧彿',
  `creator` varchar(64) DEFAULT '' COMMENT '鍒涘缓鑰?,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '鍒涘缓鏃堕棿',
  `updater` varchar(64) DEFAULT '' COMMENT '鏇存柊鑰?,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '鏇存柊鏃堕棿',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '鏄�鍚﹀垹闄�',
  PRIMARY KEY (`id`),
  KEY `idx_notice_status_sort` (`tenant_id`, `status`, `sort`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='骞冲彴鍏�鍛婅�?;

-- ---------- 鎰忚�佸弽棣堣�?----------
CREATE TABLE IF NOT EXISTS `transport_feedback` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '鍙嶉�堢紪鍙�',
  `user_id` bigint NOT NULL DEFAULT 0 COMMENT '浼氬憳缂栧彿',
  `name` varchar(30) NOT NULL DEFAULT '' COMMENT '鑱旂郴浜哄�撳�?,
  `mobile` varchar(11) NOT NULL DEFAULT '' COMMENT '鑱旂郴鐢佃瘽',
  `content` varchar(500) NOT NULL DEFAULT '' COMMENT '鍙嶉�堝唴瀹�',
  `status` tinyint NOT NULL DEFAULT 0 COMMENT '鐘舵�?0寰呭�勭�?1宸插洖澶?',
  `reply` varchar(500) DEFAULT NULL COMMENT '鍥炲�嶅唴瀹�',
  `reply_time` datetime DEFAULT NULL COMMENT '鍥炲�嶆椂闂�',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '绉熸埛缂栧彿',
  `creator` varchar(64) DEFAULT '' COMMENT '鍒涘缓鑰?,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '鍒涘缓鏃堕棿',
  `updater` varchar(64) DEFAULT '' COMMENT '鏇存柊鑰?,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '鏇存柊鏃堕棿',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '鏄�鍚﹀垹闄�',
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='鎰忚�佸弽棣堣�?;

-- 宸叉湁搴撲汉宸ユ墽琛岋紙CREATE IF NOT EXISTS 涓嶄細缁欏凡鏈夎〃鍔犲垪锛屽崌绾ц�锋墽琛屼互涓� ALTER锛夛細
-- ALTER TABLE `transport_order`
--   ADD COLUMN `member_user_id` bigint NOT NULL DEFAULT 0 COMMENT '涓嬪崟浼氬憳缂栧彿(灏忕▼搴忓瘎璐?' AFTER `total_amount`,
--   ADD KEY `idx_transport_order_member` (`tenant_id`, `member_user_id`, `status`);
-- ALTER TABLE `transport_cargo_order`
--   ADD COLUMN `goods_name` varchar(128) NOT NULL DEFAULT '' COMMENT '璐х墿鍚嶇О' AFTER `volume_m3`,
--   ADD COLUMN `goods_note` varchar(255) NOT NULL DEFAULT '' COMMENT '璐х墿澶囨敞' AFTER `goods_name`,
--   ADD COLUMN `photo_url` varchar(255) NOT NULL DEFAULT '' COMMENT '璐х墿鐓х墖' AFTER `goods_note`,
--   ADD COLUMN `receiver_name` varchar(64) NOT NULL DEFAULT '' COMMENT '鏀惰揣浜? AFTER `photo_url`,
--   ADD COLUMN `receiver_mobile` varchar(32) NOT NULL DEFAULT '' COMMENT '鏀惰揣鐢佃瘽' AFTER `receiver_name`,
--   ADD COLUMN `receiver_address` varchar(255) NOT NULL DEFAULT '' COMMENT '鏀惰揣鍦板潃' AFTER `receiver_mobile`;
-- ALTER TABLE `transport_shift_execution`
--   ADD COLUMN `loaded_count` int NOT NULL DEFAULT 0 COMMENT '宸茶�呰溅浠舵�?琛屾潕鑸辫繍鍔?鍙?vehicle.cargo_capacity 绾︽潫)' AFTER `current_station_id`;

-- ========== 澶氭�佃仈杩愭�嗘灦锛圴018锛夛細杩愯緭娈?/ 璐х墿浜ゆ帴 / 璁㈠崟浜嬩欢 / 鐢ㄦ埛閫氱煡 / 鍙告満鐘舵�?==========
CREATE TABLE IF NOT EXISTS `transport_leg` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '杩愯緭娈电紪鍙?,
  `order_id` bigint NOT NULL COMMENT '杩愯緭璁㈠崟缂栧彿',
  `leg_sequence` int NOT NULL COMMENT '娈靛簭鍙凤紙1=绗�涓�娈碉紝2=绗�浜屾�碘�︼級',
  `from_station_id` bigint NOT NULL COMMENT '璧峰�嬬珯鐐圭紪鍙�',
  `to_station_id` bigint NOT NULL COMMENT '鐩�鐨勭珯鐐圭紪鍙�',
  `vehicle_id` bigint DEFAULT NULL COMMENT '鎵胯繍杞﹁締缂栧彿',
  `driver_id` bigint DEFAULT NULL COMMENT '鎵胯繍鍙告満缂栧彿',
  `shift_id` bigint DEFAULT NULL COMMENT '鎵胯繍鐝�娆＄紪鍙�',
  `plan_item_id` bigint DEFAULT NULL COMMENT '鍏宠仈璋冨害鏂规�堟槑缁嗙紪鍙�',
  `plan_id` bigint DEFAULT NULL COMMENT '鎵�灞炶繍杈撴柟妗堢紪鍙?,
  `route_id` bigint DEFAULT NULL COMMENT '鏈�娈垫壙杩愮嚎璺�缂栧彿',
  `status` tinyint NOT NULL DEFAULT 0 COMMENT '鐘舵�侊細0宸茶�勫�?1宸插垎閰?2鍙告満宸叉帴鍗?3绛夊緟鍙戣溅 4鍓嶅線璧风偣 5宸插埌杈捐捣鐐?6瑁呰揣涓?7杩愯緭涓?8宸插埌杈剧粓鐐?9浜ゆ帴涓?10娲鹃�佷腑 11宸插畬鎴?99寮傚父',
  `estimated_departure` datetime DEFAULT NULL COMMENT '棰勮�″嚭鍙戞椂闂�',
  `estimated_arrival` datetime DEFAULT NULL COMMENT '棰勮�″埌杈炬椂闂�',
  `actual_departure` datetime DEFAULT NULL COMMENT '瀹為檯鍑哄彂鏃堕棿',
  `actual_arrival` datetime DEFAULT NULL COMMENT '瀹為檯鍒拌揪鏃堕棿',
  `distance_km` decimal(12,2) DEFAULT NULL COMMENT '鏈�娈甸噷绋�(km)',
  `duration_minutes` int DEFAULT NULL COMMENT '鏈�娈甸�勮�¤�楁椂(鍒嗛挓)',
  `navigation_source` varchar(20) DEFAULT NULL COMMENT '瀵艰埅鏉ユ簮锛欰MAP/ESTIMATED/PROJECT',
  `navigation_polyline` text DEFAULT NULL COMMENT '瀵艰埅 polyline(JSON)',
  `cargo_count` int DEFAULT NULL COMMENT '鏈�娈佃揣鐗╀欢鏁�',
  `cargo_weight` decimal(12,2) DEFAULT NULL COMMENT '鏈�娈佃揣鐗╅噸閲�(kg)',
  `handover_required` bit(1) NOT NULL DEFAULT b'0' COMMENT '鏄�鍚﹂渶瑕佹崲涔樹氦鎺ワ紙闈炴渶缁堟�碉�?,
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '绉熸埛缂栧彿',
  `creator` varchar(64) DEFAULT '' COMMENT '鍒涘缓鑰?,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '鍒涘缓鏃堕棿',
  `updater` varchar(64) DEFAULT '' COMMENT '鏇存柊鑰?,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '鏇存柊鏃堕棿',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '鏄�鍚﹀垹闄�',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_leg_order_sequence` (`order_id`, `leg_sequence`, `tenant_id`),
  KEY `idx_leg_vehicle` (`tenant_id`, `vehicle_id`),
  KEY `idx_leg_driver` (`tenant_id`, `driver_id`),
  KEY `idx_leg_status` (`tenant_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='杩愯緭娈佃〃锛堟敮鎸佸�氭�佃仈杩愶級';

CREATE TABLE IF NOT EXISTS `transport_handover` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '浜ゆ帴缂栧彿',
  `order_id` bigint NOT NULL COMMENT '杩愯緭璁㈠崟缂栧彿',
  `plan_id` bigint DEFAULT NULL COMMENT '鎵�灞炶繍杈撴柟妗堢紪鍙?,
  `leg_from_id` bigint DEFAULT NULL COMMENT '鏉ユ簮杩愯緭娈电紪鍙?,
  `leg_to_id` bigint DEFAULT NULL COMMENT '鐩�鐨勮繍杈撴�电紪鍙?,
  `station_id` bigint NOT NULL COMMENT '浜ゆ帴绔欑偣缂栧彿',
  `from_driver_id` bigint DEFAULT NULL COMMENT '浜ゅ嚭鍙告満缂栧彿',
  `to_driver_id` bigint DEFAULT NULL COMMENT '鎺ユ敹鍙告満缂栧彿',
  `from_vehicle_id` bigint DEFAULT NULL COMMENT '浜ゅ嚭杞﹁締缂栧彿',
  `to_vehicle_id` bigint DEFAULT NULL COMMENT '鎺ユ敹杞﹁締缂栧彿',
  `item_count` int NOT NULL DEFAULT 0 COMMENT '浜ゆ帴浠舵暟',
  `weight_kg` decimal(12,2) DEFAULT NULL COMMENT '浜ゆ帴閲嶉噺(kg)',
  `cargo_volume_m3` decimal(12,4) DEFAULT NULL COMMENT '浜ゆ帴浣撶Н(m鲁)',
  `photo_url` varchar(255) DEFAULT '' COMMENT '浜ゆ帴鐓х墖URL',
  `status` tinyint NOT NULL DEFAULT 0 COMMENT '鐘舵�侊細0绛夊緟浜ゆ帴 1鍓嶅簭宸插埌杈?2鎺ユ敹鏂瑰緟鎺?3浜ゆ帴涓?4浜ゆ帴瀹屾垚 5浜ゆ帴瓒呮椂 6宸插彇娑?7寮傚父',
  `handover_time` datetime DEFAULT NULL COMMENT '浜ゆ帴鏃堕棿',
  `confirm_time` datetime DEFAULT NULL COMMENT '纭�璁ゆ椂闂�',
  `remark` varchar(255) DEFAULT '' COMMENT '澶囨敞',
  `arrived_at` datetime DEFAULT NULL COMMENT '鍓嶅簭鍙告満鍒拌揪鎹�涔樼珯鏃堕�?,
  `handover_started_at` datetime DEFAULT NULL COMMENT '寮�濮嬩氦鎺ユ椂闂?,
  `handover_completed_at` datetime DEFAULT NULL COMMENT '浜ゆ帴瀹屾垚鏃堕棿',
  `confirmed_by` bigint DEFAULT NULL COMMENT '纭�璁や汉锛堝徃鏈虹紪鍙凤�?,
  `exception_reason` varchar(255) DEFAULT NULL COMMENT '瓒呮椂/寮傚父鍘熷洜',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '绉熸埛缂栧彿',
  `creator` varchar(64) DEFAULT '' COMMENT '鍒涘缓鑰?,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '鍒涘缓鏃堕棿',
  `updater` varchar(64) DEFAULT '' COMMENT '鏇存柊鑰?,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '鏇存柊鏃堕棿',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '鏄�鍚﹀垹闄�',
  PRIMARY KEY (`id`),
  KEY `idx_handover_order` (`tenant_id`, `order_id`),
  KEY `idx_handover_station` (`tenant_id`, `station_id`),
  KEY `idx_handover_from_driver` (`tenant_id`, `from_driver_id`),
  KEY `idx_handover_to_driver` (`tenant_id`, `to_driver_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='璐х墿浜ゆ帴璁板綍琛?;

CREATE TABLE IF NOT EXISTS `transport_order_event` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '浜嬩欢缂栧彿',
  `order_id` bigint NOT NULL COMMENT '杩愯緭璁㈠崟缂栧彿',
  `event_type` varchar(64) NOT NULL COMMENT '浜嬩欢绫诲瀷',
  `event_time` datetime NOT NULL COMMENT '浜嬩欢鏃堕棿',
  `operator` varchar(64) DEFAULT '' COMMENT '鎿嶄綔浜猴紙绯荤粺/鍙告満ID/绠＄悊鍛樺悕锛?,
  `detail` varchar(500) DEFAULT '' COMMENT '浜嬩欢璇︽儏',
  `extra_data` text DEFAULT NULL COMMENT '鎵╁睍鏁版嵁JSON',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '绉熸埛缂栧彿',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '鍒涘缓鏃堕棿',
  PRIMARY KEY (`id`),
  KEY `idx_order_event_order` (`order_id`, `event_time`),
  KEY `idx_order_event_type` (`tenant_id`, `event_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='璁㈠崟浜嬩欢鏃堕棿绾胯〃';

CREATE TABLE IF NOT EXISTS `transport_user_notification` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '閫氱煡缂栧彿',
  `user_id` bigint NOT NULL COMMENT '鐢ㄦ埛缂栧彿锛堜細鍛業D锛?,
  `recipient_type` varchar(20) NOT NULL DEFAULT 'USER' COMMENT '鎺ユ敹鏂圭被鍨嬶細USER/DRIVER/ADMIN',
  `event_id` varchar(128) NOT NULL DEFAULT '' COMMENT '涓氬姟浜嬩欢鍞�涓�鏍囪瘑(骞傜瓑閿?',
  `event_type` varchar(64) NOT NULL COMMENT '浜嬩欢绫诲瀷',
  `title` varchar(128) NOT NULL COMMENT '閫氱煡鏍囬��',
  `content` varchar(500) NOT NULL DEFAULT '' COMMENT '閫氱煡鍐呭��',
  `order_id` bigint DEFAULT NULL COMMENT '鍏宠仈璁㈠崟缂栧彿',
  `plan_id` bigint DEFAULT NULL COMMENT '鎵�灞炴柟妗堢紪鍙?,
  `leg_id` bigint DEFAULT NULL COMMENT '鍏宠仈杩愯緭娈电紪鍙?,
  `level` varchar(20) NOT NULL DEFAULT 'INFO' COMMENT '绾у埆锛欼NFO/SUCCESS/ACTION_REQUIRED/WARNING/EXCEPTION',
  `action_required` bit(1) NOT NULL DEFAULT b'0' COMMENT '鏄�鍚﹂渶瑕佹帴鏀舵柟鎿嶄綔',
  `read_status` tinyint NOT NULL DEFAULT 0 COMMENT '闃呰�荤姸鎬侊細0鏈�璇� 1宸茶��',
  `read_time` datetime DEFAULT NULL COMMENT '闃呰�绘椂闂�',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '绉熸埛缂栧彿',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '鍒涘缓鏃堕棿',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '鏄�鍚﹀垹闄�',
  PRIMARY KEY (`id`),
  KEY `idx_notification_user` (`user_id`, `read_status`),
  KEY `idx_notification_order` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='鐢ㄦ埛閫氱煡琛?;

CREATE TABLE IF NOT EXISTS `transport_driver_status` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '鐘舵�佺紪鍙?,
  `driver_id` bigint NOT NULL COMMENT '鍙告満缂栧彿',
  `online_status` tinyint NOT NULL DEFAULT 0 COMMENT '鍦ㄧ嚎鐘舵�侊細0绂荤嚎 1鍦ㄧ嚎 2蹇欑��',
  `current_vehicle_id` bigint DEFAULT NULL COMMENT '褰撳墠缁戝畾杞﹁締缂栧彿',
  `current_plan_id` bigint DEFAULT NULL COMMENT '褰撳墠鎵ц�屾柟妗堢紪鍙�',
  `last_heartbeat` datetime DEFAULT NULL COMMENT '鏈�鍚庡績璺虫椂闂?,
  `last_latitude` decimal(10,7) DEFAULT NULL COMMENT '鏈�鍚庣含搴?,
  `last_longitude` decimal(10,7) DEFAULT NULL COMMENT '鏈�鍚庣粡搴?,
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '绉熸埛缂栧彿',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '鍒涘缓鏃堕棿',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '鏇存柊鏃堕棿',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_driver_status` (`driver_id`, `tenant_id`),
  KEY `idx_driver_status_online` (`tenant_id`, `online_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='鍙告満瀹炴椂鐘舵�佽〃';

-- 宸叉湁搴撲汉宸ユ墽琛岋紙CREATE IF NOT EXISTS 涓嶄細缁欏凡鏈夎〃鍔犲垪/绱㈠紩锛屽崌绾ц�锋墽琛屼互涓� ALTER锛夛細
-- ALTER TABLE `transport_station`
--   ADD COLUMN `parent_station_id` bigint DEFAULT NULL COMMENT '涓婄骇绔欑偣缂栧彿(绔欑偣灞傜骇鏍?' AFTER `address`,
--   ADD COLUMN `is_transfer_hub` bit(1) NOT NULL DEFAULT b'0' COMMENT '鏄�鍚︽崲涔樼�? AFTER `parent_station_id`;
-- ALTER TABLE `transport_order`
--   ADD COLUMN `leg_count` int NOT NULL DEFAULT 0 COMMENT '杩愯緭娈垫暟閲?0=鏈�瑙勫�?' AFTER `status`,
--   ADD COLUMN `current_leg_sequence` int NOT NULL DEFAULT 0 COMMENT '褰撳墠鎵ц�屽埌绗�鍑犳��' AFTER `leg_count`;
-- ALTER TABLE `transport_vehicle`
--   ADD COLUMN `realtime_status` tinyint NOT NULL DEFAULT 0 COMMENT '瀹炴椂鐘舵�侊細0绌洪棽 1鍦ㄩ�?2鏁呴殰 3绂荤嚎' AFTER `status`;
-- ALTER TABLE `transport_handover`
--   ADD UNIQUE KEY `uk_handover_leg` (`leg_from_id`, `leg_to_id`, `tenant_id`);

