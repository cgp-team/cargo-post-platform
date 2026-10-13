-- =====================================================================
-- V034 · 交接记录补 `sign_url`：签收/交接的**手写签名**图片地址
--
-- 为什么不能复用现成的 photoUrl：那一列是"交接拍照凭证"（货与舱的照片），
--   签名是"谁认了这笔交接"。合成一列会同时毁掉两件事的可追溯性 ——
--   后台看到一张图，分不清是货物凭证还是签收签名。
-- 《标记说明》司机端（三）收车业务里"电子签名"标的是"是（预留）"，用户 2026-10-13 放开范围后开始做。
--
-- 写法沿用 V024/V025/V030/V033：逐列查 information_schema + PREPARE/EXECUTE ⇒ **可安全重复执行**。
-- ⚠ 三条前置：① 本文件不在 `deploy-dev.yml` 的迁移白名单里（现读 V018/V019/V021/V022/V024/V025）⇒ 需人工执行；
--   ② 基线 `sql/mysql/transport-schema.sql` 本轮**没动**（工作树里有他人未提交改动，不裹进这一笔），
--      新库同样靠执行本文件补列；③ 未执行前，司机端提交带签名的交接会因缺列报错。
-- =====================================================================

SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'transport_handover' AND COLUMN_NAME = 'sign_url');
SET @ddl := IF(@col_exists = 0,
  'ALTER TABLE `transport_handover` ADD COLUMN `sign_url` varchar(512) NOT NULL DEFAULT '''' COMMENT ''签收/交接手写签名图片地址（空=没签名，后台要念"没签名"而不是显示空白）'' AFTER `photo_url`',
  'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
