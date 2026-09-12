# 部署说明

## 开发环境

复制 `.env.example` 为 `.env`，修改开发占位密码，然后运行：

```bash
docker compose --env-file .env -f deploy/docker-compose.yml config
docker compose --env-file .env -f deploy/docker-compose.yml up -d --build
deploy/scripts/health-check.sh
```

Compose 提供 MySQL、Redis、Mock 算法服务与自研算法服务（`algorithm/`，OR-Tools；MinIO 自 2026-08 起默认停用，compose 中保留注释可一键恢复；文件存储默认使用数据库）。MySQL/Redis/算法端口仅绑定 `127.0.0.1`，MySQL 已关闭 performance-schema 以适配小内存机器。业务后端与完整 Vue 管理端暂按本机进程启动。生产环境不得直接复用开发 Compose；应使用独立密钥、TLS、网络策略、监控、日志采集和经过演练的恢复流程。

### 服务器加固与精简基线（2026-08 起）

dev 服务器已完成一轮系统性精简（方案与实测数据见 [slimming-plan.md](slimming-plan.md)），基线如下，新增配置时不要回退：

- 后端外部化配置 `/opt/cargo-post/config/application-dev.yaml` 叠加：~~Quartz 整体禁用~~（2026-08-23 起已因首个业务定时任务 `expiryWarningJob` 重新启用 Quartz 自动配置）、Redisson 线程收缩、Druid stat/监控台关闭、springdoc/knife4j 关闭（`/druid`、`/v3/api-docs` 不对公网开放）、api-encrypt 关闭、actuator 仅暴露 health。
- 日志表由 deploy 用户 crontab 每周执行 `deploy/scripts/cleanup.sql` 清理（访问日志留 7 天、错误/登录日志留 30 天）。
- `vm.swappiness=10` 已持久化（`/etc/sysctl.d/99-cargo-post.conf`），保护 mysqld 不被换出。

### 后端 Redis 归属（2026-09 起，登录/下单类故障首要排查项）

jar 内 `application-dev.yaml` 的 Redis 默认地址是 yudao 公共演示 Redis（`400-infra.server.iocoder.cn`）。**共享实例一旦拒绝写命令（读命令仍正常），
后端所有写 Redis 的链路会统一 500「系统异常」**：短信验证码下发、手机号/微信登录、令牌创建、图形验证码、商城下单、寄货提交。
读接口（商品/线路/站点）不受影响，因此 `/actuator/health` 依旧 `UP`，故障很隐蔽。

部署流水线已内置自愈步骤 `Ensure backend has a writable Redis`：先用 `send-sms-code` 探测当前后端能否写 Redis，只有确认 500、
且本机 Redis 容器 SET/GET 自检通过时，才把 `SPRING_DATA_REDIS_HOST/PORT/DATABASE/PASSWORD` 幂等写入 `/opt/cargo-post/app.env` 并重启验证；
重启后不健康会自动回滚 `app.env`（备份 `app.env.before-redis-switch`）并让部署失败。生产环境同样应使用独立 Redis 与独立口令。

手工排查用 `deploy/scripts/diagnose-backend.sh`（在服务器仓库根目录执行）：一次输出健康检查、读写链路探测、磁盘、本机 Redis/MySQL 自检与服务日志。

### 文件（照片）URL 必须带 `/api` 前缀

### 演示登录：短信渠道不可用时用密码登录

dev 服务器的 `system_sms_channel` 用的是上游示例凭据（`DEBUG_DING_TALK`），`/app-api/member/auth/send-sms-code` 会返回 `500 系统异常`，
因此**短信验证码登录在现场演示时不可用**（商城下单/我的寄货都要求登录）。执行 `sql/mysql/demo-member.sql` 预置一个已知密码的演示会员：

| 账号 | 密码 | 登录方式 |
|---|---|---|
| `13800000000` | `123456` | 小程序登录页 → 切到「密码登录」 |

```bash
set -a; source /opt/cargo-post-platform/.env; set +a
docker compose --env-file /opt/cargo-post-platform/.env -f deploy/docker-compose.yml \
  exec -T mysql mysql --default-character-set=utf8mb4 -u root -p"${DB_PASSWORD}" "${DB_NAME}" < sql/mysql/demo-member.sql
```

若要连短信流程一起演示，需要在管理端「系统管理 → 短信管理」配置一个真实可用渠道（阿里云/腾讯云，需实名与模板报备），
或增加一个"开发调试渠道"（只写日志不真发短信）——后者可作为后续增强项。

