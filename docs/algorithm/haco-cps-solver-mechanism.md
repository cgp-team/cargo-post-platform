# HACO-CPS 求解器机制解析

> 版本：haco-cps-1.4.1（生产主链）
> 源码：`algorithm/app/haco/`，入口 `algorithm/app/solver.py`
> 本文性质：机制说明文档（论文风格），面向需要理解或维护该算法的工程师与研究者

---

## 摘要

HACO-CPS（Hybrid Ant Colony Optimization for Cargo-Postal Scheduling）是本平台自研的路径规划求解器，用于解决"公交客运为主线、货运顺路捎带"场景下的车辆调度问题。该问题在学术上属于**带时间窗的取送货问题**（Pickup-and-Delivery Problem with Time Windows, PDPTW）的一个变体，额外叠加了"公交骨架线路不可变"这一强约束。

求解器采用**混合元启发式**架构：以蚁群优化（ACO）为主体搜索框架，融合模拟退火（SA）接受准则、Best-Improvement 局部搜索、自适应大邻域搜索（ALNS）与精英存档机制，并叠加了工程化的确定性保证与完备性兜底。目标函数采用 6 维字典序而非加权求和，使业务优先级（可行性 > 最少用车 > 乘客体验 > 货运绕行 > 里程 > 时长）得到精确表达。整个求解过程在 4 秒硬时限内完成，且对同一问题实例保证输出完全确定。

**关键词**：车辆路径问题；PDPTW；蚁群优化；ALNS；字典序多目标；公交骨架约束

---

## 1. 问题背景与业务场景

平台的运营模式可以概括为一句话：**公交车按固定线路跑客运，货仓空位顺路捎带包裹**。

由此产生三类任务，需要在同一次规划中统一安排：

| 任务类型 | 事件构成 | 业务含义 |
|---|---|---|
| PASSENGER | BOARD（上车）+ ALIGHT（下车） | 预约乘车的乘客 |
| SHIPMENT | PICKUP（取货）+ DELIVER（送货） | 两端都不在场站的配对货运单 |
| DELIVERY / PICKUP | 单一事件 | 一端是场站的单向派送 / 揽收 |

规划结果必须为每辆车输出一条闭环路线：从场站（depot）空载或预装出发，途经骨架站与任务点，最终返回场站。调度以**半小时批次**为单位静态锁定，规划在批次开始前一次性完成，不做滚动重规划。

规模上限（服务契约）：100 个站点、25 个订单、3 辆车、单次求解 10 秒内。

## 2. 问题的数学定义

### 2.1 形式化描述

给定：

- **场站** $d$ 与站点集合 $S$（|S| ≤ 100，GCJ-02 坐标）；
- **车辆集合** $V$（|V| ≤ 3），每车 $v$ 具有乘客容量 $C^p_v$、货物容量 $C^c_v$、初始载客数、初始载货量，以及一条**骨架序列** $\sigma_v = (s_1, s_2, \dots, s_k)$——该车必须按此顺序经停的公交站点序列；
- **任务集合** $T$（|T| ≤ 25），每个任务 $t$ 编译为不可拆分的 `TaskBlock`：配对任务含取件点 $p_t$ 与送件点 $d_t$ 及需求量 $q_t$（人数或件数）；
- **批次时间窗** $[B_{start}, B_{end}]$；
- **距离/时长函数** $\delta(i,j)$、$\tau(i,j)$：优先取高德路网实测值，降级时用欧氏距离与均速 25 km/h 估算。

求解：为每辆车 $v$ 构造一个事件序列 $R_v = \langle \text{DEPOT}, e_1, e_2, \dots, e_n, \text{RETURN} \rangle$，使得所有约束（§6）满足，且按字典序目标（§5）最优。

### 2.2 与经典问题的关系

| 经典问题 | 本问题的对应/扩展 |
|---|---|
| TSP | 单车退化的特例 |
| CVRP | 容量约束的基础（且拆为三个维度，见 §6.2） |
| PDPTW | 配对任务的前序约束（先取后送）+ 批次时间窗 |
| 公交骨架约束 | **本平台特有**：骨架站作为固定 PASS 事件预置于路线中，任务只能插入骨架间隙 |

### 2.3 求解难度

PDPTW 本身是 NP-hard；骨架约束与三维度容量进一步压缩了可行域。实验表明（`benchmarks/v14_vs_baseline_summary.json`），在中大规模实例上，基于约束规划的 OR-Tools baseline 会直接判定不可行，而 HACO 能找到可行解——这正是自研元启发式存在的理由。

