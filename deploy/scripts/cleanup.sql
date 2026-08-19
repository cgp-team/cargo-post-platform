-- 客货邮平台 - 日志表周期清理（每周执行，替代已关闭的 Quartz 清理 Job）
-- 部署：deploy 用户 crontab 每周日 03:17 执行，见 docs/slimming-plan.md §3.3
-- 幂等可重复执行。

DELETE FROM infra_api_access_log WHERE create_time < DATE_SUB(NOW(), INTERVAL 7 DAY);
DELETE FROM infra_api_error_log  WHERE create_time < DATE_SUB(NOW(), INTERVAL 30 DAY);
DELETE FROM system_login_log     WHERE create_time < DATE_SUB(NOW(), INTERVAL 30 DAY);
