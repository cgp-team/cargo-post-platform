-- ---------- V021：移除模拟运营/开发者中心（表 + 菜单 + 授权） ----------
-- 背景：模拟运营子系统（SimulationEngine/SimulationRuntimeService 等）与开发者中心已整体下线，
--       本脚本清理其运行时表与菜单。全部幂等，可重复执行。

-- 1. 删除模拟运营运行时表（来自已移除的 V017__simulation_runtime.sql）
DROP TABLE IF EXISTS `simulation_scenario_event`;
DROP TABLE IF EXISTS `simulation_event`;
DROP TABLE IF EXISTS `simulation_run`;
DROP TABLE IF EXISTS `simulation_scenario`;

-- 2. 删除菜单的子孙按钮（parent_id 挂在 6915 模拟运营 / 6920 开发者中心 / 6921 / 6922 下）
DELETE FROM system_role_menu WHERE menu_id IN (
  SELECT id FROM (SELECT id FROM system_menu WHERE parent_id IN (6915, 6920, 6921, 6922)) child
);
DELETE FROM system_menu WHERE id IN (
  SELECT id FROM (SELECT id FROM system_menu WHERE parent_id IN (6915, 6920, 6921, 6922)) child
);

-- 3. 删除主菜单：6915 模拟运营、6920 开发者中心、6921 模拟运营（历史重复项）、6922 模拟控制
DELETE FROM system_role_menu WHERE menu_id IN (6915, 6920, 6921, 6922);
DELETE FROM system_menu WHERE id IN (6915, 6920, 6921, 6922);