## 3. 求解框架总览

求解器入口 `solver.py` 按请求的 `algorithmMode` 分流三种模式：

- **HACO（默认）**：本文所述的自研元启发式；
- **BASELINE**：OR-Tools 路由模型（`app/baseline/ortools_solver.py`），作为对照与兜底；
- **HYBRID**：两个引擎都跑，按统一的 6 维字典序目标选优，HACO 失败时如实标注回落到 baseline。

HACO 主体的求解分四个阶段：

```
阶段0  预检 + 初始解（贪心 cheapest-insertion，独立 2s 预算）
阶段1  ACO 主循环（24 蚁 × ≤50 代，含自适应调参、SA、局部搜索、MMAS 信息素）
阶段2  ALNS 收尾（4 种破坏 × 3 种修复，自适应算子权重，约 150 代）
阶段3  车辆压实（从 m=1 向上找最小可行车辆数）+ 强制任务全覆盖兜底
```

全程共享一个 4 秒的单调时钟硬截止（`SearchDeadline`），任何阶段超时都会带着当前最优解安全退出。

## 4. 解的表示：RouteGenome 事件序列

> 这是 1.4 版的关键重写。早期版本（≤1.3）在"任务→车辆"的分配层面搜索，站点访问顺序由插入规则间接决定，被诊断出**无法有效改变站点顺序**的结构性缺陷。1.4 改为直接搜索**事件序列本身**。

每辆车的路线是一个 `RouteGenome`（`route_genome.py:57-102`）：

```
events = [DEPOT] + [骨架站 PASS 事件…] + [插入的任务事件…] + [RETURN]
```

- **骨架 PASS 事件是预置的、不可删、不可换序**。骨架约束因此不靠事后检查，而是由**表示层天然保证**——任何操作都破坏不了它；
- 任务事件（BOARD/ALIGHT/PICKUP/DELIVER）成对或单个地插入骨架间隙，`placements` 记录每个任务的 (pickup_idx, delivery_idx)；
- 结构不变量由代码强制：RETURN 恒在末位、同一任务 pickup 在 delivery 之前、配对任务恰好一取一送。

评估时，PASS 事件不产生服务时间，但与相邻站点之间照常累计行驶距离与时长。

**一个直观类比**：骨架像一列地铁的固定站点，规划者能决定的只是"在哪两站之间拐出去办点事（取送货/接送客），再拐回来接着跑"。

## 5. 目标函数：6 维字典序

`ObjectiveVector`（`encoding.py:86-122`）：

$$
\min\;(\; I,\; N_{veh},\; P_{impact},\; D_{cargo},\; D_{total},\; T_{total} \;)
$$

按严格字典序比较（`key()`），**不做加权求和**：

| 维度 | 含义 | 业务理由 |
|---|---|---|
| $I$ infeasibility | 未分配任务数 | 可行性压倒一切 |
| $N_{veh}$ vehicle_count | 用车数 | 契约要求优先单车出车 |
| $P_{impact}$ passenger_impact | 乘客影响（秒） | 客运是主业，货运不能折腾乘客 |
| $D_{cargo}$ cargo_detour | 货运绕行（km） | 其次才优化货的代价 |
| $D_{total}$ total_distance | 总里程 | 运营成本 |
| $T_{total}$ total_duration | 总时长（秒） | 批次时间窗内尽量紧凑 |

两个核心指标的精确定义（`evaluator.py:77-138`）：

- **绕行量 detour**：把事件 $e$ 插在 $prev$ 与 $next$ 之间的额外里程
  $\text{detour}(e) = \delta(prev,e) + \delta(e,next) - \delta(prev,next) \ge 0$
- **乘客影响 passengerImpact**：货运绕行让**车上乘客**多坐的秒数
  $\text{impact} = \frac{\text{detour}_{km}}{25\ \text{km/h}} \times 3600 \times (\text{当前车上乘客数})$
  空车绕行不计入——鼓励趁没乘客时去拉货。

> 工程注记：另有一个标量化函数 `normalized_cost()`（$I{\cdot}10^4 + N_{veh}{\cdot}10^3 + 0.1 P_{impact} + 10 D_{cargo} + D_{total} + 0.01 T_{total}$），仅供信息素沉积与 SA 计算能量差使用；`objective_compare.py` 明令禁止用它替代字典序做业务排序。

## 6. 约束体系

