# ARCHITECTURE STATUS

最后更新：DISPATCH_CORE_V047（2026-09-23）
目的：**明确 production / experimental 分层**，避免“改了 2.0，生产实际跑 1.4”。

---

## PRODUCTION（生产实际运行）

| 组件 | 位置 | 版本/标识 | 说明 |
|---|---|---|---|
| HACO-CPS | `algorithm/app/haco/v14_solver.py` | `haco-cps-1.4.1` | `solver.solve` → `_solve_haco` 唯一实体 |
| ALNS | `algorithm/app/haco/alns_v14.py` | v14 | 与 1.4.1 配套 |
| 硬约束 | `algorithm/app/haco/feasibility_engine.py` | `FeasibilityContext` | 统一 station/matrix/duration/detour 口径 |
| 动态调度 | `algorithm/app/dispatch_opt/coordinator.py` | `DynamicDispatchCoordinator` | 新订单唯一主入口（`/api/v1/dispatch/allocate`） |
| 路由 | `algorithm/app/routing/*` | Local/GraphHopper → Cache → AMap | formal geometry 才允许进入正式成本 |
| 坐标边界 | `algorithm/app/routing/coordinate.py` | GCJ-02 ⇄ WGS-84 | 唯一转换入口 |
| OR-Tools baseline | `algorithm/app/baseline/ortools_solver.py` | `ortools-1.3.0` | BASELINE / HYBRID 用 |
| GH Branch Selector | `algorithm/learning/path_search/gh_branch_selector.py` | `ROUTE_SEARCH_BRANCH_SELECTOR_V046_CANDIDATE` | `ENGINEERING_SIGNAL_POSITIVE`；V047 训练完成后替换 ranker |

**生产算法版本常量**

- `ALGORITHM_VERSION = haco-cps-1.4.1`（`app/main.py`, `app/solver.py`）
- `BASELINE_VERSION = ortools-1.3.0`
- `PARAMETER_VERSION = haco-cps-default-v1.4.1`

**生产调用链**

```
/api/v1/plan            → build_result → solver.solve → v14_solver.solve (HACO-CPS 1.4.1)
/api/v1/dispatch/allocate → DynamicDispatchCoordinator → DispatchPlan + Decision Trace
```

## EXPERIMENTAL（实验线，**不在**生产运行路径）

| 组件 | 位置 | 状态 | 备注 |
|---|---|---|---|
| HACO 2.0 | `algorithm/app/haco/solver.py`, `global_*.py`, `route_genome.py` | EXPERIMENTAL | 未被 `solver.solve` 分流调用 |
| GlobalRouteGenome | `algorithm/app/haco/global_construction.py` 等 | EXPERIMENTAL | 同上 |
| LocalGraph Python Pruner | `algorithm/learning/path_search/pruner_v044.py`, `pruner_v045.py` | `RESEARCH_ONLY` | V045 已证：即使去掉 LightGBM 仍明显慢于纯 A*；**不重新启用** |
| PATH SEARCH V047 训练 | `algorithm/learning/training/run_v047_branch_training.py` | 并行训练中 | 本任务**禁止**修改 trainer / dataset label / ranker 核心 |

## 禁止事项（回归红线）

1. 不停止 / 重写 / 污染 PATH SEARCH V047 训练；
2. 不重写 V046 GH Branch Selector；
3. 不重新启用 Python A* LocalGraph Pruner 作为生产路径；
4. 不把 Path Search ML 与 Dispatch ML 混成一个模型；
5. 不为了赶时间引入 GNN / Transformer / RL。

## 已知非本任务问题（不影响本任务验收，但会出现在全量 pytest）

`tests/test_lsr_training.py` 有 3 个用例调用 `HardNegativeMiner.mine(...)`，而
`algorithm/learning/training/hard_negative_mining.py` 只提供
`mine_model_disagreement(...)` / `mine_boundary(...)`。该失败属于 **PATH SEARCH 训练线**，
本任务禁止修改，故保留原状并在报告中如实记录。

