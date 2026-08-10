-- ============================================
-- 客货邮平台 - 清理 yudao 上游业务模块菜单
-- 删除与客货邮业务无关的上游模块菜单（含全部后代与角色关联），
-- 保留：系统管理(1)、基础设施(2)、客货邮管理(6800)。
-- 幂等可重复执行；已纳入 deploy-dev.yml 迁移步骤（每次部署自动执行，菜单缓存随后端重启刷新）。
-- ============================================

-- 第一步：清理角色-菜单关联
WITH RECURSIVE menu_tree AS (
  SELECT id FROM system_menu WHERE parent_id = 0 AND deleted = 0 AND id IN (
    1254, -- 作者动态
    2159, -- Boot 开发文档
    2160, -- Cloud 开发文档
    1117, -- 支付管理
    1281, -- 报表管理
    1185, -- 工作流程
    2262, -- 会员中心
    2362, -- 商城系统
    2084, -- 公众号管理
    2397, -- CRM 系统
    2563, -- ERP 系统
    6400, -- WMS 系统
    5100, -- MES 系统
    2758, -- AI 大模型
    4000, -- IoT 物联网
    6500  -- IM 即时通讯
  )
  UNION ALL
  SELECT m.id FROM system_menu m JOIN menu_tree t ON m.parent_id = t.id
)
DELETE FROM system_role_menu WHERE menu_id IN (SELECT id FROM menu_tree);

-- 第二步：删除菜单本体
WITH RECURSIVE menu_tree AS (
  SELECT id FROM system_menu WHERE parent_id = 0 AND deleted = 0 AND id IN (
    1254, 2159, 2160, 1117, 1281, 1185, 2262, 2362, 2084, 2397, 2563, 6400, 5100, 2758, 4000, 6500
  )
  UNION ALL
  SELECT m.id FROM system_menu m JOIN menu_tree t ON m.parent_id = t.id
)
DELETE FROM system_menu WHERE id IN (SELECT id FROM menu_tree);

-- 第三步：隐藏系统管理与基础设施顶级目录（功能保留，可直接通过 URL 访问，如 /system/user、/infra/file-config）
UPDATE system_menu SET visible = b'0' WHERE id IN (1, 2);
