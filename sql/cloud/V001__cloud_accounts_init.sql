-- ============================================================
-- 客货邮云平台 · 初始化账号脚本（仅账号，不含业务演示数据）
-- 执行前提：ruoyi-vue-pro.sql + transport-schema.sql 已执行完毕
-- 适用数据库：MySQL 8.4 / 芋道框架（yudao-cloud / ruoyi-vue-pro）
-- ============================================================
-- 本脚本只做三件事：
--   1. 建后台管理账号（system_users + system_user_role）
--   2. 建 App 会员账号（member_user）—— Android 端登录用
--   3. 建司机档案（transport_driver）—— mobile 与 member_user 对齐，
--      后端 DriverAppServiceImpl.currentDriverOrNull() 按登录会员手机号匹配
--
-- 不含：线路、站点、车辆、商品、订单、调度方案等任何业务数据。
-- 队友在管理端自行录入即可。
-- ============================================================

-- 防止重复执行：用 INSERT IGNORE 跳过已存在的记录
-- （system_users 的 username 有唯一索引 idx_username；
--   member_user 的 mobile 有唯一索引 uk_mobile；
--   transport_driver 的 license_no+tenant_id 有唯一索引 uk_driver_license_tenant）

-- ============================================================
-- 一、后台管理账号（Web 管理端登录）
-- 登录入口：POST /admin-api/system/auth/login
-- 请求体：{"username":"cargo_admin","password":"admin123"}
-- ============================================================

INSERT IGNORE INTO `system_users`
  (`id`, `username`, `password`, `nickname`, `remark`, `dept_id`, `post_ids`,
   `email`, `mobile`, `sex`, `avatar`, `status`,
   `login_ip`, `login_date`, `creator`, `create_time`, `updater`, `update_time`,
   `deleted`, `tenant_id`)
VALUES
  (200, 'cargo_admin',
   '$2a$04$.OplvPfDUeY3uVzVyujwvusxBmm5IRaPmP3TPOv7JQ./QuVCTMg6q',
   '平台管理员', '客货邮项目管理员账号', NULL, NULL,
   '', '13900001000', 0, '', 0,
   '', NULL, '1', NOW(), '1', NOW(),
   b'0', 1);

-- 分配超级管理员角色（role_id=1，与 ruoyi-vue-pro.sql 中 system_roles 对齐）
INSERT IGNORE INTO `system_user_role`
  (`id`, `user_id`, `role_id`, `creator`, `create_time`, `updater`, `update_time`, `deleted`, `tenant_id`)
VALUES
  (200, 200, 1, '1', NOW(), '1', NOW(), b'0', 1);

-- ============================================================
-- 二、App 会员账号（Android 端登录）
-- 登录入口：POST /app-api/member/auth/login
-- 请求体：{"mobile":"13900001001","password":"123456"}
-- ============================================================

-- 普通用户（村民/货主）
INSERT IGNORE INTO `member_user`
  (`id`, `nickname`, `name`, `sex`, `mobile`, `password`, `status`,
   `avatar`, `register_ip`, `creator`, `create_time`, `updater`, `update_time`,
   `deleted`, `tenant_id`)
VALUES
  (201, '测试用户', '李小明', 1, '13900001001',
   '$2a$04$Aeo560dG/pQsjvWefOz0HeTXXwjQuEsccG/HJMswJxMV4l2oZMUbi',
   0, '', '127.0.0.1',
   '1', NOW(), '1', NOW(), b'0', 1);

-- 司机用户（登录后选"我是司机"，后端按 mobile 匹配 transport_driver）
INSERT IGNORE INTO `member_user`
  (`id`, `nickname`, `name`, `sex`, `mobile`, `password`, `status`,
   `avatar`, `register_ip`, `creator`, `create_time`, `updater`, `update_time`,
   `deleted`, `tenant_id`)
VALUES
  (202, '张师傅', '张建国', 1, '13900001003',
   '$2a$04$Aeo560dG/pQsjvWefOz0HeTXXwjQuEsccG/HJMswJxMV4l2oZMUbi',
   0, '', '127.0.0.1',
   '1', NOW(), '1', NOW(), b'0', 1);

-- ============================================================
-- 三、司机档案（transport_driver）
-- mobile 必须与上面的 member_user 对齐，
-- 后端 currentDriverOrNull() 按 memberUserApi.getUser(memberId).getMobile()
-- 再 driverMapper.selectByMobile(mobile) 匹配
-- ============================================================

INSERT IGNORE INTO `transport_driver`
  (`id`, `name`, `mobile`, `license_no`, `license_expire_date`, `status`,
   `tenant_id`, `creator`, `create_time`, `updater`, `update_time`, `deleted`)
VALUES
  (201, '张建国', '13900001003', '500101199001011234', '2028-12-31', 0,
   1, '1', NOW(), '1', NOW(), b'0');

-- ============================================================
-- 账号清单（执行后可用以下信息登录验证）
-- ============================================================
--
-- ┌─────────────┬──────────────────┬────────────┬──────────────────────────────────┐
-- │ 角色        │ 登录入口          │ 账号       │ 密码                             │
-- ├─────────────┼──────────────────┼────────────┼──────────────────────────────────┤
-- │ 后台管理员  │ Web 管理端       │ cargo_admin│ admin123                         │
-- │ 普通用户    │ Android App      │ 13900001001│ 123456                           │
-- │ 司机        │ Android App      │ 13900001003│ 123456                           │
-- └─────────────┴──────────────────┴────────────┴──────────────────────────────────┘
--
-- 验证步骤：
-- 1. 后台管理：浏览器打开管理端 → 输入 cargo_admin / admin123
-- 2. App 用户：Android 端输入 13900001001 / 123456 → 选"我是用户"
-- 3. App 司机：Android 端输入 13900001003 / 123456 → 选"我是司机"
--
-- 注意事项：
-- - 本脚本不含车辆、线路、站点、商品等业务数据
-- - 司机登录前需先在管理端录入车辆并绑定（transport_driver_vehicle）
-- - 司机端功能依赖：车辆绑定 → 班次 → 调度方案，均需管理端配置
-- - 短信登录需要配置 SMS 服务商（阿里云/腾讯云短信），本脚本未涉及
-- ============================================================
