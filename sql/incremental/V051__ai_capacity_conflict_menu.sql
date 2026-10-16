-- =====================================================================
-- V051 · AI 辅助：客货空间冲突检视（队列 #93 / 契约 docs/AI_ASSIST_BOUNDARY.md §6 第 3 项）
--
-- 为什么不是 AI 的一部分：这是排班保存前的**确定性约束校验**——核定载客/载货 vs 预报量，
--   比大小就能出冲突点与缓冲建议，不需要模型。属于本线"不接 AI 也能交付"的第三块。
--
-- 编号归属（契约 §2）：迁移从 V050 起，菜单主键用 7100–7199。本脚本只加一个只读页 7110，
--   挂在 V050 已建的顶级目录「AI 辅助」(7100) 之下。
--
-- 幂等：菜单按 permission 判存在 + INSERT IGNORE，可反复执行。
-- ⚠ 本页不含任何写业务数据的按钮（契约 §0），所以只发 query 权限。
-- =====================================================================

INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component, component_name,
                         status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted)
SELECT 7110, '客货空间检视', 'transport:ai:capacity-conflict:query', 2, 2, 7100, 'capacity-conflict', 'ep:van',
       'transport/ai/capacity-conflict/index', 'TransportAiCapacityConflict',
       0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE permission = 'transport:ai:capacity-conflict:query' AND deleted = b'0');

-- 角色授权：与基线同一写法（INSERT IGNORE + 按 id 取）
INSERT IGNORE INTO system_role_menu (role_id, menu_id)
SELECT 1, id FROM system_menu WHERE id = 7110;
