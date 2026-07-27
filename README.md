# 客货邮运营管理平台

# 🚨 **所有协作人员请务必先阅读：[Git 协作指南](docs/Git%20协作.md)**

面向县域客运、货运、生鲜与邮快件协同运营的单体管理平台开发基础仓库。项目基于 [RuoYi-Vue-Pro](https://github.com/YunaiV/ruoyi-vue-pro) `v2026.06(jdk17/21)` 发布线改造，保留登录、权限、菜单、字典、日志、文件与基础设施能力，并新增独立 `transport` 业务模块。

## 技术栈

- 后端：JDK 21、Spring Boot 3.5.15、Spring Security 6、MyBatis Plus、MySQL、Redis、Maven。
- 管理端：Vue 3 + TypeScript + Element Plus + pnpm。完整工程已接入 `yudao-ui/yudao-ui-admin-vue3`（应用名 `cargo-post-admin`），含 `package.json` 与 `pnpm-lock.yaml`；其余 `yudao-ui` 子目录仅为指向上游的占位说明。
- 算法 Mock：Python 3.12、FastAPI、Pydantic。
- 开发基础设施：Docker Compose、MySQL、Redis、MinIO、Nginx 示例。

## 目录

```text
yudao-server/              单体后端启动模块（仅启用 system、infra、transport 三个业务模块）
yudao-framework/           RuoYi-Vue-Pro 框架能力
yudao-module-system/       账号、权限、菜单、字典、日志
yudao-module-infra/        文件、配置、任务等基础设施
yudao-module-transport/    客货邮业务模块（当前为验证接口 + 领域包骨架）
yudao-ui/                  管理端 Vue3 工程与其余前端占位说明
mock-algorithm/            路线规划算法契约 Mock 服务（FastAPI + pytest）
deploy/                    本地基础设施 Compose、Nginx、运维脚本与 systemd 单元
docs/                      需求、架构、接口、部署、测试与协作文档
sql/mysql/                 MySQL 初始化脚本与 transport 表结构草案
.github/workflows/         CI 校验与 dev 环境持续部署流水线
```

## 开发环境

需要 JDK 21、Maven 3.9+、Node.js（以前端 `package.json` 的 engines 为准）、pnpm（以 lockfile 为准）、Python 3.12+ 和 Docker Compose v2。不得提交真实密码、令牌、`.env` 或生产数据。

## 启动开发基础设施

```bash
cp .env.example .env
docker compose --env-file .env -f deploy/docker-compose.yml up -d
```

默认启动 MySQL、Redis、MinIO 和 Mock 算法服务。数据库初始化请先导入项目官方 `sql/mysql/ruoyi-vue-pro.sql`，再按评审结果手工执行 `sql/mysql/transport-schema.sql`；本仓库不会自动执行破坏性迁移。

## 启动后端

```bash
mvn -pl yudao-server -am clean test
mvn -pl yudao-server -am spring-boot:run
```

默认本地端口为 `48080`，Knife4j/Swagger UI 为 `http://localhost:48080/swagger-ui`，OpenAPI JSON 为 `http://localhost:48080/v3/api-docs`。验证接口为 `GET /admin-api/transport/test/get`，沿用现有登录鉴权。

## 启动管理端

`yudao-ui/yudao-ui-admin-vue3` 已是完整工程，使用其 lockfile 对应的 pnpm 版本：

```bash
cd yudao-ui/yudao-ui-admin-vue3
pnpm install --frozen-lockfile
pnpm dev
# 生产构建
pnpm build:prod
```

`src/api/transport` 与 `src/views/transport` 目前仅有接入验证页（`dashboard/index.vue`，调用 `/transport/test/get`），其余业务目录为骨架。不硬编码菜单，页面需由系统菜单动态挂载。

## 开发服务器

dev 环境部署在腾讯云开发服务器上，管理端入口为 **<http://1.15.29.107/>**（无域名、无 HTTPS，仅供团队开发验证）：

- Nginx 80 端口直接服务前端静态文件（`/opt/cargo-post-web`），`/api/` 反代到本机 `48080` 的业务后端。
- 后端以 systemd 服务 `cargo-post` 运行（jar 位于 `/opt/cargo-post`），服务器专属配置在 `/opt/cargo-post/config/application-dev.yaml`，不提交仓库。
- push 到 `master` 即触发 `.github/workflows/deploy-dev.yml` 自动部署：服务器本机的 self-hosted runner 构建后端 jar 与前端产物，systemd 重启后轮询 `/actuator/health` 和前端页面确认存活；旧 jar 保留为 `.bak` 可手工回滚。
- 服务器一次性配置、runner 注册、备份与回滚细节见 [docs/deployment.md](docs/deployment.md)。

## 调用边界

```text
管理端前端
  -> 管理端业务后端
    -> transport 模块算法适配层
      -> 路线规划算法服务
```

前端不得直接调用算法服务；算法服务不得直接读取或修改业务数据库。业务后端负责快照、鉴权、幂等、结果合法性校验和状态落库。

## 当前状态与待办

工程基座已完成，业务功能尚未开工：

- 已就绪：完整 Vue3 管理端接入与品牌 Logo 替换、后端 transport 验证接口与领域包骨架、算法契约草案（`docs/api/algorithm-api.yaml`）与可运行的 Mock 服务（含 pytest）、MySQL/Redis/MinIO Compose 环境、transport 表结构 DDL 草案、CI 校验流水线（后端测试打包 / 前端构建 / Mock 测试 / 漂移检查）与 dev 服务器 self-hosted 持续部署。
- 待办：评审并落地 P0 数据模型与状态机（`transport-menu.sql`、`transport-demo-data.sql` 仍为占位）、实现 transport 各领域 Service 与算法适配层、配置动态菜单权限、接入 GIS 服务，并与算法组冻结坐标系、规模上限、时限、错误码和版本规则。

## 上游同步

`origin` 是本项目远程，`upstream` 仅跟踪 RuoYi-Vue-Pro。不得直接在 `main`、`master` 或 `develop` 合并上游；每次同步创建 `chore/sync-upstream-*` 分支，评审迁移说明和回归结果后再合并。详见 [docs/upstream.md](docs/upstream.md)。

## 许可证与来源

本项目继续遵循仓库中的 MIT License。上游版权、许可证与来源说明予以保留；业务改造不代表上游项目对本平台的背书。
