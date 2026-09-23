# DISPATCH_CORE_V047 — Benchmark

脚本：`algorithm/benchmarks/dispatch_v047_benchmark.py`
原始结果：`algorithm/data/dispatch_v047_benchmark.json`
失败用例：`algorithm/data/dispatch_v047_failure_cases.json`

## 数据来源（不使用随机经纬度 / 合成道路）

沿用 `learning/training` 同源的真实重庆站点 fixture（WGS84，来自 OSM 区域）：

```
CUPT(29.5333,106.6074) CTBU(29.5020,106.5830) NANPING(29.5220,106.5680)
SHAPINGBA(29.5400,106.4500) YANGJIAYU(29.5600,106.5700) JIEFANGBEI(29.5630,106.5750)
CHANGAN(29.5280,106.5500) LIJIAYU(29.5450,106.5300)
```

场景覆盖：主城区（CUPT/JIEFANGBEI/CTBU）、跨区（SHAPINGBA↔CHANGAN）、
SHORT / MEDIUM / LONG、普通 / 复杂 / MultiLeg / Gap。

## 本机环境限制（必须如实声明）

1. 本机**没有 OSM/GraphHopper 路网图**，也**没有 AMAP key**；
2. 因此 `HACO_DYNAMIC_GH` 记 `SKIPPED_NO_ROUTING_BACKEND`，**不伪造**真实道路指标；
3. `HACO_ONLY` 是真实 HACO-CPS 1.4.1 求解，但矩阵口径为 `matrix=None`（degree/直线），报告中标注；
4. `HACO_DYNAMIC` 的增量成本在无正式路网时**显式标记 `costIsFormal=false`**，不是正式成本。

## 四策略定义

| 策略 | 定义 |
|---|---|
| `BASELINE_RULE` | 最近车辆 / 当前班次优先，不看真实增量成本 |
| `HACO_ONLY` | 现有 HACO + ALNS（批次求解） |
| `HACO_DYNAMIC` | HACO + ALNS + Dynamic Dispatch Coordinator + Marginal Cost + TripLock + MultiLeg + Economic Admission |
| `HACO_DYNAMIC_GH` | 在 `HACO_DYNAMIC` 基础上再接入 V046 GH Branch Selector |

## 实测结果（本轮）

### HACO_DYNAMIC（7 个场景）

| 指标 | 值 |
|---|---|
| 场景数 | 7 |
| 订单完成率 | 0.714（5/7） |
| HOLD 率 | 0.286（2/7） |
| P50 dispatch runtime | 0.28 ms |
| P95 dispatch runtime | 0.506 ms |
| level 分布 | LEVEL_0_FAST_INSERT ×3、LEVEL_2_OTHER_ROUTE_OR_MULTILEG ×2、LEVEL_5_HOLD ×2 |
| 正式成本可用 | false（本机无正式路网） |
| Global HACO 触发次数 | 0 |
| GH Branch Selector | SKIPPED_NO_ROUTING_BACKEND |

### HACO_ONLY（真实 HACO-CPS 1.4.1）

| case | status | algorithmVersion | runtime | matrix |
|---|---|---|---|---|
| haco-short | feasible | haco-cps-1.4.1 | 63.4 ms | None(degree) |
| haco-medium | feasible | haco-cps-1.4.1 | 31.6 ms | None(degree) |

### BASELINE_RULE

按最近车辆/当前班次完成分配（7/7），**不评估**容量、绕行预算、乘客影响、SLA、经济价值 ——
这正是 V047 要修复的“默认 candidate 无真实增量成本”问题。

## 未能在本机测量的指标（不编造）

以下指标需要真实路网 + AMAP 校验，本机缺失，故未给出数值：

`cargo detour mean/p95`（正式口径）、`passenger impact`（正式口径）、`GH calls`、
`average incremental cost`（正式口径）、`SLA violation`（需真实乘客时刻表/真实 ETA）、
`P50/P95 delivery ETA`（需正式道路 ETA）。

## Worst-20 与 failure cases

`algorithm/data/dispatch_v047_failure_cases.json`（本轮 `round=baseline`，共 9 条）：

| 类别 | 条数 | 说明 |
|---|---|---|
| `ROUTING_FALLBACK` | 7 | 无 OSM/GraphHopper/AMAP → 成本显式非 formal（**预期行为，非缺陷**） |
| `UNKNOWN` | 2 | 复杂场景（DEPARTED + 高价值）在无正式成本下进入 HOLD（**预期行为**） |

> 说明：这两类记录是“诚实降级”的证据，不是隐藏失败。接入正式路网后应重新跑本轮基准，
> 并确认 `ROUTING_FALLBACK` 归零、DEPARTED+高价值场景转为 `HIGH_VALUE_REALTIME_INSERT`。

## 复现命令

```bash
cd algorithm
python -m benchmarks.dispatch_v047_benchmark
python -m benchmarks.objective_sensitivity_v047
python -m pytest tests/test_dispatch_v047_p0.py tests/test_dispatch_v047_gap_compact.py \
                 tests/test_dispatch_runtime_api.py tests/test_coordinate_conversion.py -q
```

