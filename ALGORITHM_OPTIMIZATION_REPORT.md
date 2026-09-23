# ALGORITHM_OPTIMIZATION_REPORT

HACO-CPS 1.4.1 算法专项优化与缺陷修复报告

工作范围：`cargo-post-platform/algorithm/`（及 MultiLegPlanner 兼容性小修）
原则：只修算法正确性与可回归性，不扩展业务功能，不实现乘客预约。

---

## 1. 本轮发现的问题

### P0（正确性）

| ID | 问题 | 位置 |
|----|------|------|
| P0-1 | ALNS SA 接受概率未使用真实 delta：`delta = key() > key()` 是布尔，`exp(-1.0/T)` 与目标差无关，所有更差解同概率 | `alns_v14.py` |
| P0-2 | Swap evaluate/apply 邻域不一致：`_try_swap` 评估双向交换，`apply_move` 只移动 task1；Swap 的 pickup/delivery 记为 (0,0)/(None,None) | `local_search_v14.py` |
| P0-3 | RouteGenome 缺少统一 `assert_invariants()`；`GlobalRouteGenome.copy()` 共享可变 skeleton 列表 | `route_genome.py` |
| P0-4 | Local Search / ALNS 按 raw event index 插入，可绕开 skeleton gap 语义 | `route_genome.py` / `local_search_v14.py` / `alns_v14.py` |
| P0-5 | ALNS destroy/repair 失败时可能静默丢任务仍进入 SA | `alns_v14.py` |

### P1（一致性 / 稳定性 / 可观测）

| ID | 问题 | 位置 |
|----|------|------|
| P1-1 | passenger impact：`evaluate_route_genome` 从 0 起步，FeasibilityEngine / legacy 用 `initial_passenger_load`，口径分裂 | `evaluator.py` |
| P1-2 | 多套评分权重（normalized_cost / cheap score / insertion score）未集中声明职责 | `encoding.py` / `construction.py` / `heuristic.py` |
| P1-3 | Construction cheap pruning 可能误杀优秀候选，缺少 diversity 与 screened/feasible/selected 遥测 | `construction.py` |
| P1-4 | Exact Oracle 被表述为“全局最优”，实际是简化模型（无 skeleton / gap / 时间窗 / passenger_impact） | `exact_oracle.py` / `test_optimality_gap.py` |
| P1-5 | MultiLegPlanner fallback Haversine 直送被包装成 DIRECT 可行；TRANSFER_PENALTY 与 HANDOVER_DWELL 语义重叠未澄清 | `MultiLegPlanner.java` |

---

## 2. P0 修复项

### 2.1 SA 接受概率（P0-1）

**修复前：**
```python
delta = repaired_obj.key() > current_obj.key()  # 布尔值
if temperature > 0.01:
    prob = math.exp(-1.0 / temperature)        # 常数，与 delta 无关
```

**修复后：**
- 业务比较：`ObjectiveVector.key()`（lexicographic）
- SA delta：`scalar_delta_worse()` → `objective_to_scalar(candidate) - objective_to_scalar(current)`
- candidate better/equal → 必接受
- candidate worse → `accept = exp(-delta / temperature)`，delta > 0
- 边界：`temperature <= 0` / 非有限 → 只接受非更差；delta 非有限 → 拒绝；`exp` 下溢保护
- 硬约束不可行（`infeasibility > 0`）禁止 SA 随机接受
- destroy/repair 后任务集合不完整 → 按不可行处理，不进入 SA

新增 `sa_accept()` 与单元测试：改善必接受、相等必接受、轻微变差概率高于严重变差、降温后概率下降、`temperature <= 0` 无 NaN、不可行拒绝。

### 2.2 Swap evaluate/apply 一致性（P0-2）

**修复前：** `_try_swap` 双向交换评估；`apply_move` 只执行 relocate（`task_ids[0]`）。

**修复后：**
- 统一 `MoveType`：`RELOCATE` / `SWAP`（`OR_OPT` / `PAIR_RELOCATE` 预留）
- `NeighborhoodMove` 记录：affected routes、task ids、pickup/delivery positions、objective
- SWAP 原子语义：route1↔route2 交换两个 task；evaluate 与 apply 使用同一组插入位
- `apply_move_detailed()`：before snapshot → 应用 → 不变量检查；失败完整 rollback
- 后续改为“对方原位置换位”邻域（避免 O(n²) 插入位组合爆炸）

回归测试：2 车 swap、task 集合不变、数量不变、PASSENGER BOARD/ALIGHT、失败 rollback、precedence 保持。

### 2.3 RouteGenome 不变量（P0-3）

新增 `assert_invariants()`：
1. DEPOT 首事件
2. RETURN 唯一且恒为最后业务事件
3. RETURN 后无业务/PASS 事件
4. pickup 先于 delivery / BOARD 先于 ALIGHT
5. skeleton PASS 顺序不变
6. 任务不丢失、不重复、placements 与 events 一致
7. 空 route 合法

