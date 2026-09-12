# 项目审计报告 (Project Audit)

**日期**: 2026-08-25
**当前分支**: fix/collect-orders
**最新 master SHA**: b5f1e64c
**审计版本**: v1.0

---

## 1. 当前架构图

```
┌─────────────┐   ┌──────────────┐   ┌─────────────┐
│  小程序前端   │   │  管理后台前端  │   │  司机端      │
│ (微信原生)   │   │ (Vue3+Elem+) │   │ (小程序内)   │
└──────┬───────┘   └──────┬───────┘   └──────┬──────┘
       │                  │                   │
       └──────────────────┼───────────────────┘
                          │
                    ┌─────▼─────┐
                    │   Nginx   │
                    │  (反向代理) │
                    └─────┬─────┘
                          │
                    ┌─────▼──────────────────┐
                    │    Spring Boot 3.5     │
                    │    (yudao-server)      │
                    │    Port: 48080         │
                    ├────────────────────────┤
                    │ yudao-module-transport │  ← 核心业务
                    │ yudao-module-system    │  ← 系统管理
                    │ yudao-module-infra     │  ← 基础设施
                    │ yudao-module-member    │  ← 会员中心
                    └─────┬──────────────────┘
                          │
              ┌───────────┼───────────┐
              │           │           │
        ┌─────▼─────┐ ┌──▼──┐ ┌─────▼──────────┐
        │  MySQL 8.4 │ │Redis│ │ 算法服务       │
        │           │ │ 7.4 │ │ (FastAPI+OR)   │
        └───────────┘ └─────┘ │ Port: 8000     │
                              └────────────────┘
                                      │
                              ┌───────▼───────┐
                              │  高德地图 API   │
                              │ (路线/距离/ETA) │
                              └───────────────┘
```

## 2. 服务清单

| 服务 | 技术栈 | 端口 | 说明 |
|------|--------|------|------|
| yudao-server | Java 21 + Spring Boot 3.5.15 | 48080 | 单体后端 |
| algorithm | Python 3.11 + FastAPI + OR-Tools | 8000 | 路径规划算法 |
| mock-algorithm | Python 3.12 + FastAPI | 8000 | 算法 Mock 服务 |
| mysql | MySQL 8.4.5 | 3306 | 数据库 |
| redis | Redis 7.4.5 | 6379 | 缓存 |
| nginx | Nginx | 80 | 反向代理/前端静态 |

## 3. 前端清单

### 管理后台前端 (Vue 3)
- **路径**: `yudao-ui/yudao-ui-admin-vue3/`
- **框架**: Vue 3.5 + Vite 8 + Element Plus 2.13 + TypeScript 6
- **页面**: 18 个 transport 业务页面（dashboard/dispatch/driver/order/station/vehicle/route/shift/monitoring 等）
- **测试**: ❌ 无测试框架配置，无单元测试
- **类型检查**: ✅ vue-tsc (`pnpm ts:check`)
- **Lint**: ✅ ESLint + Stylelint + Prettier

### 小程序前端 (微信原生)
- **路径**: `miniprogram/`
- **页面**: 18 个页面（index/goods/parcel/send/orders/bus/driver 等）
- **组件**: 4 个（driver-tab-bar/icon/empty-state/station-picker）
- **工具**: 12 个 utils（api/config/auth/location/demo-location/weather 等）
- **测试**: ⚠️ 仅 1 个手写测试文件 `tests/location.test.js`（Node assert，无框架）

## 4. 后端模块

### yudao-module-transport（核心业务）
- **源文件**: 267 个 Java 文件
- **Admin Controller**: 22 个（dashboard/dispatch/monitoring/order/station/vehicle/driver/route/shift/product 等）
- **App Controller**: 7 个（bus/send/product/order/notice/feedback/driver）
- **Service**: 40 个接口+实现对
- **Entity**: dispatch/driver/feedback/notice/order/product/route/shift/station/vehicle/algorithm
- **测试文件**: 15 个（含 controller/service/integration/util 测试）

### 其他模块
- **yudao-module-system**: ~30 个 Controller（用户/角色/菜单/租户/字典/认证等）
- **yudao-module-infra**: ~11 个 Controller（文件/代码生成/配置/任务/日志等）
- **yudao-module-member**: ~18 个 Controller（会员/地址/积分/签到等）
- **yudao-framework**: 15 个 starter（安全/MyBatis/Redis/Web/监控等）

## 5. 算法服务

