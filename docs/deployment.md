# 部署说明

## 开发环境

复制 `.env.example` 为 `.env`，修改开发占位密码，然后运行：

```bash
docker compose --env-file .env -f deploy/docker-compose.yml config
docker compose --env-file .env -f deploy/docker-compose.yml up -d --build
deploy/scripts/health-check.sh
```

Compose 只提供 MySQL、Redis、MinIO 和 Mock 算法服务。业务后端与完整 Vue 管理端暂按本机进程启动。生产环境不得直接复用开发 Compose；应使用独立密钥、TLS、网络策略、监控、日志采集和经过演练的恢复流程。

## Nginx

`deploy/nginx/nginx.conf` 是无域名、无证书路径的示例：`/` 服务前端静态文件，`/api/` 代理后端，`/ws/` 代理 WebSocket。算法服务未配置浏览器入口，只允许后端通过内部地址访问。

## 备份与恢复

`deploy/scripts/backup.sh` 生成 gzip 压缩的 MySQL 逻辑备份，默认保留 14 天。该脚本是开发基础版本，不代表完整灾备；上线前必须完成恢复演练、对象存储备份、异地副本、RPO/RTO 和加密要求。
