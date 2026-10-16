-- =====================================================================
-- V035 · 把「计价规则」菜单补到空闲主键上（给已经跑过旧版 V032 的库）
--
-- 要修的事实（台账 D-127，队列 #70）：旧版 V032 用 6930/6931，而基线种子
--   `sql/mysql/transport-multi-leg-menu.sql` 占了 6930-6935（6930=货物交接、6931=交接争议处理）。
--   两边都是幂等写法，撞号的结果是**先落地的赢**：菜单基线先建 ⇒ V032 的
--   `WHERE NOT EXISTS(id=6930)` 永远为假 ⇒ 计价规则一条都没插进去。
--   症状不是报错，是"后台没有这一页"，而 CI 全绿 —— 少东西比红难发现得多。
--
-- 为什么不 UPDATE/DELETE 把旧行搬走：这个库里**根本没有旧行**（那条 INSERT 从来没生效）。
--   在极小概率"V032 先跑过"的库里，计价规则会占着 6930/6931 —— 那种情况下本文件按 permission
--   判存在，什么都不会插入（不会造出第二个计价规则页），要处理的是**反过来少掉的交接菜单**，
--   那是重放 `transport-multi-leg-menu.sql` 的事，不在本文件里偷偷做。
--   所以：这里只补"缺的那一页"，不搬、不删、不猜。
--
-- 幂等与自检：与 V024/V025/V030/V031/V032 同一套（`WHERE NOT EXISTS` + `INSERT IGNORE`），
--   可反复执行；末尾的自检必须读出 2 行，读不满就是没装上 —— 与 V030 的"末尾 3/3 自检"同一条纪律。
--   ⚠ 主键 6940/6941 是当场量出来的空段，不是挑的吉利数：
--   `grep -rhoE '\(69[0-9][0-9],' sql/ | sort -u` 现读 6900-6902 / 6910-6915 / 6920 / 6930-6935，6940 起为空。
-- =====================================================================

INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component, component_name,
                         status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted)
SELECT 6940, '计价规则', 'transport:pricing-rule:query', 2, 15, 6800, 'pricing-rule', 'ep:money',
       'transport/pricingRule/index', 'TransportPricingRule',
       0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE permission = 'transport:pricing-rule:query' AND deleted = b'0');

INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component, component_name,
                         status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted)
SELECT 6941, '计价规则保存', 'transport:pricing-rule:save', 3, 1, 6940, '', '', '', NULL,
       0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE permission = 'transport:pricing-rule:save' AND deleted = b'0');

-- 角色授权：与基线同一写法（`transport-menu.sql` 用的就是 INSERT IGNORE + 按 id 区间 SELECT）。
-- 这里按 permission 取 id 而不是写死 6940/6941：万一本库走的是"V032 先跑"那一支（菜单在 6930/6931），
-- 授权仍然落在真正存在的那两行上，不会给一个不存在的菜单号发权限。
INSERT IGNORE INTO system_role_menu (role_id, menu_id)
SELECT 1, id FROM system_menu
WHERE permission IN ('transport:pricing-rule:query', 'transport:pricing-rule:save') AND deleted = b'0';

-- 自检：必须是 2（两条都在），少一条就是这一页还是没装上
SELECT COUNT(*) AS pricing_rule_menus_should_be_2
FROM system_menu
WHERE permission IN ('transport:pricing-rule:query', 'transport:pricing-rule:save') AND deleted = b'0';

-- 顺手把撞号本身也查出来：这两行必须都不存在，存在就说明 6940/6941 又被人占了
SELECT id, name, permission FROM system_menu
WHERE id IN (6940, 6941)
  AND permission NOT IN ('transport:pricing-rule:query', 'transport:pricing-rule:save');
