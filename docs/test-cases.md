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
