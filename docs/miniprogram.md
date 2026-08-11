# 微信小程序说明

`miniprogram/` 是平台的微信小程序（项目名 rural-logistics，appid `wx687e9bf8544ac559`），同时服务两类角色：村民/用户（商城、寄件、包裹查询）与司机（工作台）。小程序与管理端共用同一个 Spring Boot 后端，只调用 `/app-api`，不得直连算法服务。

## 页面结构

底部自定义 tab-bar（`custom-tab-bar/`）：首页、商城、包裹、我的。

- `pages/login/login`、`pages/register/register`：登录与注册。
- `pages/index/index`：首页，含天气展示。
- `pages/goods/goods`、`pages/goods/detail/detail`：农产品商城列表与详情。
- `pages/send/send`：寄件下单。
- `pages/parcel/parcel`：我的包裹。
- `pages/mine/mine`、`pages/settings/settings`：我的与设置（含老年人模式、主题颜色，由 `utils/appearance.js` 统一处理）。
- `pages/driver/workbench/workbench`：司机工作台，含待发车 / 行驶中 / 到站停靠三态、班次进度、行李舱运力、到站任务。
- `pages/driver/routes/routes`：今日排班与途经站点。
- `pages/driver/earnings/earnings`：运输收益总览与明细。

## 后端对接现状

- 已对接真实接口：登录注册（member 模块）、商城列表与详情（`/app-api/transport/product/list`、`/app-api/transport/product/get`，对应 transport 商品模块的后台 CRUD 与管理端商品页）。
- 首页天气走 Open-Meteo 免费接口（无 Key），失败时回退本地模拟（`utils/weather.js`）。
- 仍为页面内静态演示数据：寄件、包裹、我的、司机工作台/路线/收益——后端对应 `/app-api` 端点尚未提供，对接时需替换为真实数据。

## 登录鉴权链路

- 请求封装在 `miniprogram/utils/api.js`，统一处理 yudao 响应格式（`{code, msg, data}`）、`Authorization: Bearer <token>` 头与 401 踢回登录页。
- 登录走 `yudao-module-member` 的 `/app-api/member/auth/*`：短信验证码登录（新用户自动注册）、微信小程序一键登录、手机号 + 密码登录。
- 会员数据存 `member_user` 表，由 `sql/mysql/transport-menu.sql` 初始化。

## 环境配置

`api.js` 中 `BASE_URL` 目前硬编码为 `http://localhost:48080`，仅适合本机联调；切换环境的规范方式（区分开发/生产配置）尚未建立，是有待解决的待办。本地调试需在微信开发者工具勾选「详情 → 本地设置 → 不校验合法域名」。

## 位置权限

`app.json` 声明了 `scope.userLocation`（用途：展示公交实时位置和物流轨迹）。当前页面未实际上报司机位置，后续车辆实时定位接入后需与监控中心的位置数据链路对齐。

## 联调步骤

1. 微信开发者工具「导入项目」选择 `miniprogram/` 目录，使用仓库内 `project.config.json`。
2. 启动本机后端（默认 48080），并确认数据库已执行 `transport-menu.sql`（含 `member_user`）；商城联调还需 `transport-schema.sql` 中的 `transport_product` 表与 `transport-demo-data.sql` 中的商品演示数据。
3. 勾选「不校验合法域名」后编译，走短信或账号密码登录验证 `/app-api` 连通性。
