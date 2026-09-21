# 5 项需求端到端演示记录（2026-09-12）

> 📦 **存档说明（2026-09-21）**：本记录为比赛演示期的端到端演示证据，所依赖的演示数据与演示设施已随运营化改造（PR #153）删除/下线。仅作历史存档。

## 0. 运行环境（本机真实起服务）

| 组件 | 方式 | 结果 |
|---|---|---|
| MySQL | 本机已运行（127.0.0.1:3306，库 `ruoyi-vue-pro`，root/123456） | 95 张表，transport_* 有演示数据 |
| Redis | 本机已运行（127.0.0.1:6379，带 requirepass） | 后端 Redisson 正常连接 |
| 自研算法服务 | `python -m uvicorn app.main:app --host 127.0.0.1 --port 18081`（algorithm/，带 AMAP_KEY） | 启动成功 |
| 后端 | `java -jar yudao-server/target/yudao-server.jar --spring.profiles.active=local` | `Started YudaoServerApplication in 19.286 seconds`，端口 48080 |
| 小程序 | 微信开发者工具（无法在终端渲染） | 改用「与小程序完全相同的 app-api 接口 + 小程序源码路径核对」验证 |

演示账号：后台 admin/admin123；用户端 13800000000/123456；司机端 13800138001~13800138004/123456。

---

## 需求 1：调度可视化按车辆视角 + 站点转接点

**验证接口**：`GET /admin-api/transport/dispatch/plan/roadmap?id=28`、`GET /admin-api/transport/topology/order?orderId=209`

**观察结果**

```
方案28 roadmap: provider=AMAP 段数=8
  车=101 序号=2 重邮南门货运站 → 海棠溪 provider=AMAP 轨迹点=257
  车=101 序号=3 海棠溪 → 磁器街 provider=AMAP 轨迹点=160
  车=101 序号=6 五公里 → 七公里 provider=AMAP 轨迹点=59
  车=101 序号=7 七公里 → 重邮南门货运站 provider=AMAP 轨迹点=270
  车=102 序号=2 重邮南门货运站 → 龙洲湾枢纽站 provider=AMAP 轨迹点=445
  车=102 序号=3 龙洲湾枢纽站 → 重邮南门货运站 provider=AMAP 轨迹点=432

订单 209（TPDEMO4，南岸邮电大学 → 重大A区）按订单视角：
  段1: 邮电大学 → 福利社·小米熊儿童医院 | 车=渝A·B5202 司机=李伟民 | 需交接=True
  段2: 福利社·小米熊儿童医院 → 小龙坎立交 | 车=渝A·B5203 司机=王守义 | 需交接=True
  段3: 小龙坎立交 → 重大A区 | 车=渝A·B5201 司机=张建国 | 需交接=False
```

**结论**：跨片区订单不是同一辆车「南岸→重大→再回来」，而是在换乘站交给下一位司机（3 段 3 车 3 司机）；
`handoverRequired=True` 的到达站就是地图橙色菱形标注的转接点，标注文案取下一段的司机 + 车牌
（`DispatchVisualDialog.vue` 的 `handoverStationMap` / `linkOrders.handoverTarget` 逻辑与数据一致）。

---

## 需求 2：订单池一键演示 `Data too long for column 'plan_reason'`

**先复现（修复前）**

```
1) 归集入池 code=0 collected=14
2) 智能调度: code=1005007003
   msg=算法结果校验失败：智能调度执行失败：Data truncation: Data too long for column 'plan_reason' at row 1

DB: plan_reason | varchar(500)
已有方案 28 的 plan_reason 实际长度 = 556 字符（>500）
```

**根因**：代码已把 `plan_reason` 截断到 2000 字符，但**线上/演示库的列宽仍是 varchar(500)**；
`transport-schema.sql` 用 `CREATE TABLE IF NOT EXISTS` 不会修改已存在的表，而 `sql/incremental/V020__*.sql`
里的 ALTER 是注释状态，`deploy-dev.yml` 的增量迁移只应用了 V018/V019 —— 没有任何环节会把列改宽。

**修复**：在 `sql/mysql/transport-schema-incremental.sql`（部署时自动执行、幂等）追加
`ALTER TABLE transport_dispatch_plan MODIFY COLUMN plan_reason varchar(2000)`，并对本机库执行。

**修复后验证（一键演示三步全通）**

```
1) 归集入池 code=0 n=6        ② 一键智能调度 code=0 planId=32
3) 审核通过 code=0 data=True

方案32: planning_mode=MULTI_LEG 联运段数=10 转接=4 状态=已下发(1) LENGTH(plan_reason)=681
```

结论：超过 500 字符的方案解释（681 字符）现在可以正常落库，一键演示不再中断。

---

## 需求 3：按订单可视化全部改为真实路线

**验证**：订单视角对「直线估算（ESTIMATED）」段调用 `GET /admin-api/transport/dispatch/plan/route-between`

```
入参：106.573967,29.504969 → 106.542055,29.376765（订单203 五公里 → 龙洲湾枢纽站，库中来源=ESTIMATED）
返回：345 个点；首点/末点与入参一致，中点 106.554003,29.446771（明显偏离直线）
```

