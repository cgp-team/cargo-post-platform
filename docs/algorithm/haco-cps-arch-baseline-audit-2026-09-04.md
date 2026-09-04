# HACO-CPS 19 条架构基准 · 逐条审计与差距报告

- 日期:2026-09-04
- 范围:算法层 + API 调用链(`PlanRequest 入 API → algorithm/app/solver.py 分发 → 三模式返回`)
- 方法:只依据当前磁盘代码;关键结论均经本人复核到 `文件:行号`;禁止依据 commit message / 注释臆测。
- 结论前置:**本报告只审计、未改任何代码。**

---

## 0. 一句话核心结论

> 现状是 **2.x 旧主链在跑,1.4 新链是"未接线的库"**:
> 生产请求进入的是 `hybrid_optimizer`(HACO-CPS **2.1.0**,基于 `GlobalRouteGenome`/`global_*` 旧模块),
> 版本字面量 `haco-cps-1.4.0` 在响应中从不出现;
> 基准要求的 `RouteGenome + FeasibilityEngine + 1.4 construction/evaluator/pheromone/local_search + ALNS + Archive` 主链,
> 在 `algorithm/app` 内**没有任何生产调用者**,只被单测/benchmark 引用。
> 且 fallback 会把真正的 OR-Tools 结果标成 `haco-cps-2.1.0`(仅用 warning 区分),违反"OR-Tools 不得伪装成 HACO"。

---

## 1. 真实生产调用链(A→Z,全部复核实证)

```
yudao 后端 AlgorithmClient.java:59  HTTP POST /api/v1/plan
→ algorithm/app/main.py:207  create_plan
→ main.py:223  validate_request → build_result(request)
→ main.py:115  build_result 内 solve(request, matrix)      (matrix 高德/欧氏)
→ algorithm/app/solver.py:38  solve
     mode = algorithmConfig.algorithmMode
     ├─ mode==BASELINE  → solver.py:42-43  _solve_baseline() → baseline/ortools_solver.solve()
     │                    algorithm_version = ortools-1.3.0   (solver.py:83 —— 达标)
     └─ 其它(含 HACO / HYBRID / 默认)→ solver.py:47-51  solve_hybrid()   ← 注意 HACO 没有单独分发
          → haco/hybrid_optimizer.py:75  solve_hybrid
                imports(35-51): global_evaluator / global_local_search / global_construction
                                / pheromone.PheromoneMatrix / route_genome.GlobalRouteGenome
                                / station_backbone / station_neighborhoods / station_pheromone
                                + exact_oracle.solve_exact(37,但从未调用,死 import)
                precheck(87-89/453-474)
                初始解(100-109): NN/Greedy/ACO/backbone/sweep(_generate_initial_solutions 246-293)
                主循环(135-223): build_backbone_from_genome → apply_best_station_neighborhood
                                → _backbone_to_genome(贪心插,296-357)→ [elite] global_local_search
                                → evaluate_genome(global_evaluator) → SA 接受(168-173)
                                → pheromone.deposit(_extract_task_sequence(...),184)/deposit_best(194)
                best_obj.feasible 才出解;否则 225-227 → _fallback_to_baseline(602-605)
                _ortools_validate(582-599) = 纯 Python validators,不调 OR-Tools
→ main.py:130-140  PlanResult(algorithmVersion=outcome.algorithm_version, ...)  ← 版本原样透出
```

**不变量**:版本字符串一路从 `hybrid_optimizer.SolveOutcome.algorithm_version(= haco-cps-2.1.0)` 透传到 HTTP 响应;`main.py:39 ALGORITHM_VERSION = haco-cps-2.1.0` 同时用于 `/health`。

---

## 2. 19 条逐项判定总表

