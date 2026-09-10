# 微信小程序说明

`miniprogram/` 是平台的微信小程序（项目名 rural-logistics，appid `wx687e9bf8544ac559`），同时服务两类角色：村民/用户（商城、寄件、包裹查询）与司机（工作台）。小程序与管理端共用同一个 Spring Boot 后端，只调用 `/app-api`，不得直连算法服务。

## 页面结构

底部自定义 tab-bar（`custom-tab-bar/`）：首页、商城、包裹、我的。

- `pages/login/login`、`pages/register/register`：登录与注册。
- `pages/index/index`：首页，含天气展示与平台公告轮播（点击看详情）。
- `pages/goods/goods`、`pages/goods/detail/detail`：农产品商城列表与详情。
- `pages/goods/trace/trace`：商品溯源，商城订单的大巴承运轨迹（地图轨迹线 + 途经站点），从「我的订单」已发货/已完成订单进入。
- `pages/send/send`：寄件下单（货物名称/重量/类型/件数/长宽高体积/是否生鲜/备注），支持从地址簿回填收货信息。物体信息随订单落 `transport_cargo_order`（`cargo_category`/`item_count`/`volume_m3`/`fresh_flag`），「我的寄货」按标签回显；前端限重与后端承运审核规则一致（单件 30kg），超限在提交前提示。
- `pages/parcel/parcel`：我的包裹。邮快件列表直显取件码（点击复制）；单号查询展示取件码二维码、承运班次与到达预估（已分配/已发车时）。
- **车来取货/送货提醒（演示可见）**：`page` 列表与单号查询会显示「班车 距<目标站>约 N 分钟」，距目标站点 **≤10 分钟**时升级为高亮「车快到了」提醒条并弹一次提示（同一订单不重复打扰）。位置来自**统一位置模型**：司机真实上报优先（5 分钟内标"实时"、过期标"位置可能过期"），无上报时用**确定性班次模拟**位置并标注"模拟演示"——因此演示时**不需要司机开 GPS** 也能看到倒计时；车辆位置与目标站点的距离/分钟按 Haversine + 均速 25km/h 估算，随时间刷新（15s）。
- `pages/bus/index`、`pages/bus/detail`：实时公交独立页（车来了式：线路地图 + 车辆列表 + 车辆详情），从首页「附近公交」或快递页进入。
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
- 监控中心（管理端）车辆位置：司机上报 5 分钟内的真实位置优先，否则回退按时刻表的插值模拟（`MonitoringServiceImpl.fillTimetableSimulation`：当前班次窗口内按经停站 `planned_minutes` 线性插值，窗口外空闲停靠起点站，无班次不上图）。
- 实时公交（首页「附近公交 · 实时到站」）：`GET /app-api/transport/bus/realtime`、`/bus/lines`、`/bus/nearby`（均免登录）复用监控车辆位置，返回线路起终点/下一站/ETA/经度纬度；车辆带 `dataSource`（`REAL` 司机真实上报 / `SIMULATED` 时刻表模拟演示），小程序对 `SIMULATED` 显式标注「模拟演示」，不冒充真实位置。真实上报车辆按派单明细/当天班次执行回填班次与线路后才进公交列表（取不到则不上图，不猜线路）。
- 答辩/演示：无需司机开播也能看到在线车辆——班次时刻表数据（`sql/mysql/transport-demo-data.sql` 的 `transport_shift` 06:30–20:30 共 10 班）覆盖运营时段，公交页与首页附近公交在运营时段内会有 3 辆「模拟演示」班车沿真实站点插值移动；超出运营时段显示「当前不在运营时间」空态。后续完善方向：寄货流程「X班车距村口站还有Y分钟」按站 ETA 细化（当前为整单送达预估）。
- 首页天气走 Open-Meteo 免费接口（无 Key），失败时回退本地模拟（`utils/weather.js`）。
- 附近公交无在线车辆时展示「附近站点 + 关联线路 + 当前不在运营时间」空态（接口失败给可重试错误态），不展示硬编码假车辆；车辆位置的模拟/真实来源由 `dataSource` 标注。

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

## 现实公交接入（两种 key，二选一即可）

"附近公交"分两层：**REAL_TRANSIT**（现实公交站点）与 **PROJECT_TRANSIT**（项目自建客货邮线路）+ 模拟车辆（SIMULATED，标注"模拟演示"）。
现实层有两条接入路线，**选一条配置即可**，都没配时不影响项目线路与模拟车辆（页面会注明"未配置现实公交数据源"），绝不伪造。

### 路线 A（推荐给演示：高德微信小程序 key + 客户端 SDK，即官方 wx 插件路线）

1. 高德控制台 → 应用管理 → 创建应用 → 添加 Key → **服务平台选「微信小程序」**，绑定小程序 AppID `wx687e9bf8544ac559`；
2. SDK：`miniprogram/libs/amap-wx.js`（仓库已内置；如需最新版可从高德「微信小程序插件 → 相关下载」解压后覆盖，文件头部即 `function AMapWX(a){...}`，约 8KB）；
3. 微信公众平台 → 管理 → 开发设置 → **request 合法域名加入 `https://restapi.amap.com`**（开发版可在开发者工具勾"不校验合法域名"跳过）；
4. `miniprogram/utils/config.js` 的 `AMAP_MINI_KEY = '<你的 key>'`。

