# E2E 演示闭环

## 服务层冒烟（`DispatchE2ESmokeTest`）
串联 归集入池 → 智能派单 → 方案审核下发 → 发车核验，验证状态流转：
- 订单：待入池 → 已入池 → 已分配(CAS 抢占) → 已发车
- 方案：待审核 → 已下发 → 执行中

## 完整业务闭环（最终目标，已具备/待接通）
1. 建立站点/线路/班次/车辆 ✅
2. 客户提交货运订单 ✅
3. 自动承运审核 → 客户反馈（可运/拒运/需操作 + 推荐站点）✅
4. 待客户操作 → 确认送站 → 待入池 ✅
5. 订单池归集 ✅
6. Smart Dispatch（公交骨架 + 货运绕行）✅
7. 生成完整任务段（Operational Stops）✅
8. 每段真实 Road Route polyline ✅
9. 模拟/真实车辆沿道路运行 ✅
10. 司机端完整任务段 + 真实道路地图 ✅
11. 司机执行 BOARD/ALIGHT/PICKUP/DELIVERY（到站/装车/妥投）✅
12. 订单完成 → 方案 COMPLETED ✅
13. 首页实时公交 / 后台监控同步 ✅（模拟/REAL 数据源明确）

## 演示指引
- 本地：算法服务（`ALGORITHM_BASE_URL`）+ 后端（`SIMULATION_ENABLED=true` 时启动模拟）+ 小程序开发者工具。
- 司机端测试账号：`13800138001`（绑定司机1）。
- 管理端调度：订单池（待入池 8）勾选归集 → 智能派单（可指定班次）→ 审核下发 → 发车核验 → 司机端执行。

## 答辩主链路（2026-09-11 口径）

一次演示按下面 6 步走，全程用正式接口（无演示后门）：

1. **小程序寄货**（`pages/send`）：货物名称/类别/件数/长宽高（自动折算 m³）/是否生鲜 →「取货方式：使用当前位置」
   → 高德定位（GCJ-02）→ `POST /app-api/transport/send/reachability` 可达性评估。
   - 校园内车辆进不去 → 提示"需前往最近服务站点 + 距离/步行分钟" → 点「使用推荐站点」确认（`pickupServiceMode=NEAREST_STATION`）。
   - 位置可达 → 直接就近上门（`DOOR_PICKUP`）。
2. **管理端订单管理**（`/transport/order`）：看到该单的「用户寄货位置 / 取货方式 / 交接服务站 / 物品信息（类别·件数·重量·体积·生鲜）」
   → 点「审核」→ 通过。自动审核已通过的订单这里是**幂等复核确认**（只记录结论，不会把订单打回，也不再报 `CARGO_AUDIT_STATUS_ILLEGAL`）。
3. **调度工作台**（`/transport/dispatch`）：订单池默认展示「待入池 + 已入池」，刚审核的单在这里可见；
   池里还有同片区（`sql/mysql/demo-cqupt-stations.sql` 的 TPCQ00xx）与其他片区（`transport-demo-data.sql` 的 TP2026…）的模拟订单。
4. **一键演示**（归集 → 按片区智能调度 → 每套方案审核通过）：
   - 归集把所有「待入池」订单入池；
   - 一键调度按**片区**分批（`AutoDispatchPlanner.selectAutoBatch`，最新订单优先、≤50km 同片区），
     重庆邮电大学片区一套、成都片区一套，不会跨城混批导致算法无解；
   - 同一台车不会被两套在途方案同时占用（`selectVehicles(excluded)`）。
5. **调度结果可视化**（自动弹出）：方案摘要（方案/订单/车辆/总里程）+ 每车**任务段时间线**
   （场站发车 → 揽收/派送/上下客 → 返场，含站点名、订单号、预计到达时间）+ **地图按车分色画经停线路**
   + **▶ 播放路线**（多车同步移动，可拖进度）。地图 SDK 不可用时自动降级为真实坐标线路示意图。
6. **司机端 / 用户端闭环**：司机端工作台扫码装车 → 发车 → 到站妥投；用户端「我的寄货 / 快递」页看到车辆动态与「车快到了」提醒。

演示前复位（把上一轮流程态订单放回「待入池」，清掉今天生成的方案）：

```bash
set -a; source /opt/cargo-post-platform/.env; set +a
docker compose --env-file /opt/cargo-post-platform/.env -f deploy/docker-compose.yml \
  exec -T mysql mysql --default-character-set=utf8mb4 -u root -p"${DB_PASSWORD}" "${DB_NAME}" < sql/mysql/demo-reset.sql
# 重邮片区站点/线路/班次 + 3 单同片区演示订单（幂等，可重复执行）
docker compose --env-file /opt/cargo-post-platform/.env -f deploy/docker-compose.yml \
  exec -T mysql mysql --default-character-set=utf8mb4 -u root -p"${DB_PASSWORD}" "${DB_NAME}" < sql/mysql/demo-cqupt-stations.sql
```
