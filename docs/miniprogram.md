# 微信小程序说明

`miniprogram/` 是平台的微信小程序（项目名 rural-logistics，appid `wx687e9bf8544ac559`），同时服务两类角色：村民/用户（商城、寄件、包裹查询）与司机（工作台）。小程序与管理端共用同一个 Spring Boot 后端，只调用 `/app-api`，不得直连算法服务。

## 页面结构

底部自定义 tab-bar（`custom-tab-bar/`）：首页、商城、包裹、我的。

- `pages/login/login`、`pages/register/register`：登录与注册。
- `pages/index/index`：首页，含天气展示与平台公告轮播（点击看详情）。
- `pages/goods/goods`、`pages/goods/detail/detail`：农产品商城列表与详情。
- `pages/goods/trace/trace`：商品溯源，商城订单的大巴承运轨迹（地图轨迹线 + 途经站点），从「我的订单」已发货/已完成订单进入。
- `pages/send/send`：寄件下单，支持从地址簿回填收货信息。
- `pages/parcel/parcel`：我的包裹。邮快件列表直显取件码（点击复制）；单号查询展示取件码二维码、承运班次与到达预估（已分配/已发车时）。
- `pages/orders/orders`：农产品商城订单列表（状态筛选、取消订单、溯源入口），从"我的"页进入，不在 tab-bar。
- `pages/mine/mine`、`pages/settings/settings`：我的与设置（含老年人模式、主题颜色，由 `utils/appearance.js` 统一处理）。
- `pages/mine/profile/profile`：个人资料编辑（昵称/头像/性别）与修改密码（短信验证码 scene=3）。
- `pages/mine/address/address`：收货地址管理（member/address 接口，省市区三级选择）；寄件页「地址簿」选择模式回填。
- `pages/mine/feedback/feedback`：意见反馈提交与我的反馈列表（含平台回复）。
- `pages/driver/workbench/workbench`：司机工作台，含待发车 / 行驶中 / 到站停靠三态、班次进度、行李舱运力、到站任务。发车、到站、扫码装车、扫码妥投均为真实写操作；行驶中通过 `wx.getLocation` 每 10 秒上报车辆位置。
- `pages/driver/routes/routes`：今日排班与途经站点。
- `pages/driver/earnings/earnings`：运输收益总览与明细。

## 后端对接现状

- 已对接真实接口：登录注册（member 模块）、商城列表与详情与下单/订单页、寄件下单、包裹查询、我的寄货记录、司机端档案/班次/待装车/收益、平台公告、意见反馈、收货地址、个人资料与修改密码、商品溯源轨迹。
- 平台公告：`GET /app-api/transport/notice/list`（免登录，上架公告按 sort 排序，最多 20 条），管理端「客货邮管理 → 公告管理」发布；首页拉取失败/为空时回退静态演示公告。表 `transport_notice`（`sql/incremental/V007__notice.sql`）。
- 意见反馈：`POST /app-api/transport/feedback/create` + `GET /app-api/transport/feedback/page`（需登录，只查本人），管理端「运维客服 → 意见反馈」回复后小程序可见。表 `transport_feedback`（`sql/incremental/V008__feedback.sql`）。
- 收货地址：`/app-api/member/address/*`（member 模块标准接口），表 `member_address`（DDL 在 `sql/mysql/transport-menu.sql`）。
- 寄货到达预估：`send/track` 响应含 vehiclePlate/shiftCode/targetStation/estimatedArrivalTime/etaMinutes（取该订单最新调度方案明细，ETA 为未来时间才给分钟差），parcel 页据此展示「班次 + 预计到达」。
- 商品溯源：管理端发货时可关联承运车辆/班次（`transport_product_order.vehicle_id/shift_id`，老库走 `transport-schema-incremental.sql` 守卫式 ALTER）；`GET /app-api/transport/product-order/trace?id=`（需登录且本人订单）返回承运信息 + 线路站点 + 当天轨迹点（`transport_vehicle_location_track`，`sql/incremental/V009__vehicle_location_track.sql`，仅班次在途时落历史，上限 2000 点）+ 最新位置；未关联车辆时返回空数据，小程序显示「商品还未发车」。
- 司机端写操作闭环：`POST /app-api/transport/driver/depart`（发车）、`/arrive`（到站/终点完成班次）、`/pickup-confirm`（扫码装车）、`/deliver`（扫码妥投）、`/location`（位置上报）。班次执行状态落 `transport_shift_execution` 表（按天一条），车辆最新位置落 `transport_vehicle_location` 表（每车一行）；两张表见 `sql/incremental/V005__driver_execution.sql`。货运订单状态机：0待调度 → 1已入池 → 2已分配 → 3已发车 → 4已完成（5已取消）。
- 监控中心（管理端）车辆位置：司机上报 5 分钟内的真实位置优先，否则回退按时刻表的插值模拟。
- 实时公交（首页「附近公交 · 实时到站」）：`GET /app-api/transport/bus/realtime`（免登录）复用监控车辆位置，返回线路起终点/下一站/ETA；首页拉取失败时回退静态演示数据。后续完善方向：寄货流程「X班车距村口站还有Y分钟」按站 ETA 细化（当前为整单送达预估）。
- 首页天气走 Open-Meteo 免费接口（无 Key），失败时回退本地模拟（`utils/weather.js`）。
- 附近公交在接口失败/无在线车辆时回退页面内静态演示数据，接口恢复后自动替换。

