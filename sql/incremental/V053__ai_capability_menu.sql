-- =====================================================================
-- V053 · AI 辅助：能力状态页（队列 #93 / 契约 docs/AI_ASSIST_BOUNDARY.md §7）
--
-- 为什么先做它：契约 §11.1 说得很直白——"没有推理端点之前，不要写任何会显示'AI 生成中'的界面"，
--   所以第一块该落地的是**开关 + 状态页**：没有端点时它如实说"能力未就绪"，绝不假装。
--   开关与端点是服务端配置（buspost.ai.enabled / base-url），本脚本只负责一个只读页面入口。
--
-- 编号归属（契约 §2）：迁移从 V050 起，菜单主键用 7100–7199。本脚本加只读页 7130，挂在 7100 之下。
-- 幂等：菜单按 permission 判存在 + INSERT IGNORE。
-- =====================================================================

INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component, component_name,
                         status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted)
SELECT 7130, '能力状态', 'transport:ai:capability:query', 2, 4, 7100, 'capability', 'ep:connection',
       'transport/ai/capability/index', 'TransportAiCapability',
       0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE permission = 'transport:ai:capability:query' AND deleted = b'0');

-- 角色授权：与基线同一写法（INSERT IGNORE + 按 id 取）
INSERT IGNORE INTO system_role_menu (role_id, menu_id)
SELECT 1, id FROM system_menu WHERE id IN (7130);
