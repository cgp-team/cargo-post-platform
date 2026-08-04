-- ============================================
-- 客货邮模块 - 菜单与会员表初始化 SQL
-- 在云服务器 ruoyi-vue-pro 数据库执行：
--   mysql -u root -p ruoyi-vue-pro < transport-menu.sql
-- ============================================

-- ---------- member_user 表（小程序登录必需）----------
CREATE TABLE IF NOT EXISTS `member_user` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `nickname` varchar(30) NOT NULL DEFAULT '' COMMENT '用户昵称',
  `name` varchar(30) NULL DEFAULT NULL COMMENT '真实名字',
  `sex` tinyint NULL DEFAULT NULL COMMENT '性别',
  `birthday` datetime NULL DEFAULT NULL COMMENT '出生日期',
  `area_id` int NULL DEFAULT NULL COMMENT '所在地',
  `mark` varchar(255) NULL DEFAULT NULL COMMENT '用户备注',
  `point` int NULL DEFAULT 0 COMMENT '积分',
  `avatar` varchar(255) NOT NULL DEFAULT '' COMMENT '头像',
  `status` tinyint NOT NULL DEFAULT 0 COMMENT '状态',
  `mobile` varchar(11) NOT NULL COMMENT '手机号',
  `email` varchar(50) NULL DEFAULT NULL COMMENT '邮箱',
  `password` varchar(100) NOT NULL DEFAULT '' COMMENT '密码',
  `register_ip` varchar(32) NOT NULL DEFAULT '' COMMENT '注册IP',
  `register_terminal` int NULL DEFAULT NULL COMMENT '注册终端',
  `login_ip` varchar(50) NULL DEFAULT '' COMMENT '最后登录IP',
  `login_date` datetime NULL DEFAULT NULL COMMENT '最后登录时间',
  `tag_ids` varchar(255) NULL DEFAULT NULL COMMENT '标签列表',
  `level_id` bigint NULL DEFAULT NULL COMMENT '等级编号',
  `experience` bigint NULL DEFAULT NULL COMMENT '经验',
  `group_id` bigint NULL DEFAULT NULL COMMENT '用户分组编号',
  `creator` varchar(64) NULL DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) NULL DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_mobile` (`mobile`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='会员表';

-- ---------- 客货邮管理菜单 ----------
DELETE FROM system_role_menu WHERE menu_id BETWEEN 6800 AND 6844;
DELETE FROM system_menu WHERE id BETWEEN 6800 AND 6844;

INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component, component_name, status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted) VALUES
-- Root directory
(6800, '客货邮管理', '', 1, 50, 0, '/transport', 'ep:ship', NULL, NULL, 0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0'),
-- Dashboard
(6801, '运营概览', 'transport:dashboard:query', 2, 1, 6800, 'dashboard', 'ep:data-analysis', 'transport/dashboard/index', 'TransportDashboard', 0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0'),
-- Station
(6810, '站点管理', '', 1, 2, 6800, 'station', 'ep:location', NULL, NULL, 0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0'),
(6811, '站点列表', 'transport:station:query', 2, 1, 6810, 'list', '', 'transport/station/index', 'TransportStation', 0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0'),
(6812, '站点新增', 'transport:station:create', 3, 2, 6811, '', '', '', NULL, 0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0'),
(6813, '站点编辑', 'transport:station:update', 3, 3, 6811, '', '', '', NULL, 0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0'),
(6814, '站点删除', 'transport:station:delete', 3, 4, 6811, '', '', '', NULL, 0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0'),
-- Vehicle
(6820, '车辆管理', '', 1, 3, 6800, 'vehicle', 'ep:van', NULL, NULL, 0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0'),
(6821, '车辆列表', 'transport:vehicle:query', 2, 1, 6820, 'list', '', 'transport/vehicle/index', 'TransportVehicle', 0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0'),
(6822, '车辆新增', 'transport:vehicle:create', 3, 2, 6821, '', '', '', NULL, 0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0'),
(6823, '车辆编辑', 'transport:vehicle:update', 3, 3, 6821, '', '', '', NULL, 0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0'),
(6824, '车辆删除', 'transport:vehicle:delete', 3, 4, 6821, '', '', '', NULL, 0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0'),
-- Route
(6830, '线路管理', '', 1, 4, 6800, 'route', 'ep:guide', NULL, NULL, 0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0'),
(6831, '线路列表', 'transport:route:query', 2, 1, 6830, 'list', '', 'transport/route/index', 'TransportRoute', 0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0'),
(6832, '线路新增', 'transport:route:create', 3, 2, 6831, '', '', '', NULL, 0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0'),
(6833, '线路编辑', 'transport:route:update', 3, 3, 6831, '', '', '', NULL, 0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0'),
(6834, '线路删除', 'transport:route:delete', 3, 4, 6831, '', '', '', NULL, 0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0'),
-- Driver
(6840, '司机管理', '', 1, 5, 6800, 'driver', 'ep:user', NULL, NULL, 0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0'),
(6841, '司机列表', 'transport:driver:query', 2, 1, 6840, 'list', '', 'transport/driver/index', 'TransportDriver', 0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0'),
(6842, '司机新增', 'transport:driver:create', 3, 2, 6841, '', '', '', NULL, 0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0'),
(6843, '司机编辑', 'transport:driver:update', 3, 3, 6841, '', '', '', NULL, 0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0'),
(6844, '司机删除', 'transport:driver:delete', 3, 4, 6841, '', '', '', NULL, 0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0');

-- 将菜单分配给超级管理员角色
INSERT IGNORE INTO system_role_menu (role_id, menu_id) SELECT 1, id FROM system_menu WHERE id BETWEEN 6800 AND 6844;