`FeasibilityEngine`（`feasibility_engine.py`）是唯一的可行性裁决者，被构造、局部搜索、ALNS 等所有组件调用（候选级增量检查 + 采纳前整体验证的"双轨"制）。检查按以下顺序短路：

### 6.1 结构与前序约束

1. RETURN 必须在末位（闭环）；
2. 前序约束：PICKUP < DELIVER，BOARD < ALIGHT；
3. 骨架 PASS 子序列必须完整保序。

### 6.2 容量约束：乘客一维 + 货物三维

- **乘客容量**：BOARD 加人、ALIGHT 减人（座位动态释放），叠加出发时的初始载客，全程 ≤ 乘客容量。
- **货物容量三维度**（`feasibility_engine.py:186-270`）——这是"公交闲置运力"业务的直接建模：

| 维度 | 语义 | 直观理解 |
|---|---|---|
| CargoLoad | 车内实时货量（SHIPMENT 取+送−） | 车厢此刻装了多少 |
| CargoOut | 出程派送累计 ≤ 容量 | 去程满载派送，送一件腾一件 |
| CargoIn | 返程揽收累计 ≤ 容量 | 返程顺路收件，货仓反向复用 |

  三维独立核算，去程与回程的货仓容量互不挤占。另有：预装货（PRELOADED）派送总量 ≤ 出发载货量，否则归因 `PRELOAD_INSUFFICIENT`。

### 6.3 时间窗约束

路线总耗时 = Σ(各段行驶时间) + Σ(服务时间：上车 30s / 下车 20s / 取送件 60s)，不得超过批次窗口 $B_{end} - B_{start}$。

### 6.4 绕行硬约束

货运任务的插入绕行超过 `max_detour_km`（默认 2 km，可在请求中覆盖）的候选在预筛阶段即被淘汰；实在放不下的订单进入 `unassignedOrderIds`，交回后端走**多段联运**——算法承认"不是每单都该这趟车带"。

## 7. 蚁群构造机制

每只蚂蚁构造一个完整解（`construction.py:803-911`），循环执行"选任务 → 选插入位置"直到任务清空。

### 7.1 选任务：信息素 × 类型先验

$$
\text{score}(t) = \tau(\text{last},\, t)^{\alpha} \cdot \text{urgency}(t)
$$

urgency 是固定的业务先验：客运 1.5 > 配对货运 1.3 > 单向任务 1.0——优先安排"难伺候"的任务。按 score 归一化后轮盘赌选择。

### 7.2 选位置：两阶段候选筛选

对每个任务枚举所有 (车辆, 取件位置, 送件位置) 组合代价太高，因此分两级过滤（`construction.py:484-800`）：

**Stage A —— 廉价预筛**（不算完整可行性）：
- 只算增量里程 $\Delta d$，并在此**执行 2 km 绕行硬约束**（超限直接丢弃）；
- $\text{cheap\_score} = \Delta d + 10 \cdot \text{骨架罚} + 50 \cdot \text{容量风险}$；
  （骨架罚：取/送站不在骨架线上各 +0.5，鼓励顺路；容量风险：车辆利用率越高罚越重）
- 候选池截断至 32 个，并做**车辆多样性保护**：每辆车的最优槽位必入池、每个 (车, 取件点) 组合有配额，防止候选挤在同一辆车上导致搜索视野变窄。

**Stage B —— 完整评估**：对池内候选逐一真实插入、过 FeasibilityEngine 全约束检查、算完整目标，按
$\text{score} = \Delta d + 0.01 \cdot P_{impact} + 10 \cdot D_{cargo}$
排序，保留前 8 个（`candidate_size`）。

**轮盘赌选位**：

$$
P(\text{slot}) \propto \tau^{\alpha} \cdot \left(\frac{1}{\eta}\right)^{\beta}
$$

同一任务的候选共享 τ 因子，实际起区分作用的是 $(1/\eta)^{\beta}$；$\beta=3.0$ 使选择强烈偏向贪小便宜，又保留探索余地。

### 7.3 信息素模型（MMAS）

`pheromone.py` 实现 MAX-MIN 蚁系统：

