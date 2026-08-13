# 2026-08-13 部署修复：checkout 竞速下载 + 迁移补 13 列漂移

- **deploy-dev.yml checkout 改 8 流竞速 tarball**：服务器直连 github 实测每流仅 ~25KB/s 且随机被重置，代理订阅 44 节点全灭，codeload 不支持 Range（无法续传/分段），git 单流必断；竞速任一流完整即胜出（PR #45 的 git 重试 → PR #46 竞速 tarball）。
- **transport-schema-incremental.sql 补 13 列**：活库建于 07-23，此后 PR 只改 CREATE TABLE IF NOT EXISTS（不给老表补列）→ demo-data 报 `Unknown column 'batch_start'`，这才是 PR #42 起迁移失败的真因（#44 的"MySQL 不可达"为误诊）。补齐：`transport_order.member_user_id`、`transport_cargo_order` 6 列（goods_name/note/photo_url/receiver_*）、`transport_dispatch_task` 3 列（batch_start/batch_end/error_message）、`transport_dispatch_plan` 2 列（mode/total_distance）、`transport_dispatch_plan_item.station_id`。已在活库手动应用 + demo-data 全链路验证通过。

---

# 2026-08-12 司机写闭环安全加固 + 监控执行视图 + 前端完善

## 一、写接口安全（司机身份从登录态解析）

- `transport_driver` 无需改表：写端点（depart/arrive/pickup-confirm/deliver/location）一律 `getLoginUserId()` → member 模块 `MemberUserApi.getUser(id)` 取手机号 → `transport_driver.mobile` 解析当前司机；客户端 `driverId` 仅做一致性校验（不一致 → `DRIVER_IDENTITY_MISMATCH`）。`profile` 同改登录态解析（去掉 mobile 参数）。
- `transport` pom 新增 `yudao-module-member` 依赖（yudao-server 已启用两模块）。

## 二、订单归属 + 到站校验 + 运力落库 + CAS

- deliver/pickup-confirm 校验订单在该司机**已下发/执行中**的调度方案明细（`dispatch_plan_item`），否则 `DRIVER_ORDER_NOT_ASSIGNED`。
- arrive 校验站点属于班次线路且按 sequence 顺序推进（防跳站），终点站才完成班次。
- `transport_shift_execution` 新增 `loaded_count`（V006）：装车校验 `vehicle.cargo_capacity` 上限并累加，妥投递减。
- deliver/pickup-confirm 改条件更新 CAS（`where status=?`），影响 0 行报 `DRIVER_ORDER_STATUS_ILLEGAL`，防重复提交。
- `pickups()` 按司机派单过滤；`shifts()` 返回真实 `loadedCount/currentStationId`。

## 三、后台监控中心执行视图

- `/monitoring/shift-execution` 优先读 `transport_shift_execution` 真实执行记录（司机/车辆/当前站/已装件数/发到站时间），无记录回退时钟推导；与司机端三态一致。管理端监控页「今日班次」展示司机·车牌·当前站·已装件数。

## 四、小程序司机端完善

- workbench/routes 用后端真实 `loadedCount`（运力）与 `currentStationId`（恢复进度/当前站）。
- 发车/到站/扫码加防双击 `submitting` 锁；位置上报 `onHide` 清定时器、`onShow` 恢复；getLocation 权限被拒弹窗引导去设置。

## 五、演示数据与文档

- `transport-demo-data.sql` 补司机1（张建国）/车辆1/班次1 派单明细（plan status=2 执行中，订单4/5），装车/妥投归属校验可演示。
- `docs/database.md` 补 V005/V006；`.claude/architecture-current.md` 同步。

## 验证

- 管理端 `pnpm build:prod` 通过；小程序 `node --check` 各 js 通过。
- 后端单测已补（DriverAppServiceImplTest 19 例：登录态解析/身份不符/归属/跳站/货仓满/CAS 幂等/loaded_count），**本机无 maven，需在有 maven 环境跑 `mvn -pl yudao-module-transport test -Dtest=DriverAppServiceImplTest`**。

---

# 2026-08-11（续三）司机端小程序同步更新 + 算法接入准备

## 概述

司机端三页（工作台/今日排班/收益）由纯 mock 同步为真实后端数据（班次/线路/站点/车辆/货运订单），并为**算法组实时路径规划**提前做结构准备。

## 一、司机身份（手机号匹配）

