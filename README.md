# 客货邮运营管理平台（Cargo Post Platform）

![Java](https://img.shields.io/badge/Java-21-007396?logo=openjdk)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.15-6DB33F?logo=springboot)
![Vue](https://img.shields.io/badge/Vue-3-4FC08D?logo=vuedotjs)
![MySQL](https://img.shields.io/badge/MySQL-8.4-4479A1?logo=mysql)
![License](https://img.shields.io/badge/License-MIT-yellow.svg)
[![CI](https://github.com/Ferron2333/cargo-post-platform/actions/workflows/ci.yml/badge.svg)](https://github.com/Ferron2333/cargo-post-platform/actions/workflows/ci.yml)

面向县域**客运、货运、生鲜与邮快件协同运营**的单体管理平台。基于 [RuoYi-Vue-Pro](https://github.com/YunaiV/ruoyi-vue-pro)（`2026.06` / JDK 21 发布线）深度改造：保留登录、权限、菜单、字典、日志、文件等基础能力，新增独立的 `transport` 客货邮业务模块，并配套微信小程序与**自研路线规划算法服务**（OR-Tools，契约与算法组书面约定对齐）。

> 🚨 协作人员请务必先阅读：[Git 协作指南](docs/Git%20协作.md)

## 功能特性

- **运营总览**：数据大盘，订单、运力、履约、异常摘要与统计图表
- **车辆监控**：GIS 实时监控地图（百度地图 GL）、车辆状态管理、位置轨迹落库
- **运力资源**：车辆/司机注册审核与档案、人车绑定、转运中心/乡镇站/村级站点
- **班次调度**：线路班次配置、预约订单池、手工派单与智能派单、调度方案审核下发、发车核验、方案 ETA 与收入/成本估算
- **订单业务**：客运、货运/生鲜、邮快件三类订单及状态流转
- **农产品商城**：管理端商品 CRUD + 小程序商城、下单、物流溯源
- **微信小程序**：登录（短信/微信）、商城下单、寄件与包裹轨迹、实时公交、公告反馈、司机工作台（班次任务/装车核验/发车签收/收益）
- **到期预警**：司机驾驶证/车辆保险到期查询与定时扫描预警
- **算法服务**：自研路线规划算法（OR-Tools 求解，FastAPI 交付 Docker 镜像）+ 业务侧受控适配层（快照、幂等、超时重试、结果校验、规模预检）；契约 Mock 保留做混沌测试，附契约验收套件

## 技术栈

| 端 | 技术 |
|---|---|
| 后端 | JDK 21 · Spring Boot 3.5 · Spring Security 6 · MyBatis Plus · MySQL 8.4 · Redis 7 · Maven |
| 管理端 | Vue 3 · TypeScript · Element Plus · pnpm |
| 小程序 | 微信原生小程序（仅调用 `/app-api`） |
| 算法服务 | Python 3.11 · FastAPI · OR-Tools（pywrapcp）· Docker |
| 算法 Mock | Python 3.12 · FastAPI · Pydantic · pytest（契约 Mock / 混沌测试） |
| 基础设施 | Docker Compose · Nginx · systemd · GitHub Actions（self-hosted） |

## 项目结构

```text
yudao-server/              单体后端启动模块（装配 system、infra、transport、member）
yudao-framework/           框架能力（starter 集合，源自上游并裁剪）
yudao-module-system/       账号、权限、菜单、字典、日志、短信/社交登录
yudao-module-infra/        文件、配置、API 日志等基础设施
yudao-module-member/       会员体系（小程序登录依赖）
yudao-module-transport/    客货邮业务模块（资源/订单/调度闭环/监控大盘/商品/算法适配层）
yudao-ui/yudao-ui-admin-vue3/  管理端 Vue3 工程（cargo-post-admin）
miniprogram/               微信小程序（商城、寄件、包裹、实时公交 + 司机工作台）
algorithm/                 自研路线规划算法服务（FastAPI + OR-Tools，生产用）
mock-algorithm/            路线规划算法契约 Mock 服务（FastAPI + pytest，混沌测试用）
deploy/                    基础设施 Compose、Nginx 示例、运维脚本、systemd 单元
docs/                      需求、架构、接口、部署、测试与协作文档（索引见 docs/README.md）
sql/                       MySQL 初始化脚本、transport 表结构与增量迁移（sql/incremental/）
.github/workflows/         CI 校验与 dev 环境持续部署流水线
```

## 快速开始

### 环境要求

JDK 21 · Maven 3.9+ · Node.js 22 · pnpm（以 lockfile 为准）· Python 3.12+ · Docker Compose v2

### 1. 启动基础设施

```bash
cp .env.example .env   # 修改其中的开发占位密码
docker compose --env-file .env -f deploy/docker-compose.yml up -d
deploy/scripts/health-check.sh
```

默认启动 **MySQL、Redis、Mock 算法服务与自研算法服务**（MinIO 已停用并保留注释，需要对象存储时取消注释即可；默认文件存储为数据库）。MySQL/Redis/算法端口仅绑定 `127.0.0.1`。本地开发默认连 Mock（`ALGORITHM_BASE_URL` 见 `.env.example`），指向 `algorithm` 服务即可联调真实求解。

### 2. 初始化数据库

```bash
# 依次导入（MySQL 客户端内 SOURCE 或等价方式，与 CI 迁移顺序一致）：
sql/mysql/ruoyi-vue-pro.sql                  # 上游全量基础库
sql/mysql/cleanup-upstream-menus.sql         # 清理上游无关模块菜单（含 demo 菜单）
sql/mysql/transport-schema.sql               # transport 领域表
sql/mysql/transport-schema-incremental.sql   # 增量列补齐（幂等）
sql/mysql/transport-menu.sql                 # 业务菜单 + member_user 表
sql/mysql/transport-demo-data.sql            # 可选：演示数据（禁止用于生产）
```

后续结构变更走 `sql/incremental/` 下的人工迁移入口（V001~V011），详见 [docs/database.md](docs/database.md)。

### 3. 启动后端

```bash
mvn -pl yudao-server -am clean test
mvn -pl yudao-server -am spring-boot:run
```

默认端口 `48080`；本地 profile 下接口文档为 `http://localhost:48080/swagger-ui`（dev 服务器已关闭文档端点）。冒烟接口：`GET /admin-api/transport/test/get`。

### 4. 启动管理端

```bash
cd yudao-ui/yudao-ui-admin-vue3
pnpm install --frozen-lockfile
pnpm dev          # 生产构建：pnpm build:prod
```

`src/api/transport` 与 `src/views/transport` 含数据大盘、车辆监控（百度地图 GL）、调度、订单、车辆、司机、站点、线路、班次、商品等页面，由系统菜单动态挂载。

### 5. 启动小程序

微信开发者工具导入 `miniprogram/` 即可。开发版需在「详情 → 本地设置」勾选「不校验合法域名」。页面已全量对接后端真实接口（登录/商城/寄件/包裹/司机工作台/公告反馈/实时公交），详见 [docs/miniprogram.md](docs/miniprogram.md)。

## 架构与调用边界

```text
管理端 / 微信小程序 ──> Nginx ──> 单体业务后端 ──> 算法适配层 ──> 路线规划算法服务
```

- 管理端与小程序**不得直接调用算法服务**；算法服务**不得直接读写业务数据库**；业务后端负责快照、鉴权、幂等、结果校验与状态落库（见 [docs/architecture.md](docs/architecture.md)）。
- 平台历经一次系统性精简与性能优化（模块/配置/依赖/资产/部署五个层面），实测 JVM 线程 120→56、启动 20.8s→17.8s、服务器可用内存显著提升。完整方案、证据与执行记录见 [docs/slimming-plan.md](docs/slimming-plan.md)。

## 部署

dev 环境由 `.github/workflows/deploy-dev.yml` 持续部署：push 到 `master` 后，服务器本机的 self-hosted runner 按需构建后端 jar 与前端产物，systemd 重启并健康检查；旧 jar 保留 `.bak` 可回滚。服务器一次性配置、runner 注册、备份恢复见 [docs/deployment.md](docs/deployment.md)。

## 文档

| 文档 | 内容 |
|---|---|
| [requirements.md](docs/requirements.md) | 需求基线与 P0/P1/P2 优先级（含实现现状） |
| [architecture.md](docs/architecture.md) | 总体架构、模块边界、调用约束 |
| [database.md](docs/database.md) | 数据库脚本与增量迁移 |
| [algorithm-integration.md](docs/algorithm-integration.md) / [api/algorithm-api.yaml](docs/api/algorithm-api.yaml) | 算法对接契约 |
| [miniprogram.md](docs/miniprogram.md) | 小程序页面、登录链路、联调步骤 |
| [deployment.md](docs/deployment.md) | 部署、CI/CD、Nginx、备份恢复 |
| [slimming-plan.md](docs/slimming-plan.md) | 精简与性能优化方案及执行记录 |
| [docs/README.md](docs/README.md) | 完整文档索引 |

## 当前状态与路线

- **已就绪（P0/P1）**：资源档案与状态、三类订单流转、派单与调度闭环（手工/智能派单、审核下发、发车核验）、**自研路线规划算法接入（OR-Tools）与 Mock 降级**、GIS 监控与轨迹回放、数据大盘、农产品商城全链路、小程序全页面真实接口、证照/保险到期预警、调度方案每站 ETA 与收入/成本估算、算法契约验收套件、CI 与 dev 持续部署。
- **不启动（经 2026-08-23 决策）**：异常工单、财务统计与对账结算（项目用不上）。
- **规划（P2）**：自动滚动调度（需按"半小时批次锁定"契约重新定位）、多版本算法对比、高德路网距离切换。

## 贡献

分支 + PR 协作；master 已开启分支保护（须 PR 且 CI 五项检查全绿合并），详见 [Git 协作指南](docs/Git%20协作.md) 与 [团队分工](docs/team-work.md)。不得提交真实密码、令牌、`.env` 或生产数据。

## 上游同步

`origin` 为本项目远程，`upstream` 仅跟踪 RuoYi-Vue-Pro；上游同步一律走 `chore/sync-upstream-*` 分支评审合并，规则见 [docs/upstream.md](docs/upstream.md)。

## 许可证

[MIT](LICENSE)。上游版权与许可证说明予以保留；业务改造不代表上游项目对本平台的背书。
