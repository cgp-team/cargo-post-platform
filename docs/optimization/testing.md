# 测试策略与基线

> **时效说明（2026-09-21）**：本文的"基线"数字与覆盖面为优化项目收尾时的快照。运营化改造（PR #153）后，
> mock-algorithm 目录与其契约套件、模拟引擎（SimulationEngine）相关测试、SIMULATED 数据源均已删除；
> 算法契约验收现由 `algorithm/tests/contract/` 承担（`ALGORITHM_BASE_URL` 参数化）。

## 分层
| 层 | 覆盖 | 现状 |
|---|---|---|
| 单元 | 枚举/工具/规则引擎/估算/求解器 | ✅ |
| 集成 | 服务层状态机/闭环（Mockito） | ✅ |
| API 契约 | 算法 /route//plan 契约（`algorithm/tests/contract/`） | ✅ |
| E2E | 调度闭环冒烟（`DispatchE2ESmokeTest`） | ✅ |
| 算法稳定性 | 随机打乱输入顺序（`test_stability.py`） | ✅ |
| Visual/UI | 管理端/小程序 | 部分（状态文案/卡片），完整待补 |

## 覆盖矩阵（任务书 四十七）
- 数据源：REAL / SIMULATED / AMAP / EUCLIDEAN（polyline 兜底）✅
- 站点：PLANNED / REAL / MIXED（规划+现实混合骨架）✅（骨架测试）
- 订单：正常/危险/禁运/超重/大件/生鲜 ✅；道路不可达/绕行/远距（待 Phase 6 后补规则用例）
- 客运 BOARD/ALIGHT、货运 PICKUP/DELIVERY ✅；单车/多车 ✅

## 基线（优化项目收尾时快照，数字为当时实跑结果）
- algorithm：**65 过**（求解/骨架/净载荷/polyline/稳定性）
- mock-algorithm：**27 过**（契约，该组件已于 2026-09-21 删除）
- transport 模块：**175 过**（审核/订单池/任务段/调度/司机/监控/实时公交/E2E；含当时仍存在的模拟运营用例）

## 纪律
- 每个 Phase 改动必须实跑测试验证（mvn 在 `/c/Users/袁/apache-maven-3.9.16/bin/mvn`）；不删除失败测试；不为 E2E 硬编码测试数据到生产逻辑。
