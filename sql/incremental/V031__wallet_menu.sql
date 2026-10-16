-- =====================================================================
-- V031 · 用户钱包后台菜单（只读那一页）
--
-- 为什么单独一个文件而不是往 `sql/mysql/transport-menu.sql` 里加一行：
--   那份基线文件用的是**裸 `INSERT INTO system_menu ... VALUES`**（`transport-menu.sql:130-146`），
--   固定主键重复执行会 `Duplicate entry`；而白名单脚本会被反复重放（`deploy-dev.yml` 在仓库变量
--   `AUTO_DB_MIGRATE=1` 时整段重放，`db-migrate-guarded.yml` 每次 apply 也重跑）
--   ⇒ 追加进基线文件等于把"第二次部署"变成一次红。这里用 `WHERE NOT EXISTS` 写成可安全重复执行的形态
--   （沿用 V024/V025/V030 的规矩：**进白名单的脚本必须能重跑**）。
--
-- ⚠ 两条部署前置，都不在本文件能解决的范围里：
--   1) 本文件**目前不在** `deploy-dev.yml` 的迁移白名单里（白名单现读 V018/V019/V021/V022/V024/V025）
--      ⇒ 加进去之前，菜单要生效需要人工执行一次；
--   2) 这一页依赖 `transport_wallet_account` / `transport_wallet_transaction` 两张表，
--      它们来自 `V028__wallet_tables.sql` —— **同样不在白名单里**（台账 D-086 已登记）。
--      所以"云上打开这一页能看到数据"这一格，在 V028 + V031 都被执行之前不成立，界面会直接报错而不是显示空表。
-- =====================================================================

INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component, component_name,
                         status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted)
SELECT 6920, '钱包查询', 'transport:wallet:query', 2, 14, 6800, 'wallet', 'ep:wallet',
       'transport/wallet/index', 'TransportWallet',
       0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE id = 6920);

-- 角色授权：基线（`transport-menu.sql:159-160`）用 INSERT IGNORE，那张表没唯一键挡不住重复 ⇒ 改按存在性判重
-- 幂等授权：system_role_menu 没有 (role_id, menu_id) 唯一键，INSERT IGNORE 挡不住重复执行（guarded 的 apply 每次都重跑整份白名单）
INSERT INTO system_role_menu (role_id, menu_id)
SELECT 1, id FROM system_menu WHERE id = 6920
  AND NOT EXISTS (SELECT 1 FROM system_role_menu rm
                  WHERE rm.role_id = 1 AND rm.menu_id = system_menu.id AND rm.deleted = b'0');

-- ⚠ 这里**不**给 `transport:wallet:adjust`（改钱那个口）配菜单/按钮：
--   `WalletController.adjustBalance` 没有幂等键，且 amount=0 时什么都不做也返回"调整成功"（台账 D-093）。
--   补齐那两条之前，后台不提供这个操作，页面也不该出现一个能点到它的入口。
