-- =====================================================================
-- V050 · AI 辅助：顶级目录菜单（队列 #93 / 契约 docs/AI_ASSIST_BOUNDARY.md）
--
-- 为什么只剩菜单：原先 V050 还建了一张「乡镇事件日历」表，但主线已在 V037 落了
--   transport_town_event_calendar（带 recurrence），两条线各建一套同一业务对象 —— 第 14 本静态闸
--   scripts/check_business_table_single_owner.py（队列 #98）把那种形状判红。经拍板：**保留 transport 那份**，
--   本线不再建表。于是 V050 只留这个所有 AI 页共用的顶级目录「AI 辅助」(7100)。
--
-- 编号归属（契约 §2）：迁移从 V050 起，菜单主键用 7100–7199。
-- 幂等：菜单按 id 判存在 + INSERT IGNORE，可反复执行。
-- =====================================================================

-- 菜单：独立顶级目录「AI 辅助」(7100)。各 AI 页（7110/7120/7130/7140…）挂在它之下。
INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component, component_name,
                         status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted)
SELECT 7100, 'AI 辅助', '', 1, 21, 0, 'ai', 'ep:magic-stick',
       NULL, NULL, 0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE id = 7100);

-- 角色授权：与基线同一写法（INSERT IGNORE + 按 id 取）
INSERT IGNORE INTO system_role_menu (role_id, menu_id)
SELECT 1, id FROM system_menu WHERE id = 7100;
