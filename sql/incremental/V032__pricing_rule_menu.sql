-- =====================================================================
-- V032 · 运输计价规则后台菜单（单行配置页）
--
-- 为什么需要这一份：`transport_pricing_rule` 表与 `PricingRuleService` 早就在了
--   （`CargoPricingService` / `DispatchEstimationService` 都在读它），但后台**没有任何页面**能改它
--   ⇒ 改运价 = 直接改库。这一页把"读当前生效值 + 保存"接上。
--
-- 写法沿用 V024/V025/V030/V031 的规矩：`WHERE NOT EXISTS` + `INSERT IGNORE`，可安全重复执行。
--   基线 `sql/mysql/transport-menu.sql` 是裸 `INSERT ... VALUES`（固定主键），
--   而 `deploy-dev.yml` 每次 push master 无条件重放白名单脚本 ⇒ 往基线追加会让第二次部署红在迁移步。
--
-- ⚠ 部署前置：本文件**不在** `deploy-dev.yml` 的迁移白名单里（白名单现读 V018/V019/V021/V022/V024/V025）
--   ⇒ 加进白名单之前，这一页要生效需人工执行一次；不执行的表现是"菜单里没有这一项"，不是报错
--     （表 `transport_pricing_rule` 在基线里已建，所以不像 V028/V031 那样依赖未执行的建表脚本）。
-- =====================================================================

INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component, component_name,
                         status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted)
SELECT 6930, '计价规则', 'transport:pricing-rule:query', 2, 15, 6800, 'pricing-rule', 'ep:money',
       'transport/pricingRule/index', 'TransportPricingRule',
       0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE id = 6930);

INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component, component_name,
                         status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted)
SELECT 6931, '计价规则保存', 'transport:pricing-rule:save', 3, 1, 6930, '', '', '', NULL,
       0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE id = 6931);

INSERT IGNORE INTO system_role_menu (role_id, menu_id)
SELECT 1, id FROM system_menu WHERE id IN (6930, 6931);
