# 2026-09-11 司机端 → 用户端闭环打通（返场确认 / 用户端到站提醒 / 商城订单同理）

- **修掉"一键演示生成的方案装不了车"**：智能派单的经停明细不绑定固定班次（`shift_id` 为空），`pickupConfirm` 原先直接抛 `DRIVER_SHIFT_EXECUTION_NOT_EXISTS` → 司机扫码装车走不下去。现按"司机今天实际发车的那条执行记录"兜底（`ShiftExecutionMapper.selectListByDriverAndDate`），装车/妥投的执行记录口径一致；单测覆盖"明细无班次仍能装车"。
- **班次不再串片区**：司机工作台新增 `pickShiftForNav`，选"经停站与本次任务段重合度最高"的班次（同分在途优先）；发车/表头线路名与地图任务段一致（重邮片区就显示重庆邮电大学—黄桷垭线）。
- **发车后不再提示"下一站=出发点"**：恢复进度时跳过只有 DEPART/RETURN、没有取派/上下客作业的经停场站。
- **返场确认补回队尾**：`buildNavPoints` 按站点去重会把"出发/返回同一场站"折叠成首站，导致司机端没有返场入口、班次执行记录永远不结束。现把计划末站补回队尾并标记 `isReturn`，前端显示「🏁 返场确认」；返场后 `shift_execution` 完成，用户端"司机已到达交付点"才有数据源（`nav.test.js` 覆盖）。
- **用户端「司机已到达」提醒（寄货）**：`AppSendOrderRespVO` 新增 `carrierArrived/ carrierArrivedStation/ carrierTaskStatus/ carrierArrivedTime/ carrierLoaded/ carrierDelivered/ driverName/ driverMobile`，由派单经停明细状态推导（已到站/揽收中/派送中/已完成）；`fillCarrierBatch` 现在对已完成订单也填充进度（不依赖车辆位置）。小程序「我的寄货 / 查件」显示红色到达横幅（含司机姓名电话），首次触发弹一次提示，装车/妥投后分别显示"已揽收装车 / 已完成派送"。
- **商城订单同样走司机作业闭环（同理寄货）**：新增 `transport_product_order` 的 `driver_id / deliver_station_id / load_photo_url / load_time / deliver_photo_url / deliver_time`（全量 schema + 增量迁移，部署自动执行）；发货时按人车绑定写入承运司机、交付站点=班次线路终点站；`/driver/pickups` 带上本车待执行商城订单（`bizType=PRODUCT`，orderType=4，🛒 展示），新增 `POST /driver/product-load`（装车拍照核验）与 `POST /driver/product-deliver`（妥投交付凭证，订单转已完成）。
- **用户端商城订单可见配送全流程**：溯源 VO 补 `orderNo/status/statusName/receiver*/driverName/driverMobile/deliverStationName/driverArrived/loadTime/loadPhotoUrl/deliverTime/deliverPhotoUrl`（`driverArrived` 由班次执行记录的当前站点 vs 交付站点推导）；溯源页新增订单状态、承运司机、到达/装车/已送达提醒、装车与妥投凭证照片预览、订单二维码（司机扫码用）；商城订单列表对已发货/已完成给出"司机配送中 / 已送达"提示。
- **后台商城订单可见司机执行**：`ProductOrderRespVO` 补承运车牌/班次/司机/交付站点/装车与妥投照片，列表新增「承运车辆/司机」「交付站点」「装车核验」「妥投凭证」列；发货弹窗要求必选车辆+班次并说明"发货即派单给司机"；`vehicle/simple-list` 补绑定司机（发货/派单选车时能看到"这车谁开"，派单页面车辆名同样带司机）。

---

# 2026-09-11 答辩主链路收口：审核不再被打回 + 一键调度分片区 + 调度结果可视化

