-- =====================================================================
-- V052 · AI 辅助：查询构造器（存条件）与运营简报（队列 #93 / 契约 §6 第 4 项）
--
-- 为什么不是 AI 的一部分：结构化筛选（存条件）+ 简报模板导出，都是纯规则能力——
--   现成的分页/聚合接口够用，"自然语言"那一层将来只做"话→参数"，永远不做"话→SQL"。
--
-- 编号归属（契约 §2）：迁移从 V050 起，菜单主键用 7100–7199。本脚本加只读/草稿页 7120
--   及其三个写按钮 7121–7123，挂在 V050 的顶级目录「AI 辅助」(7100) 之下。
--
-- 幂等：CREATE TABLE IF NOT EXISTS + 菜单按 permission 判存在 + INSERT IGNORE。
-- ⚠ 本表是 AI 辅助自己的草稿（存的是"筛选条件"，不是业务结果快照），不碰任何业务表（契约 §0）。
-- =====================================================================

CREATE TABLE IF NOT EXISTS `ai_saved_query`
(
    `id`              bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `name`            varchar(64)  NOT NULL COMMENT '名称',
    `scene`           varchar(32)  NOT NULL COMMENT '业务场景：ORDER 订单 / DISPATCH 调度 / FEEDBACK 反馈',
    `conditions_json` text         NOT NULL COMMENT '结构化筛选条件（JSON 文本）',
    `remark`          varchar(255) DEFAULT NULL COMMENT '备注',
    `tenant_id`       bigint       NOT NULL DEFAULT 0 COMMENT '租户编号',
    `creator`         varchar(64)  DEFAULT '' COMMENT '创建者',
    `create_time`     datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`         varchar(64)  DEFAULT '' COMMENT '更新者',
    `update_time`     datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`         bit(1)       NOT NULL DEFAULT b'0' COMMENT '是否删除',
    PRIMARY KEY (`id`),
    KEY `idx_scene` (`scene`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='AI 辅助：保存的查询条件（存条件，不存结果快照）';

-- 菜单：页「查询构造器」(7120) + 三个写按钮，父目录 7100。
INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component, component_name,
                         status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted)
SELECT 7120, '查询构造器', 'transport:ai:saved-query:query', 2, 3, 7100, 'query-builder', 'ep:filter',
       'transport/ai/query-builder/index', 'TransportAiQueryBuilder',
       0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE permission = 'transport:ai:saved-query:query' AND deleted = b'0');

INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component, component_name,
                         status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted)
SELECT 7121, '查询条件新增', 'transport:ai:saved-query:create', 3, 1, 7120, '', '', '', NULL,
       0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE permission = 'transport:ai:saved-query:create' AND deleted = b'0');

INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component, component_name,
                         status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted)
SELECT 7122, '查询条件修改', 'transport:ai:saved-query:update', 3, 2, 7120, '', '', '', NULL,
       0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE permission = 'transport:ai:saved-query:update' AND deleted = b'0');

INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component, component_name,
                         status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted)
SELECT 7123, '查询条件删除', 'transport:ai:saved-query:delete', 3, 3, 7120, '', '', '', NULL,
       0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE permission = 'transport:ai:saved-query:delete' AND deleted = b'0');

-- 角色授权：与基线同一写法（INSERT IGNORE + 按 id 取）
INSERT IGNORE INTO system_role_menu (role_id, menu_id)
SELECT 1, id FROM system_menu WHERE id IN (7120, 7121, 7122, 7123);