- 后端 `GET /app-api/transport/driver/profile?mobile=`：按登录会员手机号匹配 `transport_driver.mobile`，返回司机档案 + 绑定车辆（`transport_driver_vehicle` 有效绑定）运力
- 测试用 `13800138001` 登录即绑定司机「张建国」(id=1)；查不到档案前端提示"未找到司机档案"

## 二、工作台 / 今日排班（真实班次 + 经停站点）

- `GET /app-api/transport/driver/shifts`：启用班次（`transport_shift`）+ 经停站点序列（`transport_route_station`+`transport_station`，带坐标）+ 班次三态（未发车/在途/已完成，复用监控计算逻辑）
- 工作台展示真实司机/车牌/货仓件数运力 + 今日班次地图（真实坐标 markers/polyline）+ 待装车任务（`GET /app-api/transport/driver/pickups`：待处理货运订单）
- 今日排班页：班次卡片 + 站点时间线 + 状态（待发车/进行中/已完成）
- 三态行驶模拟保留前端（无实时位置上报），但站点序列/进度/任务用真实数据

## 三、收益页（真实运营统计）

- `GET /app-api/transport/driver/earnings`：今日计划班次、今日/累计货运订单数、今日/累计订单总额、待装车任务数、最近订单明细
- 不建结算表、不做抽成（财务结算后续另立），文案标注"运营统计"

## 四、算法接入准备（算法组后续实时调度）

- **修 `DispatchServiceImpl.insertPlanItems`**：落库补填 `driverId`（按车辆当前有效人车绑定解析），保证算法/手工派单结果按司机可查
- `DispatchPlanItemMapper` 新增 `selectListByDriverId` / `selectListByVehicleId`
- App 预留 `GET /app-api/transport/driver/tasks?driverId=`：按司机查已下发/执行中调度方案明细（站点/动作/订单/到达时间），当前无派单数据 → 空列表，算法接入后司机端自动出现实时路径任务
- 管理平台监控已预留"调度闭环落地后切换真实派单结果"注释

## 验证

- 后端 `mvn -pl yudao-module-transport -am compile` 通过
- 小程序 JS 语法检查通过；发布需微信开发者工具上传
- 端到端（部署后）：用 `13800138001` 登录 → 切换司机模式 → 工作台/路线/收益真实数据

---

# 2026-08-11（续二）商品下单闭环 + 寄货/包裹接后端 + 我的联动

## 概述

补齐小程序三大功能闭环，把寄货页（send）与包裹页（parcel）的 mock 数据全部替换为真实后端接口，新增农产品商城下单（货到付款）全链路。全部在 transport 模块内实现（服务器仅启用 system/infra/transport/member 四模块）。

## 一、农产品商城下单闭环

**数据库**（`sql/mysql/transport-schema.sql`）
- 新增 `transport_product_order`（订单主表）+ `transport_product_order_item`（明细表，下单商品快照）
- `sql/mysql/transport-menu.sql` 新增商品订单菜单 6890-6893

**后端**（`yudao-module-transport`）
- 商品订单模块：`ProductOrderDO/ItemDO`、`ProductOrderMapper`（含 `deductStock` 带 `stock>=qty` 条件防超卖）、`ProductOrderService`（下单/我的订单/取消/发货/完成）
- App 接口：`/app-api/transport/product-order/create|page|cancel`（需登录，`getLoginUserId`）
- 管理接口：`/admin-api/transport/product-order/page|get|ship|complete`，权限 `transport:product-order:*`
- 错误码段 `1_005_010_xxx`

**管理端**：`api/transport/productOrder/index.ts` + `views/transport/productOrder/index.vue`（列表/查看/发货/完成）+ `ProductOrderDetail.vue`

**小程序**：详情页「立即购买」弹窗下单（数量步进 + 收货人/电话/地址/备注）；新增 `pages/orders/` 我的订单列表（状态 tab + 取消）；「我的」页订单入口打通

## 二、寄货功能接后端

- `transport_order` 增列 `member_user_id`；`transport_cargo_order` 增列 `goods_name/goods_note/photo_url/receiver_*`
- App 接口：`/app-api/transport/send/create`（创建货运订单，status=0 待调度，天然可被管理端调度归集派单）、`/app-api/transport/send/page`（我的寄货）、`/app-api/transport/send/stations`（站点列表）
- `send.js` 改为真实提交：站点选择 + 拍照 + 收货信息 → 创建订单 → 显示订单号，等待调度排班

