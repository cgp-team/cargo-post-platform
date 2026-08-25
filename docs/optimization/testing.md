# 测试策略与基线

## 分层
| 层 | 覆盖 | 现状 |
|---|---|---|
| 单元 | 枚举/工具/规则引擎/估算/求解器/模拟引擎 | ✅ |
| 集成 | 服务层状态机/闭环（Mockito） | ✅ |
| API 契约 | 算法 /route//plan 契约、mock-algorithm | ✅ |
| E2E | 调度闭环冒烟（`DispatchE2ESmokeTest`） | ✅ |
| 算法稳定性 | 随机打乱输入顺序（`test_stability.py`） | ✅ |
| Visual/UI | 管理端/小程序 | 部分（状态文案/卡片），完整待补 |

## 覆盖矩阵（任务书 四十七）
- 数据源：REAL / SIMULATED / AMAP / EUCLIDEAN（polyline 兜底）✅
- 站点：PLANNED / REAL / MIXED（规划+现实混合骨架）✅（骨架测试）
- 订单：正常/危险/禁运/超重/大件/生鲜 ✅；道路不可达/绕行/远距（待 Phase 6 后补规则用例）
- 客运 BOARD/ALIGHT、货运 PICKUP/DELIVERY ✅；单车/多车 ✅

## 基线（全部实跑通过）
- algorithm：**65 过**（求解/骨架/净载荷/polyline/稳定性）
- mock-algorithm：**27 过**（契约）
- transport 模块：**175 过**（审核/订单池/任务段/调度/司机/模拟/监控/实时公交/E2E）

## 纪律
- 每个 Phase 改动必须实跑测试验证（mvn 在 `/c/Users/袁/apache-maven-3.9.16/bin/mvn`）；不删除失败测试；不为 E2E 硬编码测试数据到生产逻辑。
