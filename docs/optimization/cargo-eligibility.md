# 承运资格审核（Cargo Eligibility）

## 流程
```
客户提交 → 自动承运审核 → 结果 → 客户反馈
  ACCEPTED      → READY_FOR_POOL
  CONDITIONAL   → WAITING_CUSTOMER_ACTION → 客户确认送站 → READY_FOR_POOL
  MANUAL_REVIEW → PENDING_REVIEW → 管理端审核 → READY_FOR_POOL / REJECTED
  REJECTED      → CANCELLED（不可入池）
```

## 状态分离
- **OrderLifecycle**（`TransportOrderStatusEnum`）：已创建/待审核/待客户操作/待入池/已入池/已分配/已发车/已完成/已取消。
- **ReviewStatus**（`ReviewStatusEnum`）：待审/通过/需客户操作/需人工/拒运。
- **ServiceMode**（`ServiceModeEnum`）：上门/最近站/安全点/客户送站/站到站（取送分别判定）。
- **ReasonCode**（`ReviewReasonCodeEnum`）：PROHIBITED_GOODS / DANGEROUS_GOODS / OVER_WEIGHT / OVER_SIZE / ROAD_UNREACHABLE / DETOUR_TOO_LARGE / PASSENGER_SERVICE_CONFLICT / NO_SAFE_HANDOFF_POINT / CUSTOMER_ACTION_REQUIRED / MANUAL_REVIEW_REQUIRED。**后端返回码，前端映射文案**（小程序仓库 cargo-post-miniprogram 的 `utils/review.js`）。

## 规则引擎（`CargoReviewService`）
| 规则 | 结果 |
|---|---|
| 危险品/禁运关键词 | REJECTED + DANGEROUS/PROHIBITED |
| 单件 > 30kg | REJECTED + OVER_WEIGHT |
| 大件/超规关键词 | CONDITIONAL + CUSTOMER_TO_STATION（推荐送达站） |
| 生鲜/需冷链 | MANUAL_REVIEW + MANUAL_REVIEW_REQUIRED |
| 其余 | PASSED + STATION_TO_STATION |

## 落库
`transport_cargo_order`：review_status / review_reason_codes / pickup_service_mode / delivery_service_mode / service_point_station_id（增量 V013）。

## 已知项
- 道路可达/绕行成本/客运影响/时间窗口等维度需 Road Route + 联合调度能力后接入（Phase 6/5 已就绪，规则引擎扩展点）。
