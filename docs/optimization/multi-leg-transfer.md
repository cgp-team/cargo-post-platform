# 多段联运（换乘接力）实现说明

> 对应审计报告「第四部分：多段联运」「第八部分：数据库审计」的缺口补齐。
> 目标：让"一辆车送不到/不划算"的订单，拆成多段、经换乘站由不同车辆接力承运，并全程可追踪、可交接。

## 1. 一句话结论

订单 → `transport_leg`（运输段）→ `transport_handover`（换乘站交接）→ `transport_order_event`（事件时间线）
→ `transport_user_notification`（用户通知），四张表把"谁在哪一段、货在谁手上、用户看到什么"串成闭环。

## 2. 数据模型

| 表 | 作用 | 关键字段 |
| --- | --- | --- |
| `transport_leg` | 运输段 | `order_id` `leg_sequence` `from_station_id` `to_station_id` `vehicle_id` `driver_id` `status` `estimated/actual_departure/arrival` |
| `transport_handover` | 换乘站货物交接 | `order_id` `leg_from_id` `leg_to_id` `station_id` `from_driver_id` `to_driver_id` `item_count` `photo_url` `status` |
| `transport_order_event` | 订单事件时间线 | `order_id` `event_type` `event_time` `operator` `detail` `extra_data` |
| `transport_user_notification` | 用户通知（消息中心） | `user_id` `event_type` `title` `content` `order_id` `read_status` |
| `transport_driver_status` | 司机实时状态 | `driver_id` `online_status` `current_vehicle_id` `last_heartbeat` |

DDL：`sql/incremental/V018__multi_leg_framework.sql`（人工执行入口）；新库由 `sql/mysql/transport-schema.sql` 一并创建。

## 3. 状态机

运输段 `TransportLegStatusEnum`：`0 待分配 → 1 已分配 → 2 运输中 → 3 已到达 → 4 已交接`，`5 异常`。

订单 `TransportOrderStatusEnum` 新增 `9 部分完成`：首段交付换乘站后置为"部分完成"，全部段交接完成后置为"已完成"。

交接 `TransportHandoverStatusEnum`：`0 待确认 → 1 已确认`，`2 有争议`（件数不符/破损，需人工介入）。

## 4. 规划算法（MultiLegServiceImpl.planLegs）

1. **是否需要多段**
   - 取/送站在同一条线路覆盖内且距离 < 60km → 直达（1 段）。
   - 否则（无直达线路覆盖，或干线 ≥ 60km）→ 需换乘。
2. **选换乘站**：在候选站点里取"绕行代价最小"者，
   `绕行 = d(取,换) + d(换,送) − d(取,送)`，要求 `绕行 ≤ max(0.6 × 直达, 3km)`；
   同分优先"与送达站同线路"的站点（更贴近目的地方向）；没有合适站点则退化为直达，不硬拆。
3. **拆段与估时**：`取货站 → 换乘站 → 送达站`；每段按 `Haversine ÷ 25km/h` 估时，换乘停留 20 分钟，首段前置准备 10 分钟。
4. **绑定司机/车辆**：取当前有效人车绑定，相邻段尽量用不同车辆（这正是换乘的意义）；无绑定则保持"待分配"，可人工后派。
5. **落库**：`transport_leg`（唯一键 `order_id + leg_sequence + tenant_id`），并写一条 `DISPATCHED` 订单事件。

> 幂等：同一订单已有运输段时直接返回，不重复拆段。

## 5. 执行与交接（HandoverServiceImpl）

- **创建交接**：交接站点取来源段的 `to_station_id`；交出/接收司机取两段的 `driver_id`。
- **确认交接**（事务）：`transport_handover` 置已确认 + `legFrom` 置已交接 + `legTo` 置已分配/运输中 + 订单置部分完成/已完成，一并提交；
  接收司机未分配时可由现场司机确认并"认领"。
- **争议**：置"有争议" + 写 `HANDOVER_DISPUTED` 事件 + 通知用户"交接异常"。

## 6. 事件与通知（事件驱动）

`OrderEventService.record(...)` 在订单状态推进处调用；`UserNotificationService.sendToOrderUser(...)` 按 `transport_order.member_user_id` 推送。
已接入节点：下单、审核通过/拒运、调度拆段、段发车/到达、交接创建/确认/争议、订单完成。

## 7. 接口清单

| 端 | 方法 | 说明 |
| --- | --- | --- |
| 用户 APP | `GET /app-api/transport/notification/page` | 我的消息分页 |
| 用户 APP | `GET /app-api/transport/notification/unread-count` | 未读数（红点） |
| 用户 APP | `PUT /app-api/transport/notification/read` / `read-all` | 标记已读 |
| 用户 APP | `GET /app-api/transport/send/legs?no=` | 按单号查多段进度（仅下单人/收件人） |
| 司机 APP | `GET /app-api/transport/driver/handovers?driverId=` | 待确认交接 |
| 司机 APP | `POST /app-api/transport/driver/handover/confirm` | 拍照确认交接 |
| 司机 APP | `GET /app-api/transport/driver/legs?driverId=` | 我的运输段 |
| 后台 | `GET /admin-api/transport/handover/page` / `list-by-order` | 交接记录查询 |
| 后台 | `PUT /admin-api/transport/handover/dispute` | 标记争议 |
| 后台 | `GET /admin-api/transport/notification/page` / `POST /send` | 用户通知查询/发送 |
| 后台 | `GET /admin-api/transport/order-event/list-by-order` | 订单事件时间线 |

## 8. 演示路径（配合 demo SQL）

1. 执行 `sql/mysql/demo-cqupt-stations.sql`、`demo-cqupt-vehicles.sql`、`demo-multi-leg.sql`。
2. 订单 `TPCQ0004`（黄桷垭 → 南山）/`TPCQ0005`（南山 → 黄桷垭）无直达线路 → 一键调度后被拆成 2 段。
3. 司机端工作台出现"有 1 个换乘交接待确认"入口 → 进入交接页拍照确认。
4. 包裹追踪页显示"多段联运 · 换乘进度"，订单状态先"部分完成"后"已完成"；用户消息中心同步收到通知。

## 9. 尚未覆盖（后续可做）

- 后台"多段调度可视化"（DispatchVisualDialog）暂未叠加分段视角。
- 异常处理：司机迟到检测、换乘超时检测、车辆故障重调度、偏航完善（P3）。
- 真实道路分段里程/时长（当前段估时用 Haversine ÷ 均速，可接 `/api/v1/route` 换成高德路网）。
