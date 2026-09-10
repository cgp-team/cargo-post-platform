-- 演示会员账号（密码登录兜底）
--
-- 背景：dev 服务器的 system_sms_channel 用的是上游示例凭据（DEBUG_DING_TALK），
--       短信发送会失败（/member/auth/send-sms-code 返回 500 系统异常），
--       因此"短信验证码登录"在现场演示时不可用。
--       本脚本预置一个已知密码的演示会员，小程序登录页切到「密码登录」即可完成登录，
--       从而不被短信渠道阻塞（商城下单、我的寄货、寄货提交等都需要登录）。
--
-- 账号：13800000000 / 123456（BCrypt 加密存储，与后端 PasswordEncoder 一致）
--
-- 执行（服务器仓库根目录）：
--   set -a; source /opt/cargo-post-platform/.env; set +a
--   docker compose --env-file /opt/cargo-post-platform/.env -f deploy/docker-compose.yml \
--     exec -T mysql mysql --default-character-set=utf8mb4 -u root -p"${DB_PASSWORD}" "${DB_NAME}" < sql/mysql/demo-member.sql
--
-- 幂等：按唯一键 mobile 覆盖密码/昵称/状态，可重复执行。

INSERT INTO member_user
    (nickname, name, sex, point, avatar, status, mobile, password, register_ip, creator, updater, tenant_id, deleted)
VALUES
    ('演示用户', '演示用户', 1, 0, '', 0, '13800000000',
     '$2a$10$b7Zw5dh0ldzG08ReZBVGZenwyyYUwS07iMM/KAZOP6cqjP7..RRzi', '127.0.0.1', '1', '1', 0, b'0')
ON DUPLICATE KEY UPDATE
    password = VALUES(password),
    nickname = VALUES(nickname),
    status = 0,
    deleted = b'0';

-- 校验（预期 1 行、status=0）
SELECT id, mobile, nickname, status, LEFT(password, 8) AS pwd_prefix FROM member_user WHERE mobile = '13800000000';
