# 司机端微信小程序说明

`miniprogram/` 是面向司机的微信小程序（项目名 rural-logistics，appid `wx687e9bf8544ac559`），与管理端共用同一个 Spring Boot 后端，只调用 `/app-api`，不得直连算法服务。

## 页面结构

- `pages/login/login`、`pages/register/register`：登录与注册。
- `pages/index/index`：首页。
- `pages/driver/workbench/workbench`：司机工作台，含待发车 / 行驶中 / 到站停靠三态、班次进度、行李舱运力、到站任务。
- `pages/driver/routes/routes`：今日排班与途经站点。
- `pages/driver/earnings/earnings`：运输收益总览与明细。

现状：登录注册已对接后端；三个司机业务页面目前使用页面内静态演示数据，尚未对接后端接口（transport 模块也还没有对应 `/app-api` 端点），对接时需把静态数据替换为真实班次、任务与收益数据。

## 登录鉴权链路

- 请求封装在 `miniprogram/utils/api.js`，统一处理 yudao 响应格式（`{code, msg, data}`）、`Authorization: Bearer <token>` 头与 401 踢回登录页。
- 登录走 `yudao-module-member` 的 `/app-api/member/auth/*`：短信验证码登录（新用户自动注册）、微信小程序一键登录、手机号 + 密码登录。
- 会员数据存 `member_user` 表，由 `sql/mysql/transport-menu.sql` 初始化。

## 环境配置

`api.js` 中 `BASE_URL` 目前硬编码为 `http://localhost:48080`，仅适合本机联调；切换环境的规范方式（区分开发/生产配置）尚未建立，是有待解决的待办。本地调试需在微信开发者工具勾选「详情 → 本地设置 → 不校验合法域名」。

## 位置权限

`app.json` 声明了 `scope.userLocation`（用途：展示公交实时位置和物流轨迹）。当前演示页面未实际上报司机位置，后续车辆实时定位接入后需与监控中心的位置数据链路对齐。

## 联调步骤

1. 微信开发者工具「导入项目」选择 `miniprogram/` 目录，使用仓库内 `project.config.json`。
2. 启动本机后端（默认 48080），并确认数据库已执行 `transport-menu.sql`（含 `member_user`）。
3. 勾选「不校验合法域名」后编译，走短信或账号密码登录验证 `/app-api` 连通性。
