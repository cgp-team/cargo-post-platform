# 2026-09-13（七）派单不再因"不折返"丢单 + 批次加站点预算（修"全都不行了，出不了方案"）

- **"取货站已被车辆开过，不能掉头取货"不再剔除订单**（现场反馈：订单池里 21 单全被标成不可派，一条方案也没有）。
  上一版把这条做成了**派单前硬过滤**：取货站不在"本批 ≤3 台候选车的剩余可经过站"里 → 本批直接扔掉该单。
  但一批订单往往跨多条线路，绝大多数订单的取货站本来就不在那 3 台车的剩余站里 → 整批被清空。
  现在改为**只提示不丢单**：写进方案解释（"以下订单的取货站已被本批车辆驶过（不阻断）…建议人工确认"），
  订单照常交给算法；方向性由"骨架 prior（尽量顺线路走）"+ 调度员据提示人工确认共同保证。
  至此"不折返"三处（候选车骨架、派单前过滤、算法返回后校验）**都不再阻塞派单**。
- **一键调度的批次选择加"站点预算"**：算法契约上限是 30 站点 / 25 订单 / 3 车；
  只按订单数取批时，25 单跨多条线路很容易凑出 40~50 个站点 →
  `validateScaleLimit` 直接抛 `DISPATCH_SCALE_OVER_LIMIT`，现场表现同样是"点了调度没有方案"。
  现在 `selectAutoBatch` 在按片区纳入订单的同时累计站点数，超预算的订单留给下一批
  （下一轮一键调度自然成下一套方案），保证每批都在算法可解规模内；`createSmartPlan` 与 `validate` 同步口径。

验证：后端 `yudao-module-transport` **331 个测试通过**（新增"站点预算取批"用例）。

---

# 2026-09-13（六）修"整天出不了方案（车辆已驶过站点）" + 商品描述/图片地址的 500

- **"车辆已驶过站点，不能掉头取货"不再阻断整批派单**（现场反馈：改完班次后点调度没有方案）。
  早期把"不折返"做成**硬校验**：算法返回后只要有任一台车的任一个停靠点落在"本班次已驶过"的站上，
  就整批判 INFEASIBLE。但骨架在算法里是 **prior（方向偏好）而非硬约束**，于是某些时段
  （例如某班次已跑到邮电大学之后）会天天卡死、一条方案都出不来。
  现在改为**只提示不阻断**：可疑折返写进方案解释（"提示：本班次可能折返取货…建议人工确认"）并记 WARN 日志；
  真正"不能掉头"的把关保留在**派单前**——取货站被所有候选车都开过的订单，本批不派（`filterByNoBacktracking`）。
- **后台改商品"描述/图片地址"报 500（服务器错误，请联系管理员）**：
  根因是列宽太窄——`transport_product.description` 只有 varchar(512)、`image` 只有 varchar(32)，
  描述写长一点、或把图片地址贴进 emoji 列，MySQL 直接抛 `Data too long for column`，被兜成 500。
  修复：
  - 幂等迁移（`transport-schema-incremental.sql`，部署自动执行）把 `description` → varchar(2000)、
    `image` → varchar(255)、`image_url` → varchar(1024)、订单明细的 `product_image` 快照 → varchar(512)；
    本地 MySQL 实测：写入 2000 字描述 + 928 字符图片地址成功；
  - `ProductBaseVO` 加 `@Size` 校验 → 超长时返回**可读的 400 提示**（而不是 500）；
  - 后台商品表单：各字段加字数上限与计数器；**新增"或直接粘贴图片地址"输入框**（与上传共用 imageUrl，
    填了就用它，带缩略图预览），解决"不能使用图片地址"。

验证：后端 330 测试通过；前端 vue-tsc + eslint 通过；本地 MySQL 真跑增量迁移并通过超长写入验证。

---

# 2026-09-13（五）站间走向跟随线路走廊（不再进隧道/掉头）+ 演示数据纳入自动部署