- **后台订单管理补齐"寄货全链路"字段**：`TransportOrderRespVO`/`toVO` 新增 `originalAddress/原坐标`、`pickupServiceMode/deliveryServiceMode`、`servicePointStationId/Name`、`reviewStatus/reviewReasonCodes`、`cargoCategory/freshFlag/cargoItemCount/cargoVolumeM3`，并回填 `pickupStationName/deliveryStationName`（站点名一次查表映射，无 N+1）；管理端订单列表新增「订单状态 / 用户寄货位置 / 交接服务站 / 物品信息（类别·件数·重量·体积·生鲜）」列与「详情」弹窗，审核弹窗同步显示取货方式与用户位置——"人在重庆邮电大学明志苑寄货、车去重庆邮电大学站接"在后台一眼可见。
- **取货方式随单落库**：`AppSendOrderCreateReqVO` 新增 `pickupServiceMode`，小程序可达性评估（车辆进不去校园 → `NEAREST_STATION`）随订单写入 `transport_cargo_order.pickup_service_mode`；`applyAutoReview` 以客户端值为准，仅缺省时才用承运审核推导值，寄货成功卡按服务方式显示「车辆上门交接 / 就近站点交接」。
- **修复「审核已自动通过的订单」报错**：村民提交后自动审核通过的订单直接是「待入池(8)」，此前管理员在订单管理点「审核通过」会抛 `CARGO_AUDIT_STATUS_ILLEGAL`（演示中断）。现改为**幂等复核确认**：只记录审核结论、不改变生命周期（绝不把订单从池里打回），复核不通过则带原因取消订单；原有"待审核/需人工审核才可审"的状态机与单测口径不变。
- **调度工作台默认展示待入池**：订单池默认筛选由 `status=1` 改为不过滤（后端订单池口径为「待入池+已入池」），刚审核通过、还没归集的订单不再"消失"；筛选下拉同步收敛为这两个状态。
- **一键调度按"片区"分批，避免跨城混批无解**：新增 `AutoDispatchPlanner.selectAutoBatch`（最新订单优先 → 以该单起终站为锚点，`≤50km` 视为同片区；跨片区订单留在池里，下一次调度自动成第二套方案）+ `DispatchServiceImpl.createSmartPlan/validate` 接入；单批仍受算法上限约束。管理端「一键调度 / 一键演示」改为循环出多套方案（最多 4 套）并逐套审核，完成后一次展示全部方案结果。
- **同车不并发占用**：`AutoDispatchPlanner.selectVehicles` 新增"排除已被在途方案（待审核/已下发/执行中）占用车辆"的重载，自动模式优先避让，全部在途时回退不排除——多片区连出多套方案时不会把两个片区的经停塞给同一台车（司机端任务不再串片区）。
- **调度结果可视化（管理端）**：新增 `DispatchVisualDialog`——一键演示/一键调度后就地展开：方案摘要（方案/订单/车辆/总里程）+ 每车**任务段时间线**（场站发车 → 揽收/派送/上下客 → 返场，带站点名、订单号、预计到达时间）+ 地图（百度 BMapGL，GCJ-02→BD-09 换算）按车分色画经停线路与站点气泡 + **▶ 播放路线**（多车同步沿线移动，可拖进度条）；地图 SDK 不可用时自动降级为"真实坐标线路示意图"，演示不会因为没配地图 key 而中断。方案列表每行新增「可视化」入口，方案详情弹窗显示订单号与站点名。
- **经停明细回填展示字段**：`DispatchPlanItemDO` 新增 `@TableField(exist=false)` 的 `stationName/orderNo`，`getPlan` 批量补齐，方案详情/可视化无需再逐条回查。
- **`station/simple-list` 返回坐标**：`StationSimpleRespVO` 补 `longitude/latitude`，管理端站点下拉与调度可视化可直接取坐标。
- **校园片区演示订单**：`sql/mysql/demo-cqupt-stations.sql` 追加南山站 + 3 单重庆邮电大学片区货运订单（待入池，时间窗用 `NOW()` 相对值，避免算法按历史窗口判不可行），让"调度工作台里不止我这一单、同片区还有其他模拟订单"可演示；成都片区订单留在池里，第二次调度自动成第二套方案。

---

# 2026-09-10 寄货物体体积/信息 + 实时公交演示兜底 + 写链路故障自愈