## 登录鉴权链路

- 请求封装在 `miniprogram/utils/api.js`，统一处理 yudao 响应格式（`{code, msg, data}`）、`Authorization: Bearer <token>` 头与 401 踢回登录页。
- 登录走 `yudao-module-member` 的 `/app-api/member/auth/*`：短信验证码登录（新用户自动注册）、微信小程序一键登录、手机号 + 密码登录。
- 会员数据存 `member_user` 表，由 `sql/mysql/transport-menu.sql` 初始化。

## 环境配置

`utils/config.js` 的 `getBaseUrl()` 按 `envVersion`（develop/trial/release）返回 BASE_URL，三个环境目前指向同一地址（暂无独立体验/正式环境）。本地调试需在微信开发者工具勾选「详情 → 本地设置 → 不校验合法域名」。

## 上线前置条件

- **正式版必须配置 HTTPS 域名**：微信小程序正式环境只允许 HTTPS 请求，后端需有正式域名并配置 TLS 证书，不能继续使用 IP + HTTP。
- **合法域名需在微信公众平台登记**：`request` 合法域名与 `uploadFile` 合法域名是两个独立清单，都要在「微信公众平台 → 开发管理 → 开发设置 → 服务器域名」分别登记；只登记 request 不会自动覆盖 uploadFile。
- **当前配置仅供开发版**：`utils/config.js` 三环境（develop/trial/release）均为 `http://1.15.29.107/api`，仅能在微信开发者工具勾选「不校验合法域名」的开发版下使用；体验版/正式版用该地址会直接请求失败。
- **拍照上传依赖 uploadFile 域名**：寄货拍照（`pages/send/send.js`）与司机装车拍照（`pages/driver/workbench/workbench.js`）经 `utils/api.js` 的 `uploadFile` 上传照片，`uploadFile` 合法域名未登记时，上传在体验版/正式版直接失败。

## 位置权限

`app.json` 声明了 `scope.userLocation` 与 `requiredPrivateInfos`（getLocation/chooseLocation）。司机工作台行驶中每 10 秒通过 `wx.getLocation`（gcj02）获取真实位置并调用 `/app-api/transport/driver/location` 上报，与监控中心共用 `transport_vehicle_location` 数据链路。

## 联调步骤

1. 微信开发者工具「导入项目」选择 `miniprogram/` 目录，使用仓库内 `project.config.json`。
2. 启动本机后端（默认 48080），并确认数据库已执行 `transport-menu.sql`（含 `member_user`、`member_address` 与全部菜单）；商城联调还需 `transport-schema.sql` 中的 `transport_product` 表与 `transport-demo-data.sql` 中的商品演示数据；司机端写操作需 `transport_shift_execution` 与 `transport_vehicle_location` 两张表（新库直接 SOURCE 全量 `transport-schema.sql`，老库按序执行 `sql/incremental/` 的 V005~V009 入口；公告/反馈/轨迹表同理，新库全量、老库 V007/V008/V009）。
3. 勾选「不校验合法域名」后编译，走短信或账号密码登录验证 `/app-api` 连通性。
