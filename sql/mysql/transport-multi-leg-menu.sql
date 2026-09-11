-- 多段联运相关菜单（货物交接管理 / 用户通知管理）+ 事件时间线权限
-- 执行：mysql -u root -p ruoyi-vue-pro < sql/mysql/transport-multi-leg-menu.sql
-- 幂等：INSERT IGNORE，可重复执行。
--
-- 菜单编号区间说明：6800-6919 已被客货邮基础菜单占用，6920/6922 为「开发者中心/模拟运营」
-- （见 transport-schema-incremental.sql），故本文件使用 6930-6933，避免编号冲突。

-- 用 ON DUPLICATE KEY UPDATE 而非 INSERT IGNORE：重复执行时修正名称/权限（历史误执行留下的占位名会被覆盖）
INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component, component_name, status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted) VALUES
-- 货物交接管理（多段联运换乘站交接记录）
(6930, '货物交接', 'transport:handover:query', 2, 15, 6800, 'handover', 'ep:switch', 'transport/handover/index', 'TransportHandover', 0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0'),
(6931, '交接争议处理', 'transport:handover:update', 3, 1, 6930, '', '', '', NULL, 0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0'),
-- 用户通知管理（订单事件通知 / 手动发送）
(6932, '用户通知', 'transport:notification:query', 2, 16, 6800, 'notification', 'ep:bell', 'transport/notification/index', 'TransportNotification', 0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0'),
(6933, '发送通知', 'transport:notification:send', 3, 1, 6932, '', '', '', NULL, 0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0'),
-- 运输拓扑（订单运输链可视化：分段 + 换乘交接 + 候选方案解释 + 时间线）
(6934, '运输拓扑', 'transport:topology:query', 2, 17, 6800, 'topology', 'ep:connection', 'transport/topology/index', 'TransportTopology', 0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0'),
(6935, '调度中心', 'transport:dispatch:query', 2, 18, 6800, 'dispatch-center', 'ep:monitor', 'transport/dispatch-center/index', 'TransportDispatchCenter', 0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0')
ON DUPLICATE KEY UPDATE
    name = VALUES(name),
    permission = VALUES(permission),
    type = VALUES(type),
    sort = VALUES(sort),
    parent_id = VALUES(parent_id),
    path = VALUES(path),
    icon = VALUES(icon),
    component = VALUES(component),
    component_name = VALUES(component_name),
    visible = VALUES(visible),
    deleted = b'0';

-- 将菜单分配给超级管理员角色
INSERT IGNORE INTO system_role_menu (role_id, menu_id) SELECT 1, id FROM system_menu WHERE id BETWEEN 6930 AND 6939;

SELECT id, name, permission, parent_id, path, component FROM system_menu WHERE id BETWEEN 6930 AND 6939;