| # | 基准条目 | 判定 | 一句话 |
|---|---------|------|--------|
| 一 | 业务数据层 | ✅ | 字段齐全 |
| 二 | 三种模式(不能让 HYBRID==HACO) | ❌ | HACO/HYBRID 折叠进同一 `solve_hybrid`;`solve_haco` 从不被生产调用;HYBRID 内无 OR-Tools portfolio/refinement |
| 三 | TaskBlock/TaskType 编码 | ✅(事件层) | TaskType 枚举齐全;展开在 `route_genome` 事件层实现,映射正确 |
| 四 | RouteGenome 唯一真实路线 | ⚠️ 部分 | 1.4 `RouteGenome.events` 本体正确(唯一真实顺序),但**活链不用它**,活链用 task-list |
| 五 | RouteGenome 约束 | ❌ | 无"RETURN 后不得有业务事件"校验,且构造可产出该非法态并被判可行 |
| 六 | FeasibilityEngine 统一入口 | ❌ | 主项在,但**零生产接线**;且缺 RETURN 校验、duration 从不在搜索期触发、PRELOADED 丢失 |
| 七 | Objective(6 维 lexicographic,events 计算) | ⚠️ 部分 | 1.4 `ObjectiveVector`+1.4 `evaluator` 完全达标(死链);活链 `GenomeEvaluation` 多插 backtracking_ratio,且 cargo/passenger 结构恒 0 |
| 八 | Construction(candidate_size 生效) | ❌ | candidate_size 只在死函数生效;`config.from_algorithm_config` 不解析它,活链无候选截断 |
| 九 | Pheromone 避免跨车假边 | ❌ | 活链把多车拍平成单序列沉积→跨车假边;且主循环只写不读,信息素无引导 |
| 十 | Adaptive ACO(_adapt_parameters→construction) | ❌(活链) | 自适应只在不可达的 2.0 `haco/solver.py`;活链无此机制 |
| 十一 | Local Search(RouteGenome/Best Improvement) | ⚠️ | 活链 LS 操作 GlobalRouteGenome(非 events),含 Relocate/Swap/2-opt 但无 Or-opt、非完整 best-improvement |
| 十二 | ALNS(destroy/repair/接受准则/不劣化 incumbent) | ❌(活链) | 真 ALNS 在死链 alns_v14,不连 archive;活链无 ALNS |
| 十三 | Adaptive Operator Selector(usage 增长) | ❌(活链) | 只在死链 alns_v14;destroy_repair 版 usage 双重计数 |
| 十四 | Elite Archive(存 elite/多样性/sample/参与搜索) | ❌ | archive.py / elite_archive.py 双份类全仓无 import,孤儿 |
| 十五 | Baseline OR-Tools 保留 | ✅ | 独立保留,作 fallback+benchmark+业务约束参考,版本标 ortools-1.3.0 正确 |
| 十六 | Fallback 版本/warning 红线 | ❌ | 真回落 OR-Tools 标 `haco-cps-2.1.0`/`2.0.0`,仅 warning;异常也兜成 HACO 版号 |
| 十七 | 旧模块不占默认主链 | ❌ | 默认主链正是旧 `GlobalRouteGenome`+`global_*` |
| 十八 | 最终生产调用链(1.4 主链) | ❌ | 见 §1:真实链路不含 1.4 组件 |
| 十九 | 修改原则 | — | 审计仅快照现状,不评估 |

图例:✅ 达标 · ⚠️ 部分达标(本体对但接线/细节不对)· ❌ 未达标。

---

## 3. 逐条证据(关键行引用)

### 一、业务数据层 —— ✅ 达标
- `algorithm/app/models.py:138-149` PlanRequest 含 depot/stations/vehicles/orders/shipments/algorithmConfig。
- `models.py:53-65` Vehicle 含 `vehicleId / passengerCapacity / cargoCapacity / initialPassengerLoad / initialCargoLoad / skeleton[]`。
- `models.py:23-27` `OrderType = PASSENGER/DELIVERY/PICKUP`。
- `models.py:121-136` PlanShipment 含 pickupStationId/deliveryStationId/quantity;注释明确"Solver 内部展开为 PICKUP+DELIVERY 配对、自动同车+顺序约束"。
- `models.py:29-34` CargoSource = PRELOADED/SHIPMENT 两成员(SHIPMENT 标注历史死语义)。
- `models.py:89-118` algorithmConfig 参数齐全,`extra="allow"`。

### 二、三种模式 / 不允许 HYBRID==HACO —— ❌ 未达标
- `app/solver.py:40-51`:仅 `mode==BASELINE` 走 OR-Tools;**HACO 与 HYBRID 以及默认全部落 `solve_hybrid`**。`solver.py:4-6` 头注自称 "HACO: haco-cps-2.0.0",但 `solve_haco` 全仓(除定义 `haco/solver.py:70` 与 docs/.mimosa 快照)无任何生产调用者 → HACO 从未走 2.0 链,HYBRID 实际 = HACO(2.1)单一路径。
- `hybrid_optimizer.py:9` docstring 声称 "OR-Tools 仅用于 warm-start 和最终验证",但 `_generate_initial_solutions`(246-293)只用 NN/Greedy/ACO/backbone/sweep,**没有任何 OR-Tools 求解**;OR-Tools 仅作为失败兜底(602-605)与命名误导的纯 Python 复核(582-599)。
- `hybrid_optimizer.py:37` `from .exact_oracle import solve_exact` 存在但全文从未调用 → 死 import。