### 算法 (algorithm/)
- **版本**: ortools-1.2.0
- **核心**: OR-Tools VRP 求解器
- **端点**: route planning / distance / health
- **限制**: 30 站点 / 25 订单 / 3 车辆 / 10s 超时
- **外部依赖**: 高德地图 API（可选，AMAP_KEY）
- **测试**: 5 个测试文件 + 1 个共享契约测试

### Mock 算法 (mock-algorithm/)
- **版本**: mock-2.0.0
- **场景**: SUCCESS / PARTIAL_REJECTION / NO_FEASIBLE_SOLUTION / TIMEOUT / INTERNAL_ERROR
- **距离**: 仅 haversine（无高德）
- **测试**: 2 个测试文件 + 1 个共享契约测试

## 6. Docker 服务

| 服务 | Dockerfile | Base | Healthcheck |
|------|-----------|------|-------------|
| yudao-server | yudao-server/Dockerfile | eclipse-temurin:21-jre | /actuator/health |
| algorithm | algorithm/Dockerfile | python:3.11-slim | /health |
| mock-algorithm | mock-algorithm/Dockerfile | python:3.12.11-slim | /health |
| mysql | (官方镜像) | mysql:8.4.5 | mysqladmin ping |
| redis | (官方镜像) | redis:7.4.5-alpine | redis-cli ping |

**MinIO**: 已于 2026-08-19 停用（0 对象），文件存储回退到数据库。

## 7. 外部依赖

| 依赖 | 用途 | 必需 | 备注 |
|------|------|------|------|
| 高德地图 API | 路线距离/ETA | 可选 | 空 AMAP_KEY → 直线距离 fallback |
| MySQL 8.4 | 数据存储 | 是 | |
| Redis 7.4 | 缓存 | 是 | |
| OR-Tools | VRP 求解 | 是（算法） | 仅真实算法需要 |
| 微信小程序 SDK | 小程序运行 | 是（前端） | |

## 8. 当前测试覆盖情况

| 组件 | 测试框架 | 测试文件数 | 覆盖情况 |
|------|---------|-----------|---------|
| Java 后端 | JUnit 5 + Mockito | 15 (transport) | ⚠️ 部分模块 |
| 算法服务 | pytest | 5 + 1 contract | ✅ 较完整 |
| Mock 算法 | pytest | 2 + 1 contract | ✅ 较完整 |
| 管理后台 | ❌ 无 | 0 | ❌ 无测试 |
| 小程序 | ⚠️ 手写 assert | 1 | ⚠️ 仅 location |

### Java 测试文件清单 (transport)
1. TransportTestControllerTest
2. AppSendControllerTest
3. DispatchEstimationServiceTest
4. DispatchServiceImplTest
5. MonitoringServiceImplTest
6. AppBusServiceImplTest
7. DriverAppServiceImplTest
8. DriverServiceImplTest
9. TransportOrderServiceImplTest
10. AppSendRouteInfoServiceTest
11. VehicleServiceImplTest
12. AlgorithmAdapterTest
13. AlgorithmClientTest
14. AlgorithmResultValidatorTest
15. GeoDistanceUtilTest

## 9. 已知风险

| 风险 | 严重程度 | 说明 |
|------|---------|------|
| 管理后台无测试 | P1 | 仅 vue-tsc 类型检查，无运行时验证 |
| 小程序测试极少 | P1 | 仅 location 模块有测试 |
| 高德 API 无 key 时 fallback | P2 | 直线距离可能不准确 |
| 算法输入顺序敏感性 | P2 | 未验证不同输入顺序的影响 |
| MinIO 已停用 | P2 | 需确认所有上传路径已切换 |
| 归集入池语义 | P1 | PR#90 改为 orderIds，需验证行为正确 |

## 10. ENVIRONMENT_BLOCKED 项目

| 项目 | 原因 | 说明 |
|------|------|------|
| 本地 Docker 完整启动 | 需要 MySQL+Redis+算法 同时运行 | 本地可能无法完整启动 |
| 高德 API 真实测试 | 需要有效 AMAP_KEY | .env.example 中为空 |
| 小程序真机测试 | 需要微信开发者工具 | 无法在 CLI 环境执行 |
| 管理后台视觉测试 | 需要浏览器环境 | 无法在 CLI 环境执行 |
| 服务器部署测试 | 不负责服务器运维 | 属于其他成员职责 |

---

**审计结论**: 项目架构清晰，后端测试有一定覆盖，但前端测试严重不足。下一步将运行所有现有测试，记录结果。
