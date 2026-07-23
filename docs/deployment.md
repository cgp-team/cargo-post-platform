# 部署说明

## 开发环境

复制 `.env.example` 为 `.env`，修改开发占位密码，然后运行：

```bash
docker compose --env-file .env -f deploy/docker-compose.yml config
docker compose --env-file .env -f deploy/docker-compose.yml up -d --build
deploy/scripts/health-check.sh
```

Compose 只提供 MySQL、Redis、MinIO 和 Mock 算法服务。业务后端与完整 Vue 管理端暂按本机进程启动。生产环境不得直接复用开发 Compose；应使用独立密钥、TLS、网络策略、监控、日志采集和经过演练的恢复流程。

## 持续部署（GitHub Actions）

`.github/workflows/deploy-dev.yml` 在 push 到 `master`（或在 Actions 页手动 Run workflow）时自动构建后端、上传 jar 到开发云服务器并通过 systemd 重启，随后轮询 `/actuator/health` 确认启动成功，失败则本次部署标记失败并输出服务状态。同一时刻只允许一个部署排队执行；旧包保留为 `yudao-server.jar.bak`，回滚时将其改回 `yudao-server.jar` 并重启服务即可。PR 门禁测试由 `.github/workflows/ci.yml` 承担，部署 workflow 不重复跑测试。

### 服务器一次性配置

```bash
# 先克隆仓库（私有仓库：把服务器公钥加入 GitHub 仓库 Settings → Deploy keys，只读即可）
ssh-keyscan github.com >> ~/.ssh/known_hosts   # 首次连接先信任 GitHub 主机密钥
# 私钥若非默认文件名（如 ~/.ssh/github_deploy），需在 ~/.ssh/config 指定，否则 SSH 不会使用它：
#   Host github.com
#     IdentityFile ~/.ssh/github_deploy
#     IdentitiesOnly yes
git clone git@github.com:<你的账号>/cargo-post-platform.git /opt/cargo-post-platform
cd /opt/cargo-post-platform   # 本小节后续命令均在仓库根目录执行

sudo useradd -m -s /bin/bash deploy
sudo mkdir -p /opt/cargo-post/config
sudo chown -R deploy:deploy /opt/cargo-post
sudo cp deploy/cargo-post.service /etc/systemd/system/
# 按需编辑服务文件：User= 为部署用户，ExecStart 的 java 路径以 which java 为准
sudo systemctl daemon-reload && sudo systemctl enable cargo-post
# 允许部署用户免密重启该服务：sudo visudo -f /etc/sudoers.d/cargo-post
deploy ALL=(root) NOPASSWD: /usr/bin/systemctl restart cargo-post
```

服务器专属配置（数据库、Redis 密码等）放 `/opt/cargo-post/config/application-dev.yaml`（Spring Boot 自动读取）或 `/opt/cargo-post/app.env`（systemd EnvironmentFile，权限 600），均不得提交仓库。

### GitHub Secrets

在仓库 Settings → Secrets and variables → Actions 配置：

- `DEPLOY_HOST`：服务器 IP
- `DEPLOY_USER`：部署用户（如 `deploy`）
- `DEPLOY_SSH_KEY`：专用部署私钥。用 `ssh-keygen -t ed25519 -f deploy_key -N ""` 生成，公钥追加到服务器部署用户的 `~/.ssh/authorized_keys`；不要使用个人密钥
- `DEPLOY_PORT`：可选，SSH 非 22 端口时设置

workflow 引用 `environment: dev`，首次运行自动创建；可在 Settings → Environments → dev 配置 required reviewers，使部署需人工批准。启用 `develop` 集成分支后，在 `deploy-dev.yml` 的 `branches` 列表追加即可。

## Nginx

`deploy/nginx/nginx.conf` 是无域名、无证书路径的示例：`/` 服务前端静态文件，`/api/` 代理后端，`/ws/` 代理 WebSocket。算法服务未配置浏览器入口，只允许后端通过内部地址访问。

## 备份与恢复

`deploy/scripts/backup.sh` 生成 gzip 压缩的 MySQL 逻辑备份，默认保留 14 天。该脚本是开发基础版本，不代表完整灾备；上线前必须完成恢复演练、对象存储备份、异地副本、RPO/RTO 和加密要求。
