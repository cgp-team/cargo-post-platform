-- =====================================================================
-- V054 · AI 辅助：调用日志（队列 #93 / 契约 docs/AI_ASSIST_BOUNDARY.md §5）
--
-- 为什么它是"唯一需要新增的表"：契约 §5 要求 AI 的每次调用都可见（数得出来、查得到），
--   否则"AI 到底答了什么"没人能复盘。所以这张表必须存在，且**只读页**——不许删、不许改。
--
-- 三条纪律（写进表注释，免得接手的人忘）：
--   1. masked_input 落库前**已过 §4 的唯一脱敏出口**，本表永不存原文；
--   2. 写入是**旁路**：落库失败不得阻断页面（见 AiInvocationLogServiceImpl#record）；
--   3. error_brief 不许只写"失败"，要写要点。
--
-- 编号归属（契约 §2）：迁移从 V050 起，菜单主键用 7100–7199。本脚本加只读页 7140，挂在 7100 之下。
-- 幂等：CREATE TABLE IF NOT EXISTS + 菜单按 permission 判存在 + INSERT IGNORE。
-- =====================================================================

CREATE TABLE IF NOT EXISTS `ai_invocation_log`
(
    `id`           bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `scene`        varchar(64)  NOT NULL COMMENT '场景：nl-query / ticket-review / capacity-brief / risk-card / digest',
    `operator_id`  bigint       DEFAULT NULL COMMENT '操作人编号（登录管理员，不是 AI）',
    `masked_input` text         DEFAULT NULL COMMENT '送出去的内容（落库前已过 §4 脱敏出口，不许存原文）',
    `output`       text         DEFAULT NULL COMMENT '模型返回的文本',
    `tokens`       int          DEFAULT NULL COMMENT '消耗 token 数',
    `latency_ms`   int          DEFAULT NULL COMMENT '耗时（毫秒）',
    `status`       varchar(16)  NOT NULL COMMENT '状态：OK / RATE_LIMITED / ERROR / EMPTY',
    `error_brief`  varchar(255) DEFAULT NULL COMMENT '失败要点（不许只写"失败"）',
    `tenant_id`    bigint       NOT NULL DEFAULT 0 COMMENT '租户编号',
    `creator`      varchar(64)  DEFAULT '' COMMENT '创建者',
    `create_time`  datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`      varchar(64)  DEFAULT '' COMMENT '更新者',
    `update_time`  datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`      bit(1)       NOT NULL DEFAULT b'0' COMMENT '是否删除',
    PRIMARY KEY (`id`),
    KEY `idx_scene` (`scene`),
    KEY `idx_status` (`status`),
    KEY `idx_operator` (`operator_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='AI 调用日志（每次调用的可见留痕；只读）';

-- 菜单：只读页「调用日志」(7140)，父目录 7100。没有增/改/删按钮——这张表只许后端写、运维查。
INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component, component_name,
                         status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted)
SELECT 7140, '调用日志', 'transport:ai:invocation-log:query', 2, 5, 7100, 'invocation-log', 'ep:document',
       'transport/ai/invocation-log/index', 'TransportAiInvocationLog',
       0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE permission = 'transport:ai:invocation-log:query' AND deleted = b'0');

-- 角色授权：与基线同一写法（INSERT IGNORE + 按 id 取）
INSERT IGNORE INTO system_role_menu (role_id, menu_id)
SELECT 1, id FROM system_menu WHERE id IN (7140);