边界：
- `pickup_index` 允许落在 RETURN 下标（`list.insert` = 插在 RETURN 前）
- `delivery_index == len(events)` 表达“尽可能晚”，规范化后 RETURN 仍最后
- `GlobalRouteGenome.copy()` 深拷贝 skeletons 列表，避免串改

### 2.4 Skeleton / Gap-aware 操作层（P0-4）

在 RouteGenome 上增加（不改整体数据结构）：
- `get_gap_ranges()` / `get_gap_for_task()`
- `can_insert_into_gap()`
- `move_task_between_gaps()` / `swap_tasks_between_gaps()`（失败 rollback）

`insert_task()` 强制 gap 校验，禁止 raw index 把 cargo 插进 skeleton PASS 之间。

### 2.5 destroy/repair 任务完整性（P0-5）

ALNS 主循环增加 `_task_set_preserved()`：repair 后任务集合必须与 destroy 前一致（不丢不重），否则拒绝进入 current。

---

## 3. P1 修复项

### 3.1 ObjectiveVector 统一（P1-2）

| 用途 | API | 说明 |
|------|-----|------|
| 硬约束 | `FeasibilityEngine.check/validate_solution` | 唯一裁判 |
| 业务最终比较 | `ObjectiveVector.key()` | lexicographic |
| 搜索内部能量 | `ObjectiveVector.objective_to_scalar()` | SA / 信息素 / cheap ranking |
| 向后兼容 | `normalized_cost()` | 别名 = `objective_to_scalar()` |

权重集中在 `encoding.SEARCH_ENERGY_*`，注释明确“搜索内部能量函数，不是业务最终目标”。
Construction cheap score 改为同一组权重。

### 3.2 Passenger impact 统一（P1-1）

抽出 `calculate_passenger_impact(route, tasks, station_map, matrix, initial_passenger_load)`：
- `current_passengers` 从 `initial_passenger_load` 起步
- BOARD/ALIGHT 更新载客
- cargo detour × 载客 × 25km/h 折算秒

`evaluate_route_genome` / `evaluate_route_states` / Construction Stage B 共用。
注释明确：这是 detour 折算的**代理指标**，不是真实到站延误；本轮不引入乘客预约。

### 3.3 FeasibilityEngine 统一裁决（P1）

新增 `validate_solution()`：
- task uniqueness（跨 route 不重复）
- task completeness（可选 expected_task_ids）
- 每条 route `assert_invariants()` + `check()` 全量硬约束

### 3.4 Construction 多样性（P1-3）

- top-k cheap（约 70% 池位，自适应）
- diversity candidates（按 vehicle/pickup group 轮转抽样）
- 全量 FeasibilityEngine + passenger impact 一致评估
- 遥测：`generate_insertion_candidates.last_stats = {screened_count, feasible_count, selected_count}`

### 3.5 Exact Oracle / Benchmark 命名（P1-4）

- `exact_oracle.py` 标记为 **简化模型 Oracle**，列出与生产模型差异
- `test_optimality_gap.py` 改为 consistency check 表述，禁止“距全局最优只差 X%”结论

### 3.6 MultiLegPlanner 兼容小修（P1-5）

- fallback Haversine 直送 reason 标注 `[TRANSPORT_UNCERTAIN]`
- 新增 `transportStatus(Candidate)`：`TRANSPORT_FEASIBLE` / `TRANSPORT_UNCERTAIN` / `TRANSPORT_INFEASIBLE`
- 澄清 `TRANSFER_PENALTY`（软偏好）与 `HANDOVER_DWELL`（物理停靠）各计一次
- 3-leg `score(3, 2, ...)` 换乘惩罚次数正确（2 次，无重复）
- 未重构 transport 体系

---

## 4. 修改文件

| 文件 | 原因 |
|------|------|
| `algorithm/app/haco/encoding.py` | `objective_to_scalar` / `scalar_delta_worse` / SEARCH_ENERGY 权重集中 |
| `algorithm/app/haco/alns_v14.py` | SA 真实 delta；任务完整性；温度下限 |
| `algorithm/app/haco/local_search_v14.py` | 统一 Move；原子 SWAP；rollback；passenger load 透传 |
| `algorithm/app/haco/route_genome.py` | `assert_invariants`；Gap-aware 层；copy 隔离；insert 边界 |
| `algorithm/app/haco/evaluator.py` | `calculate_passenger_impact` 统一；initial_passenger_load |
| `algorithm/app/haco/feasibility_engine.py` | `validate_solution` 全解裁决 |
| `algorithm/app/haco/construction.py` | diversity 候选；统一 cheap score；筛选遥测 |
| `algorithm/app/haco/exact_oracle.py` | 简化模型 Oracle 声明 |
| `algorithm/tests/test_algorithm_correctness_p0.py` | 新增：SA / Swap / Invariant / Gap / passenger 回归 |
| `algorithm/tests/test_multi_seed_stability.py` | 新增：多 seed 稳定性 |
| `algorithm/tests/test_haco_141_regression.py` | exhaustive helper 对齐 SEARCH_ENERGY |
| `algorithm/tests/benchmarks/test_optimality_gap.py` | 修正“全局最优”表述 |
| `.../dispatch/MultiLegPlanner.java` | TRANSPORT_UNCERTAIN 标注；惩罚语义澄清；transportStatus |