## 三、包裹查询接后端

- `parcel.js` 接真实数据：tab「我的寄货」(`pageMySendOrders`) + 「单号查询」(`trackParcel`)
- 按 `TransportOrderStatusEnum`(0待调度…5已取消) 渲染进度条 + 时间轴
- 「我的」页新增「我的寄货」入口（switchTab + globalData 传意图）

## MVP 简化（后续可增强）

- 不做「下单即匹配班次」（寄货成功后由管理端调度闭环负责排班）
- 不做独立轨迹事件表（时间轴由订单状态渲染）
- 照片暂存本地路径（未接 OSS）
- 司机端仍为 mock

## 验证

- 后端 `mvn -pl yudao-module-transport -am compile` 通过
- 管理端 `pnpm build:prod` 通过（install 用 npmmirror 镜像源）
- 小程序 JS 语法检查通过；发布需微信开发者工具上传
- 端到端需合入 master 部署后验证

---

# 2026-08-11 小程序重构 — 底部导航 + 四大界面

## 概述

为客货邮小程序新增底部四 Tab 导航，新建商城/快递/我的页面，保留首页完整内容，统一 UI 风格，修复性能问题。

---

## 新增文件

```
miniprogram/custom-tab-bar/index.*        # 自定义底部导航组件（白底绿选中态）
miniprogram/pages/goods/goods.*           # 商城 tab — 买家界面（分类筛选 + 商品网格）
miniprogram/pages/parcel/parcel.*         # 快递 tab — 物流追踪（单号查询 + 时间轴）
miniprogram/pages/mine/mine.*             # 我的 tab — 个人中心 + 农户【我要寄货】入口
miniprogram/pages/send/send.*             # 寄货流程（填信息 → 拍照 → 智能匹配倒计时）
miniprogram/pages/settings/settings.*     # 设置页（老年人模式 + 主题颜色切换）
miniprogram/utils/weather.js              # 天气服务模块（Open-Meteo 免费 API + 降级）
```

## 修改文件

| 文件 | 改动 |
|------|------|
| `app.json` | 添加 `tabBar.custom` 配置 + 5 个新页面路由 + `requiredPrivateInfos: [getLocation]` |
| `app.js` | 添加 `globalData.currentVillage` / `elderlyMode` / `themeColor` |
| `app.wxss` | 卡片阴影升级为双层阴影、页面入场淡入动画、按钮按压反馈 |
| `pages/index/index.wxml` | 新增天气卡片（蓝色系）、悬浮寄货 FAB 按钮、修复 emoji 图片加载 500 错误 |
| `pages/index/index.wxss` | 底部 padding 适配 tabBar、天气卡片样式、FAB 样式、卡片阴影升级 |
| `pages/index/index.js` | 天气 API 调用 + 动态 mock 降级、四宫格跳转真实页面、状态栏适配 |
| `components/driver-tab-bar/index.js` | 新增"用户版"tab，切换回主界面 |
| `utils/api.js` | BASE_URL 改为服务器地址 `http://1.15.29.107/api` |

## Tab 架构

```
🏠 首页      🛒 商城       📦 快递       👤 我的
(保留原内容)  (买家界面)    (物流追踪)    (卖家入口)
```

## 关键修复

1. **微信登录"网络不可用"** — `api.js` BASE_URL 从 `localhost:48080` 改为 `http://1.15.29.107/api`
2. **首页 emoji 图片 500 错误** — `<image src="🍵">` 改为 `<view><text>🍵</text></view>`
3. **状态栏遮挡** — 所有页面使用 `wx.getSystemInfoSync().statusBarHeight` 动态适配
4. **Tab 切换卡顿** — TabBar 改用 `pageLifetimes.show()` 同步 + 点击立即 `setData` + 增大触摸区至 100rpx
5. **定位权限报错** — `app.json` 添加 `requiredPrivateInfos: ["getLocation"]`
6. **司机端无法回到用户端** — driver-tab-bar 新增"用户版"入口

## 待办

- [ ] 设置页开关无响应（已改为 inline 集成到"我的"页，仍需排查 tap 事件）
- [ ] 真实天气精度不足（Open-Meteo 全球模型，后续可换国内 API）
- [ ] 逆地理编码（OpenStreetMap 国内被墙，腾讯 LBS 待配置 Key）
- [ ] 老年人模式全局生效（目前仅存储状态，未做全量字体缩放）
- [ ] 主题颜色全局生效（目前仅在"我的"页即时响应，其他页面需 onShow 重读）

