-- V016：模拟运营控制菜单（挂在「车辆监控」组下，权限复用 transport:dispatch:smart-plan）。
-- 幂等：已存在则跳过；不存在才插入。
INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component, component_name, status, visible, keep_alive, creator, create_time, updater, update_time, deleted)
SELECT 6915, '模拟运营', 'transport:dispatch:smart-plan', 2, 3, 6877, 'simulation', 'ep:video-play', 'transport/simulation/index', 'TransportSimulation', 0, b'1', b'1', '1', NOW(), '1', NOW(), b'0'
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE id = 6915);
