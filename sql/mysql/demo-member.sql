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

-- ---------- 司机端演示账号 ----------
-- 司机端用「登录会员手机号 = 司机档案手机号」匹配司机身份（DriverAppServiceImpl.currentDriverOrNull），
-- 因此调度方案落到哪台车，就要用「绑定该车的司机手机号」登录司机端才能看到任务。
-- 这里给 transport-demo-data.sql 的 4 位在职司机各配一个会员账号（密码同 123456）：
--   13800138001 张建国 / 13800138002 李伟民 / 13800138003 王守义 / 13800138004 赵德柱
-- 管理端「调度结果可视化」每条线路标题里会显示该车司机姓名（手机号），照着登录即可。
INSERT INTO member_user
    (nickname, name, sex, point, avatar, status, mobile, password, register_ip, creator, updater, tenant_id, deleted)
VALUES
    ('张建国', '张建国', 1, 0, '', 0, '13800138001', '$2a$10$b7Zw5dh0ldzG08ReZBVGZenwyyYUwS07iMM/KAZOP6cqjP7..RRzi', '127.0.0.1', '1', '1', 0, b'0'),
    ('李伟民', '李伟民', 1, 0, '', 0, '13800138002', '$2a$10$b7Zw5dh0ldzG08ReZBVGZenwyyYUwS07iMM/KAZOP6cqjP7..RRzi', '127.0.0.1', '1', '1', 0, b'0'),
    ('王守义', '王守义', 1, 0, '', 0, '13800138003', '$2a$10$b7Zw5dh0ldzG08ReZBVGZenwyyYUwS07iMM/KAZOP6cqjP7..RRzi', '127.0.0.1', '1', '1', 0, b'0'),
    ('赵德柱', '赵德柱', 1, 0, '', 0, '13800138004', '$2a$10$b7Zw5dh0ldzG08ReZBVGZenwyyYUwS07iMM/KAZOP6cqjP7..RRzi', '127.0.0.1', '1', '1', 0, b'0')
ON DUPLICATE KEY UPDATE
    password = VALUES(password),
    nickname = VALUES(nickname),
    status = 0,
    deleted = b'0';

-- 校验（预期 4 行）
SELECT id, mobile, nickname, status FROM member_user WHERE mobile IN ('13800138001', '13800138002', '13800138003', '13800138004');