- **寄货新增物体体积与物体信息**：`pages/send/send` 增加货物类型（农产品/生鲜果蔬/日用品/文件票据/其他）、件数、长×宽×高（cm，前端折算 m³ 保留 4 位小数）、是否生鲜；`AppSendOrderCreateReqVO` 新增 `cargoCategory/itemCount/volumeM3/freshFlag` 并落 `transport_cargo_order`，「我的寄货」按标签回显。
- **修复寄货全单转人工审核**：`TransportOrderServiceImpl.createSendOrder` 曾把 `freshFlag` 硬编码 `true`，导致每单都被承运审核判为「生鲜需人工确认」；现按客户勾选落库（缺省 false），普通货物正常流转到「待入池」。
- **前端限重与后端规则对齐**：单件 30kg（60 斤）在提交前提示，避免提交后被判拒运；`utils/util.cmSizeToM3` 体积折算与 `miniprogram/tests/util.test.js` 单测。
- **实时公交演示兜底**：`MonitoringServiceImpl.fillTimetableSimulation` 恢复「班次时刻表插值」——无司机上报、未启动模拟引擎时按当前班次窗口在经停站 `planned_minutes` 上插值给出位置，`dataSource=SIMULATED`，供 `/bus/lines`、`/bus/realtime`、`/bus/nearby` 展示；真实上报车辆优先，且需回填班次/线路才进公交列表（`AppBusRespVO` 新增 `dataSource`）；开发模式模拟运行（含 GPS 关闭）不叠加演示位置。
- **模拟位置显式标注**：公交页/车辆详情/首页附近公交对 `SIMULATED` 显示「模拟演示」，`REAL_FRESH` 才显示「实时」。
- **下单失败可读化**：商城下单失败改用弹窗展示后端原因（不再是一闪而过的「失败」）；`api.js` 的 401 记录来源页，重新登录后回到原页，已填收货信息不丢。
- **部署自愈：后端 Redis 可写性**：`deploy-dev.yml` 新增 `Ensure backend has a writable Redis`——先探测后端写 Redis 是否 500，只有确认写失败且本机 Redis 容器 SET/GET 自检通过时，才把 `SPRING_DATA_REDIS_*` 幂等写入 `app.env` 并重启验证，不健康自动回滚；新增 `deploy/scripts/diagnose-backend.sh` 一次性排查（健康/读写链路/磁盘/Redis/MySQL/日志）。
- **修复小程序商城订单页线上报错**：`pages/orders/orders.js` 不再传 `status: undefined`（wx.request 会把它序列化成字符串 `"undefined"`，后端 `ProductOrderPageReqVO.status(Integer)` 绑定失败：`For input string: "undefined"`），切 tab 的 dataset 值统一归一为数字；`utils/api.js` 新增统一 `cleanParams`（过滤 undefined/null/空串，保留 0/false），`request()` 对 GET/POST 统一清理，附近公交的手写过滤收敛到该处，页面侧不再出现 `undefined` 字面量；新增 `tests/api-params.test.js`、`tests/orders-params.test.js` 回归测试。后端 DTO 保持不变。
- **统一位置模型（REAL / SIMULATED / OFFLINE）**：新增 `DeterministicScheduleSimulator`（班次时刻表确定性模拟，无需启动 SimulationEngine），`VehicleLocationProvider` 三级回退 REAL → 模拟引擎 → 班次模拟 → OFFLINE；`VehicleLocationSnapshot` 补全班次/线路/当前站/下一站/进度/ETA 上下文；实时公交不再因 REAL 车辆 `shiftCode=null` 被过滤；OFFLINE 只表示真正没有位置。
- **附近公交分层数据源**：新增 `TransitProvider` 抽象 + `AmapTransitProvider`（现实公交站点，未配 key 自动禁用不伪造）+ `ProjectTransitProvider`（项目自建线路），`/transport/bus/nearby` 返回 `dataSource/nearbyStations.lines/lineCount/realTransitAvailable` 等分层字段；首页与「实时公交」页共用同一 nearby 接口与文案语义（"附近有 N 条线路 / 当前暂无实时车辆数据" vs "附近暂无公交线路"）。
- **定位精度修复**：微信定位改 GCJ-02（与站点表/高德一致），新增 `GeoCoordUtil` WGS84/GCJ02/BD09 互转；定位缓存"秒出"阈值 90s（超时同步重取，避免用旧坐标查公交）；精度不足（>200m）自动补测取更准结果；高精度窗口 6s→10s；新增"定位精度较低，点此重新定位"与精度自适应搜索半径；定位失败不再显示默认"云山村"；新增 `[Location] ...` 日志与 19 项定位单测。
- **一键智能调度**：新增 `AutoDispatchPlanner`（自动选场站：场站级候选按到订单站点距离和最小/同分取 ID；自动选候选车辆：运力降序取前 3 台，实际车辆数由算法决定；算法默认参数 ant_count=30 等 7 项），`DispatchSmartPlanReqVO/DispatchValidateReqVO` 增加 `auto`（旧字段兼容），管理端弹窗改「一键开始智能调度 + 高级设置折叠」，`getPlan` 补 `orderCount/vehicleCount/depotStationName` 摘要。
- **一键演示（后台）**：`POST /transport/dispatch/order-pool/collect` 新增 `all=true`（免勾选归集全部「待入池」订单）；管理端「班次调度」页新增「一键演示」按钮，链式调用正式接口（**归集 → 一键智能调度 → 方案审核通过**）并逐步显示 loading 文案，完成后给方案摘要，现场演示只点一次。**刻意不做发车核验**：核验与发车留给司机端（扫码装车 → 发车 → 到站妥投），保持"管理员调度 / 司机执行"的分工；管理员代核验入口仍在方案列表中保留。
- **车来取货/送货提醒改为演示可见**：`AppSendController` 位置改走统一位置模型 `VehicleLocationProvider.getLocations(vehicles, true)`（真实上报 > 模拟引擎 > 确定性班次模拟），因此**无需司机开 GPS 也有倒计时**；响应新增 `carrierLocationSource`（REAL_FRESH / REAL_STALE / SIMULATED）与 `carrierApproaching`（≤10 分钟）；小程序「我的寄货」列表与单号查询在 ≤10 分钟时显示高亮「车快到了」横幅并弹一次提示（同单不重复）。
- **高德双 key 接入与文档**：小程序端 `AMAP_MINI_KEY`（微信小程序类型 key，`libs/amap-wx.js` + `https://restapi.amap.com` 合法域名，已配）；后端/算法侧 `AMAP_KEY`（Web 服务类型 key，`.env` 一处配置，部署流水线新增 `Sync AMAP_KEY to backend env` 幂等同步到后端 systemd env）；两种 key 类型不可互换（混用会 `USERKEY_PLAT_NOMATCH`）。真实高德公交站 POI 的 `address` 实为途经线路，已在前后端解析为线路标签。
- **修复后台看不到寄货/司机照片**：`infra_file_config.domain` 被修成 `http://1.15.29.107`（缺 `/api`），生成的文件 URL 形如 `/admin-api/infra/file/4/get/xxx.jpg`；而 nginx 只把 `/api/` 转发后端（剥前缀），其余落到前端 SPA → 浏览器拿到 index.html（实测 `text/html`），后台订单列表/审核弹窗里的照片全是裂图。新增 V018 迁移：domain 与历史 URL 统一补齐 `/api` 前缀（含 `infra_file.url`、`transport_cargo_order.photo_url/driver_photo_url`、`system_users.avatar`），部署流水线新增"文件 URL 必须带 /api"的硬校验。
- **就近站点匹配 + 通知客户前往**：`CargoReviewServiceImpl.selectServicePointStation` 按确定性规则匹配交接站点（取货站本身是场站级 → 本站交接；否则取距取货站最近的启用站点，同距取 ID 升序；无站点数据回退送达站点）；响应补 `servicePointStationName/坐标/距取货点公里数`，小程序寄货成功卡与「快递」页在"需客户操作"时显示"请送往就近站点 X（约 Y km）"并提供**导航前往**（`wx.openLocation`）。
- **实时公交页重构（农村客货邮版"车来了"）**：页面改为 定位状态 → 概览 → **地图（45vh 第一视觉焦点）** → 附近线路（默认 6 条，可展开全部）→ 正在运行车辆列表，不再是几十个站点铺满首屏；地图含我的位置（`marker-me.png`）、公交站、运行车辆（真实绿色 `/images/marker-bus-real.png`、模拟橙色 `marker-bus-sim.png`）与线路 polyline，并有「回到我的位置」；拖动地图后不再被 15s 刷新抢回中心；详情页补地图+车辆实时位置+数据来源标注。
- **统一定位层（AmapLocationProvider）**：全项目仅 `utils/location.js` 调用 `wx.getLocation({type:'gcj02'})`（首页/公交页/详情页/司机端均已改为 `location.getCurrentLocation()` / `getDeviceLocationGcj02()`）；高德链路=设备定位+`amap-wx.js` 逆地理，统一输出 `{success,latitude,longitude,accuracy,timestamp,source,level,district,city}`，`source ∈ AMAP|CACHE|DEMO|UNKNOWN`，日志 `[AMAP_LOCATION] ...`；缓存 1~5 分钟（60s 秒出、超时同步刷新），搜索半径按精度 5000/8000/15000m，定位失败显示"无法获取当前位置"而不是伪造地点。
- **站点去重（同名同坐标合并线路）**：客户端 `transit-amap.dedupeStations`、后端 `AmapTransitProvider.dedupe`、合并层 `AppBusServiceImpl.dedupeNearbyStations` 用同一规则（规范化名称去掉 `(公交站)` + 5 位小数坐标），线路取并集，修复线上"曾家岩(公交站)"重复两条的问题。
- **车辆平滑移动动画**：新增 `utils/bus-motion.js`（单定时器统一循环，1.2s 内插值约 12 帧，含朝向计算），15s 刷新只更新目标坐标并只重设 markers；`onHide/onUnload` 清理动画与刷新定时器，避免定时器泄漏。
- **答辩主链路：用户位置不可达 → 就近服务站点**：新增 `POST /app-api/transport/send/reachability`（`AppSendReachabilityService`：候选站点按距离升序、同距取 id，**高德道路距离优先、失败回退 Haversine 并标注"路线估算"**，≤0.3km 可就近服务，否则 `USER_LOCATION_UNREACHABLE` + `NEAREST_STATION` 推荐送站并给步行分钟）；小程序寄货页新增「取货方式：使用当前位置 / 自选取货站点」、可达性卡片、`使用推荐站点` 确认；订单新增 `original_address/original_latitude/original_longitude`（V019 迁移 + 全量 schema），**用户原始地址与服务站分开保存、不互相覆盖**；`ReviewReasonCodeEnum` 新增 `USER_LOCATION_UNREACHABLE` 并在小程序映射文案。
- **reasonCode 结果标准化（不删校验）**：`AlgorithmPlanRespDTO.reasonCode` 增加 `@JsonAlias("reason_code")` 兼容旧接口；`infeasible` 结果缺原因码时**兜底 `INFEASIBLE`** 而不是抛异常（此前会把"无解"升级成接口异常导致调度中断），并更新单测断言。
- **校园演示数据**：新增 `sql/mysql/demo-cqupt-stations.sql`（重庆邮电大学站/黄桷垭站 + 线路 + 班次，幂等），让"校园内真实定位 → 不可达 → 推荐最近站点"的演示有真实可用的站点与班次数据（代码中无任何地点硬编码）。