- 矩阵 $\tau(t_i, t_j)$ 覆盖全部任务与 DEPOT，初值 $\tau_0 = 1/\text{初始解成本}$；
- **蒸发**：$\tau \leftarrow (1-\rho)\tau$，$\rho=0.1$，下限钳位 $\tau_{\min}=0.01$；
- **沉积**：仅**每代最优解**沉积，$\Delta = Q/\text{cost}$（$Q=100$）；若刷新全局最优，追加一次权重 2.0 的精英强化；上限钳位 $\tau_{\max}=10.0$；
- **逐车沉积**（关键设计）：每辆车的任务序列独立沉积，配对货运只记 PICKUP 端——刻意避免"跨车假边"和"PICKUP→DELIVER 虚假边"，否则信息素会学到"这两件事该挨着做"的错觉；
- **停滞重启**：连续 12 代无改善时 $\tau \leftarrow 0.5\tau_0 + 0.5\tau$，跳出信息素陷阱。

## 8. 主循环：每代做什么

`v14_solver.py:252-421`，每代 24 只蚂蚁，最多 50 代：

```
for 代 in 1..max_iterations:
    1. 多样性自适应调 (α, β)            # 见 §9
    2. for 蚁 in 1..24:
         若 蚁编号 % 4 == 0 且存档非空 → 从精英存档采样重启（70% 取最优 / 30% 随机）
         否则 → 按 §7 全新构造，再贪心补插未分配任务
    3. 全体解校验 + 评估 ObjectiveVector
    4. SA 准则决定是否接受一个更差的解作为本代代表  # 见 §10
    5. 仅对「本代最优」做 Best-Improvement 局部搜索（≤3 轮）  # 见 §11
    6. MMAS 信息素蒸发 + 沉积
    7. 本代解入精英存档（容量 10，按字典序剪尾）
    8. 记录遥测（候选筛选/评估/局部搜索各自耗时）
    9. 连续 25 代无改善 → 收敛退出；12 代 → 信息素重启
```

两个值得注意的工程取舍：

- **只为每代最优做局部搜索**，而不是为每只蚂蚁做——把昂贵的邻域枚举花在刀刃上；
- **单只蚂蚁构造内部不按墙钟打断**，超时只在迭代边界判定——保证同一随机种子下 RNG 消耗序列固定，这是全链路确定性的前提之一。

## 9. 多样性自适应调参

每代统计最近 8 个解的"唯一签名占比"（签名 = 各车排序后 task_id 的拼接）作为种群多样性 $div$：

| $div$ | 含义 | 动作 |
|---|---|---|
| < 0.2 | 解都长一样，陷入局部 | α ↓ 至 0.5（弱化信息素），β ↑ 至 5.0（更贪心），扩大探索 |
| > 0.8 | 解太发散，迟迟不收敛 | α ↑ 至 3.0，β ↓ 至 1.0，加速收敛 |
| 中间 | — | α ∈ [0.5, 3.0]、β ∈ [1.0, 5.0] 线性插值 |

## 10. 模拟退火接受准则

主循环与 ALNS 共用同一套 SA 参数（$T_0 = 10$，每代 $T \leftarrow 0.995T$，下限 0.01）。对更差的候选解：

$$
P(\text{接受}) = \exp\!\left(-\frac{\Delta}{T}\right),\qquad
\Delta = \text{normalized\_cost}(\text{候选}) - \text{normalized\_cost}(\text{当前最优})
$$

温度随代数衰减：早期容许大步倒退以翻山越岭，后期趋于纯贪心。

> 已知瑕疵：ALNS 接受处（`alns_v14.py:655`）有一个未使用的变量 `delta`，其接受概率实为固定形式 $\exp(-1/T)$，不与劣化幅度挂钩——属简化实现，后续可修。

## 11. 局部搜索（Best-Improvement）

`local_search_v14.py` 定义两类邻域算子：

- **Relocate**：任意任务移到任意车的任意合法 (取件位, 送件位)；
- **Swap**：仅**跨车**两两交换任务（实现为"在对方路线首个可行位置重插"的简化版）。

采用 Best-Improvement 策略：枚举全部合法邻居（每个邻居都过全体车辆的 FeasibilityEngine 检查），取字典序最优者，优于当前才移动，最多 3 轮、无改善即停。每个邻居评估前后都检查 deadline，可随时安全中断。收尾阶段对全局最优追加一轮（共 4 轮）。

## 12. ALNS 收尾阶段

主循环结束后，对最优解做自适应大邻域搜索（`alns_v14.py`），默认约 150 代，仍受全局 4 秒约束。

**破坏算子**（每代删除 ⌊25%⌋ 的任务）：