`deploy/nginx/nginx.conf` 只把 `/api/` 转发到后端（转发时**剥掉** `/api` 前缀），其余路径落到前端 SPA。
因此 `infra_file_config.config.domain` 必须是 **`http://1.15.29.107/api`**（不是 `http://1.15.29.107`）：
否则 DB 存储生成的文件 URL 形如 `http://1.15.29.107/admin-api/infra/file/4/get/xxx.jpg`，浏览器请求会命中前端路由拿到 `index.html`
（表现为**后台订单里的寄货/司机照片全是裂图**，Network 里类型是 `text/html` 而不是 `image/*`）。

`sql/mysql/transport-schema-incremental.sql` 的 V018 区块会自动把 domain 与历史 URL 补齐 `/api`；
部署流水线 `Validate historical URL repair` 步骤会硬校验（domain 不带 `/api`、或订单照片 URL 缺前缀 → 部署失败）。
手工核查：

```sql
SELECT id, JSON_EXTRACT(config,'$.domain') FROM infra_file_config WHERE id = 4;   -- 期望 http://1.15.29.107/api
SELECT COUNT(*) FROM infra_file WHERE url LIKE 'http://1.15.29.107/%' AND url NOT LIKE 'http://1.15.29.107/api/%';  -- 期望 0
```

### 高德 key 的两条路线（类型不能混用）

| 用途 | key 类型（高德控制台「服务平台」） | 配置位置 | 生效范围 |
|---|---|---|---|
| 小程序端「现实公交」站点层 | **微信小程序**（绑定 AppID `wx687e9bf8544ac559`） | `miniprogram/utils/config.js` 的 `AMAP_MINI_KEY`（客户端，绑定 AppID+域名，安全） | 首页/实时公交页的近公交站（`REAL_TRANSIT`） |
| 后端现实公交层 + 算法路网距离 | **Web 服务**（建议 IP 白名单填 `1.15.29.107`） | 服务器 `/opt/cargo-post-platform/.env` 的 `AMAP_KEY=`（算法容器同源）+ 后端 env | 后端 `AmapTransitProvider` 站点层；算法 `/distance` 从直线估算切驾车路网（路线预览/ETA/司机导航） |

两种 key **不能互换**：把小程序 key 配到后端，高德返回 `USERKEY_PLAT_NOMATCH (10009)`（已实测）；反之亦然。
部署流水线新增 `Sync AMAP_KEY to backend env`：`.env` 里配了 `AMAP_KEY` 就幂等同步到 `/opt/cargo-post/app.env`（后端重启后生效）；未配置时保持降级（现实公交层走项目线路+模拟车辆，路网距离走直线估算），不会报错。

## 持续部署（GitHub Actions）

`.github/workflows/deploy-dev.yml` 在 push 到 `master`（或在 Actions 页手动 Run workflow）时，在开发云服务器本机的 self-hosted runner 上构建后端 jar、通过 systemd 重启，随后轮询 `/actuator/health` 确认启动成功，失败则本次部署标记失败并输出服务状态。同一时刻只允许一个部署排队执行；旧包保留为 `yudao-server.jar.bak`，回滚时将其改回 `yudao-server.jar` 并重启服务即可。PR 门禁测试由 `.github/workflows/ci.yml` 承担，部署 workflow 不重复跑测试。

部署流水线含两项提速机制（2026-08 起）：

- **路径跳过**：`docs/`、`miniprogram/`、`.github/`、`mock-algorithm/` 的纯变更不触发部署；CI 门禁（`ci.yml`）自 2026-08-23 起改为「始终触发 + 变更探测按需跳过 job」（docs-only PR 也会产生 skipped 的必需检查，配合 master 分支保护可正常合并）。
- **部分构建**：`Detect changed areas` 步骤以「最近一次成功部署的 commit」为基准用 GitHub compare API 分析变更文件，后端打包/前端构建/对应发布步骤按需执行（如纯 SQL 变更只跑迁移）；compare API 失败或手动触发时一律全量构建。后端 Maven 打包为「离线优先（`-o`）+ 多核并行（`-T 1C`）」，离线失败自动回退在线。

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

Compose 技术栈（MySQL/Redis/Mock 算法）与 CI 的数据库迁移步骤统一从持久检出根目录的 `.env` 读取配置：按 `.env.example` 创建 `/opt/cargo-post-platform/.env`（权限 600），并用 `deploy/scripts/deploy.sh` 启动技术栈。注意 `.env` 放在 runner 工作区无效——`actions/checkout` 每次构建都会清理未跟踪文件。

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
