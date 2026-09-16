# 客货邮平台 —— P0/P1/P2/P3 修复与回归手册（2026-09-12）

> 本手册对应本次 13 项缺陷修复（P0-A/B/C、P1-D/E/F/G/H/I、P2-J/K/L/M/N、P3-O/P）后的回归验证步骤。
> 全部改动已在 `fix/dispatch-p1-p3-hardening`（基于 P0 分支 `fix/dispatch-p0-hardening`，P0 已并入 PR #128）。

## 一、演示前体检（必须全为 0）

后端连本地库 `ruoyi-vue-pro` 执行（或直接跑 `sql/mysql/demo-reset.sql`，末尾自带这 3 项）：

```sql
-- 1) 孤儿段（plan 已不存在）
SELECT l.id FROM transport_leg l LEFT JOIN transport_dispatch_plan p ON p.id = l.plan_id WHERE p.id IS NULL;
-- 2) 方案声称段数 vs 实际段数
SELECT p.id, p.total_leg_count, COUNT(l.id) actual FROM transport_dispatch_plan p
LEFT JOIN transport_leg l ON l.plan_id = p.id GROUP BY p.id, p.total_leg_count HAVING actual <> p.total_leg_count;
-- 3) 在途但无方案归属的订单
SELECT o.id, o.order_no FROM transport_order o WHERE o.status IN (2,3)
  AND NOT EXISTS (SELECT 1 FROM transport_leg l WHERE l.order_id = o.id);
```

**本次实测**：孤儿段=0、在途无段=0；段数不一致=4（plan 29/31/32/33，均为今天旧缺陷残留）——执行
`demo-reset.sql` 后归零，且 P0-A 已从代码层保证新方案不会复用旧段。

## 二、修复清单 → 回归点映射

| 编号 | 修复 | 回归验证 |
|---|---|---|
| P0-A | 运输段幂等维度改为「方案」，作废旧方案未执行段 | 同批订单连续调度两次，两方案各有独立段；`SELECT plan_id,COUNT(*) FROM transport_leg GROUP BY plan_id` 与 `total_leg_count` 一致 |
| P0-B | `plan/roadmap` 改由本方案 leg 聚合（车辆视角=订单视角） | `plan/roadmap` 每段 vehicleId 与 `topology/order` 的 leg 车辆一一对应，无跨片区单车往返 |
| P0-C | 派单失败释放抢占订单 + 清理半成品方案 | 注入 `dispatchPlanMapper.insert` 异常 → 订单回 POOLED、无 ASSIGNED 残留 |
| P1-D | demo-reset 清理运输段/交接 + 体检 SQL | 三项体检全 0 |
| P1-E | 司机端 currentLeg 只取已下发/执行中方案的段 | 待审核方案不进入司机端；审核下发后立即可见 |
| P1-F | 位置上报触发"即将送达"站内通知（订单+档位幂等） | 1.9km 提醒一次、0.4km 再提醒一次且不重复；`transport_user_notification` 同 event_id 只落一行 |
| P1-G | 距离分级（2km/1km/0.5km，可配置）+ carrierApproachStage | 2.1km 不提醒、1.9km NEAR_2KM、0.4km ARRIVING |
| P1-H | shiftId 允许为空（缺省用当前活跃段班次兜底） | 无班次司机 `POST /driver/location` 不再 400 |
| P1-I | validate 复选批次时间窗，提前给可执行原因 | validate 对过期订单报"送达截止时间早于本批次开始"而非 submit 才失败 |
| P2-J | DispatchPlanRespVO 补 planReason 等 | `GET /plan/get?id=` 返回 planReason（681 字符） |
| P2-K | 派单通知补 orderId/legId | 司机点消息可跳订单 |
| P2-L | markAsRead 按 (recipient_type, recipient_id) 校验 | 司机不能标用户的单条通知已读 |
| P2-M | etaSource 统一 ETA 口径（实时>模拟>计划） | 有实时位置时 etaSource=REALTIME，无位置时 PLANNED |
| P2-N | topology totalLegs/transferCount 以实际段为准 | 不再依赖可能为 0 的方案字段 |
| P3-O | demo 订单时间窗 ON DUPLICATE 刷新 + reset 全量清理模式 | 隔天重跑 demo-real-orders.sql 后时间窗自动刷新 |

## 三、一键演示三步（幕 2）

```bash
# 1) 归集入池（把待入池订单全部归集）
curl -s -X POST http://127.0.0.1:48080/admin-api/transport/dispatch/order-pool/collect \
  -H "Authorization: Bearer <admin-token>" -H "Content-Type: application/json" -d '{"collectAll":true}'
# 2) 一键智能调度
curl -s -X POST http://127.0.0.1:48080/admin-api/transport/dispatch/plan/smart \
  -H "Authorization: Bearer <admin-token>" -H "Content-Type: application/json" -d '{"auto":true}'
# 3) 审核通过（下发）
curl -s -X PUT http://127.0.0.1:48080/admin-api/transport/dispatch/plan/review \
  -H "Authorization: Bearer <admin-token>" -H "Content-Type: application/json" -d '{"planId":<id>,"approve":true}'
```

预期：三步 code=0；方案 `planReason` 长度可 >500；不可行时返回可读业务错误（非 500）。

## 四、用户端"快到了"标准动作（幕 4）

```bash
curl -s -X POST http://127.0.0.1:48080/app-api/transport/driver/location \
  -H "Authorization: Bearer <driver-token>" -H "Content-Type: application/json" \
  -d '{"driverId":3,"longitude":106.4700,"latitude":29.5700,"speedKmh":30}'   # 距目标 0.74km → ARRIVING/提醒一次
curl -s -X POST http://127.0.0.1:48080/app-api/transport/driver/location \
  -H "Authorization: Bearer <driver-token>" -H "Content-Type: application/json" \
  -d '{"driverId":3,"longitude":106.4645,"latitude":29.5667}'                  # 距目标 0.10km（同档不重复提醒）
```

预期：`GET /app-api/transport/send/track?no=...` 返回 `carrierDistanceKm≈0.74`、`carrierEtaMinutes≈2`、
`carrierApproachStage=ARRIVING`、`carrierApproaching=true`、`etaSource=REALTIME`；
用户消息中心收到 `CARRIER_APPROACHING 车辆即将送达` 一条（同档幂等）。

## 五、本地回归命令

```bash
cd <repo>
export MAVEN_HOME='C:\Users\袁\apache-maven-3.9.16'
mvn -B -o -pl yudao-module-transport test        # 全模块 309+ 用例
mvn -B -pl yudao-server -am clean test           # 最终全量（提交/CI 前必跑）
```
