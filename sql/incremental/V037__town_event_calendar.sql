-- =====================================================================
-- V037 · 乡镇事件日历（赶集 / 农忙 / 节假日 / 活动）
--
-- 为什么要这张表（队列 #93 / 契约 docs/AI_ASSIST_BOUNDARY.md §6 第 2 项）：
--   "赶集日提前留货舱"这类情景提示，卡点从来不是会不会推演，而是**平台里没有乡镇活动这条时间轴**。
--   现状只有订单历史，而订单是滞后量 —— 乡镇通常是"先赶集、才产生寄件"，等系统看见订单再准备已经晚了。
--   没有这张表，任何"情景感知"的功能都是无源之水；有了它，连模型都不需要，规则就能出提示。
--
-- 为什么带 recurrence 而不是只存一条条日期：
--   县域场期是周期的（多数场镇逢 1/4/7 或 2/5/8 日赶场）。若只能存单日，运营要替每个镇一年填几十行，
--   这张表一定长不出来 ⇒ 填不出来的表等于没有表。所以给一行"每 N 天一轮"的规则，
--   区间用 start_date/end_date 圈住季末（农忙类尤其需要）。
--   ⚠ 只做这两种（NONE / EVERY_N_DAYS）：农历、赶集网爬取这类都先不做 —— 前者要引历法依赖，
--   后者是 §8 明写的合规坑（不抓外网）。
--
-- 写法沿用 V024/V025/V028/V031/V032 的规矩：`CREATE TABLE IF NOT EXISTS` +
--   菜单按 **permission** 判存在（id 是户口，permission 才是业务身份）+ 授权按存在性判重，可安全重复执行。
--   新增菜单只写 incremental 脚本，不往基线大 SQL 里追加（基线是裸 INSERT VALUES，重放必撞主键）。
--
-- 主键用 6950/6951/6952：现读 6xxx 段已占到 6941（`grep -rhoE '\b6[0-9]{3}\b' sql | sort -un`）。
--   撞号由 `scripts/check_menu_id_collisions.py` 拦（K1–K7 七把刀，已挂 CI），不靠记性。
--
-- ⚠ 这份脚本**不会自己跑到云上**：云端执行走 `.github/workflows/db-migrate-guarded.yml`，
--   `mode=check` 只读、`mode=apply` 需要人显式给 `confirm=APPLY`。
-- =====================================================================

CREATE TABLE IF NOT EXISTS `transport_town_event_calendar`
(
    `id`            bigint      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `town`          varchar(64) NOT NULL COMMENT '乡镇/场镇名（本期是自由文本，不与站点建外键）',
    `event_type`    varchar(32) NOT NULL COMMENT '类型：MARKET 集市 / FARMING 农忙 / HOLIDAY 节假日 / EVENT 活动',
    `start_date`    date        NOT NULL COMMENT '起始日（含）',
    `end_date`      date        NOT NULL COMMENT '结束日（含）；单日事件与 start_date 相同',
    `recurrence`    varchar(16) NOT NULL DEFAULT 'NONE' COMMENT '重复规则：NONE 单次 / EVERY_N_DAYS 每 N 天一轮',
    `interval_days` int         NOT NULL DEFAULT 0 COMMENT 'recurrence=EVERY_N_DAYS 时的间隔天数，其余为 0',
    `note`          varchar(255) DEFAULT NULL COMMENT '备注（哪个场镇、什么货为主）',
    `tenant_id`     bigint      NOT NULL DEFAULT 0 COMMENT '租户编号',
    `creator`       varchar(64)  DEFAULT '' COMMENT '创建者',
    `create_time`   datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`       varchar(64)  DEFAULT '' COMMENT '更新者',
    `update_time`   datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`       bit(1)      NOT NULL DEFAULT b'0' COMMENT '是否删除',
    PRIMARY KEY (`id`),
    KEY `idx_town_start` (`town`, `start_date`),
    KEY `idx_type_date` (`event_type`, `start_date`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='乡镇事件日历（赶集/农忙/节假日/活动）';

-- 日期区间与"同镇同日同类型重复登记"都由服务层挡（TownEventCalendarServiceImpl）。
-- ⚠ 这里**故意不加唯一索引**：本表是软删（deleted bit），`UNIQUE(town, type, start_date, deleted)`
--   在"建一条 → 删掉 → 再建同一条 → 再删"的第二轮删除上会撞键（两行 deleted=1 同键），
--   而那次撞键报出来是 update 失败，看不出根因。唯一约束要与软删共存就得把 deleted 换成
--   delete-time 进键，那是另一套规矩；本期先用服务层 + 普通索引，把这条坑写在这儿而不是留给下一个人踩。
-- 幂等建索引：apply 每次重跑整份白名单，裸 CREATE INDEX 第二遍必报 1061 Duplicate key name
SET @idx_exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'transport_town_event_calendar' AND INDEX_NAME = 'idx_town_lookup');
SET @ddl := IF(@idx_exists = 0,
  'ALTER TABLE `transport_town_event_calendar` ADD KEY `idx_town_lookup` (`town`, `event_type`, `start_date`)',
  'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 菜单：挂在运输业务根菜单 6800 下（与计价规则同一层）
INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component, component_name,
                         status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted)
SELECT 6950, '事件日历', 'transport:town-event:query', 2, 16, 6800, 'town-event', 'ep:calendar',
       'transport/townEvent/index', 'TransportTownEvent',
       0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE permission = 'transport:town-event:query' AND deleted = b'0');

INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component, component_name,
                         status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted)
SELECT 6951, '事件日历保存', 'transport:town-event:save', 3, 1, 6950, '', '', '', NULL,
       0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE permission = 'transport:town-event:save' AND deleted = b'0');

INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component, component_name,
                         status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted)
SELECT 6952, '事件日历删除', 'transport:town-event:delete', 3, 2, 6950, '', '', '', NULL,
       0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE permission = 'transport:town-event:delete' AND deleted = b'0');

-- 幂等授权：system_role_menu 没有 (role_id, menu_id) 唯一键，INSERT IGNORE 挡不住重复执行（guarded 的 apply 每次都重跑整份白名单）
INSERT INTO system_role_menu (role_id, menu_id)
SELECT 1, id FROM system_menu WHERE id IN (6950, 6951, 6952)
  AND NOT EXISTS (SELECT 1 FROM system_role_menu rm
                  WHERE rm.role_id = 1 AND rm.menu_id = system_menu.id AND rm.deleted = b'0');
