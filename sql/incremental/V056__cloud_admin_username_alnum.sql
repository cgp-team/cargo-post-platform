-- =====================================================================
-- V056 · 云后台管理员账号改成**纯数字字母**，否则登录接口自己会把它拒了
--
-- 为什么必须改：`AuthLoginReqVO.username` 上游的校验是
--   @Pattern(regexp = "^[a-zA-Z0-9]{4,30}$", message = "账号格式为数字以及字母")
--  而 `sql/cloud/V001__cloud_accounts_init.sql` 建的账号是 `cargo_admin`（**带下划线**）
--  ⇒ 这个文档里写给用户用的账号，**一次都登不进去**：请求还没走到鉴权就被 400 挡回来。
--  这不是放宽校验能解决的（那是上游对所有后台账号的统一约束，放宽等于改全站口径）；
--  错在我们建号时没对齐平台自己的规则 ⇒ 改号。
--
-- 新号 `cargoadmin`：10 个字母、落在 {4,30} 区间内，与基线里的 `admin` / `yudao` / `yuanma` 同形。
-- **密码、角色、id 都不动**（role 仍是 1 超管），只改 username。
--
-- 幂等（guarded 每次都会重跑整份白名单）：
--   · 目标号已存在 ⇒ 什么都不做（NOT EXISTS 挡住，避免撞 idx_username）；
--   · 改完再跑一遍 ⇒ 源号 `cargo_admin` 已经不存在 ⇒ WHERE 命中 0 行。
--   · 授权/菜单/表一概不碰，所以 32 格探针的既有期望一个都不变。
--
-- ⚠ 这份脚本不会自己跑到云上：云端执行只走 .github/workflows/db-migrate-guarded.yml，
--   mode=check 只读、mode=apply 需要人显式给 confirm=APPLY。
-- =====================================================================

UPDATE system_users
SET username = 'cargoadmin', updater = '1', update_time = NOW()
WHERE username = 'cargo_admin'
  AND deleted = b'0'
  AND NOT EXISTS (
    SELECT 1 FROM (SELECT id FROM system_users WHERE username = 'cargoadmin' AND deleted = b'0') t
  );