| 算子 | 策略 |
|---|---|
| random | 均匀随机——纯多样化 |
| worst | 删掉"边际贡献最贵"的任务——把碍事的拔掉重插 |
| shaw | 按相关性删相似任务（0.35 取点距离 + 0.35 送点距离 + 0.15 类型相同 + 0.15 同车）——相似的一起搬走 |
| segment | 删掉某车一段连续任务序列——整块重构 |

**修复算子**：greedy（每步全局最优插入）、regret-2 / regret-3（遗憾值 = 第 k 好位置成本 − 最优位置成本，**遗憾最大者优先**插回——现在不放，以后可能更贵）。

**自适应权重**：轮盘赌按权重选算子；算子发现更优解获得奖励分（刷新全局最优 +8 / 本代最优 +5 / 接受的更优解 +3 / 仅可行 +1），每 10 代按
$$w \leftarrow 0.9w + 0.1 \cdot \frac{\text{得分}}{\text{使用次数}}$$
更新——好用的算子会被用得越来越多。

## 13. 收尾机制：最小用车与完备性

### 13.1 车辆压实（`_compact_solution`）

由于 vehicle_count 在目标中仅次于可行性，收尾时从 **m = 1 辆车开始向上试**：先算容量下界（m 辆车的总容量须 ≥ 出/入双向总需求），再对按 task_id 规范排序的任务跑确定性 cheapest-insertion，第一个"完整 + 可行"的 m 即采纳。

收益有两点：

1. **保证最小用车**——搜索过程可能停留在 2 车解，压实会尝试挤回 1 车；
2. **输入顺序无关**——同一批任务无论以什么顺序到达，输出同车数、同里程。

### 13.2 绝不返回半成品

输出前用独立 2 秒预算强制补插任何未覆盖的任务；补不回则回退到搜索过程中记录的最后一个完整可行解（warning: `OUTPUT_FALLBACK_TO_LAST_COMPLETE_SOLUTION`）；连兜底都没有，才诚实地返回 `infeasible` + 归因码。

## 14. 确定性与可复现性

调度结果要经人工审核并留痕审计，因此求解器把**确定性**作为一等公民：

- 固定随机种子（默认 `randomSeed = 20260903`），单 RNG 贯穿全程；
- 任务先按 task_id 排序再编码，与请求中的到达顺序解耦；
- 蚂蚁构造内部不做墙钟打断，RNG 消耗序列固定；
- 压实阶段使用规范序 + 独立预算重建。

测试侧有"同一实例跑 100 次结果一致""输入顺序打乱后结果一致"的回归用例守护（`tests/test_comprehensive_verification.py`）。

## 15. 时间预算与遥测

| 预算项 | 额度 | 说明 |
|---|---|---|
| 总搜索 | 4 s | `min(haco_time_limit=4, overall=5)`，所有组件共享同一单调时钟 |
| 初始解（贪心种子） | 2 s | 独立于主搜索 |
| 无解归因探测 | 2 s | 判 infeasible 前去掉时间窗再试，以区分归因 |
| 输出补全 | 2 s | 收尾强制全覆盖 |
| 契约上限 | 10 s | `/api/v1/plan` 端到端 |

响应的 `warnings` 字段内嵌遥测：`SEED_MS / CANDIDATE_MS / LOCAL_SEARCH_MS / ALNS_MS / TOTAL_SEARCH_MS / ITERATION_COUNT` 等，线上可直接观测时间花在哪一环。

## 16. 质量验证体系

| 层次 | 手段 | 结论 |
|---|---|---|
| 正确性 | ≤8 任务的 branch-and-bound 穷举 oracle（`haco/exact_oracle.py`） | 小场景最优性 gap ≤ 10%（更简单场景 ≤ 5%） |
| 回归 | 1493 行回归矩阵 + 14 组综合场景 | 硬截止/不变量/确定性/顺序无关全覆盖 |
| 对比 | S/M/L 三规模 benchmark | 中大规模 baseline 判无解，HACO 可行（M: 2 车 6.5s；L: 3 车 21.4s） |
| 消融 | 算子/参数敏感性测试 | 支撑各组件存在的必要性 |

## 17. 参数总表

`config.py` 中 v14 主链实际消费的默认值（均可由请求的 `algorithmConfig` 覆盖）：