### 三、任务编码映射 —— ✅(事件层)
- `haco/encoding.py:20-39`:TaskType = PASSENGER/SHIPMENT/DELIVERY/PICKUP;TaskBlock 含 task_id/task_type/pickup_station/delivery_station/size/order_ids。
- 事件展开在 `haco/route_genome.py:337-378`:`PASSENGER→BOARD/ALIGHT`;`SHIPMENT/PICKUP→PICKUP`;`DELIVERY→DELIVER`;单节点 DELIVERY 仅一个 DELIVER 事件。与 OR-Tools 节点展开(`baseline/ortools_solver.py:136-152`)一致。
- 注:TaskBlock 无 cargoSource 字段(见第六条 PRELOADED 丢失)。

### 四、RouteGenome 为唯一真实路线 —— ⚠️ 本体对,但活链不用
- `route_genome.py:57-66`(1.4 RouteGenome docstring):"events 是唯一真实路线顺序…不再把 Task->Gap 作为真实路线表示";`80-100` 构造 `[DEPOT, PASS…, RETURN]`;`placements` 由插入/重建派生(`_rebuild_placements` 222-269),不反向充当顺序。
- **但活链承载顺序的是 `GlobalRouteGenome.vehicle_routes: dict[int, list[str]]`(每车 = task_id 列表)**:`route_genome.py:413-425`;输出 `hybrid_optimizer._genome_to_plans`(491-562)直接按 task 序展开为 stop。1.3 gap 表示另存于 `route_state.py:40-56`。三代表示并存;活链 = task-list,非 events。
- 另注:stop-level 起点事件名 `ActionType.DEPART`(`stop_level/models.py:18`)与 RouteGenome `EventType.DEPOT`、models `StopAction.DEPART` 不一致(审计混淆源)。

### 五、RouteGenome 约束 / RETURN 后禁止业务事件 —— ❌
- 无任何模块显式检查 RETURN 是否末事件/其后有无业务事件。`FeasibilityEngine` 与 `RouteGenome.validate_precedence/validate_skeleton`(`route_genome.py:288-331`)都不检查。
- 更严重:插入守卫允许两段式任务的 delivery 落到 RETURN 之后——`route_genome.py:171-174` 仅拦 `delivery_index>len(events)`(允许等于);`insert_task`(185-192)在 `adjusted_delivery_index` 处 `events.insert` 会把 DELIVER/ALIGHT 追加到 RETURN 之后;`construction.py:508-511` 遍历 `delivery_index in range(pickup_index+1, event_count+1)`(含 `event_count`)。配合只查 `pickup_index>=delivery_index` 的 precedence(`route_genome.py:291-301`),**"RETURN 后仍带 DELIVER/ALIGHT" 的路线会被判可行**。
- `BOARD<ALIGHT` 与 `PICKUP<DELIVER` 都由同一 precedence 覆盖(共用 reason `PICKUP_AFTER_DELIVER`),无细分码。

### 六、FeasibilityEngine —— ❌(本体主项在,未接生产 + 三项缺口)
- 检查项覆盖:`_check_precedence`(feasibility_engine.py:79-89)、`_check_skeleton`(91-101)、`_check_passenger_capacity`(103-132,含 initial_load)、`_check_cargo_capacity`(134-198)。
- **零生产接线**:`FeasibilityEngine` 仅被 `construction.py / alns_v14.py / local_search_v14.py / tests` import(grep 证实),这三个文件本身也只有 tests/benchmark 引用;活链 `hybrid_optimizer` 的约束集 = `global_evaluator + validators`(见 §4 R8)。
- **RETURN 末位**:不检查(见第五条)。
- **duration/time**:`check()`(63-72)仅在 `max_duration is not None and station_map is not None` 才跑;而三个 1.4 调用点(construction.py:527-545 / 608-626、alns_v14.py:521-530、local_search_v14.py:346-355)都未传 `max_duration` → 搜索期永不触发;且 `_check_duration`(200-230)只累加行驶时长,不含 service_duration。业务时间窗只在最终 `validators.validate_time_window`(validators.py:242-273,含 service_duration)校验,不在搜索期。
- **PRELOADED 丢失**:`_encode_tasks`(hybrid_optimizer.py:477-488)DELIVERY 分支不读 `order.cargoSource`;TaskBlock 无 cargoSource → 进入搜索后 PRELOADED 与普通 DELIVERY 不可分。PRELOADED 只在预检(455)与最终 validators 识别,搜索过程不感知 initialCargoLoad 占用。