> 当前仓库已配置 `AMAP_MINI_KEY`（微信小程序类型 key，实测可返回真实公交站；`place/around` 的 `address` 字段实为"途经线路"，
> 已解析为站点线路标签）。**上线/体验版前必须完成第 3 步域名登记**，否则真机体验版会直接请求失败。
> 若该 key 需要更换，只需改 `utils/config.js` 一处；后端 Web 服务 key（路线 B）与此互不影响。

生效链路：`utils/transit-amap.js` 用 `AMapWX({key}).getPoiAround({ querytypes: '150700', location })` 拉公交车站 POI →
合并进 `/transport/bus/nearby` 的结果（标注 `dataSource=REAL_TRANSIT`、`transitProvider=AMAP_MINI`），首页与「实时公交」页共用。
特点：key 与 AppID/域名绑定，放小程序里是安全的；不需要后端参与。

### 路线 B（后端代理：高德 Web 服务 key）

在服务器配 `AMAP_KEY=<Web服务 key>`（`/opt/cargo-post/app.env`）或 `yudao.transport.amap.key`（`/opt/cargo-post/config/application-dev.yaml`）。
后端 `AmapTransitProvider` 走 `place/around` 返回真实站点，前端无需 key、无需新增合法域名。
注意：**Web 服务 key 不能放客户端**（可被盗用），只能配在后端。

路由优先级：后端现实层已返回（`realTransitAvailable=true`）→ 客户端不再请求高德（省配额，避免双份数据）；否则客户端层补齐。

## 故障排查：「登录/商城下单/寄货提交」统一失败

## 答辩演示：最短操作路径（含"一键演示"）

后台「班次调度」页顶部新增 **一键演示（归集→调度→审核→核验）** 按钮，复用正式接口与权限校验，现场点一次即完成四步：

1. 归集全部「待入池」订单（`POST /transport/dispatch/order-pool/collect`，`all=true`，免勾选）；
2. 一键智能调度（`POST /transport/dispatch/plan/smart`，`auto=true`：后端自动选场站 + 自动挑候选车辆，算法决定实际车辆数）；
3. 方案自动审核通过（`PUT /transport/dispatch/plan/review`）；
4. 逐车发车核验通过（`POST /transport/dispatch/departure-check`）。

完成后弹窗给出「归集单数 / 方案号 / 订单数 / 车辆数 / 场站」摘要，并提示接着去小程序端演示。

完整演示动线（约 3 分钟）：

1. **用户端**：我要寄货（填货物/体积/类型/件数 + 拍照）→ 提交（承运审核：普通货自动通过 → 待入池）；
2. **后台**：点「一键演示」→ 出方案摘要；
3. **用户端**：快递页「我的寄货」→ 看到车辆「车快到了」高亮提醒（≤10 分钟，模拟位置会标注"模拟演示"）→ 点它跳实时公交看车在动；
4. **司机端**：工作台按方案发车 → 到站/妥投（可选）；
5. **用户端**：首页附近公交（现实公交站点 + 项目线路 + 演示车辆分层）→ 商城下单 → 我的订单。

> 演示前建议清一次旧数据：执行 `sql/mysql/demo-reset.sql`（把 已入池/已分配/已发车 订单放回「待入池」，并清掉今天的调度方案；**不删除会员/商品/站点/班次等基础数据**，含只读校验 SQL），避免一键演示把陈年订单一起派掉。

1. 微信开发者工具「导入项目」选择 `miniprogram/` 目录，使用仓库内 `project.config.json`。
2. 启动本机后端（默认 48080），并确认数据库已执行 `transport-menu.sql`（含 `member_user`、`member_address` 与全部菜单）；商城联调还需 `transport-schema.sql` 中的 `transport_product` 表与 `transport-demo-data.sql` 中的商品演示数据；司机端写操作需 `transport_shift_execution` 与 `transport_vehicle_location` 两张表（新库直接 SOURCE 全量 `transport-schema.sql`，老库按序执行 `sql/incremental/` 的 V005~V009 入口；公告/反馈/轨迹表同理，新库全量、老库 V007/V008/V009）。
3. 勾选「不校验合法域名」后编译，走短信或账号密码登录验证 `/app-api` 连通性。

## 故障排查：「登录/商城下单/寄货提交」统一失败

现象：商品能浏览、公交/站点能加载（读接口正常），但发短信验证码、登录、商城下单、寄货提交一律失败，后端返回 `{"code":500,"msg":"系统异常"}`。

判定：这是**后端写链路**问题，不是小程序问题（小程序只能用弹窗把真实原因说清楚：`pages/goods/detail/detail.js` 下单失败弹窗显示后端 `msg`；401 会记录来源页，重新登录后回到原页，收货信息不丢）。

排查顺序（服务器上执行，脚本见 [deployment.md](deployment.md)）：

1. `bash deploy/scripts/diagnose-backend.sh`：一次输出健康检查、读写链路探测、磁盘、本机 Redis/MySQL 自检与日志。
2. 若第 3/4 步（发验证码/登录）返回 500 且本机 Redis 读写正常 → 后端连的 Redis（默认 yudao 公共演示 Redis）写命令被拒，按脚本结论把后端 Redis 指到本机容器并重启；部署流水线已内置同样的自检与切换（`Ensure backend has a writable Redis`）。
3. 若磁盘满或 MySQL 只读 → 先释放空间/恢复挂载，再重启后端并复测。
