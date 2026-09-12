-- 生产环境文件配置修复脚本
-- 目标: 修复 infra_file_config id=4 的 domain 字段
-- 问题: domain = http://127.0.0.1:48080 (本地开发地址)
-- 修复: domain = http://1.15.29.107 (生产服务器地址)

-- ==============================
-- 第1步: 查询当前配置（备份）
-- ==============================
SELECT id, name, storage, master, config, updater, update_time
FROM infra_file_config
WHERE id = 4;

-- ==============================
-- 第2步: 修改配置（使用JSON_SET只更新domain字段）
-- ==============================
UPDATE infra_file_config
SET
    config = JSON_SET(
        config,
        '$.domain',
        'http://1.15.29.107'
    ),
    updater = 'admin',
    update_time = NOW()
WHERE id = 4;

-- ==============================
-- 第3步: 验证修改结果
-- ==============================
SELECT id, name, storage, master, config, updater, update_time
FROM infra_file_config
WHERE id = 4;

-- ==============================
-- 第4步: 检查是否还有其他配置使用本地地址
-- ==============================
SELECT id, name, storage, master,
       JSON_EXTRACT(config, '$.domain') as domain
FROM infra_file_config
WHERE config LIKE '%127.0.0.1%'
   OR config LIKE '%localhost%';
