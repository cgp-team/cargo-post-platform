-- =====================================================================
-- V032 · 运输计价规则后台菜单（单行配置页）
--
-- 为什么需要这一份：`transport_pricing_rule` 表与 `PricingRuleService` 早就在了
--   （`CargoPricingService` / `DispatchEstimationService` 都在读它），但后台**没有任何页面**能改它
--   ⇒ 改运价 = 直接改库。这一页把"读当前生效值 + 保存"接上。
--
-- 写法沿用 V024/V025/V030/V031 的规矩：`WHERE NOT EXISTS` + `INSERT IGNORE`，可安全重复执行。
--
-- ⚠ 主键从 6930/6931 挪到 **6940/6941**（2026-10-16，台账 D-127）：基线种子 `sql/mysql/transport-multi-leg-menu.sql`
--   自己就占着 6930-6935（6930=货物交接、6931=交接争议处理…），而它的注释写着"本文件使用 6930-6933，避免编号冲突"
--   —— 那句"避免冲突"恰恰是冲突本身：它躲开了当时已用的 6920，却没躲后来才写的 V032。
--   两半各自都幂等，撞在一起的结果是**先落地的赢**：云上基线菜单先建，于是这一页的
--   `WHERE NOT EXISTS(id=6930)` 永远为假 ⇒ 计价规则菜单一条都没插进去，页面在云上永远不出现，
--   而 CI 一片绿（没有报错，只有"少了东西"）。这一类"两个文件都自认唯一"的编号，只能靠
--   `grep -rhoE '\(69[0-9][0-9],' sql/ | sort -u` 当场量，不能靠记性。
--   已经跑过旧版 V032 的库由 `V035__pricing_rule_menu_free_ids.sql` 补上。
--   守卫也一并从"按 id 判存在"改成**按 permission 判存在**：id 是本页的户口，permission 才是业务身份
--   —— 拿 id 当身份，id 一改就会把"已经装过"误判成"没装过"，第二次装就多出重复菜单。
--   另有一条规矩别破：基线 `sql/mysql/transport-menu.sql` 是裸 `INSERT ... VALUES`（固定主键），
--   重放第二次必然主键冲突 ⇒ 新增菜单只写 incremental 脚本，不往基线大 SQL 里追加。
--
-- 执行入口（2026-10-16 起）：`deploy-dev.yml` 的迁移步骤默认不写库（仓库变量 AUTO_DB_MIGRATE 不为 1 就整段跳过），
--   本文件与 V035 都在 `.github/workflows/db-migrate-guarded.yml` 的 apply 白名单里；
--   没执行的表现是"菜单里没有这一项"，不是报错（表 `transport_pricing_rule` 在基线里已建，
--   所以不像 V028/V031 那样依赖未执行的建表脚本）—— 少东西比红难发现，所以 guarded 的 `mode=check`
--   与落地核对都把这一页的数量（2/2）当成必须读出来的数。
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

INSERT IGNORE INTO system_role_menu (role_id, menu_id)
SELECT 1, id FROM system_menu WHERE id IN (6940, 6941);