---

# 2026-08-17 取件核销/货运审核加固 + 调度结算口径修正 + 10 列回流 schema

- **track 接口取件码按归属分层返回**：包裹追踪接口按订单归属决定取件码是否返回，防凭单号枚举他人取件码。
- **返程结算口径调整为含执行中方案**：结算汇总由仅"已完成"方案放宽为含执行中方案，当日在途班次计入里程/包裹量。
- **货运审核增加状态机校验**：审核接口校验当前审核状态，仅"待审核"可通过/拒绝，防重复审核覆盖结论。
- **邮快件妥投强制取件码核销**：deliver 邮快件必须校验取件码，未核销不得妥投。
- **取件核销回减已装件数**：pickup-verify 核销后回减 `transport_shift_execution.loaded_count`，与妥投递减口径一致。
- **编辑订单保留取件码/审核状态**：管理端编辑订单不再重置取件码、审核状态等核销/审核字段。
- **车辆绑定一车一司机**：绑定时校验目标车辆无其他有效绑定（原仅约束一司机一车）；菜单 6845「人车绑定」补按钮权限 6846 `transport:driver:update`。
- **司机任务接口归属校验**：tasks 接口校验任务归属当前登录司机。
- **调度校验/结算批量查询优化**：发车核验与返程结算由逐条查询改批量查询，消除 N+1。
- **管理端主题色与校验残留修复**：主题色细节与表单校验残留清理。
- **小程序翻译死代码清理与拍照上传提示**：删除同声传译插件遗留死代码，拍照上传失败提示补充合法域名登记引导。
- **10 列回流 schema**：PR #48/#49 新增列此前只写进 `sql/mysql/transport-schema-incremental.sql`，现回流 `sql/mysql/transport-schema.sql` 的 CREATE TABLE（`transport_postal_order` 7 列 + `transport_cargo_order` 3 列），手工按全量 schema 建库不再缺列。

---

# 2026-08-15 取件核销 + 货运审核 + 人车绑定 + 大巴调度台 + 实时公交（PR #48/#49）

- **取件核销（邮快件下行闭环，PR #48）**：邮快件下行快递进村闭环——快递到总站 → 司机取件装车 → 送上门/定点 → 收件人凭 6 位取件码核销；`transport_postal_order` 新增收件人/取件码/取件状态/取件时间/核销人 7 列，司机端新增 pickup-verify 核销接口。
- **货运拍照核对（PR #48）**：司机收件装车强制拍照（`transport_cargo_order.driver_photo_url`），作为快递总站核对"这是哪家货"的凭证。
- **货运物品审核（PR #48）**：村民寄货散件需管理端审核，危险品/违禁品拒绝运输（`audit_status`/`reject_reason`），未审核/被拒的货运订单不进调度池。
- **司机-车辆绑定管理（PR #48）**：后端绑定/解绑接口 + 管理端「人车绑定」页（菜单 6845）。
- **大巴调度台增量（PR #49）**：约束校验/运力预警 + ACO 参数面板 + 乘车通知 + 返程结算。
- **实时公交（PR #49）**：车来了式实时公交——线路地图 + 车辆列表 + 车辆详情。

---

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