| 分组 | 参数 | 默认值 |
|---|---|---|
| 蚁群 | ant_count / max_iterations | 24 / 50 |
| 蚁群 | α / β（自适应范围） | 1.0 / 3.0（α∈[0.5,3]，β∈[1,5]） |
| 信息素 | ρ / Q / τ_min / τ_max | 0.1 / 100 / 0.01 / 10 |
| 收敛 | convergence_threshold | 25（一半时触发重启） |
| 构造 | candidate_size | 8（预筛池 32） |
| 存档 | archive_size | 10 |
| ALNS | destroy_fraction | 0.25 |
| 局部搜索 | local_search_rounds | 3（收尾 4） |
| SA | T0 / 冷却率 / T_min | 10.0 / 0.995 / 0.01 |
| 约束 | max_detour_km | 2.0 |
| 时限 | 搜索 / 种子 / 补全 | 4 s / 2 s / 2 s |
| 确定性 | random_seed | 20260903 |

> 注：`config.py` 中另有约 10 个参数（`elite_count`、`lns_probability`、`penalty_*` 等）是 2.x 遗留定义，v14 主链不消费，阅读时注意区分。

## 18. 讨论与局限

**优势**：
- 事件级 RouteGenome 表示让"骨架不可变"从约束变成表示层的天然属性，这是 1.4 质量反超 OR-Tools baseline 的关键转折（演进史见 `haco-cps-development-log.md`）；
- 字典序目标精确对应业务优先级，规避了多目标加权求和的调参困境；
- 完备性兜底 + 全链路确定性 + 穷举 oracle 验证，工程严谨度高于一般自研启发式。

**已知局限**：
- §10 所述 ALNS 接受概率的简化实现；
- `heuristic.py`（6 项加权启发式）与 `construction.py` 内置简化评分两套评分体系并存，前者主要服务旧链路，易造成阅读混淆；
- `app/haco/` 下的 `solver.py`（2.0）、`hybrid_optimizer.py`（2.1）、`stop_level/` 为未接线的历史实验代码，非生产路径；
- 时间窗目前只以"批次总时长"形式出现，尚无站点级的到达时间窗。

---

## 附录 A：端到端伪代码

```
SOLVE(request):
    precheck()                        # 容量/载荷/时间窗先验，不行直接归因
    tasks ← encode(request)           # 订单 → TaskBlock，按 task_id 排序
    seeds ← cheapest_insertion() + 4×ACO(不同α,β)   # 初始解，2s 预算
    if 无完整可行种子: probe(去掉时间窗再试) 归因返回

    best ← 最优种子
    loop ≤ 50 代, 受 4s deadline:      # ACO 主循环
        (α,β) ← adapt(最近8解多样性)
        ants ← 24 只: 1/4 从精英存档重启, 其余 §7 构造
        evaluate(ants); SA 接受; LS(本代最优, 3轮)
        MMAS 蒸发; 本代最优沉积; 精英强化
        更新存档; 收敛?退出 : 停滞?重启信息素

    best ← ALNS(best, ~150 代)         # §12
    best ← LS(best, 4 轮)
    best ← compact(best)               # m=1 向上压实，§13.1
    output ← ensure_complete(best, 2s) # §13.2 兜底
    return PlanResult(output, warnings=遥测)
```

## 附录 B：源码文件索引

| 文件 | 职责 |
|---|---|
| `app/solver.py` | 三模式分流入口（HACO / BASELINE / HYBRID） |
| `app/haco/v14_solver.py` | 1.4.1 主求解器：种子、主循环、ALNS 调度、压实、兜底 |
| `app/haco/route_genome.py` | RouteGenome 事件序列表示与不变量 |
| `app/haco/encoding.py` | TaskBlock、ObjectiveVector |
| `app/haco/feasibility_engine.py` | 统一可行性检查（六类约束） |
| `app/haco/construction.py` | 蚂蚁构造、两阶段候选筛选 |
| `app/haco/pheromone.py` | MMAS 信息素（逐车沉积） |
| `app/haco/local_search_v14.py` | Relocate / Swap 邻域，Best-Improvement |
| `app/haco/alns_v14.py` | 4 破坏 × 3 修复，自适应算子权重 |
| `app/haco/elite_archive.py` | 精英存档（签名去重，容量 10） |
| `app/haco/evaluator.py` | 解评估、detour/impact 计算、多样性度量 |
| `app/haco/config.py` | HacoConfig 参数定义与请求覆盖 |
| `app/haco/deadline.py` | SearchDeadline 单调时钟 |
| `app/haco/exact_oracle.py` | ≤8 任务穷举最优（验证用） |
| `app/baseline/ortools_solver.py` | OR-Tools baseline 引擎 |
| `app/distance.py` | 距离提供方（高德路网 / 欧氏兜底） |
