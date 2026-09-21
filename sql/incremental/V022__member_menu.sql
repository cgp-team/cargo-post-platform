-- ---------- V022：会员中心菜单（顶级目录 + 会员列表 + 查询按钮） ----------
-- 幂等：INSERT ... SELECT ... WHERE NOT EXISTS；授权用 INSERT IGNORE。

-- 1. 顶级目录「会员中心」（path /member，目录无组件，侧边栏可见）
INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon,
    component, component_name, status, visible, keep_alive, always_show,
    creator, create_time, updater, update_time, deleted)
SELECT 7000, '会员中心', '', 1, 20, 0, 'member', 'ep:user',
    NULL, NULL, 0, b'1', b'1', b'1',
    '1', NOW(), '1', NOW(), b'0'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE id = 7000);

-- 2. 子菜单「会员列表」（path user，component=member/user/index，权限 member:user:query）
INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon,
    component, component_name, status, visible, keep_alive, always_show,
    creator, create_time, updater, update_time, deleted)
SELECT 7001, '会员列表', 'member:user:query', 2, 1, 7000, 'user', 'ep:user',
    'member/user/index', 'MemberUser', 0, b'1', b'1', b'1',
    '1', NOW(), '1', NOW(), b'0'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE id = 7001);

-- 3. 查询按钮权限
INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon,
    component, component_name, status, visible, keep_alive, always_show,
    creator, create_time, updater, update_time, deleted)
SELECT 7002, '会员查询', 'member:user:query', 3, 1, 7001, '', '',
    '', NULL, 0, b'1', b'1', b'1',
    '1', NOW(), '1', NOW(), b'0'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE id = 7002);

-- 4. 授权给超级管理员角色（与 transport-menu.sql 末尾授权写法一致：role_id=1）
INSERT IGNORE INTO system_role_menu (role_id, menu_id) VALUES (1, 7000);
INSERT IGNORE INTO system_role_menu (role_id, menu_id) VALUES (1, 7001);
INSERT IGNORE INTO system_role_menu (role_id, menu_id) VALUES (1, 7002);