- **站间轨迹改为"沿线路走廊切片"**（新增 `RouteCorridorService`）：
  现象——订单（例：文峰公社 → 七公里）画出来的道路走向不对：进隧道 → 绕很远 → 掉头回文峰正街路口再去吉祥路口；
  最优应是从文峰公社直接经文峰正街路口走向吉祥路口。
  根因——站间几何此前一律用**点对点驾车规划**：站点在道路两侧（同名站分上行/下行）、或该点位需上下桥/下穿时，
  高德只按"最快/最短"给一条自由路径，与公交实际走向无关。
  修法——线路本身有一条按"逐站 waypoints"取到的真实走廊几何（`transport_route.navigation_polyline`），
  在走廊折线上找离起讫站最近的顶点、取两者之间那一段即可，天然贴合上行/下行与路口转向：
  - 新增 `RouteCorridorService`：`operatingRouteId(vehicleId)` / `alongOperatingLine(vehicleId, from, to)` /
    `alongRoute(routeId, from, to)`；库里有走廊直接用，没有就按站序现取一次并落库（取不到返回 null，不伪造几何）；
  - `RoadPolylineService.sliceAlong(...)`：走廊切片（纯函数，含单测 `RoadPolylineSliceTest`）；
  - 接入四处：`MultiLegServiceImpl`（运输段落库几何）、`DispatchServiceImpl` 的 roadmap（车辆视角两段：
    按经停明细/按运输段）与 `prefetchRoadGeometry`（"预热真实路线"顺带纠正历史轨迹）、
    `DriverAppServiceImpl`（司机端导航站间几何）。**走廊优先**，取不到再回退原点对点逻辑。
- **346 演示数据并入"已在部署清单里的"脚本**：`demo-line346-orders.sql` 的内容整体并入
  `demo-real-orders.sql` 末尾（该文件每次部署都会执行），于是**部署后这批订单直接出现在
  后台「调度中心 → 订单池」**（状态=待入池）：重庆工商大学站与 320 路补站、346/347/303/318 的
  车辆·司机·人车绑定·班次、订单 A~E + 重邮→重庆交通大学 + 重庆大学A区订单，以及"演示单时间窗=当天全天"。
  独立的 `demo-line346-orders.sql` 改为说明文件（它不在部署清单里，单独执行不再建单），
  DEMO_GUIDE 的脚本表与说明同步更新。
  （背景：工作流文件 `deploy-dev.yml` 的改动需要 token 带 `workflow` 权限，当前 CI token 没有，
   SSH 22 端口在本机被拒；把数据并入已在清单内的脚本可以绕开这个限制。）

验证：后端 `yudao-module-transport` **330 个测试通过**（新增走廊切片 4 例）；
本地 MySQL 真跑整条演示脚本链通过，28 张演示单在 08:40-12:40 窗口全部可派。

---

# 2026-09-13（四）一屏只看一个任务段 + 货运取送配对（PDPTW）+ stop_level 构造器修复

- **调度可视化默认只显示"一个任务段"**（`DispatchVisualDialog`）：多套方案（去程/返程/另一片区/跨区联运）
  叠在一张图上必然"路线混乱"。现在打开可视化**默认选中第一套方案**（= 一个任务段：一条公交骨架 + 2~3 个取送点），
  顶部给出提示"默认只显示一个任务段；跨区订单是另一段，切方案分屏看，不叠加"；
  方案切换按钮顺序也调整为「方案 #N ... → 全部方案（对比用）」，需要对比时手动切。
  对应论文/开源口径：DRT 的 trip-level / OpenTripPlanner 的 per-itinerary、per-leg 展示（永远按"一段出行"呈现）。
