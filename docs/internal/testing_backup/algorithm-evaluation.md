# 算法评估报告 (Algorithm Evaluation)

**日期**: 2026-08-25

---

## 1. 测试覆盖

### 1.1 Algorithm (OR-Tools)

| 测试文件 | 测试数 | 覆盖内容 |
|----------|--------|---------|
| test_distance.py | 6 | Haversine 距离计算 |
| test_distance_endpoint.py | 8 | 距离 API 端点 |
| test_route.py | 20 | 路线规划端点 |
| test_solver.py | 10 | VRP 求解器逻辑 |
| contract/test_contract.py | 16 | 跨服务契约 |

**总计**: 60 tests, 全部通过

### 1.2 Mock Algorithm

| 测试文件 | 测试数 | 覆盖内容 |
|----------|--------|---------|
| test_main.py | 19 | Mock 端点和场景 |
| contract/test_contract.py | 8 | 跨服务契约 |

**总计**: 27 tests, 全部通过

---

## 2. 求解器能力验证

### 2.1 基础功能

| 场景 | 测试 | 状态 |
|------|------|------|
| 单车辆单订单 | test_single_vehicle_preferred_when_capacity_enough | ✅ |
| 单车辆多订单 | test_success_single_vehicle | ✅ |
| 多车辆容量溢出 | test_auto_second_vehicle_on_overload | ✅ |
| 乘客超载分车 | test_auto_second_vehicle_on_passenger_overload | ✅ |
| 货物超载分车 | test_auto_second_vehicle_on_cargo_overload | ✅ |
| 总需求超总容量 | test_total_demand_over_total_capacity_infeasible | ✅ |
| 闭环出发返回 | test_closed_loop_depart_return | ✅ |
| 先上后下约束 | test_board_before_alight | ✅ |
| 容量永不超限 | test_capacity_never_exceeded | ✅ |

### 2.2 边界条件

| 场景 | 测试 | 状态 |
|------|------|------|
| 超规模请求 | test_over_limit_413 | ✅ |
| 幂等重放 | test_idempotent_replay_returns_cached | ✅ |
| 未知站点 | test_unknown_station_400 | ✅ |
| 超时轮询 | test_timeout_then_poll_result | ✅ |
| 无可行解 | test_no_feasible_solution_scenario | ✅ |
| 部分拒绝 | test_partial_rejection_scenario | ✅ |
| 内部错误 | test_internal_error_scenario | ✅ |
| 25 订单压力 | test_full_scale_25_orders_under_time_limit | ✅ |

### 2.3 确定性验证

| 测试 | 说明 | 状态 |
|------|------|------|
| test_deterministic_same_input_same_output | 相同输入多次运行结果一致 | ✅ |

---

## 3. 距离计算模式

| 模式 | 条件 | 精度 | 状态 |
|------|------|------|------|
| Haversine (默认) | 无 AMAP_KEY | 直线距离，误差 10-30% | ✅ |
| 高德路网 | 有 AMAP_KEY | 真实驾驶距离 | ⚠️ 需配置 AMAP_KEY 测试 |
| 降级 fallback | 高德失败 | 回退到 Haversine | ✅ |

---

## 4. 性能基准

| 指标 | 值 | 说明 |
|------|-----|------|
| 25 订单求解时间 | < 10s | 测试通过 |
| 算法服务启动 | ~2s | FastAPI + OR-Tools |
| 契约测试总耗时 | 1.81s | 60 tests |

---

## 5. 已知限制

| 限制 | 说明 | 影响 |
|------|------|------|
| 最大 30 站点 | 求解器硬限制 | 超规模返回 413 |
| 最大 25 订单 | 求解器硬限制 | 超规模返回 413 |
| 最大 3 车辆 | 求解器硬限制 | 超规模返回 413 |
| 10s 超时 | 求解器超时 | 超时返回轮询 |
| 输入顺序敏感性 | 未验证 | ⚠️ 建议后续测试 |

---

## 6. 建议

1. **输入顺序测试**: 随机打乱订单输入顺序，验证结果一致性
2. **Greedy baseline**: 对比贪心算法和 OR-Tools 的结果质量
3. **真实场景压测**: 使用生产数据规模测试性能
4. **AMAP_KEY 配置**: 配置后测试真实路网距离
