-- =====================================================================
-- V057 · 后台导航集成：30 个页面叶子收成 8 个入口（队列 #104 / #105）
--
-- 要解决的事：值班员面对的是 `/transport` 下 24 个叶子 + 11 个目录、`/ai` 下 5 个叶子，
--   找一格的成本比看一格的成本高。前端加了 7 个工作台壳（`transport/capacity`、`dispatch-desk`、
--   `live-monitor`、`freight`、`communication`、`rules-accounts`、`ai/workbench`），
--   每格挂的还是原来那个页面组件本身，取数、表单、写口、权限位一行没改。
--
-- 三条口径，都是实测出来的，不是偏好：
-- 1) **旧叶不删、只 `visible = b'0'`**。
--    `src/utils/routerHelper.ts:92` 是 `hidden: !route.visible` —— 隐藏只撤侧边栏入口，
--    路由照样生成，所以 `/transport/station/list` 这类既有深链一条都不会断；
--    回退也不需要重放脚本，一条 `UPDATE ... SET visible = b'1'` 就回到合并前。
--    （形状沿用 `sql/mysql/cleanup-upstream-menus.sql:44`，那是仓里唯一的"停用而不删"先例。）
-- 2) **被抽空的目录必须一起隐藏**。
--    `layout/components/Menu/src/helper.ts:43`：可见子节点为 0 时它合成一个
--    `onlyOneChild = {...parent, noShowingChildren: true}`，于是这一档**渲染成可点的单条**，
--    点过去是 `type=1 / component=NULL` 的空 Layout —— 正是队列 #63 判红过的那个形状。
--    只藏叶子不藏目录，等于把"六个按钮六个空"换成"十一个侧栏条目点了都白屏"。
-- 3) **新叶的 permission 一律留空，授权从被折叠的子叶去重继承**。
--    工作台只是容器，读权限还在各格自己的 `checkPermi` 上、写权限还在各页自己的 `v-hasPermi` 上；
--    若给容器编一个新权限位，就得给所有角色补发一次，那个账号能看见的格反而和合并前不一致。
--    从子叶继承 ⇒ **没有人因为这次合并多权或少权**（本脚本第 2 段有对账探针）。
--
-- 编号归属：迁移 V057、菜单 7160–7166（`docs/AI_ASSIST_BOUNDARY.md` 登记过"V056 之后、7160 起仍空闲"，
--   本脚本是第一次使用；撞号由 `scripts/check_menu_id_collisions.py` 拦，不靠记性）。
-- 幂等：菜单按 **id** 判存在（这些行的 permission 是空的，按 permission 判会撞所有空权限行）；
--   授权按 (role_id, menu_id) 存在性判重；visible 是 UPDATE，天然可重跑。
--
-- ⚠ 这份脚本不会自己跑到云上：云端执行只走 .github/workflows/db-migrate-guarded.yml，
--   mode=check 只读、mode=apply 需要人显式给 confirm=APPLY。
-- =====================================================================

-- ---------- 1 · 七个工作台叶 ----------
INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component, component_name,
                         status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted)
SELECT 7160, '运力资源', '', 2, 2, 6800, 'capacity', 'ep:van',
       'transport/capacity/index', 'TransportCapacityWorkbench',
       0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE id = 7160);

INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component, component_name,
                         status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted)
SELECT 7161, '调度', '', 2, 3, 6800, 'dispatch-desk', 'ep:guide',
       'transport/dispatch-desk/index', 'TransportDispatchWorkbench',
       0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE id = 7161);

INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component, component_name,
                         status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted)
SELECT 7162, '在途监控', '', 2, 4, 6800, 'live-monitor', 'ep:map-location',
       'transport/live-monitor/index', 'TransportMonitorWorkbench',
       0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE id = 7162);

INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component, component_name,
                         status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted)
SELECT 7163, '货运与商品', '', 2, 5, 6800, 'freight', 'ep:document',
       'transport/freight/index', 'TransportFreightWorkbench',
       0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE id = 7163);

INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component, component_name,
                         status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted)
SELECT 7164, '通知与反馈', '', 2, 6, 6800, 'communication', 'ep:bell',
       'transport/communication/index', 'TransportMessageWorkbench',
       0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE id = 7164);

INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component, component_name,
                         status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted)
SELECT 7165, '规则与账务', '', 2, 7, 6800, 'rules-accounts', 'ep:setting',
       'transport/rules-accounts/index', 'TransportRulesWorkbench',
       0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE id = 7165);

INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component, component_name,
                         status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted)
SELECT 7166, 'AI 工作台', '', 2, 0, 7100, 'workbench', 'ep:magic-stick',
       'transport/ai/workbench/index', 'TransportAiWorkbench',
       0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE id = 7166);

-- ---------- 2 · 授权从被折叠的子叶去重继承（谁原来能进这些页，谁就能进新工作台）----------
-- 派生表包一层：NOT EXISTS 里直接引用 `system_role_menu` 会和外层 INSERT 撞同一张表，
-- 这个形状和 V056 的改名语句是一致的（那支脚本已在云上跑过一遍）。
INSERT INTO system_role_menu (role_id, menu_id)
SELECT s.role_id, s.menu_id
FROM (
         SELECT rm.role_id, 7160 AS menu_id FROM system_role_menu rm WHERE rm.menu_id IN (6811, 6821, 6831, 6841, 6845, 6861, 6914) AND rm.deleted = b'0'
 UNION SELECT rm.role_id, 7161 FROM system_role_menu rm WHERE rm.menu_id IN (6871, 6935, 6934) AND rm.deleted = b'0'
 UNION SELECT rm.role_id, 7162 FROM system_role_menu rm WHERE rm.menu_id IN (6878, 6879) AND rm.deleted = b'0'
 UNION SELECT rm.role_id, 7163 FROM system_role_menu rm WHERE rm.menu_id IN (6851, 6891, 6881, 6930) AND rm.deleted = b'0'
 UNION SELECT rm.role_id, 7164 FROM system_role_menu rm WHERE rm.menu_id IN (6910, 6932, 6901) AND rm.deleted = b'0'
 UNION SELECT rm.role_id, 7165 FROM system_role_menu rm WHERE rm.menu_id IN (6940, 6950, 6920) AND rm.deleted = b'0'
 UNION SELECT rm.role_id, 7166 FROM system_role_menu rm WHERE rm.menu_id IN (7110, 7120, 7130, 7140, 7150) AND rm.deleted = b'0'
     ) s
WHERE NOT EXISTS (
    SELECT 1 FROM (SELECT r2.role_id, r2.menu_id FROM system_role_menu r2 WHERE r2.deleted = b'0') had
    WHERE had.role_id = s.role_id AND had.menu_id = s.menu_id
);

-- ---------- 3 · 收起：被折叠的 27 个叶子 ----------
UPDATE system_menu
SET visible = b'0', updater = '1', update_time = NOW()
WHERE deleted = b'0'
  AND id IN (6811, 6821, 6831, 6841, 6845, 6861, 6914,
             6871, 6935, 6934,
             6878, 6879,
             6851, 6891, 6881, 6930,
             6910, 6932, 6901,
             6940, 6950, 6920,
             7110, 7120, 7130, 7140, 7150);

-- ---------- 4 · 收起：被抽空的 11 个目录（不藏就会留下十一个点了白屏的单条，见头部第 2 条）----------
UPDATE system_menu
SET visible = b'0', updater = '1', update_time = NOW()
WHERE deleted = b'0'
  AND type = 1
  AND id IN (6810, 6820, 6830, 6840, 6850, 6860, 6870, 6877, 6880, 6890, 6900);