---

## 5. Benchmark / 多 seed 稳定性结果

### 5.1 测试执行

核心正确性与回归（按模块）：

- `test_algorithm_correctness_p0.py`：**33 passed**
- `test_route_genome_v14.py` + `test_route_genome_return_terminal.py`：32 passed
- feasibility / objective / construction / pheromone / passenger impact：全部通过
- `test_haco_141_regression.py`：59 passed（含 pruning adversarial）
- `test_solver.py`：13 passed（单独模块；批量下偶发时间预算抖动，非断言逻辑错误）
- benchmark：`test_haco_vs_baseline` 10 passed；`test_algorithm_quality` 6 passed；`test_baseline_comparison` 3 passed；`test_benchmark_scenarios` 8 passed；`test_optimality_gap` 4 passed
- contract：8 passed

### 5.2 多 seed 稳定性（10 seeds）

```
MULTI_SEED feasible=10/10 best=0.161 mean=0.165 median=0.161 std=0.006
```

- feasible rate：10/10
- exception count：0
- invariant failure count：0（无任务丢失/重复，RETURN 末位）
- distance：best 0.161 / mean 0.165 / median 0.161 / std 0.006

说明：当前 `solve()` 对外签名不暴露 seed 参数，seed 经 `AlgorithmConfig.randomSeed` 注入。HACO 与 BASELINE 比较时，若 instance / constraints / time limit / objective / seed policy 不完全一致，**不得声称严格可比**；现有 benchmark 已尽量同参，但时间预算仍可能导致路径差异。

---

## 6. 仍然存在但本轮没有处理的问题

1. **增量 evaluator 未实现**：仍全量重算 route objective；未做 `incremental_score == full_recompute_score` 对照（属性能优化项，本轮正确性优先）。
2. **同 route 内 SWAP / OR_OPT / PAIR_RELOCATE**：接口已预留，未启用。
3. **Gap 跨段 pickup/delivery（pickup_gap < delivery_gap）**：数据结构支持校验，搜索邻域未充分探索跨 gap 配对。
4. **`test_solver` 批量下偶发确定性失败**：根因是 wall-clock 时间预算（`haco_time_limit`）导致搜索截断不同，不是 SA/Swap 逻辑错误；需要确定性时间片或迭代预算才能根治。
5. **Exact Oracle 与生产模型仍不同构**：无 skeleton gap / 时间窗+服务时间 / CargoOut-CargoIn-PRELOADED 完整口径 / passenger_impact。
6. **MultiLegPlanner**：未引入真正的道路可行性验证；Haversine fallback 仍可能被执行，仅状态标注为 UNCERTAIN。
7. **heuristic.py / gap_pheromone / hybrid_optimizer / stop_level** 等旁路评分未全部收敛到 `objective_to_scalar`（主路径 HACO 已统一）。
8. **性能**：Local Search relocate 仍为 O(tasks × positions) 全枚举；Swap 为跨车 pair 换位；未做 pheromone 增量更新。

---

## 7. 下一阶段建议

1. 将时间预算从 wall-clock 改为“迭代/评估次数预算”，消除批量确定性抖动。
2. 实现增量 evaluator，并保留 full recompute debug 校验开关。
3. 启用同 route Swap / Or-opt，并补 PAIR_RELOCATE（乘客/货物配对整体移动）。
4. 为 Exact Oracle 补齐 skeleton + initial_load + 时间窗的同构模式，再谈 optimality gap。
5. MultiLegPlanner fallback 接入真实道路/时刻数据后，再把 UNCERTAIN 升级为 FEASIBLE。
6. Benchmark 输出统一记录：feasible rate / vehicle count / passenger impact / cargo detour / distance / duration / runtime / objective key / seed / exception count / invariant failure count，并输出 best/mean/median/std。

---

## 8. 兼容性声明

- 未实现乘客预约 / 预约订单 / 时间窗 / 购票
- 未改数据库业务模型，无新表
- 未改小程序 / 管理端 / 司机端业务页面
- 未改客货邮整体业务流程
- 未删除 HACO / ALNS / Local Search / Feasibility / Benchmark
- API 核心语义保持：`normalized_cost()` 保留为 `objective_to_scalar()` 别名
- 未声称“全局最优 / 世界先进 / 行业领先”