订单 209 的三段在库中 `navigation_source=AMAP`，`navigationPolyline` 分别 245/378/65 个点，前端直接绘制。
结论：订单视角没有残留的「两点直线」，只有高德不可用时才会回退并打「直线估算」标签。

---

## 需求 4：完整演示流程（小程序下单 → 后台调度 → 司机端通知 → 用户端通知）

按小程序实际调用的接口逐步执行（`miniprogram/utils/api.js` 中的同一批 URL）：

| 步骤 | 接口 | 结果 |
|---|---|---|
| 1 用户下单 | `POST /app-api/transport/send/create` | 订单 #220 / TP20260912134149873，自动审核通过，状态=待入池 |
| 2 归集入池 | `POST /admin-api/transport/dispatch/order-pool/collect {orderIds:[220]}` | code=0，归集 1 单 |
| 3 一键智能调度 | `POST /admin-api/transport/dispatch/plan/smart {auto:true}` | code=0，方案 #30（3 个站点、1 单货物） |
| 4 审核下发 | `PUT /admin-api/transport/dispatch/plan/review {planId:30,approve:true}` | code=0；方案状态→已下发 |
| 5 用户端通知 | `GET /app-api/transport/notification/page` | 收到 `PLAN_ISSUED 运输方案已下发`、`PLAN_CREATED`、`ORDER_CREATED`、`REVIEW_PASSED` |
| 6 司机端通知 | `GET /app-api/transport/driver/messages?driverId=3` | 收到 `LEG_ASSIGNED 您有新的运输任务：方案#30已分配给您：3个站点、1单货物，请及时接单`（未读 1） |

**过程中发现并修复的缺陷**：小程序的「我的消息」查询只按 `user_id` 过滤，没有过滤 `recipient_type`。
司机通知以 `user_id = 司机编号` 落库，而司机编号与会员编号共用同一 id 空间，
导致**顾客能看见司机的派单通知**（实测：会员 3 的列表里出现「您有新的运输任务」）。

修复：`TransportUserNotificationMapper` 的 `selectPageByUserId / selectUnreadCount / selectListByUserAndOrder`
与 `UserNotificationServiceImpl.markAllAsRead` 增加 `recipient_type = 'USER'` 过滤。

**修复后实测**：用户端 21 条通知全部为用户事件（PLAN_ISSUED×5、PLAN_CREATED×10、ORDER_CREATED×2、
REVIEW_PASSED×2、COMPLETED×1、ORDER_ARRIVED×1），司机事件泄漏数=0；司机端消息中心仍能收到 LEG_ASSIGNED。

---

## 需求 5：司机端地图（公交站点 + 导航）

**验证接口**：`GET /app-api/transport/driver/route?driverId=3`

```
planId=30 vehicleId=3 routeProvider=amap 轨迹点数=1065 站点数=3
  站 重邮南门货运站 (106.602,29.529) 状态=待执行
  站 重大A区      (106.463646,29.566241) 状态=待执行
  站 重邮南门货运站 (106.602,29.529) 状态=待执行

GET /app-api/transport/driver/tasks?driverId=3 → 3 条任务：出发 / 派送(TP20260912134149873) / 返回
```

小程序侧（代码核对）：`pages/driver/routes/routes.js` 用 `route.stops` 生成 `mapMarkers` + `mapPolyline`，
「导航」按钮调用 `wx.openLocation` 打开下一站坐标；`pages/driver/workbench/workbench.js` 由
`route + tasks` 合成 `navPoints` 驱动工作台地图与「导航到 X」。后端返回的站点名/坐标/真实路线
（routeProvider=amap）正好是该页所需数据。

---

## 本次发现的问题清单

| # | 问题 | 状态 |
|---|---|---|
| 1 | `plan_reason` 列宽在老库仍是 500，部署链路不会改宽 → 一键演示必报 Data too long | **已修**（幂等 MODIFY 纳入部署增量脚本） |
| 2 | 司机通知（recipient_type=DRIVER）泄漏到用户端消息列表 | **已修**（用户侧查询加 recipient_type 过滤） |
| 3 | `DispatchPlanRespVO` 没有 `planReason` 字段，`/plan/get` 返回的方案解释恒为空 | 待确认（方案列表/可视化如需要展示原因需补字段；订单视角的 topology 接口有 planReason，不受影响） |
| 4 | 派单失败（如落库异常）时已 CAS 抢占的订单会停留在「已分配」，需要手工跑 `demo-reset.sql` 复位 | 待确认（可用 demo-reset.sql 兜底；如需自动释放可在异常路径补回滚） |
| 5 | `demo-real-orders.sql` 的时间窗是 `NOW()-1h ~ NOW()+10h`，但 ON DUPLICATE KEY UPDATE 不刷新时间列，隔天演示需手工刷新 | 建议在脚本里补 `earliest_pickup_time/latest_delivery_time = VALUES(...)` |

## 未覆盖的部分

- 微信开发者工具中的真实 UI 渲染/点击（终端环境无法运行小程序）；本次用小程序实际调用的同一批
  app-api 接口 + 小程序源码调用路径核对代替。
- 司机端扫码（wx.scanCode）与人脸/拍照核验属于客户端能力，未在终端模拟。