### 七、Objective —— ⚠️ 死链达标,活链不达标
- 死链(基准要求的 1.4):`encoding.py:82-104` `ObjectiveVector.key()` = (infeasibility, vehicle_count, passenger_impact, cargo_detour, total_distance, total_duration) —— **与基准 6 维精确一致**;`evaluator.py:23-145` `evaluate_route_genome` 遍历 `route.events`(43-44),detour 用 via−direct(100-123),真实非 0。这些模块无生产调用者。
- 活链:比较对象是 `GenomeEvaluation`,`route_genome.py:562-575` `key()` = (not feasible, vehicle_count, passenger_total_impact, **backtracking_ratio**, cargo_detour, total_distance, total_duration) —— **在 4 位硬插了 backtracking_ratio**,cargo_detour 被推后且实际恒 0。
- 活链 cargo/passenger 恒 0 证据:`global_evaluator._evaluate_route` 中 SHIPMENT 分支 `current_station` 在 156 行已置为 pickup,随后 `d=_distance(current_station,delivery)`(200)与 `direct=_distance(pickup,delivery)`(206)相同 → `detour=max(0,d-direct)≡0`(207);`cargo_detour` 仅此一处累计(208);passenger_impact(210-213)同样基于该 detour → 恒 0。输出 `_genome_to_plans` 对货运经停写死 `detourDistance=0.0`(hybrid_optimizer.py:532/539/547/555)。
- 排序:活链选解用 `__lt__`(lexicographic)与 `min(...)`(hybrid_optimizer.py:109/165/190-192),`normalized_cost`(加权标量,global_evaluator.py:70-78)仅用于 SA 接受概率与信息素量,**未用它覆盖最终业务排序** —— 这点未违反"weighted scalar 改变业务排序"。

### 八、Construction / candidate_size —— ❌(candidate_size 未真正生效)
- `config.py:21` 定义 `candidate_size: int = 8`,但 `HacoConfig.from_algorithm_config`(config.py:78-90)只读 ant_count/max_iterations/alpha/beta/rho/Q/convergence_threshold/random_seed,**不解析 candidate_size**;活链任何地方都不读 `config.candidate_size`。
- 候选截断只在死函数 `construction.py:477-672`(`generate_insertion_candidates`,668-672 `return candidates[:max(1, candidate_size)]`),调用方仅 `test_haco_v14_construction.py`。活链 `global_construction`/`_backbone_to_genome` 是全位置贪心取最优,无候选集概念。

### 九、Pheromone / 跨车假边 —— ❌
- 矩阵本体 `pheromone.py:12-39` 键为全局 `(task_i, task_j)`,本身无 vehicle 维;正确性取决于调用方是否逐车沉积。文件提供正确工具 `deposit_multi_vehicle`(83-98)与 `extract_vehicle_task_sequences`(101-141,每车 DEPOT 头)——但**活链不用**。
- 活链 `_extract_task_sequence`(hybrid_optimizer.py:398-403)把各车任务**拍平成单条** `["DEPOT", v0 任务…, v1 任务…]`,再 `pheromone.deposit(task_seq,...)`(184)/`deposit_best`(194)→ 产生 **v0 末任务→v1 首任务的跨车假边**。正确的逐车路径在不可达 `haco/solver.py:354-365`(per-vehicle 序列)。
- 更弱的是:主循环内 task 信息素**只沉积从不读取**(读取只发生在初始解 `construct_global_solution`,且当时 tau0 均匀)→ 对最终解无引导作用。`station_pheromone` 同理在活链只被 initialize/evaporate/deposit/deposit_best/restart(hybrid_optimizer.py:121-222),**全仓无任何读取**。

