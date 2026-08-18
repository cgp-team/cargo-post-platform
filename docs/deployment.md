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

`.github/workflows/deploy-dev.yml` 在 push 到 `master`（或在 Actions 页手动 Run workflow）时，在开发云服务器本机的 self-hosted runner 上构建后端 jar、通过 systemd 重启，随后轮询 `/actuator/health` 确认启动成功，失败则本次部署标记失败并输出服务状态。同一时刻只允许一个部署排队执行；旧包保留为 `yudao-server.jar.bak`，回滚时将其改回 `yudao-server.jar` 并重启服务即可。PR 门禁测试由 `.github/workflows/ci.yml` 承担，部署 workflow 不重复跑测试。

部署流水线含两项提速机制（2026-08 起）：

- **路径跳过**：`docs/`、`miniprogram/`、`.github/`、`mock-algorithm/` 的纯变更不触发部署。
- **部分构建**：`Detect changed areas` 步骤用 GitHub compare API 分析变更文件，后端打包/前端构建/对应发布步骤按需执行（如纯 SQL 变更只跑迁移）；compare API 失败或手动触发时一律全量构建。后端 Maven 打包为「离线优先（`-o`）+ 多核并行（`-T 1C`）」，离线失败自动回退在线。

托管 runner 跨境上传 jar 到国内服务器过慢（实测约 50KB/s），因此部署 workflow 固定运行在服务器本机的 self-hosted runner（`runs-on: [self-hosted, cargo-post]`）上，构建与部署同机完成，无需 DEPLOY_* Secrets 与 SSH 通道。

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
# self-hosted runner 需要在服务器本机构建后端：安装 JDK（含 javac）与 Maven
sudo apt install -y openjdk-21-jdk-headless maven
# 前端构建使用服务器预装的 Node 22（官方 tarball 装到 /usr/local，不再每次从 GitHub 下载）：
curl -fsSL -o /tmp/node.tgz https://nodejs.org/dist/v22.23.2/node-v22.23.2-linux-x64.tar.gz
sudo tar -xzf /tmp/node.tgz -C /usr/local --strip-components=1 && rm /tmp/node.tgz
sudo corepack enable   # 激活 pnpm，版本由 package.json 的 packageManager 字段锁定
sudo mkdir -p /opt/cargo-post/config
sudo chown -R deploy:deploy /opt/cargo-post
sudo cp deploy/cargo-post.service /etc/systemd/system/
# 按需编辑服务文件：User= 为部署用户，ExecStart 的 java 路径以 which java 为准
sudo systemctl daemon-reload && sudo systemctl enable cargo-post
# 允许部署用户免密重启该服务：sudo visudo -f /etc/sudoers.d/cargo-post
deploy ALL=(root) NOPASSWD: /usr/bin/systemctl restart cargo-post
```

服务器专属配置（数据库、Redis 密码等）放 `/opt/cargo-post/config/application-dev.yaml`（Spring Boot 自动读取）或 `/opt/cargo-post/app.env`（systemd EnvironmentFile，权限 600），均不得提交仓库。

Compose 技术栈（MySQL/Redis/MinIO/Mock 算法）与 CI 的数据库迁移步骤统一从持久检出根目录的 `.env` 读取配置：按 `.env.example` 创建 `/opt/cargo-post-platform/.env`（权限 600），并用 `deploy/scripts/deploy.sh` 启动技术栈。注意 `.env` 放在 runner 工作区无效——`actions/checkout` 每次构建都会清理未跟踪文件。

### Self-hosted runner

runner 以 deploy 用户运行在 `/opt/actions-runner`，注册为系统服务：

```bash
# 为 deploy 配置阿里云 Maven 镜像（~/.m2/settings.xml，mirrorOf=central）
# 获取注册 token：gh api -X POST repos/<owner>/<repo>/actions/runners/registration-token
sudo install -d -o deploy -g deploy /opt/actions-runner && cd /opt/actions-runner
sudo -u deploy curl -sL -o runner.tar.gz https://github.com/actions/runner/releases/download/v2.336.0/actions-runner-linux-x64-2.336.0.tar.gz
sudo -u deploy tar xzf runner.tar.gz && rm runner.tar.gz
sudo -u deploy ./config.sh --url https://github.com/<owner>/cargo-post-platform \
  --token <注册token> --name txcloud-4c4g --labels cargo-post --unattended --replace
sudo ./svc.sh install deploy && sudo ./svc.sh start   # 必须在 runner 根目录执行
```

Docker 镜像源如失效，在 `/etc/docker/daemon.json` 配置 `registry-mirrors`（可用 `https://docker.m.daocloud.io`、`https://docker.1ms.run`）后 `systemctl restart docker`。

workflow 引用 `environment: dev`，首次运行自动创建；可在 Settings → Environments → dev 配置 required reviewers，使部署需人工批准。启用 `develop` 集成分支后，在 `deploy-dev.yml` 的 `branches` 列表追加即可。

## Nginx

`deploy/nginx/nginx.conf` 是无域名、无证书路径的示例：`/` 服务前端静态文件，`/api/` 代理后端，`/ws/` 代理 WebSocket。算法服务未配置浏览器入口，只允许后端通过内部地址访问。

前端百度地图 AK（`yudao-ui-admin-vue3/.env.prod` 的 `VITE_BAIDU_MAP_KEY`）的 Referer 白名单必须包含部署访问地址（IP 或域名，如 `1.15.29.107`），否则车辆监控等地图页会弹「APP Referer校验失败」且地图无法加载；在白名单管理平台（lbsyun.baidu.com 控制台）修改后即时生效，无需重新构建。

## 备份与恢复

`deploy/scripts/backup.sh` 生成 gzip 压缩的 MySQL 逻辑备份，默认保留 14 天。该脚本是开发基础版本，不代表完整灾备；上线前必须完成恢复演练、对象存储备份、异地副本、RPO/RTO 和加密要求。