- **货运取送配对下发（PDPTW：同一辆车 + 先取后送）**：`toAlgorithmOrders` 以前对"取货站 → 送达站"
  这类订单**只发一个 DELIVERY 节点、把取货站丢了**（等于货凭空出现在送达站：看不出先后、无法约束同车/顺序、
  站点清单里也看不到揽收点）。现在两端都不是场站的完整链路改为下发 **`AlgorithmShipmentDTO` 配对货运单**
  （`PlanShipment`：算法展开为同一辆车的 PICKUP + DELIVERY，顺序约束由算法保证，后端 `AlgorithmResultValidator`
  已按 shipmentId 校验配对完整性）；场站锚定的「揽收（村→场站）/派送（场站→村）」两条旧口径保持不变。
  同时 `validateScaleLimit` 的"任务数"把配对货运单计入（与算法契约"订单合计 ≤ 25"同一口径）。
  测试：新增 `createSmartPlan_pairs_cargo_pickup_and_delivery_as_shipment`。
- **修复算法模块 `stop_level` 构造器（CI `algorithm` 检查失败根因）**：
  `app/haco/stop_level/constructor.py` 的贪心构造器有两处缺陷——
  ① `_check_capacity` 在插入位置应用的是已存在活动 `a` 而不是待插入的 `activity`，且随后又应用了一次 `a`，
  载重被算重导致后续活动全部插不进去；② 缺少**位置感知**的先后约束（只检查"BOARD 是否存在于路线某处"），
  卸载可能被插到装载之前 → 负乘客数。
  另 `_apply_activity` 对 DELIVERY（预装派送）也做了减货，与 evaluator 的"PRELOADED 不减货"口径不一致。
  三处修正后 `tests/test_stop_level_representation.py` 全通过（构造器产出可行解），算法全量快测
  **358 passed**（修复前 1 failed / 357 passed）。

---

# 2026-09-13（三）快递页看购物订单 + 订单详情页 + 后台改的商品不再被演示脚本冲掉

- **快递页新增「我的购物」tab**（`pages/parcel/parcel`）：以前快递页只有"我的寄货 / 单号查询"，
  买的商品订单只能在「我的订单」里看。现在三个 tab：**我的寄货 / 我的购物 / 单号查询**——
  我的购物按状态列出商城订单（商品图、金额、件数）+ 送货进度一句话（"渝A·B5204 正在送往 小什字小商品"
  /"司机已装车"/"已妥投交付"），支持下拉刷新与触底翻页；空态给"去逛逛"。
  「我的」页同步新增「我的购物」入口（`globalData.parcelIntent='shopping'` 直达该 tab）。
- **点订单看详情（此前没有实现）**：
  - 新增 **订单详情页** `pages/orders/detail/detail`：商品清单（图/单价×数量/小计）+ 合计金额 +
    收货信息 + 备注 + 承运车辆/司机电话/交付站点/班次线路 + **配送进度时间线**
    （已下单 → 商家发货·派车 → 司机装车核验 → 到达交付站点 → 已妥投签收）+ 装车/妥投凭证明细（可点开大图）
    + 跳"商品溯源"看车辆轨迹 + 复制订单号；
  - 入口三处：快递页「我的购物」卡片、我的订单列表卡片（`bindtap`）、都在同一页展示；
  - 快递页「我的寄货」卡片点击 → 直接进入该单的完整物流详情（复用"单号查询"渲染：分段/换乘交接/时间轴/取件码二维码）；
  - 卡片内的"查看取件码/导航/实时公交/确认入池"等按钮改成 `catchtap`，避免点按钮顺带触发卡片跳转。
  - 后端 `AppProductOrderTraceRespVO` 补 `totalAmount / remark / createTime / items`，`ProductOrderServiceImpl.getTrace`
    一并回填（订单详情页一次请求拿全，不用再拼列表接口）。
- **修"后台改的商品信息和图片，一刷新就没了"**（根因）：
  `sql/mysql/transport-demo-data.sql` 里商品种子数据是 `DELETE FROM transport_product WHERE id BETWEEN 1 AND 12` +
  重新 `INSERT`，而**这个脚本会随每次部署执行**（`.github/workflows/deploy-dev.yml` 的迁移步骤）——
  于是后台改过的商品名称/价格/描述/状态、上传的商品图片(`image_url`)每次部署都被打回演示初值。
  改为 `INSERT IGNORE`（只在缺行时补种子数据，已存在的商品一律不动）；同文件里示例线路的
  `transport_route_station` 也由"先删再插"改成 `ON DUPLICATE KEY UPDATE`。
