-- 钱包账户表
CREATE TABLE IF NOT EXISTS `transport_wallet_account` (
    `id`             bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`        bigint       NOT NULL COMMENT '会员用户ID',
    `balance`        decimal(12,2) NOT NULL DEFAULT 0.00 COMMENT '余额（元）',
    `frozen_amount`  decimal(12,2) NOT NULL DEFAULT 0.00 COMMENT '冻结金额',
    `total_recharge` decimal(12,2) NOT NULL DEFAULT 0.00 COMMENT '累计充值',
    `total_spent`    decimal(12,2) NOT NULL DEFAULT 0.00 COMMENT '累计消费',
    `status`         tinyint      NOT NULL DEFAULT 0 COMMENT '状态 0=正常 1=冻结',
    `tenant_id`      bigint       NOT NULL DEFAULT 0 COMMENT '租户编号',
    `creator`        varchar(64)  DEFAULT '' COMMENT '创建者',
    `create_time`    datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`        varchar(64)  DEFAULT '' COMMENT '更新者',
    `update_time`    datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`        bit(1)       NOT NULL DEFAULT b'0' COMMENT '是否删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_id` (`user_id`, `tenant_id`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='钱包账户';

-- 钱包交易记录表
CREATE TABLE IF NOT EXISTS `transport_wallet_transaction` (
    `id`            bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
    `transaction_no` varchar(32)  NOT NULL COMMENT '交易流水号',
    `user_id`       bigint        NOT NULL COMMENT '会员用户ID',
    `type`          varchar(16)   NOT NULL COMMENT '类型 recharge/consume/refund/withdraw',
    `amount`        decimal(12,2) NOT NULL COMMENT '金额（正=收入，负=支出）',
    `balance_after` decimal(12,2) NOT NULL COMMENT '交易后余额',
    `title`         varchar(128)  DEFAULT '' COMMENT '标题',
    `ref_no`        varchar(64)   DEFAULT '' COMMENT '关联业务单号',
    `pay_method`    varchar(16)   DEFAULT '' COMMENT '支付方式 balance/alipay/wechat',
    `status`        tinyint       NOT NULL DEFAULT 1 COMMENT '状态 0=处理中 1=成功 2=失败',
    `tenant_id`     bigint        NOT NULL DEFAULT 0 COMMENT '租户编号',
    `creator`       varchar(64)   DEFAULT '' COMMENT '创建者',
    `create_time`   datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`       varchar(64)   DEFAULT '' COMMENT '更新者',
    `update_time`   datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`       bit(1)        NOT NULL DEFAULT b'0' COMMENT '是否删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_transaction_no` (`transaction_no`),
    KEY `idx_user_id` (`user_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='钱包交易记录';

-- 支付订单表（对接支付宝/微信的中间态订单）
CREATE TABLE IF NOT EXISTS `transport_pay_order` (
    `id`              bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
    `order_no`        varchar(32)   NOT NULL COMMENT '支付订单号',
    `user_id`         bigint        NOT NULL COMMENT '会员用户ID',
    `channel`         varchar(16)   NOT NULL COMMENT '渠道 alipay/wechat',
    `amount`          decimal(12,2) NOT NULL COMMENT '金额（元）',
    `channel_trade_no` varchar(64)  DEFAULT '' COMMENT '第三方交易号',
    `status`          tinyint       NOT NULL DEFAULT 0 COMMENT '状态 0=待支付 1=已支付 2=已退款 3=已关闭',
    `biz_type`        varchar(32)   DEFAULT 'recharge' COMMENT '业务类型 recharge/send_order/product_order',
    `biz_no`          varchar(64)   DEFAULT '' COMMENT '关联业务单号',
    `notify_body`     text          COMMENT '回调原始内容',
    `tenant_id`       bigint        NOT NULL DEFAULT 0 COMMENT '租户编号',
    `creator`         varchar(64)   DEFAULT '' COMMENT '创建者',
    `create_time`     datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`         varchar(64)   DEFAULT '' COMMENT '更新者',
    `update_time`     datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`         bit(1)        NOT NULL DEFAULT b'0' COMMENT '是否删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_no` (`order_no`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='支付订单';