### 十、Adaptive ACO —— ❌(活链无)
- `_adapt_parameters`(haco/solver.py:563-581)只在**不可达的 2.0** `haco/solver.py:151` 调用并(163-166)真正传入 `construct_global_solution(alpha=adaptive_alpha, beta=adaptive_beta)`。该链正确(证据在),但 `solve_haco` 无生产调用者。
- 活链 hybrid_optimizer 无 `_adapt_parameters`,主循环不按 ACO 构造(仅初始解一次),alpha/beta 自适应不存在。

### 十一、Local Search —— ⚠️ 活链部分达标
- 活链 `global_local_search.py:22-69` 操作 GlobalRouteGenome(task-list,非 events);邻域 Relocate(72-114)/Swap(117-149)/2-opt(152-172),**无 Or-opt**。Relocate 内对全位置用 `_relocation_score` 取 best(95-102),但 task 随机、Swap/2-opt 单次随机抽样 → 非完整 best-improvement,也非"首个可行即返回"。LS 内可行性只经 `evaluate_genome`(容量/站点),时间窗/骨架不进 LS。
- 死链 `local_search.py`(1.2,基于 RouteState/gap)存在"首个可行即返回"反模式(local_search.py:164-176 等);`local_search_v14.py` 的 `apply_move`(374-382)为空壳直接 return。

### 十二、ALNS —— ❌(活链无)
- 真 ALNS 全在死链 `alns_v14.py`(destroy random/worst/shaw/segment 123-307;repair greedy/regret-2/regret-3 313-467;主环 `alns_search` 550-672,含 SA 接受,incumbent 只在 `repaired_obj<best_obj` 更新 645-648,劣化受门限)—— 全仓无调用方,且**全程未引用 EliteArchive**。
- 活链 hybrid 主循环是 "backbone 重建 + station 邻域 + task LS + SA",非 ALNS;`best_genome` 只在迭代最优<全局最优时更新(190-192),不劣化 incumbent —— 这点符合"incumbent 不被无条件劣化",但形态上根本没有 ALNS 组件。

### 十三、Adaptive Operator Selector —— ❌(活链无)
- `alns_v14.py` 内 selector `usage` 每次迭代 +1(584-585),按 10 轮窗口 `update_weights`(105-117)按 8/5/3/1 奖励 —— 仅死链。注意 reward_type="iteration_best"(5)在 `alns_search` 内从不触发。
- `destroy_repair.py:299-320` 另一版本在 `reward` 内 `usage += 1` 且调用方也先 `usage += 1` → 双重计数(该版不达标)。活链无 operator selector。

### 十四、Elite Archive —— ❌
- `archive.py:25` 与 `elite_archive.py:28` 各有一个 `EliteArchive`(RouteState 版 / RouteGenome 版),自带 add/dedup/prune/sample(elite_archive.py:65-80)。**全仓无任何 import 方**(grep 仅定义处);活链不建 archive;构造/repair/local search 无任何取 archive 解的路径。纯孤儿代码。

### 十五、Baseline OR-Tools —— ✅
- `baseline/ortools_solver.py:25` 真实使用 `pywrapcp`;`solve(request, matrix)` 为入口(83);`baseline/__init__.py` 注明 "preserved for benchmark comparison"。
- BASELINE 模式(`app/solver.py:42-43/73-86`)算法版本标 `ortools-1.3.0`,正确。它同时是业务约束参考:节点展开 PASSENGER→BOARD+ALIGHT、Shipment→PICKUP+DELIVER(136-152);骨架经停+顺序(154-169/377-383);CargoOut/CargoIn 双维(250-293);CargoLoad 维(Shipment 净额 + initialCargoLoad, PRELOADED DELIVER demand=0)(295-338);时间维含 service+travel(344-358);客运同车/先上后下与 shipment 同车/先取后送(360-375);PRELOAD_INSUFFICIENT 预检(108-115)。

### 十六、Fallback 版本/warning 红线 —— ❌
- `hybrid_optimizer.py:225-227` 无解 → `_fallback_to_baseline`(602-605):真调 OR-Tools 后 `algorithm_version=HACO_VERSION`(`haco-cps-2.1.0`,605),仅 warning `HACO_FALLBACK_TO_BASELINE`。
- 外层 `app/solver.py:56-62` 与异常路径 `64-70` 再兜底:同样 `baseline.algorithm_version = ALGORITHM_VERSION`(`haco-cps-2.1.0`)。OR-Tools 结果被标 HACO 版号;**违反"回落应标 ortools-1.3.0"**;异常也被 HACO 成功版本掩盖(响应 200 feasible + haco-cps-2.1.0)。
- `main.py:115-140` 把 `outcome.algorithm_version` 原样透出 HTTP 响应,无校正。
- 达标部分:`BASELINE` 直连模式与成功 HACO 路径分别返回 ortools-1.3.0 / haco-cps-2.1.0(不是 1.4.0)。

