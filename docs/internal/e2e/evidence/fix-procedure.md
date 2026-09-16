# 生产环境文件URL修复步骤

## 根因
数据库 `infra_file_config` id=4 的 `config.domain` = `http://127.0.0.1:48080`
导致后端生成的文件URL指向本地开发地址，浏览器无法访问。

## 修复步骤

### 第1步：执行SQL修复

在服务器MySQL中执行以下SQL：

```sql
-- 查询当前配置（备份）
SELECT id, name, storage, master, config, updater, update_time
FROM infra_file_config
WHERE id = 4;

-- 修改domain字段
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

-- 验证修改结果
SELECT id, name, storage, master, config, updater, update_time
FROM infra_file_config
WHERE id = 4;
```

### 第2步：等待缓存自动刷新

后端使用 Guava Cache，过期时间10秒，修改后自动生效，无需重启。

### 第3步：浏览器验证

1. 打开 http://1.15.29.107
2. DevTools → Network → Img
3. 找到图片请求
4. 确认 URL 变成：
   ```
   http://1.15.29.107/admin-api/infra/file/4/get/...
   ```
5. Console 不再出现 ERR_CONNECTION_REFUSED

## 缓存机制

- 后端使用 Guava LoadingCache
- 缓存过期时间：10秒
- 修改数据库后自动刷新
- 无需重启后端服务
