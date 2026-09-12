# 寄货完整 E2E 流程 (E2E Flow)

**日期**: 2026-08-25

---

## 1. 寄货 E2E 流程

### 1.1 流程图

```
首页
  ↓
寄货 (pages/send/send)
  ↓
选择取货站 (station-picker)
  ↓
选择送达站 (station-picker)
  ↓
路线预览 (POST /app-api/transport/send/route-preview)
  ↓
创建订单 (POST /app-api/transport/order/create)
  ↓
后台订单审核 (admin TransportOrderController.audit)
  ↓
归集入池 (POST /admin-api/transport/dispatch/order-pool/collect)
  ↓
智能派单 (POST /admin-api/transport/dispatch/plan/smart)
  ↓
生成方案 (DispatchPlan + DispatchPlanItem)
  ↓
司机执行 (DriverAppServiceImpl)
  ↓
订单完成 (TransportOrderStatus.COMPLETED)
```

### 1.2 每步验证

| 步骤 | UI | API | 数据库 | 状态机 | 状态 |
|------|-----|-----|--------|--------|------|
| 首页 | pages/index | - | - | - | ✅ 代码审查 |
| 寄货 | pages/send | - | - | - | ✅ 代码审查 |
| 选择取货站 | station-picker | GET /station/list | transport_station | - | ✅ 代码审查 |
| 选择送达站 | station-picker | GET /station/list | transport_station | - | ✅ 代码审查 |
| 路线预览 | 路线预览组件 | POST /send/route-preview | - | - | ✅ 代码审查 |
| 创建订单 | 订单表单 | POST /order/create | transport_order | CREATED | ✅ 代码审查 |
| 后台审核 | OrderForm | PUT /order/audit | transport_order | auditStatus=1 | ✅ 代码审查 |
| 归集入池 | dispatch 页面 | POST /dispatch/collect | transport_order | CREATED→POOLED | ✅ 代码审查 |
| 智能派单 | dispatch 页面 | POST /dispatch/plan/smart | dispatch_plan + items | POOLED→ASSIGNED | ✅ 代码审查 |
| 生成方案 | 方案详情 | GET /dispatch/plan/:id | dispatch_plan | PENDING | ✅ 代码审查 |
| 司机执行 | driver/workbench | POST /driver/depart | transport_order | ASSIGNED→DEPARTED | ✅ 代码审查 |
| 订单完成 | driver/workbench | POST /driver/deliver | transport_order | DEPARTED→COMPLETED | ✅ 代码审查 |

---

## 2. 司机端 E2E 流程

### 2.1 流程图

```
司机工作台 (pages/driver/workbench)
  ↓
查看待执行方案
  ↓
发车 (POST /app-api/transport/driver/depart)
  ↓
到达取货站
  ↓
取货确认 (POST /app-api/transport/driver/pickup)
  ↓
运输
  ↓
到达送达站
  ↓
送达确认 (POST /app-api/transport/driver/deliver)
  ↓
方案完成
```

### 2.2 验证

| 步骤 | 状态守卫 | CAS | 状态 |
|------|---------|-----|------|
| 发车 | status==ASSIGNED | ✅ WHERE status=ASSIGNED | ✅ |
| 取货确认 | status in {0,1,2,3} | ✅ WHERE status IN | ✅ |
| 送达确认 | status==DEPARTED | ✅ WHERE status=DEPARTED | ✅ |

---

## 3. 乘客 E2E 流程

### 3.1 流程

```
附近公交 (pages/bus/index)
  ↓
查看线路和站点
  ↓
上车 (BOARD)
  ↓
下车 (ALIGHT)
```

### 3.2 验证

| 步骤 | 状态 | 说明 |
|------|------|------|
| 附近公交 | ✅ | AppBusServiceImpl 实现 |
| 线路查询 | ✅ | RouteService |
| 上下车 | ✅ | 状态机 BOARD→ALIGHT |

---

## 4. 结论

所有 E2E 流程在代码审查层面验证通过。运行时验证需要本地 Docker 环境或测试服务器。