### 十七、旧模块不占默认主链 —— ❌
- 默认主链(hybrid_optimizer)逐行 import 的都是 `GlobalRouteGenome`(route_genome.py:42)+ `global_evaluator`/`global_local_search`/`global_construction`(38-40),即基准点名"可保留但不能再默认"的旧模块族;`route_genome.py:3-6` 自己写明旧 GlobalRouteGenome 保留给 global_* 用。

### 十八、最终生产调用链 —— ❌
- 见 §1。生产链路中 1.4 组件一个都不在;`algorithm/app` 内 `FeasibilityEngine`、`construct_ant_solution_v14`、`evaluate_route_genome/evaluate_route_states`、`local_search_v14`、`alns_search`、`EliteArchive` 等**全部无生产 import**(grep:仅 tests/benchmarks 与同族死链互相引用)。

---

## 4. 跨条目的"红线"违规汇总(最严重)

| 编号 | 违规 | 证据 | 对应基准条 |
|---|---|---|---|
| R1 | **HACO 与 HYBRID 被折叠**成同一个 `solve_hybrid`;`solve_haco`(2.0)生产不可达;HYBRID 内无 OR-Tools portfolio/refinement → HYBRID==HACO | solver.py:40-51;haco/solver.py:70(无调用者);hybrid_optimizer.py:9(声称有,实际无) | 二 |
| R2 | **fallback 把 OR-Tools 结果伪装成 HACO 版号**(`haco-cps-2.1.0`),异常也兜成 HACO 成功 | hybrid_optimizer.py:602-605;solver.py:59-60,67-68 | 十六 |
| R3 | **默认主链用错模块族**:旧 GlobalRouteGenome/global_*,无 ALNS/Archive | route_genome.py:42;hybrid_optimizer.py:38-51 | 四/十七/十八 |
| R4 | **基准要求的 1.4 主链 = 未接线库**(零生产 import) | §3 六/七/八/十二 引用链 | 六/十八 |
| R5 | **版本号不符**:生产成功返回 `haco-cps-2.1.0`/`2.0.0`,`haco-cps-1.4.0` 只存在于 docstring/测试标题 | solver.py:22-23;hybrid_optimizer.py:56;main.py:39 | 十六 |
| R6 | **活链信息素造跨车假边**(多车拍平沉积)且主循环只写不读、无引导;station 信息素只写不读 | hybrid_optimizer.py:398-403/184/194;global 循环无 `.get` | 九 |
| R7 | **活链 passenger_impact/cargo_detour 结构恒 0**;objective 插入 backtracking_ratio,非基准 6 维精确序 | global_evaluator.py:200-213(恒0);route_genome.py:562-575 | 七 |
| R8 | **cargo 语义 ≥3 套并存且互斥**:OR-Tools CargoOut/CargoIn/CargoLoad 三维(唯一正确)→ 1.4 FeasibilityEngine(无 PRELOADED)→ 活链 global_evaluator 把 DELIVERY/PICKUP 都 `current_cargo+=size` 且不回减、恰是 OR-Tools 注释已废弃的旧口径 | ortools_solver.py:250-256(注释明示旧口径废弃)vs global_evaluator.py:232-240 vs feasibility_engine.py:164-172 | 六 |
| R9 | **candidate_size 定义却不生效**(config 不解析、活链不读) | config.py:21/78-90 | 八 |
| R10 | **adaptive alpha/beta 只在不可达 2.0**;活链无 | haco/solver.py:151/163-166(无调用者) | 十 |

## 5. "即使把 1.4 接进生产也不达标"的 1.4 自身缺陷(接线前必修)

这些不是"没接线"的问题,而是目标链自身存在、接上线即继承的坑:

