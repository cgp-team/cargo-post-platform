# 客货邮运营管理平台

面向县域客运、货运、生鲜与邮快件协同运营的单体管理平台开发基础仓库。项目基于 [RuoYi-Vue-Pro](https://github.com/YunaiV/ruoyi-vue-pro) `v2026.06(jdk17/21)` 发布线改造，保留登录、权限、菜单、字典、日志、文件与基础设施能力，并新增独立 `transport` 业务模块。

## 技术栈

- 后端：JDK 21、Spring Boot 3.5.15、Spring Security 6、MyBatis Plus、MySQL、Redis、Maven。
- 管理端目标：Vue 3 + TypeScript + Element Plus + pnpm。当前主仓库仅保留了 `yudao-ui/yudao-ui-admin-vue3` 的说明和零散源码，完整前端工程（含 `package.json` 和 lockfile）仍需从团队选定的独立前端仓库接入。
- 算法 Mock：Python 3.12、FastAPI、Pydantic。
- 开发基础设施：Docker Compose、MySQL、Redis、MinIO、Nginx 示例。

## 目录

```text
yudao-server/              单体后端启动模块
yudao-framework/           RuoYi-Vue-Pro 框架能力
yudao-module-system/       账号、权限、菜单、字典、日志
yudao-module-infra/        文件、配置、任务等基础设施
yudao-module-transport/    客货邮业务模块
yudao-ui/                  前端项目引用与 transport 接入骨架
mock-algorithm/            路线规划算法 Mock 服务
deploy/                    本地基础设施、Nginx 与运维脚本
docs/                      需求、架构、接口、部署与测试文档
sql/mysql/                 MySQL 初始化与 transport SQL 草案
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

完整 Vue3 工程接入 `yudao-ui/yudao-ui-admin-vue3` 后，使用其 lockfile 对应的 pnpm 版本：

```bash
cd yudao-ui/yudao-ui-admin-vue3
pnpm install --frozen-lockfile
pnpm dev
# 生产构建
pnpm build:prod
```

当前目录缺少完整前端清单，所以上述命令暂不可在本仓库执行。`src/api/transport` 与 `src/views/transport` 已提供接入骨架，不硬编码菜单，页面需由系统菜单动态挂载。

## 调用边界

```text
管理端前端
  -> 管理端业务后端
    -> transport 模块算法适配层
      -> 路线规划算法服务
```

前端不得直接调用算法服务；算法服务不得直接读取或修改业务数据库。业务后端负责快照、鉴权、幂等、结果合法性校验和状态落库。

## 当前状态与待办

已建立 transport 模块、验证接口、算法协议草案、Mock 服务、SQL 草案和开发部署骨架。后续需接入完整 Vue3 管理端、评审并落地 P0 数据模型与状态机、配置动态菜单权限、接入 GIS 服务，并与算法组冻结坐标系、规模上限、时限、错误码和版本规则。Logo 暂保留上游资源，待设计稿确认后替换。

## 上游同步

`origin` 是本项目远程，`upstream` 仅跟踪 RuoYi-Vue-Pro。不得直接在 `main`、`master` 或 `develop` 合并上游；每次同步创建 `chore/sync-upstream-*` 分支，评审迁移说明和回归结果后再合并。详见 [docs/upstream.md](docs/upstream.md)。

## 许可证与来源

本项目继续遵循仓库中的 MIT License。上游版权、许可证与来源说明予以保留；业务改造不代表上游项目对本平台的背书。