---

# 2026-08-11（续）商品全栈功能 + 天气/外观优化 + 体验完善

## 概述

在底部导航重构基础上，补齐小程序天气体验、外观主题系统、商品全栈（后端+运营平台+小程序），并做交互与工程打磨。

## 一、商品（农产品）全栈功能 — 已合入 master（PR #34）

**数据库**（`sql/mysql/`）
- `transport-schema.sql`：新增 `transport_product` 表（名称唯一索引、utf8mb4）
- `transport-demo-data.sql`：6 条演示商品（id 1-6，高山脆李🍑/土鸡蛋🥚/红薯粉🍜/山核桃🥜/龙井茶🍵/腊肉🥩）
- `transport-menu.sql`：商品管理菜单 6880-6884

**后端**（`yudao-module-transport`）
- 后台 CRUD：`/admin-api/transport/product/*`（增删改查/分页/精简列表，权限 `transport:product:*`）
- 小程序接口：`/app-api/transport/product/list|get`（`@PermitAll` 放行浏览）
- 名称唯一校验（`PRODUCT_NAME_DUPLICATE`），错误码段 `1_005_009`

**运营平台**（`yudao-ui-admin-vue3`）
- `api/transport/product/index.ts` + `views/transport/product/index.vue` + `ProductForm.vue`（商品管理页）

**小程序**
- `utils/api.js`：新增 `listProducts` / `getProduct`
- `pages/goods/goods.js`：列表改拉后端真实上架商品；`goToDetail` 跳详情页
- 新增 `pages/goods/detail/`：商品详情页（大图/价格/产地/库存/描述/底部操作栏，支持老年模式+主题色）
- `app.json` 注册详情页；首页推荐商品点击也能进详情

## 二、天气优化（`utils/weather.js` + 首页）

- 缓存/兜底**秒开**、防重复请求
- **ECMWF IFS 高精度模型**（失败自动降级 best_match → 本地兜底天气）
- **风级换算修正**：Open-Meteo 风速单位是 km/h，之前直接当"级"显示（10 km/h 变"10级"），新增蒲福风级换算
- 免 Key 逆地理（BigDataCloud）显示真实地名；高精度定位 `isHighAccuracy`
- 天气图标加白色圆底、更新时间/加载中/兜底提示

## 三、外观系统（`utils/appearance.js`）

- 新增统一机制：老年模式 + 主题色（绿/橙/蓝），CSS 变量驱动
- 全页面接入：首页/商城/快递/我的/设置/登录/注册/寄货 + 自定义 TabBar 联动
- 设置页/我的页切换即时生效，其它 tab 页 onShow 自动同步

## 四、交互与工程

- 按钮 `hover-class` 即时反馈 + transition 提速到 0.15s（消除按压延迟）
- 弃用 API：`getSystemInfoSync`→`getWindowInfo`、`chooseImage`→`chooseMedia`
- **微信一键登录**：`getPhoneNumber` + `wx.login` → `wechatMiniAppLogin`（接口后端已存在）
- **BASE_URL 环境配置**：`utils/config.js` 按 develop/trial/release 自动切换
- 商城加载中转圈 + 空列表提示；首页推荐商品改拉后端真实数据（前 4 条）

## 待办（明天继续）

- [ ] **部署卡住**：PR #34 合入 master 后 `deploy-dev.yml` 卡在"Waiting for a runner"——需**队友重启服务器 self-hosted runner**（`cd /opt/actions-runner && sudo ./svc.sh restart`），部署完成后商品 API 才生效
- [ ] **体验完善包 PR**：`feat/miniprogram-polish`（微信登录/config/chooseMedia/loading/首页推荐）已推送，待合入 master；合入后小程序需微信开发者工具手动上传
- [ ] **验证码次数限制**：`yudao-server` 的 `application.yaml` 里 `send-maximum-quantity-per-day: 10` 太紧，测试可调大
- [ ] 商品 API 部署生效后，验证商城列表/详情/运营平台商品管理
- [ ] 下一步可选：下单闭环（详情页"立即购买"接真实订单）、寄货/我的订单接后端、公交/快递查询接后端

## 明天注意

- 服务器（1.15.29.107）由**队友管理**，用户无 SSH 权限；服务器侧问题转队友
- 小程序端发布不走 CI，需微信开发者工具上传