1. **RETURN 末位约束缺失**(第五条):`RouteGenome.insert_task` + `construction.generate_insertion_candidates` 可产出 RETURN 后仍带 DELIVER/ALIGHT 的路线,`FeasibilityEngine` 判可行。
2. **PRELOADED/initialCargoLoad 语义丢失**(第六条):编码层丢 cargoSource;FeasibilityEngine 无法区分 PRELOADED 派送、不做"派送≤initial_cargo_load"检查。
3. **duration 从不在搜索期生效**(第六条):三个 1.4 调用点都不传 `max_duration`,`_check_duration` 门控形同虚设且不含服务时间。
4. `local_search_v14.apply_move` 为空壳(374-382);`elite_archive` 与 `alns_v14` 无集成;`alns_v14` SA 劣化概率 `exp(-1/t)` 丢弃 delta(651-656)。
5. 版本/命名混乱会进一步放大审计成本:三代路线表示(RouteState/gap 1.3 · RouteGenome/events 1.4 · GlobalRouteGenome 2.0-2.1)、`config.py` 自称 "1.2.0"、`stop_level` 起点事件名不一致、`feasibility.py`/`gap_pheromone.py`/`route_state.py` 等 1.2/1.3 孤儿。

## 6. 下一步建议(仅建议,本报告未执行任何修改)

若目标是把"生产 API 请求真实进入 HACO-CPS 1.4.0 RouteGenome 主链",按依赖排序的最小动作集(建议后续单独开阶段做,并遵守 19 条修改原则):

- **P0(语义先对齐)**:先修 1.4 自身缺陷 §5(1-3:RETURN 末位、PRELOADED 穿透、duration 搜索期生效 + service 时间),并在编码层补 TaskBlock.cargoSource;以 OR-Tools baseline 的 CargoOut/CargoIn/CargoLoad 为唯一业务口径统一四套模块。
- **P1(把 1.4 链接成真正求解器)**:新增/改造一个入口函数,串起 `RouteGenome 构造 → FeasibilityEngine → 1.4 construction(candidate_size 解析生效) → 1.4 evaluator(ObjectiveVector) → pheromone(逐车 deposit_multi_vehicle) → local_search_v14(Best Improvement) → alns_v14 → archive/elite_archive 参与 repair/重建`;给 `HacoConfig.from_algorithm_config` 补齐 candidate_size/archive_size/adaptive 上下界等解析。
- **P2(调度层分流)**:`app/solver.py` 按 mode 真正分流——`HACO → P1 入口`、`HYBRID → P1 + OR-Tools portfolio/refinement(真调 OR-Tools 生成/精炼)`、`BASELINE → OR-Tools`;版本常量改为 `haco-cps-1.4.0`,`_adapt_parameters` 与 alpha/beta 传入 1.4 construction。
- **P3(不掩盖 fallback)**:任何真回落 OR-Tools 一律 `algorithm_version=ortools-1.3.0` + warning,禁止把 baseline 结果标成 HACO 版本。
- **P4(验证)**:每阶段跑完整 `pytest algorithm/tests`(含现有 `test_*_v14*`),并补一条端到端断言:同一请求在 BASELINE/HACO/HYBRID 下 `algorithmVersion` 分别= `ortools-1.3.0` / `haco-cps-1.4.0` / `haco-cps-1.4.0` 且 HYBRID 结果 ≠ HACO 结果(证明 portfolio 生效)。

## 附:证据文件定位速查

- 调度/兜底:`algorithm/app/solver.py:38-86`
- 活链求解:`algorithm/app/haco/hybrid_optimizer.py:75-243`(imports 35-51;主循环 135-223;编码 477-488;兜底 602-605;复核 582-599)
- 1.4 表示:`algorithm/app/haco/route_genome.py`(RouteGenome 57-331;GlobalRouteGenome 413-544;GenomeEvaluation 547-575)
- 统一可行性:`algorithm/app/haco/feasibility_engine.py`
- 1.4 评估/目标:`algorithm/app/haco/evaluator.py`;`encoding.py:82-118`
- 活链评估:`algorithm/app/haco/global_evaluator.py`(恒0 绕行 200-213;cargo 232-240)
- 1.4 构造/候选:`algorithm/app/haco/construction.py:477-778`
- 信息素:`algorithm/app/haco/pheromone.py`(逐车工具 83-141)
- 死链求解 2.0(自适应):`algorithm/app/haco/solver.py:70,151,163-166,563-581`
- ALNS/算子/存档(孤儿):`alns_v14.py`、`destroy_repair.py`、`archive.py`、`elite_archive.py`
- API 装配:`algorithm/app/main.py:39,104-140,177-178`
- OR-Tools 基准:`algorithm/app/baseline/ortools_solver.py`(语义 58-152/250-391)
- 配置解析缺口:`algorithm/app/haco/config.py:78-90`