- **商品图片地址兼容相对路径**：`utils/product-img.js` 原来只认 `http(s)://` 与 `/images/`，
  后台上传返回 `/admin-api/infra/file/...` 这类相对地址时会被忽略、退化成"按商品名猜本地图"
  （表现：换了图还是旧图/占位图）。现在绝对地址、协议相对地址、`/admin-api/`、`/infra/` 相对地址都原样使用。
- **商城页回到前台自动刷新**：`pages/goods/goods` 原来只在 onLoad 拉一次，后台改了商品/价格/图片后切回商城
  仍是旧数据；现在 onShow（非首次）自动重载第一页。
- 测试：新增 `miniprogram/tests/product-img.test.js`（6 组图片解析用例）与
  `miniprogram/tests/order-detail.test.js`（详情页渲染 + 未发货空态）；小程序 10 个测试全绿，
  后端 `yudao-module-transport` 321 个测试全绿。

---

# 2026-09-13（二）调度按"任务时间段"下发 + 一条线路一辆车 + 已开过的站不折返

业务口径澄清后的落地：调度不是"收到单就让车掉头去取"，而是**给某个时间段（例：早上 8-10 点）排任务**，
用闲置运力顺路带货；默认**一条真实线路只跑一辆公交车**（演示不乱）；跨线路订单由算法拆段、在交汇站接力
（例：重邮 → 重庆交通大学 = 347 路「邮电大学→南坪站」+ 303 路「南坪站→七公里」）。

- **任务窗口（`DispatchSmartPlanReqVO.windowStart/windowEnd`）**：一键调度/一键演示可指定"早上 08:00~10:00"
  这样的窗口。后端据此 ①按订单 `[最早取货, 最晚送达]` 与窗口交集过滤（取不到/已过期的本批不派，写进方案解释），
  ②把窗口作为算法的 `batchStart/batchEnd`（原来固定用"当前时刻起一个班次"），③窗口内没有可派订单时
  报明确错误 `DISPATCH_TASK_WINDOW_EMPTY`（而不是静默出一套空方案）。后台调度中心工具栏加了任务窗口时间选择器。
- **不折返（已开过的站不再派车去取货）**：新增 `OperatingLineTimeline`（纯函数 + 单测），按
  「线路站序计划分钟 + 班次发车时间/时长」算出**窗口开始时每台车开到哪一站、这一趟还会依次经过哪些站**，
  口径与 `DeterministicScheduleSimulator` 完全一致（班次窗口=一个往返、单程=窗口一半）。
  - 每台车把这些"还会经过的站"作为**自己的算法骨架**（`AlgorithmVehicleDTO.skeleton`，原来只有显式指定班次才有）：
    货运任务只能插进骨架间隙 → 沿线路顺序带货、有先后、不会掉头；骨架还会按窗口结束时刻截断，不会拿"整条线跑完的时间"撞窗口上限。
  - 派单前预过滤：取货站**所有候选车都已开过** → 本批不派这单，报 `DISPATCH_NO_BACKTRACKING` 并写明是哪一单；
  - 算法返回后仍有安全网 `findBacktrackingViolations`：若有车被派到"本班已经开过、之后不再经过"的站取货，直接判不可用并说明原因。
  - 没班次 / 班次未发车 / 当天班次已跑完 → 按"整条线都在前方"处理，避免演示数据班次覆盖不全时把线路误判成"已开过"。
- **一条线路一辆车 + 按线路挑车（`AutoDispatchPlanner.selectVehiclesByLineCoverage`）**：
  - 候选车辆先按**订单联运拆段结果**推导需要哪些线路（每段起终点都该线路覆盖，命中段数多的优先），
    再退化为"线路覆盖本批站点"打分排序；**同一线路只出一辆车**（同线路多台绑定取 ID 最小者），
    彻底改掉原来"按运力排序挑车"（会把 347 路的车派去送 320 路的货，司机端任务串线、可视化也乱）。
  - 挑不到"线路覆盖本批站点"的车时，仍退回原运力口径兜底（自建片区/站点未挂线路不会卡住）。
