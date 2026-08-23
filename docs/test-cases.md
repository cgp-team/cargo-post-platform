# 测试用例基线

| 范围 | 场景 | 预期 |
|---|---|---|
| transport 接入 | 已登录用户访问验证接口 | 返回 `transport module is running` |
| transport 鉴权 | 未登录访问验证接口 | 被统一权限体系拒绝 |
| 订单 | 三类订单创建与合法状态流转 | 数据与审计字段一致，非法跳转被拒绝 |
| 调度 | 手工派单、模拟派单、审核、下发、发车核验 | 容量/班次/订单不冲突且全程可审计 |
| 算法 Mock | SUCCESS | 返回车辆方案和已分配订单 |
| 算法 Mock | PARTIAL_REJECTION | 同时返回已分配订单与拒绝订单 |
| 算法 Mock | NO_FEASIBLE_SOLUTION | 状态为无可行解并提供原因 |
| 算法 Mock | TIMEOUT | 任务稳定保持 TIMEOUT，不伪造结果 |
| 算法 Mock | INTERNAL_ERROR | 返回标准 `ErrorResponse` |
| 幂等 | 重复 `requestId` | 不重复创建业务任务或覆盖结果 |
| 安全边界 | 浏览器尝试直连算法 URL | Nginx 无公开路由，访问失败 |
| 数据边界 | 算法容器检查数据库凭据 | 环境中不存在业务数据库凭据 |
| 部署 | Compose config、健康检查、备份帮助 | 配置有效，失败返回非零，密码来自环境变量 |

每次合并至少执行后端模块测试与打包、前端类型检查/构建（完整工程接入后）、Mock pytest、OpenAPI 解析、Compose config 和镜像构建。

## 自动化覆盖现状（2026-08-23 更新）

| 场景 | 自动化位置 |
|---|---|
| 契约 Mock 五场景与幂等 | `mock-algorithm/tests`（pytest，19 例）；适配层侧由 `AlgorithmClientTest`（12 例，含 408 轮询、503 重试、422 兼容、413/400 不重试）覆盖 |
| 契约验收（任意算法实现） | `mock-algorithm/tests/contract`（8 例，`ALGORITHM_BASE_URL` 参数化，可对自研/算法组镜像直接验收） |
| 自研算法求解器与距离提供方 | `algorithm/tests`（41 例：求解约束、矩阵缓存、QPS 限流退避、降级、双单位路径、segmentDuration） |
| 结果合法性校验（归属/容量/时序/订单唯一） | `AlgorithmResultValidatorTest`（14 例） |
| 适配层幂等（重复快照不重复建任务） | `AlgorithmAdapterTest`（5 例） |
| 调度状态机与派单 | `DispatchServiceImplTest`（16 例，含规模预检、里程换算/distanceUnit、路网分段传递） |
| 调度估算层（ETA/收入/成本） | `DispatchEstimationServiceTest`（7 例，含路网分段 ETA 与缺失回退） |
| 车来取货/送货提醒 | `AppSendControllerTest`（5 例：在途状态收口、位置新鲜度） |
| 证照/保险到期预警 | `DriverServiceImplTest` / `VehicleServiceImplTest`（到期边界） |
| 部署与浏览器直连边界 | 仍为手工/流水线用例，未自动化 |
