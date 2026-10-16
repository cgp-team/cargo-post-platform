-- =====================================================================
-- V055 · AI 辅助任务（五个需要模型的口 + 一条规则版情景提示）的菜单与授权
--
-- 编号归属（契约 docs/AI_ASSIST_BOUNDARY.md §2 / §13.4）：迁移 V055 起、菜单 7150 起 —— 本脚本正是接手时
--   那份"V055 起仍空闲"登记的第一次使用。撞号由 `scripts/check_menu_id_collisions.py` 拦，不靠记性。
--
-- 为什么两个权限位而不是一个：
--   `transport:ai:scenario:query` 那一格是**规则算出来的**（读乡镇事件日历，不需要模型），
--   所以它必须能在 `buspost.ai.enabled=false` 时被授权 —— 若与"跑 AI"共用一个位，
--   "关掉 AI 整套后台仍完整可用"这条红线就只能靠人自觉，而自觉不是约束。
--   `transport:ai:task:run` 才是那五个需要端点的口。
--
-- 幂等写法沿用 V024/V028/V031/V032/V037：CREATE 不需要（本脚本不建表）+ 菜单按 **permission** 判存在
--   （id 是户口，permission 才是业务身份）+ 授权按存在性判重 ⇒ 可安全重复执行。
--
-- ⚠ 这份脚本不会自己跑到云上：云端执行只走 .github/workflows/db-migrate-guarded.yml，
--   mode=check 只读、mode=apply 需要人显式给 confirm=APPLY。
-- =====================================================================

INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component, component_name,
                         status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted)
SELECT 7150, 'AI 辅助任务', 'transport:ai:scenario:query', 2, 50, 7100, 'assist', 'ep:magic-stick',
       'transport/ai/assist/index', 'TransportAiAssist',
       0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE permission = 'transport:ai:scenario:query' AND deleted = b'0');

INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component, component_name,
                         status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted)
SELECT 7151, '跑一次 AI 辅助', 'transport:ai:task:run', 3, 1, 7150, '', '', '', NULL,
       0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE permission = 'transport:ai:task:run' AND deleted = b'0');

-- 授权：两个位都给超级管理员角色（role_id=1）。重复执行不会插出第二行（按存在性判重，不靠 INSERT IGNORE —— 那张表没有唯一键）。
-- 幂等授权：system_role_menu 没有 (role_id, menu_id) 唯一键，INSERT IGNORE 挡不住重复执行（guarded 的 apply 每次都重跑整份白名单）
INSERT INTO system_role_menu (role_id, menu_id)
SELECT 1, id FROM system_menu WHERE id IN (7150, 7151)
  AND NOT EXISTS (SELECT 1 FROM system_role_menu rm
                  WHERE rm.role_id = 1 AND rm.menu_id = system_menu.id AND rm.deleted = b'0');