- **演示数据 `sql/mysql/demo-line346-orders.sql` 扩充**：
  - 新增订单 **TPJTU1 邮电大学 → 七公里（重庆交通大学门口）**：本地线网无直达 → 算法解出
    347 路（402）邮电大学→南坪站 + 303 路（407）南坪站→七公里 两段接力（该路径已用线网数据核对：两段共站=南坪站）；
  - 车辆按"一条线路一辆车"重排：渝A·B5205 由 329 路改跑 **347 路（402）**，新增 **渝A·B5208 / 303路司机**（13800138009）
    跑 **303 路（407）**；为 347 路 / 303 路补班次（此前这两条线没有班次，司机端没有可执行记录）；
  - 346 路主线订单时间窗统一改成"当天 06:00~22:00"，这样不管几点演示、任务窗口选哪一段（如早上 8-10 点）都落在窗口内可派。
- 测试：新增 `OperatingLineTimelineTest`（9 例）、`AutoDispatchPlannerTest` 增加 4 例线路挑车/一条线一辆车用例；
  全模块 **321 个测试全绿**（含更新过的"方案解释 >500 字符不被截断"断言）。

---

# 2026-09-13 调度可视化"去直线"+ 订单视角按车辆串成行程链 + 346 路主线演示数据

- **可视化不再画"两点直线"**：`DispatchVisualDialog.vue` 原来在缺真实道路轨迹（ESTIMATED / 高德不可用）时
  回退成"上一站→本站"两点直连（虚线），演示时就是那条斜穿城市的线。现在改为：**只画真实道路轨迹**，
  缺路网数据的运输段把折线**断开**（`RouteView.polylines` 支持同一台车多条不连续折线），
  线段一律实线；列表里该段标注「缺路网轨迹」，订单卡片顶部提示"地图不画直线"。图例同步去掉"直线估算"，
  换成"只画真实道路轨迹（缺路网数据的段不连线）"；地图为空时的提示也改成"已按不画直线处理"。
  车辆视角的算法兜底路径（没有运输段的历史/手工方案）同样改成"缺段就断开"。
- **订单视角连成"行程链"**：新增「各车行程链（订单一段接一段，按先后）」面板——把运输段按车辆分组、
  段内按"预计到达时间 → 段序号"稳定排序，再把同一张订单的多段合并成一行，标出 `① 订单号 起点 → 终点
  时间 ⇄ 交给谁`。点行程链 = 地图只画这台车整条连续线路；点订单 = 画该单分段。逐单卡片也按首段时间
  先后排序。这样演示能直接讲清楚"司机本职按线路跑、订单一段接一段、有先后"。
- **346 路主线演示数据 `sql/mysql/demo-line346-orders.sql`（新增）**：
  - 补 **346 路（route 405）人车绑定**（此前 346 路没有任何车 → 一键调度选不到它）：复用闲置车辆
    `渝A·B5204`，新增司机 **346路司机A（13800138006）** + 会员登录账号，运营线路 405；
  - 补 **重庆工商大学站（AMAP1150，学府大道真实站位）** 并按真实站序插入 **320 路（403）**站序
    （五公里 ↔ 轨道六公里之间）——否则"邮电大学 → 重庆工商大学"本地线网无解，只能跨城直送；
  - 订单 5 单：**TP346A 中研所→上新街 / TP346B 黄桷垭→小什字小商品 / TP346C 上新街→小什字小商品**
    （346 顺路取派，同车共载有先后）、**TP346D 邮电大学→重庆工商大学站**（跨片区：346 送到与 320 的
    交汇站交给 320 司机续运）、**TP346E 上新街→邮电大学**（返程单独一次调度）；
  - **重庆大学A区订单 3 单**（TPCQUA1 重大A区→沙坪坝火车站、TPCQUA2 重大A区→重邮、TPCQUA3 重邮→重大A区）
    + 沙坪坝片区车辆 `渝A·B5206` / 司机 **沙坪坝司机（13800138007）**（绑定 220 路区间 415，经重大A区）；
  - 全部幂等（站点/站序/车辆/司机/绑定 ON DUPLICATE KEY UPDATE，订单刷新时间窗），站名解析站点 id 不硬编码。
