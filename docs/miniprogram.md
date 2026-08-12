# 微信小程序说明

`miniprogram/` 是平台的微信小程序（项目名 rural-logistics，appid `wx687e9bf8544ac559`），同时服务两类角色：村民/用户（商城、寄件、包裹查询）与司机（工作台）。小程序与管理端共用同一个 Spring Boot 后端，只调用 `/app-api`，不得直连算法服务。

## 页面结构

底部自定义 tab-bar（`custom-tab-bar/`）：首页、商城、包裹、我的。

- `pages/login/login`、`pages/register/register`：登录与注册。
- `pages/index/index`：首页，含天气展示。
- `pages/goods/goods`、`pages/goods/detail/detail`：农产品商城列表与详情。
- `pages/send/send`：寄件下单。
- `pages/parcel/parcel`：我的包裹。
- `pages/orders/orders`：农产品商城订单列表（状态筛选、取消订单），从"我的"页进入，不在 tab-bar。
- `pages/mine/mine`、`pages/settings/settings`：我的与设置（含老年人模式、主题颜色，由 `utils/appearance.js` 统一处理）。
- `pages/driver/workbench/workbench`：司机工作台，含待发车 / 行驶中 / 到站停靠三态、班次进度、行李舱运力、到站任务。发车、到站、扫码装车、扫码妥投均为真实写操作；行驶中通过 `wx.getLocation` 每 10 秒上报车辆位置。
- `pages/driver/routes/routes`：今日排班与途经站点。
- `pages/driver/earnings/earnings`：运输收益总览与明细。

## 后端对接现状

- 已对接真实接口：登录注册（member 模块）、商城列表与详情与下单/订单页、寄件下单、包裹查询、我的寄货记录、司机端档案/班次/待装车/收益。
- 司机端写操作闭环：`POST /app-api/transport/driver/depart`（发车）、`/arrive`（到站/终点完成班次）、`/pickup-confirm`（扫码装车）、`/deliver`（扫码妥投）、`/location`（位置上报）。班次执行状态落 `transport_shift_execution` 表（按天一条），车辆最新位置落 `transport_vehicle_location` 表（每车一行）；两张表见 `sql/incremental/V005__driver_execution.sql`。货运订单状态机：0待调度 → 1已入池 → 2已分配 → 3已发车 → 4已完成（5已取消）。
- 监控中心（管理端）车辆位置：司机上报 5 分钟内的真实位置优先，否则回退按时刻表的插值模拟。
- 首页天气走 Open-Meteo 免费接口（无 Key），失败时回退本地模拟（`utils/weather.js`）。
- 仍为页面内静态演示数据：首页公告与附近公交——后端对应 `/app-api` 端点尚未提供，对接时需替换为真实数据。

## 登录鉴权链路

- 请求封装在 `miniprogram/utils/api.js`，统一处理 yudao 响应格式（`{code, msg, data}`）、`Authorization: Bearer <token>` 头与 401 踢回登录页。
- 登录走 `yudao-module-member` 的 `/app-api/member/auth/*`：短信验证码登录（新用户自动注册）、微信小程序一键登录、手机号 + 密码登录。
- 会员数据存 `member_user` 表，由 `sql/mysql/transport-menu.sql` 初始化。

## 环境配置

`utils/config.js` 的 `getBaseUrl()` 按 `envVersion`（develop/trial/release）返回 BASE_URL，三个环境目前指向同一地址（暂无独立体验/正式环境）。本地调试需在微信开发者工具勾选「详情 → 本地设置 → 不校验合法域名」。

## 位置权限

`app.json` 声明了 `scope.userLocation` 与 `requiredPrivateInfos`（getLocation/chooseLocation）。司机工作台行驶中每 10 秒通过 `wx.getLocation`（gcj02）获取真实位置并调用 `/app-api/transport/driver/location` 上报，与监控中心共用 `transport_vehicle_location` 数据链路。

## 联调步骤

1. 微信开发者工具「导入项目」选择 `miniprogram/` 目录，使用仓库内 `project.config.json`。
2. 启动本机后端（默认 48080），并确认数据库已执行 `transport-menu.sql`（含 `member_user`）；商城联调还需 `transport-schema.sql` 中的 `transport_product` 表与 `transport-demo-data.sql` 中的商品演示数据；司机端写操作需 `transport_shift_execution` 与 `transport_vehicle_location` 两张表（新库直接 SOURCE 全量 `transport-schema.sql`，老库执行 `sql/incremental/V005__driver_execution.sql`）。
3. 勾选「不校验合法域名」后编译，走短信或账号密码登录验证 `/app-api` 连通性。