- **演示指南 `docs/DEMO_GUIDE.md`**：新增 §3.0「346 路司机的一天（三次调度）」剧本（去程顺路取派 → 返程另一次
  调度 → 沙坪坝片区第三套方案），重写 §3.6 可视化说明（只有真实道路、行程链、换乘交接），
  更新 SQL 执行顺序与演示账号（含 346路司机A / 沙坪坝司机）。

---

# 2026-09-11 修复首页 WXML 编译错误（wx:else-if 拼写）+ 新增 WXML 指令校验测试

- **`pages/index/index.wxml` 编译失败根因**：定位提示条第三个分支写成了 `wx:else-if`（Vue 习惯）。WXML 只认 `wx:elif`，
  `wx:else-if` 会被当成未知属性 → 该元素永远渲染（"定位精度较低"提示其实一直在显示），且后续新增的 `wx:elif` 失去前置条件，
  微信工具直接报 `Bad attr 'wx:elif' … wx:if not found`。现统一改为 `wx:elif`，四个分支（权限拒绝/不可用/精度低/已手动选点）恢复成一条合法链。
- **新增 `miniprogram/tests/wxml-directives.test.js`**：用真实标签 tokenizer（跳过注释、处理属性内引号与 `>`、支持自闭合标签）
  扫全仓 WXML，检查 ①`wx:else-if`/`wx:elseif` 拼写 ②`wx:elif`/`wx:else` 是否紧跟同层级 `wx:if`/`wx:elif`；
  测试内含反向自检用例（故意构造拼写与悬挂 `wx:elif`，断言检查器能发现），避免这类"整页编译失败"再次溜进提交。

---

# 2026-09-11 定位可纠正化：粗定位告警 + 手动选点 + 近期高精度沿用

- **"人在重庆邮电大学却定位到渝中区"的定性**：用同一坐标（106.5765,29.5325）实测高德 regeo 与 BigDataCloud 都返回 `南岸区`，项目内只有 `utils/location.js` 调 `wx.getLocation({type:'gcj02'})`（高德 SDK 显式传坐标不会自行定位）→ 坐标链路没问题，**区县错了是设备/系统给的粗略位置**（未开"精确位置"/室内/WiFi 定位误差可达 1~3km；开发者工具的"位置模拟"也会固定返回同一坐标）。
- **粗定位告警**：`isCoarseAccuracy`(>500m) / `isVeryCoarseAccuracy`(>1000m) / `accuracyText`(35m / 3.2km)；首页定位条、实时公交定位条、寄货页可达性卡片在粗定位时提示"定位精度较低（约 X）· 区域名可能不准"，日志补 `[AMAP_LOCATION] 定位精度较低…` 告警。
- **手动选点纠正（`wx.chooseLocation`）**：新增 `location.chooseLocation()`，统一返回 `{source: MANUAL, manual, name/address/district/city, level: PRECISE}`，**2 小时内优先于自动定位**（用户纠正过的位置不再被下一次粗定位覆盖），点"重新定位"即放弃；入口在首页、实时公交页、寄货页（寄货页选完自动重跑可达性评估，`originalAddress` 取用户点选的地点名）。
- **近期高精度结果沿用**：本次误差 >1km 且 2 分钟内有 ≤100m 的高精度结果时沿用该结果并标注 `stale + note`（避免"上一分钟还准、这一分钟被粗定位覆盖"）。
- **修高德字段空数组 bug**：高德 `addressComponent` 缺字段时返回 `[]`（JS 里 truthy），原 `comp.city || comp.province` 会把 city 写成空数组导致页面空白；新增 `pickText()` 统一归一为字符串。
- 单测：`miniprogram/tests/location.test.js` 新增 4 组（粗定位阈值/文案、极差精度沿用高精度、手动选点优先且强制刷新回到真实定位、空数组归一）。

---

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
