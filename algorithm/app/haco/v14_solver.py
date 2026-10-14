"""HACO-CPS 1.4.1 完整求解器（RouteGenome 主链）。

把 1.4 组件串成一条可被生产 API 请求进入的主搜索链：

    TaskBlock
      → RouteGenome(每车一例, events 唯一真实顺序)
      → Construction(1.4, candidate_size 生效, alpha/beta 自适应)
      → FeasibilityEngine(统一约束, 含时间窗+服务时间, PRELOADED/CargoOut/CargoIn/CargoLoad)
      → ObjectiveVector(六维严格 lexicographic, 由 events 真实计算)
      → Pheromone(逐车 deposit_multi_vehicle, 避免跨车假边)
      → Local Search(Best Improvement)
      → ALNS(destroy/repair/自适应算子/接受准则)
      → EliteArchive(保存/多样性/采样参与重建)
      → Best RouteGenome → VehiclePlan → SolveOutcome

模式身份：algorithm_version = "haco-cps-1.4.1"。本模块不做 OR-Tools 兜底伪装；
真实回落到 OR-Tools 由调用方(app/solver)负责并把版本改成 ortools-1.3.0。
"""

from __future__ import annotations

import copy
import logging
import math
import random
import time
from dataclasses import dataclass, field

from ..distance import DistanceMatrix
from ..models import (
    CargoSource,
    OrderType,
    PlanRequest,
    RouteStop,
    StopAction,
    VehiclePlan,
)
from .alns_v14 import alns_search, repair_with_ejection
from .config import HacoConfig
from .construction import construct_ant_solution_v14, generate_insertion_candidates
from .deadline import PhaseBudget, SearchDeadline
from .elite_archive import EliteArchive
from .encoding import ObjectiveVector, TaskBlock, TaskType
from .evaluator import compute_diversity, evaluate_route_states
from .feasibility_engine import FeasibilityEngine
from .heuristic import compute_distance, compute_duration
from .local_search_v14 import local_search_improve
from .pheromone import PheromoneMatrix, extract_vehicle_task_sequences
from .route_genome import EventType, RouteEvent, RouteGenome

logger = logging.getLogger(__name__)

# 版本信息
HACO_VERSION = "haco-cps-1.4.1"
PARAMETER_VERSION = "haco-cps-default-v1.4.1"

DISTANCE_SCALE = 1000

# EventType → StopAction（事件与停靠动作一一对应；DEPOT 出站即 DEPART）
_EVENT_TO_ACTION = {
    EventType.DEPOT: StopAction.DEPART,
    EventType.PASS: StopAction.PASS,
    EventType.BOARD: StopAction.BOARD,
    EventType.ALIGHT: StopAction.ALIGHT,
    EventType.PICKUP: StopAction.PICKUP,
    EventType.DELIVER: StopAction.DELIVER,
    EventType.RETURN: StopAction.RETURN,
}

# 乘客/配对任务（两段式：pickup+delivery 两个事件）
_TWO_BLOCK = (TaskType.PASSENGER, TaskType.SHIPMENT)

# 输出阶段的补全预算（秒）：搜索硬截止到期后，仍允许把遗漏任务补插回去。
# 这是"输出收尾"而不是"继续搜索"，只做有限次贪心插桩，不违反搜索硬时限的初衷。
# ⚠ 这条预算目前**只在单与单之间生效**，管不到单次扫描内部：把 `deadline` 真传进
# `generate_insertion_candidates` 试过了（d20），结果不是省时间而是改答案 ——
# 41 行变化（25 差 / 16 好）、penalized gap 4.641→9.796、总里程 1324.65→1375.59km，
# haco 墙钟反而更慢（812.9→845.6s），已回滚（任务 #29，证据见 docs §19）。
# 正解是先让单次候选便宜（构造侧改用每车自己的矩阵），不是先把闸接上。
OUTPUT_COMPLETION_BUDGET_SECONDS = 2.0


@dataclass
class SolveOutcome:
    status: str
    reason_code: str | None = None
    vehicle_plans: list[VehiclePlan] = field(default_factory=list)
    total_distance: float = 0.0
    algorithm_version: str = HACO_VERSION
    parameter_version: str = PARAMETER_VERSION
    warnings: list[str] = field(default_factory=list)
    iteration_stats: list[dict] = field(default_factory=list)
    # 绕行硬约束/覆盖不足时未分配的订单编号（后端交给多段联运 MultiLegPlanner）
    unassigned_order_ids: list[str] = field(default_factory=list)
    # 业务字典序键 ObjectiveVector.key()，按**最终发射的那组路线**复算。
    # 只给离线度量台用（benchmarks/lab/run.py 的 anytime 判据），不进 HTTP 响应：
    # 台账只有 (用车数, 里程) 两维，而 passenger_impact / cargo_detour 在业务键里排在
    # 里程之前，缺了它们会把「少绕行换多一点里程」误读成解变差（实测 r_vrp-L-005：
    # 两臂 un/veh 相同，long 的 max_detour 2.893→1.304、里程 +0.874km，按业务键是改善）。
    objective_key: tuple = ()


@dataclass
class GreedySeedResult:
    """Deterministic cheapest-insertion 结果（含完整性标记）。

    complete=True  ⇒ routes 覆盖全部任务，可作完整 seed。
    complete=False ⇒ routes 只覆盖部分任务（deadline 超时或无可放置），
                     不得直接当完整解；须经 repair/construction fallback 补齐，
                     补齐 + FeasibilityEngine 验证通过后才可作为 seed。
    """

    routes: list[RouteGenome]
    complete: bool
    placed_count: int
    total_tasks: int
    candidate_count: int = 0        # telemetry: 累计可行的候选数
    feasibility_check_count: int = 0  # telemetry: 累计 FeasibilityEngine 检查次数（近似）


def solve(
    request: PlanRequest,
    matrix: DistanceMatrix | None = None,
    config: HacoConfig | None = None,
) -> SolveOutcome:
    """HACO-CPS 1.4.1 主入口：与 hybrid_optimizer.solve_hybrid 同形状。"""
    if config is None:
        config = HacoConfig.from_algorithm_config(request.algorithmConfig)

    t_solve_start = time.perf_counter()
    rng = random.Random(config.random_seed)

    precheck_abort, precheck_warnings = _precheck(request)
    if precheck_abort is not None:
        return precheck_abort

    station_map = {s.stationId: s for s in request.stations}
    station_map[request.depot.stationId] = request.depot
    for v in request.vehicles:
        for sid in (v.skeleton or []):
            if sid not in station_map:
                return SolveOutcome(
                    status="infeasible",
                    reason_code="SKELETON_STATION_MISSING",
                    warnings=[f"SKELETON_STATION_NOT_IN_REQUEST:{sid}"],
                )
        for sid, code in ((v.startStationId, "START_STATION_MISSING"),
                          (v.endStationId, "END_STATION_MISSING")):
            if sid is not None and sid not in station_map:
                return SolveOutcome(
                    status="infeasible",
                    reason_code=code,
                    warnings=[f"TERMINAL_STATION_NOT_IN_REQUEST:{sid}"],
                )

    tasks = _encode_tasks(request)
    if not tasks:
        return SolveOutcome(status="feasible")

    # 规范任务序：主搜索（构造/信息素/随机数消耗）与客户端订单输入顺序解耦。
    # 同批任务无论以什么顺序到达，都得到完全相同的求解 → 用车数/里程稳定。
    # 次键才看 task_id；主键 priority 让稀缺舱位/时间余量先给高优先级单
    # （契约没给 priority 时全为 0，排序与旧版逐项一致 → 既有实例零漂移）。
    tasks = sorted(tasks, key=_canonical_order)

    tasks_by_id = {t.task_id: t for t in tasks}

    # 每车一个 RouteGenome（events 是唯一真实路线顺序）
    # 多起点公交线：Vehicle.startStationId/endStationId 决定发车站与收班站，
    # 缺省回落到统一场站（单场站模型）。baseline 早已实现，HACO 之前漏了。
    templates = [
        RouteGenome(
            vehicle_index=i,
            vehicle_id=v.vehicleId,
            depot_station=v.startStationId or request.depot.stationId,
            skeleton=list(v.skeleton) if v.skeleton else None,
            end_station=v.endStationId or request.depot.stationId,
        )
        for i, v in enumerate(request.vehicles)
    ]

    passenger_capacities = {
        i: v.passengerCapacity for i, v in enumerate(request.vehicles)
    }
    cargo_capacities = {
        i: v.cargoCapacity for i, v in enumerate(request.vehicles)
    }
    initial_passenger_loads = {
        i: v.initialPassengerLoad for i, v in enumerate(request.vehicles)
    }
    initial_cargo_loads = {
        i: v.initialCargoLoad for i, v in enumerate(request.vehicles)
    }

    # 搜索前先用可靠下界淘汰「怎么带都要绕穿半个重庆」的任务（规格 §二.5）：
    # 以前这种订单会把整批拖成 infeasible，或者被强行补插绕一大圈 —— 都不符合产品。
    unservable: list[TaskBlock] = []
    discarded_along_the_way: list[TaskBlock] = []   # 因时间窗被摘掉的货运单
    # 搜索期因「构造预算耗尽」暂摘的货运单：出口仍有一次带独立预算的补插机会，
    # 补上了就不算舍弃，补不上才进 unassignedOrderIds（少服务必须是事实而不是默认值）。
    deferred: list[TaskBlock] = []
    tasks, unservable = _split_unservable(
        tasks, templates, station_map, matrix, config.discard_penalty_km,
    )
    tasks_by_id = {t.task_id: t for t in tasks}
    # 逐单时效的可靠下界淘汰（与上面同一套「只在注定不可能时才舍弃」的纪律）：
    # 连「最乐观的合法取送点 + 直达 + 不排队」都赶不上承诺时刻的单子，
    # 不可能出现在任何完整可行解里；留在批次里只会把整批判无解，连累乘客没车可坐。
    late_tasks, tasks = _split_provably_late(
        tasks, templates, station_map, matrix,
        float(request.batchStart.timestamp()),
    )
    # 货舱装不下整批时同样不该判无解：按规范序（priority 降序 → task_id）尽量装，
    # 装不下的（必然是低优先级那些）进舍弃通道。判据用的是引擎自己那条
    # 「每车 CargoOut/CargoIn 累计 ≤ cargoCapacity」的必要条件求和，
    # 只有**真的装不下**才会触发，不会因为暂时插不进就误丢订单。
    overflow_tasks, tasks = _split_capacity_overflow(tasks, cargo_capacities)
    # 再按项目既有的「峰值净载荷」口径削一次（详见 _shed_peak_overflow 的口径说明）
    peak_tasks, tasks = _shed_peak_overflow(
        tasks, request, sum(cargo_capacities.values()),
    )
    overflow_tasks = overflow_tasks + peak_tasks
    if late_tasks or overflow_tasks:
        unservable.extend(late_tasks)
        unservable.extend(overflow_tasks)
        tasks_by_id = {t.task_id: t for t in tasks}
    unservable_order_ids = sorted({oid for t in unservable for oid in t.order_ids})
    warnings_pre = list(precheck_warnings)
    if unservable:
        warnings_pre.append(
            "DISCARDED_UNSERVABLE=" + ",".join(t.task_id for t in unservable)
        )
        if late_tasks:
            warnings_pre.append(
                "DISCARDED_ETA_UNSERVABLE=" + ",".join(t.task_id for t in late_tasks)
            )
        if overflow_tasks:
            warnings_pre.append(
                "DISCARDED_CAPACITY_OVERFLOW=" + ",".join(t.task_id for t in overflow_tasks)
            )
    if not tasks:
        return SolveOutcome(
            status="feasible",
            warnings=warnings_pre,
            unassigned_order_ids=unservable_order_ids,
        )


    window_seconds = int(
        request.batchEnd.timestamp() - request.batchStart.timestamp()
    )

    # 时间窗约束渗透进每一次内部 check（含服务时间）
    # ML Branch Ranker 开关（off|auto|force）
    generate_insertion_candidates.ml_mode = getattr(config, "use_branch_ranker", "off") or "off"

    # 逐车重量/体积运力：只有配置了的车才检查（None = 沿用旧的件数口径，不凭空设上限）
    weight_caps = {
        i: float(v.cargoWeightCapacityKg)
        for i, v in enumerate(request.vehicles)
        if getattr(v, "cargoWeightCapacityKg", None) is not None
    }
    volume_caps = {
        i: float(v.cargoVolumeCapacityM3)
        for i, v in enumerate(request.vehicles)
        if getattr(v, "cargoVolumeCapacityM3", None) is not None
    }
    engine = FeasibilityEngine(
        station_map=station_map,
        matrix=matrix,
        max_duration=float(window_seconds),
        cargo_weight_capacities=weight_caps,
        cargo_volume_capacities=volume_caps,
        # 逐单时效只在「这批真有人给了时效字段」时开启：缺省批次零额外开销、零行为漂移
        base_time_s=(
            float(request.batchStart.timestamp())
            if any(
                getattr(s, "readyTime", None) is not None
                or getattr(s, "pickupDeadline", None) is not None
                or getattr(s, "deliveryDeadline", None) is not None
                for s in request.shipments
            ) else None
        ),
    )

    # ── Hard Deadline：搜索阶段时间预算 ──────────────────────────
    # haco_time_limit = HACO 搜索硬截止（ACO + LS + ALNS + Archive + 编码输出）
    # overall_time_limit = 外层应用预留的总时限上限（当前由调用方 solver.py 控制）
    # 取两者较小值作为本函数内搜索阶段的硬截止
    haco_search_budget = min(config.haco_time_limit, config.overall_time_limit)
    deadline = SearchDeadline.from_seconds(
        haco_search_budget, max_evaluations=config.max_evaluations,
    )
    t_phase_start = time.monotonic()

    # ── 阶段配额 ────────────────────────────────────────────────
    # 实测（lab 台账 v0/v1）：不分阶段时 greedy seed 单段就能吃掉 55% 预算，
    # ACO 只跑 2~3 代就把墙钟用光，导致 ALNS、收尾 LS、合车（车辆数最小化）
    # 在生产预算下**一次都不会执行** —— 交付的其实是贪心种子。
    #
    # 做法是「给尾部阶段预留固定份额」，不是「按比例砍前面的阶段」：
    # v2 按 45% 砍 ACO 后 ACO 只剩 1 代，均值里程反而变差（8.04%→8.84%）。
    # 份额同时作用于墙钟与评估次数（PhaseBudget 共享主 deadline 的计数），
    # 因此批量回归设 max_evaluations 后整条链是确定性的，不受机器负载影响。
    def phase(frac: float) -> PhaseBudget:
        return PhaseBudget(deadline, frac, t_phase_start, haco_search_budget)

    # 种子之后各阶段的累计份额上限（阶段结束点，不是时长）
    SEED_RESERVE_FRACTION = 0.20
    PHASE_ACO = 0.70
    PHASE_ALNS = 0.88
    PHASE_FINAL_LS = 0.94
    PHASE_COMPACT = 1.0

    # ── 初始解 ────────────────────────────────────────────────
    initial, seed_telemetry = _generate_initial_solutions(
        tasks, tasks_by_id, templates, engine,
        passenger_capacities, cargo_capacities,
        initial_passenger_loads, initial_cargo_loads,
        station_map, matrix, config, rng, deadline,
        seed_phase=phase(SEED_RESERVE_FRACTION),
    )
    feasible_initials = [
        s for s in initial if s is not None
    ]
    # 种子阶段只分到 SEED_RESERVE_FRACTION 份额。逐单时效落地后合法插入位显著减少
    # （同一任务原本十几个可插槽位可能只剩两三个），于是**本来可解的批次**会在份额内
    # 建不出完整初始解 —— 那是预算分配问题，不是可行性问题。
    # 所以在摘单/判无解之前，先用真实引擎（含批窗与时效）和共享到 PHASE_ALNS 的预算
    # 确定性重建一次；成功就把它当初始解继续搜，失败才走后面的舍弃与无解分支。
    if not feasible_initials:
        # 救援拿**全部剩余**预算（而不是再切一段份额）：走到这一步说明份额内已经建不出
        # 完整解，此时「可行但没时间 refinement」严格优于「判无解、乘客没车可坐」。
        # 代价如实体现在里程上（ACO/ALNS 会因此拿不到预算），由 penalized gap 记账，
        # 不在这里偷偷换取「可行率」这个单一指标。
        rescue = _cheapest_insertion(
            tasks, templates, tasks_by_id, engine,
            passenger_capacities, cargo_capacities,
            initial_passenger_loads, initial_cargo_loads,
            station_map, matrix, phase(PHASE_COMPACT),
        )
        if rescue.routes is not None and rescue.complete and _routes_feasible(
            rescue.routes, tasks_by_id, engine,
            passenger_capacities, cargo_capacities,
            initial_passenger_loads, initial_cargo_loads,
            station_map, matrix,
        ):
            feasible_initials = [(
                rescue.routes,
                evaluate_route_states(rescue.routes, tasks_by_id, station_map, matrix),
            )]
            # 计数要报出来：这个分支跑得越多，说明「份额切小了 / 时效把搜索压紧了」
            # 越严重，是任务 #10（评估成本）的直接证据，不能静默兜底。
            warnings_pre.append("SEED_RESCUE_FULL_BUDGET=1")
        else:
            # 兜底舍弃（规格 §二.5）：满力贪心把到 PHASE_ALNS 为止的预算都用尽了，
            # 仍然放不下这些货运单 —— 这是「构造已用尽预算」的证据，不是猜测。
            # 与其把整批判无解（乘客因此没车可坐），不如把这几票整批舍弃、其余照走。
            # 关键：**复用** rescue.routes 里已经放下的部分当初始解 ——
            # 预算已经花光，再跑一次 `_generate_initial_solutions` 只会立刻过期（实测
            # 就是这样白跑一轮，placed=0/16），等于什么都没做还谎报「无解」。
            placed = {tid for rt in (rescue.routes or []) for tid in rt.placements}
            unplaceable = [
                t for t in tasks
                if t.task_id not in placed and t.task_type != TaskType.PASSENGER
            ]
            pax_missing = [
                t for t in tasks if t.task_id not in placed and t.task_type == TaskType.PASSENGER
            ]
            if unplaceable and not pax_missing and rescue.routes is not None:
                # 只把这些货运单**暂摘**出搜索集（deferred），不是判死：
                # 出口 `_complete_or_discard` 还有一次带独立预算的补插机会。
                # 实测直接判舍弃会把「见证器证明存在完整按时解」的实例
                # 交成 10 单未服务（r_line-L-001 / r_village-L-001）—— 那是少服务，不是政策代价。
                drop_ids = {t.task_id for t in unplaceable}
                deferred.extend(unplaceable)
                tasks = [t for t in tasks if t.task_id not in drop_ids]
                for tid in drop_ids:
                    tasks_by_id.pop(tid, None)
                feasible_initials = [(
                    rescue.routes,
                    evaluate_route_states(rescue.routes, tasks_by_id, station_map, matrix),
                )]
                warnings_pre.append(
                    "DEFERRED_FOR_OUTPUT=" + ",".join(sorted(drop_ids))
                )
            else:
                # 救援仍不完整、又没有「可以只摘货运」的余地（缺的全是乘客或无路线）：
                # 这条出口必须单独打标记，且不能借用成功分支的标记 ——
                # 曾经这里错打了一次 SEED_RESCUE_FULL_BUDGET=1，
                # 让「救援失败」在现场读起来像「救援成功却仍判无解」，白绕一圈诊断。
                warnings_pre.append(
                    "SEED_RESCUE_INCOMPLETE"
                    f"/pax_missing={len(pax_missing)}/freight_unplaced={len(unplaceable)}"
                )
    # 一整批被判无解，常常只是因为其中一单贵得离谱（偏远件把时间窗吃光）。
    # 硬约束口径下整批 infeasible；软约束口径下应该先「舍掉那一单、其余照走」，
    # 再把被舍的单交回后端多段联运。逐次摘除地理上最离谱的货运单重试，
    # 乘客绝不参与摘除（每站必停拉客，装不下才是唯一原因）。
    shed_attempts = 0
    while not feasible_initials and shed_attempts < 4:
        anchors = {
            st for t in templates for st in ([t.depot_station, t.end_station] + list(t.skeleton))
        }
        anchor_stations = [station_map[x] for x in anchors if x in station_map]
        freight = [
            t for t in tasks
            if t.task_type in (TaskType.DELIVERY, TaskType.PICKUP, TaskType.SHIPMENT)
        ]
        if not freight or not anchor_stations:
            break

        def _min_walk(t: TaskBlock) -> float:
            pts = [station_map.get(t.pickup_station), station_map.get(t.delivery_station)]
            tot = 0.0
            for p in filter(None, pts):
                tot += min(_segment(p, a, matrix)[0] for a in anchor_stations)
            return tot

        worst = max(freight, key=_min_walk)
        if _min_walk(worst) <= DETOUR_RELAXED_MAX_KM:
            break   # 最离谱的也不超交接半径 → 无解另有原因，不再摘单
        tasks = [t for t in tasks if t.task_id != worst.task_id]
        tasks_by_id.pop(worst.task_id, None)
        discarded_along_the_way.append(worst)
        shed_attempts += 1
        initial, _extra_telemetry = _generate_initial_solutions(
            tasks, tasks_by_id, templates, engine,
            passenger_capacities, cargo_capacities,
            initial_passenger_loads, initial_cargo_loads,
            station_map, matrix, config, rng, deadline,
            seed_phase=phase(SEED_RESERVE_FRACTION),
        )
        feasible_initials = [s for s in initial if s is not None]
        warnings_pre.append(
            "DISCARDED_SHED_FOR_WINDOW=" + ",".join(t.task_id for t in discarded_along_the_way)
        )
    if discarded_along_the_way:
        unservable = discarded_along_the_way + [
            t for t in unservable if t.task_id not in {x.task_id for x in discarded_along_the_way}
        ]
        unservable_order_ids = sorted(
            {oid for t in unservable for oid in t.order_ids}
        )
    if not tasks and feasible_initials and not deferred:
        # 全部货运单都被摘走：交一个空方案 + 如实的未服务清单，而不是假装无解
        return SolveOutcome(
            status="feasible",
            warnings=list(warnings_pre) + [
                "DISCARDED_ORDERS=" + ",".join(unservable_order_ids),
                f"DISCARD_PENALTY_KM={config.discard_penalty_km}",
            ],
            unassigned_order_ids=unservable_order_ids,
        )
    if not feasible_initials:
        # 归类无解原因：去掉时间窗约束若仍能构造出可行解 → 是时间窗过紧
        probe_engine = FeasibilityEngine(
            station_map=station_map, matrix=matrix,
        )  # 无 max_duration
        # Give the diagnostic probe its own limited budget (max 2s),
        # not the main search deadline, so it can complete diagnosis.
        probe_deadline = SearchDeadline.from_seconds(
            min(2.0, deadline.remaining()),
            # 计数预算下探针的判定结果必须与机器快慢无关（否则 reason code 会漂）
            max_evaluations=(max(1, config.max_evaluations // 4)
                             if config.max_evaluations else None),
        )
        probe = _cheapest_insertion(
            tasks, templates, tasks_by_id, probe_engine,
            passenger_capacities, cargo_capacities,
            initial_passenger_loads, initial_cargo_loads,
            station_map, matrix, probe_deadline,
        )
        # 诊断：去掉时间窗后能否形成完整可行解（probe.complete 必须为 True）
        if probe.complete and _routes_feasible(
            probe.routes, tasks_by_id, probe_engine,
            passenger_capacities, cargo_capacities,
            initial_passenger_loads, initial_cargo_loads,
            station_map, matrix,
        ):
            return SolveOutcome(
                status="infeasible",
                reason_code="TIME_WINDOW_EXCEEDED",
                # 判无解的出口也必须带上前面的遥测：舍弃/暂摘/救援这些标记是
                # 区分「乘客真塞不下」与「货运该舍未舍」的唯一线索；
                # 丢了它们，现场只剩一个 reason code，谁都诊断不了。
                warnings=list(warnings_pre) + ["NO_FEASIBLE_V14_SOLUTION_WITHIN_TIME_WINDOW"],
            )
        return SolveOutcome(
            status="infeasible",
            reason_code="NO_FEASIBLE_INITIAL_SOLUTION",
            warnings=list(warnings_pre) + ["NO_FEASIBLE_V14_SOLUTION"],
        )

    best_routes, best_obj = min(
        feasible_initials, key=lambda x: x[1]
    )
    # 最后一个「完整且经过 FeasibilityEngine 验证」的解：搜索预算耗尽后，
    # 局部搜索/ALNS 可能只留下部分解，输出阶段用它兜底，绝不返回半成品方案。
    best_complete_routes = [r.copy() for r in best_routes]
    best_complete_obj = best_obj

    # ── 信息素初始化（MMAS）────────────────────────────────
    task_ids = [t.task_id for t in tasks] + ["DEPOT"]
    pheromone = PheromoneMatrix(task_ids, config)
    if best_obj.normalized_cost() > 0:
        pheromone.initialize_tau0(best_obj.normalized_cost())

    archive = EliteArchive(max_size=config.archive_size)
    archive.add(best_routes, best_obj, tasks_by_id, station_map, matrix)

    warnings: list[str] = list(warnings_pre)
    # greedy seed telemetry（含 SEED_MS / SEED_COMPLETE / SEED_PLACED / SEED_EXPECTED）
    for _k, _v in seed_telemetry.items():
        if _v is not None:
            warnings.append(f"{_k}={_v}")
    iteration_stats: list[dict] = []
    no_improve_count = 0
    temperature = config.initial_temperature
    recent_solutions: list[list[RouteGenome]] = []

    # ── 主搜索循环（ACO 构造 + 局部搜索 + SA + 存档）────────
    total_search_start = time.perf_counter()
    aco_deadline = phase(PHASE_ACO)

    for iteration in range(config.max_iterations):
        if aco_deadline.expired():
            warnings.append("HACO_ACO_PHASE_CAP")
            break

        # 依据多样性自适应 alpha/beta（须真正传入构造）
        diversity = (
            compute_diversity(recent_solutions[-8:])
            if recent_solutions
            else None
        )
        alpha, beta = _adapt_parameters(diversity, config)

        iter_cand_ms = 0.0
        iter_eval_ms = 0.0
        iter_ls_ms = 0.0

        iteration_best = None
        iteration_best_obj = ObjectiveVector(infeasibility=float("inf"))

        for ant_idx in range(config.ant_count):
            if aco_deadline.expired():
                warnings.append("HACO_ACO_PHASE_CAP")
                break

            # 存档周期性参与：部分蚂蚁从精英解出发（重建/微扰）
            seeded = archive.sample_elite(rng) if (
                ant_idx % 4 == 0 and archive.entries
            ) else None

            if seeded is not None:
                ant_routes = seeded
            else:
                _t0 = time.perf_counter()
                ant_routes = construct_ant_solution_v14(
                    tasks,
                    templates,
                    tasks_by_id,
                    pheromone,
                    engine,
                    station_map,
                    matrix,
                    passenger_capacities,
                    cargo_capacities,
                    initial_passenger_loads,
                    initial_cargo_loads,
                    config,
                    rng,
                    alpha=alpha,
                    beta=beta,
                    candidate_size=config.candidate_size,
                    deadline=aco_deadline,
                )
                iter_cand_ms += (time.perf_counter() - _t0) * 1000
                # 构造可能丢任务 → 贪心补插
                ant_routes = _repair_unassigned(
                    ant_routes, tasks, tasks_by_id, engine,
                    passenger_capacities, cargo_capacities,
                    initial_passenger_loads, initial_cargo_loads,
                    station_map, matrix, aco_deadline,
                )
                if ant_routes is None:
                    continue

            if not _routes_feasible(
                ant_routes, tasks_by_id, engine,
                passenger_capacities, cargo_capacities,
                initial_passenger_loads, initial_cargo_loads,
                station_map, matrix,
            ):
                continue

            _t0 = time.perf_counter()
            ant_obj = evaluate_route_states(
                ant_routes, tasks_by_id, station_map, matrix
            )
            iter_eval_ms += (time.perf_counter() - _t0) * 1000

            if iteration_best is None or ant_obj < iteration_best_obj:
                iteration_best = ant_routes
                iteration_best_obj = ant_obj
            elif temperature > config.temperature_min:
                # SA：可接受更差解（保留一定探索）
                delta = (
                    ant_obj.normalized_cost()
                    - iteration_best_obj.normalized_cost()
                )
                if (
                    delta > 0
                    and rng.random()
                    < math.exp(-delta / max(temperature, 1e-9))
                ):
                    iteration_best = ant_routes
                    iteration_best_obj = ant_obj

        temperature *= config.cooling_rate

        # 每代只对 iteration_best 做一次 Best Improvement 局部搜索
        # （蚂蚁只构造不精炼，控制整体耗时在契约时限内）
        if iteration_best is not None:
            _t0 = time.perf_counter()
            refined, _ = local_search_improve(
                iteration_best, tasks_by_id, station_map, matrix, engine,
                passenger_capacities, cargo_capacities,
                initial_passenger_loads, initial_cargo_loads,
                rounds=config.local_search_rounds,
                deadline=aco_deadline,
            )
            iter_ls_ms = (time.perf_counter() - _t0) * 1000
            if _routes_feasible(
                refined, tasks_by_id, engine,
                passenger_capacities, cargo_capacities,
                initial_passenger_loads, initial_cargo_loads,
                station_map, matrix,
            ):
                refined_obj = evaluate_route_states(
                    refined, tasks_by_id, station_map, matrix
                )
                if refined_obj < iteration_best_obj:
                    iteration_best = refined
                    iteration_best_obj = refined_obj

        # ── 信息素更新（逐车沉积，禁止跨车假边）──────────
        pheromone.evaporate()
        if iteration_best is not None:
            sequences = extract_vehicle_task_sequences(iteration_best)
            cost = iteration_best_obj.normalized_cost()
            if cost > 0:
                pheromone.deposit_multi_vehicle(
                    sequences, cost, weight=1.0
                )

            if iteration_best_obj < best_obj:
                best_routes = [r.copy() for r in iteration_best]
                best_obj = iteration_best_obj
                best_complete_routes = [r.copy() for r in best_routes]
                best_complete_obj = best_obj
                no_improve_count = 0
                if cost > 0:
                    pheromone.deposit_multi_vehicle(
                        sequences, cost, weight=2.0  # 精英强化
                    )
                archive.add(
                    best_routes, best_obj, tasks_by_id, station_map, matrix
                )
            else:
                no_improve_count += 1
        else:
            no_improve_count += 1

        recent_solutions.append([r.copy() for r in best_routes])
        recent_solutions = recent_solutions[-8:]

        iteration_stats.append({
            "iteration": iteration,
            "best_cost": best_obj.normalized_cost(),
            "alpha": round(alpha, 3),
            "beta": round(beta, 3),
            "diversity": round(diversity, 3) if diversity is not None else None,
            "candidate_ms": round(iter_cand_ms, 1),
            "evaluation_ms": round(iter_eval_ms, 1),
            "local_search_ms": round(iter_ls_ms, 1),
        })

        if no_improve_count >= config.convergence_threshold:
            warnings.append("HACO_CONVERGED")
            break

        if no_improve_count >= config.convergence_threshold // 2:
            pheromone.restart(config.restart_ratio)

    # ── 收尾：ALNS + 再局部搜索 ─────────────────────────────
    alns_ms = 0.0
    # alns_iterations: 0=默认自适应；>0 钉死；<0 关闭（纯 ACO 深度对照）
    _alns_cfg = int(getattr(config, "alns_iterations", 0) or 0)
    alns_deadline = phase(PHASE_ALNS)
    if not alns_deadline.expired() and _alns_cfg >= 0:
        alns_iterations = _alns_cfg if _alns_cfg > 0 else max(30, min(200, config.max_iterations * 3))
        _t0 = time.perf_counter()
        refined = alns_search(
            best_routes, tasks_by_id, engine, station_map, matrix,
            passenger_capacities, cargo_capacities,
            initial_passenger_loads, initial_cargo_loads,
            config, rng, max_iterations=alns_iterations,
            deadline=alns_deadline,
        )
        alns_ms = (time.perf_counter() - _t0) * 1000
        # ALNS destroy/repair 可能丢任务 → 必须补全后才可能被采纳
        refined = _ensure_complete(
            refined, tasks, tasks_by_id, engine,
            passenger_capacities, cargo_capacities,
            initial_passenger_loads, initial_cargo_loads,
            station_map, matrix, alns_deadline,
        )
        if refined is not None:
            refined_obj = evaluate_route_states(
                refined, tasks_by_id, station_map, matrix
            )
            if _routes_feasible(
                refined, tasks_by_id, engine,
                passenger_capacities, cargo_capacities,
                initial_passenger_loads, initial_cargo_loads,
                station_map, matrix,
            ) and refined_obj < best_obj:
                best_routes = refined
                best_obj = refined_obj
        # 收尾 LS 也必须先验证「完整 + 可行」才允许替换最优解：
        # LS 的移动可能丢任务，直接采纳会把完整解换成部分解。
        final_ls_deadline = phase(PHASE_FINAL_LS)
        ls_routes, _ls_obj_internal = local_search_improve(
            best_routes, tasks_by_id, station_map, matrix, engine,
            passenger_capacities, cargo_capacities,
            initial_passenger_loads, initial_cargo_loads,
            rounds=config.local_search_rounds + 1,
            deadline=final_ls_deadline,
        )
        # 采纳判据必须与主链同一把尺子：LS 内部按 initial_passenger_loads 计算
        # passenger_impact，而主链各处不带该入参。混用两套数值会「LS 自认变好、
        # 主链其实变差」，直接破坏 anytime 单调性（预算越大解越差的实测根因之一）。
        if _routes_feasible(
            ls_routes, tasks_by_id, engine,
            passenger_capacities, cargo_capacities,
            initial_passenger_loads, initial_cargo_loads,
            station_map, matrix,
        ):
            ls_obj = evaluate_route_states(
                ls_routes, tasks_by_id, station_map, matrix
            )
            if ls_obj < best_obj:
                best_routes, best_obj = ls_routes, ls_obj
            if ls_obj < best_complete_obj:
                best_complete_routes = [r.copy() for r in ls_routes]
                best_complete_obj = ls_obj

    # 存档里若有更优（且可行）精英则采纳
    best_entry = archive.get_best()
    if (
        best_entry is not None
        and best_entry.objective < best_obj
        and _routes_feasible(
            best_entry.routes, tasks_by_id, engine,
            passenger_capacities, cargo_capacities,
            initial_passenger_loads, initial_cargo_loads,
            station_map, matrix,
        )
    ):
        best_routes = best_entry.routes
        best_obj = best_entry.objective

    # 车辆数最小化：把全部任务重新打包进尽可能少的车（稳定用车数）。
    # 注意：主搜索每代已对 iteration_best 做过 Best Improvement LS，这里不再重复 LS，
    # 避免大骨架规模下重复全枚举拖慢求解。
    compacted = _compact_solution(
        best_routes, tasks, templates, tasks_by_id, engine,
        passenger_capacities, cargo_capacities,
        initial_passenger_loads, initial_cargo_loads,
        station_map, matrix, config, rng, phase(PHASE_COMPACT),
    )
    if compacted is not None:
        best_routes = compacted
        best_obj = evaluate_route_states(
            best_routes, tasks_by_id, station_map, matrix
        )

    # ── 输出（补全 or 舍弃：不再强制全量覆盖）────────────────
    # 输出补全用独立小预算：搜索硬截止到时点后仍允许一次有限补插，
    # 否则"已经搜到完整解、只是收尾时丢了几个任务"的批次会被误报成 INCOMPLETE_SOLUTION。
    output_deadline = SearchDeadline.from_seconds(OUTPUT_COMPLETION_BUDGET_SECONDS)
    # 出口任务表 = 搜索集 + 暂摘集：暂摘只是「不让它拖累搜索不变量」，
    # 不是判死；这里用输出补全的独立预算再试一次。
    exit_tasks = tasks + [t for t in deferred if t.task_id not in
                          {x.task_id for x in tasks}]
    exit_tasks_by_id = dict(tasks_by_id)
    for t in deferred:
        exit_tasks_by_id.setdefault(t.task_id, t)
    completed, discarded, completion_handovers = _complete_or_discard(
        best_routes, exit_tasks, exit_tasks_by_id, engine,
        passenger_capacities, cargo_capacities,
        initial_passenger_loads, initial_cargo_loads,
        station_map, matrix, output_deadline,
        discard_penalty_km=config.discard_penalty_km,
    )
    deferred_ids = {t.task_id for t in deferred}
    still_missing = [t for t in discarded if t.task_id in deferred_ids]
    if deferred:
        # 三个数一起报才看得出「暂摘→补回」这条链有没有真在工作
        warnings.append(
            f"DEFERRED_TOTAL={len(deferred)}"
            f"/RECOVERED={len(deferred) - len(still_missing)}"
            f"/DISCARDED={len(still_missing)}"
        )
    if completed is not None:
        best_routes = completed
    elif best_complete_routes is not None:
        # 兜底：回退到搜索过程中最后一个「完整 + 可行」的解，绝不输出半成品方案
        best_routes = best_complete_routes
        best_obj = best_complete_obj
        # 回退解是「搜索集全量」的完整解，暂摘单并没有被服务：
        # 必须照样计入舍弃，否则它们既不在方案里也不在 unassignedOrderIds 里（静默丢单）。
        discarded = list(deferred)
        warnings.append("OUTPUT_FALLBACK_TO_LAST_COMPLETE_SOLUTION")
    else:
        missing_tasks = _missing_tasks(best_routes, tasks)
        return SolveOutcome(
            status="infeasible",
            reason_code="INCOMPLETE_SOLUTION",
            warnings=list(warnings_pre) + [
                "NO_COMPLETE_V14_SOLUTION",
                "UNASSIGNED_TASKS=" + ",".join(sorted(t.task_id for t in missing_tasks)),
            ],
            unassigned_order_ids=sorted({oid for t in missing_tasks for oid in t.order_ids}),
        )

    # 软化阶段必须用「车上实际载着的那些任务」做表：
    # 出口补全可能已把暂摘单插回路线，而 tasks_by_id 还是搜索期的缩减表 ——
    # 那些事件会被 per-route 检查静默排除，容量与时效核算就漏了它们
    # （不报错，只是算少）。按 placements 重建表，覆盖性语义也才自洽。
    placed_now = {tid for r in best_routes for tid in r.placements}
    soften_table = {tid: exit_tasks_by_id[tid] for tid in placed_now
                    if tid in exit_tasks_by_id}
    unknown_placed = sorted(placed_now - set(soften_table))
    if unknown_placed:
        # 车上有事件却查不到任务定义：这是内部不变量破了，必须报出来而不是默默跳过
        warnings.append("UNKNOWN_PLACED_TASKS=" + ",".join(unknown_placed))
    handovers, softened_dropped, soften_stats = _soften_over_detour(
        best_routes, soften_table, engine, station_map, matrix, request, config,
        passenger_capacities, cargo_capacities,
        initial_passenger_loads, initial_cargo_loads,
    )
    discarded.extend(softened_dropped)
    warnings.extend([
        f"SOFTEN_DROP_OK={soften_stats['drop_ok']}",
        f"SOFTEN_DROP_BLOCKED={soften_stats['drop_blocked']}",
        f"SOFTEN_NO_HANDOVER_CANDIDATE={soften_stats['no_candidate']}",
        f"SOFTEN_NO_TASK={soften_stats['no_task']}",
        f"SOFTEN_REWRITE_BLOCKED={soften_stats['rewrite_blocked']}",
    ])
    # 被舍弃的订单：如实交给后端多段联运（以前这条路只在整批无解时才走得通）
    discarded_order_ids = sorted(
        set(unservable_order_ids) | {oid for t in discarded for oid in t.order_ids}
    )
    if discarded_order_ids:
        warnings.append("DISCARDED_ORDERS=" + ",".join(discarded_order_ids))
        warnings.append(f"DISCARD_PENALTY_KM={config.discard_penalty_km}")
    # 补全阶段就已经改成站点交接的订单，与收尾改写的一起进入发射标注
    for oid, info in completion_handovers.items():
        handovers.setdefault(oid, info)
    if handovers:
        warnings.append(f"DETOUR_HANDOVER_COUNT={len(handovers)}")

    vehicle_plans = _routes_to_vehicle_plans(
        best_routes, request, station_map, matrix, handovers
    )

    total_search_ms = (time.perf_counter() - total_search_start) * 1000

    # 阶段耗时 telemetry（便于定位超时阶段）
    warnings.append(f"SEARCH_START_MS={round((total_search_start - t_solve_start) * 1000, 1)}")
    if alns_ms > 0:
        warnings.append(f"ALNS_MS={alns_ms:.0f}")
    warnings.append(f"TOTAL_SEARCH_MS={total_search_ms:.0f}")
    # 评估次数 = 确定性预算的实际消耗；批量回归用它标定 max_evaluations
    warnings.append(f"EVALUATIONS={deadline.evaluations}")
    if deadline.max_evaluations:
        warnings.append(f"EVALUATION_CAP={deadline.max_evaluations}")
    warnings.append(f"ITERATION_COUNT={len(iteration_stats)}")
    if iteration_stats:
        warnings.append(
            f"CANDIDATE_MS={round(sum(s.get('candidate_ms', 0) for s in iteration_stats), 1)}"
        )
        warnings.append(
            f"LOCAL_SEARCH_MS={round(sum(s.get('local_search_ms', 0) for s in iteration_stats), 1)}"
        )

    # 导出键必须按最终发射的路线复算：主循环的 best_obj 早于出口补全与软化改写，
    # 用它会把「已经改掉的绕行/暂摘」算回账上（度量台按这把尺子判 anytime，读错就白修）。
    final_obj = evaluate_route_states(best_routes, soften_table, station_map, matrix)
    return SolveOutcome(
        status="feasible",
        vehicle_plans=vehicle_plans,
        total_distance=round(
            sum(p.totalDistance for p in vehicle_plans), 3
        ),
        algorithm_version=HACO_VERSION,
        parameter_version=PARAMETER_VERSION,
        warnings=warnings,
        iteration_stats=iteration_stats,
        # 方案可行但这一趟带不下的订单：后端据此转多段联运（换乘站接力）
        unassigned_order_ids=discarded_order_ids,
        objective_key=final_obj.key(),
    )


# ─── 初始解生成 ───────────────────────────────────────────────


def _generate_initial_solutions(
    tasks, tasks_by_id, templates, engine,
    passenger_capacities, cargo_capacities,
    initial_passenger_loads, initial_cargo_loads,
    station_map, matrix, config, rng,
    deadline: SearchDeadline | None = None,
    seed_phase=None,
) -> tuple[list[tuple[list[RouteGenome], ObjectiveVector] | None], dict]:
    """生成多种初始解：确定性 cheapest-insertion + 多组 ACO 蚂蚁。

    返回 (seeds, seed_telemetry)：
    - seeds: 完整可行初始解列表
    - seed_telemetry: 记录 greedy seed 的时间/完整性诊断（SEED_MS / SEED_COMPLETE /
      SEED_PLACED / SEED_EXPECTED / SEED_CANDIDATES / SEED_FEASIBILITY_CHECKS）
    """
    seeds: list[tuple[list[RouteGenome], ObjectiveVector] | None] = []
    seed_telemetry: dict = {
        "SEED_MS": 0.0,
        "SEED_COMPLETE": None,
        "SEED_PLACED": 0,
        "SEED_EXPECTED": len(tasks),
        "SEED_CANDIDATES": 0,
        "SEED_FEASIBILITY_CHECKS": 0,
    }

    # 1) 确定性 cheapest-insertion（保证全部任务被放置的基线）。
    #    greedy seed 有独立有限预算，不应独占整个 search budget。
    #    超时返回部分 seed（complete=False）→ 走快速 repair 补齐 → 验证后才作 seed。
    if seed_phase is not None:
        # 由调用方给出阶段预算：与主搜索共享同一个评估计数，
        # 这样批量回归设 max_evaluations 后种子阶段也是确定性的
        seed_deadline = seed_phase
    else:
        seed_time_limit = float(getattr(config, "greedy_seed_time_limit", 2.0))
        if deadline is not None:
            seed_budget = min(seed_time_limit, max(0.0, deadline.remaining()))
        else:
            seed_budget = seed_time_limit
        seed_deadline = SearchDeadline.from_seconds(seed_budget)

    _seed_t0 = time.perf_counter()
    greedy = _cheapest_insertion(
        tasks, templates, tasks_by_id, engine,
        passenger_capacities, cargo_capacities,
        initial_passenger_loads, initial_cargo_loads,
        station_map, matrix, seed_deadline,
    )
    seed_telemetry["SEED_MS"] = round((time.perf_counter() - _seed_t0) * 1000, 1)
    seed_telemetry["SEED_COMPLETE"] = greedy.complete
    seed_telemetry["SEED_PLACED"] = greedy.placed_count
    seed_telemetry["SEED_CANDIDATES"] = greedy.candidate_count
    seed_telemetry["SEED_FEASIBILITY_CHECKS"] = greedy.feasibility_check_count

    greedy_seed: list[RouteGenome] | None = None
    if greedy.complete and _routes_feasible(
        greedy.routes, tasks_by_id, engine,
        passenger_capacities, cargo_capacities,
        initial_passenger_loads, initial_cargo_loads,
        station_map, matrix,
    ):
        # 完整 seed，直接可用
        greedy_seed = greedy.routes
    elif not greedy.complete and greedy.placed_count > 0:
        # 部分 seed：标记 incomplete，不得直接当完整解；走快速 repair 补齐（主 deadline 内）
        repaired = _repair_unassigned(
            greedy.routes, tasks, tasks_by_id, engine,
            passenger_capacities, cargo_capacities,
            initial_passenger_loads, initial_cargo_loads,
            station_map, matrix, deadline,
        )
        if repaired is not None and _routes_feasible(
            repaired, tasks_by_id, engine,
            passenger_capacities, cargo_capacities,
            initial_passenger_loads, initial_cargo_loads,
            station_map, matrix,
        ):
            greedy_seed = repaired

    if greedy_seed is not None:
        obj = evaluate_route_states(greedy_seed, tasks_by_id, station_map, matrix)
        seeds.append((greedy_seed, obj))

    # 2) ACO 蚂蚁（统一信息素无先验，多样性来自不同 alpha/beta）
    for alpha, beta in (
        (config.alpha, config.beta),
        (max(config.alpha - 0.5, config.alpha_min), config.beta),
        (min(config.alpha + 0.5, config.alpha_max), config.beta),
        (config.alpha, max(config.beta - 1.0, config.beta_min)),
    ):
        task_ids = [t.task_id for t in tasks] + ["DEPOT"]
        pheromone = PheromoneMatrix(task_ids, config)
        routes = construct_ant_solution_v14(
            tasks,
            templates,
            tasks_by_id,
            pheromone,
            engine,
            station_map,
            matrix,
            passenger_capacities,
            cargo_capacities,
            initial_passenger_loads,
            initial_cargo_loads,
            config,
            rng,
            alpha=alpha,
            beta=beta,
            candidate_size=config.candidate_size,
            deadline=deadline,
        )
        routes = _repair_unassigned(
            routes, tasks, tasks_by_id, engine,
            passenger_capacities, cargo_capacities,
            initial_passenger_loads, initial_cargo_loads,
            station_map, matrix, deadline,
        )
        if routes is None:
            continue
        if not _routes_feasible(
            routes, tasks_by_id, engine,
            passenger_capacities, cargo_capacities,
            initial_passenger_loads, initial_cargo_loads,
            station_map, matrix,
        ):
            continue
        obj = evaluate_route_states(routes, tasks_by_id, station_map, matrix)
        seeds.append((routes, obj))

    return seeds, seed_telemetry


def _insertion_score(cand) -> tuple:
    """插入位置排序键：pax → detour 桶 → 里程 → 时长（粗 detour 防厘米噪声）。

    与训练标签口径一致；不另起一套权重（encoding.SEARCH_ENERGY_* 是内部能量，
    这里是「哪个插入位置更好」的字典序判据，两者用途不同）。
    """
    return (
        round(float(cand.passenger_impact), 3),
        round(float(cand.cargo_detour), 1),
        round(float(cand.delta_distance), 2),
        round(float(cand.delta_duration), 2),
    )


def _insertion_scalar(cand) -> float:
    """把插入位置折成一个可相减的数值，用于 regret 差值。

    权重直接复用 encoding 里的搜索能量常数，避免同一个项目出现第二套排序逻辑。
    """
    from .encoding import (
        SEARCH_ENERGY_CARGO_DETOUR,
        SEARCH_ENERGY_DISTANCE,
        SEARCH_ENERGY_DURATION,
        SEARCH_ENERGY_PASSENGER,
    )
    return (
        float(cand.passenger_impact) * SEARCH_ENERGY_PASSENGER
        + float(cand.cargo_detour) * SEARCH_ENERGY_CARGO_DETOUR
        + float(cand.delta_distance) * SEARCH_ENERGY_DISTANCE
        + float(cand.delta_duration) * SEARCH_ENERGY_DURATION
    )


def _cheapest_insertion(
    tasks, templates, tasks_by_id, engine,
    passenger_capacities, cargo_capacities,
    initial_passenger_loads, initial_cargo_loads,
    station_map, matrix,
    deadline: SearchDeadline | None = None,
) -> GreedySeedResult:
    """确定性 regret-2 insertion：每步先给每个任务算「最佳插入」与「次佳插入
    （换一台车）」之差 = 后悔值，插入**后悔值最大**的那个任务。

    为什么不是纯 cheapest：逐步取全局最低成本的插入（greedy）会把"现在最便宜"
    的任务先塞进去，把后面真正难放的任务的位置挤掉 —— 实测这种种子质量显著低于
    OR-Tools 的并行 cheapest 首解（lab 真实路网台账 r_vrp/M、r_vrp/L 里程差
    14.8% / 16.8%，而这些实例基线只用几十毫秒）。regret 排序正是 VRP 里针对
    这个失效模式的标准修法，同时保留原「候选越少越先放」的产品优先级作为并列判据。

    返回 GreedySeedResult（从不返回 None）：
    - 全部任务放置 → complete=True
    - deadline 到期 / 存在任务无法放置 → complete=False（保留已放置的部分 routes，
      供调用方 repair/fallback 补齐），placed_count 记录已放置数。
    """
    routes = [t.copy() for t in templates]
    remaining = list(tasks)
    placed_ids: set[str] = set()
    cand_count = 0
    feas_count = 0

    def _partial() -> GreedySeedResult:
        return GreedySeedResult(
            routes=routes, complete=False,
            placed_count=len(placed_ids), total_tasks=len(tasks),
            candidate_count=cand_count, feasibility_check_count=feas_count,
        )

    while remaining:
        if deadline is not None and deadline.expired():
            return _partial()  # 超时：返回已放置的部分（非完整解）

        # 每个任务：最佳插入 + 次佳「不同车」插入
        per_task: list[tuple] = []   # (-regret, hardness, best_score, task, cand)
        for task in remaining:
            if deadline is not None and deadline.expired():
                return _partial()
            candidates = generate_insertion_candidates(
                task,
                routes,
                tasks_by_id,
                engine,
                station_map,
                matrix,
                passenger_capacities,
                cargo_capacities,
                initial_passenger_loads,
                initial_cargo_loads,
                candidate_size=10000,  # 不截断，确定性取全局最优
                deadline=deadline,
            )
            cand_count += len(candidates)
            feas_count += min(len(candidates), 64)  # 近似：pool 上限 64 次全量检查
            if not candidates:
                continue
            scored = sorted(candidates, key=_insertion_score)
            best = scored[0]
            best_score = _insertion_score(best)
            second = next(
                (c for c in scored[1:] if c.vehicle_index != best.vehicle_index), None
            )
            if second is None:
                # 只有台车能放 → 没有备选，等于最难，优先放
                regret = float("inf")
            else:
                regret = _insertion_scalar(second) - _insertion_scalar(best)
            per_task.append((-regret, len(candidates), best_score, task, best))

        if not per_task:
            return _partial()  # 存在剩余任务无法放置 → 非全量解

        # 后悔值大者优先；并列时难任务（候选少）优先；再并列取最便宜的插入位置
        per_task.sort(key=lambda x: (x[0], x[1], x[2]))
        _, _, _, task, cand = per_task[0]
        routes[cand.vehicle_index].insert_task(
            task, cand.pickup_index, cand.delivery_index
        )
        placed_ids.add(task.task_id)
        remaining.remove(task)

    return GreedySeedResult(
        routes=routes, complete=True,
        placed_count=len(tasks), total_tasks=len(tasks),
        candidate_count=cand_count, feasibility_check_count=feas_count,
    )


def _repair_unassigned(
    routes, tasks, tasks_by_id, engine,
    passenger_capacities, cargo_capacities,
    initial_passenger_loads, initial_cargo_loads,
    station_map, matrix,
    deadline: SearchDeadline | None = None,
) -> list[RouteGenome] | None:
    """把 ACO 构造中遗漏的任务贪心补插回去；仍插不回则返回 None。"""
    placed = {
        tid for r in routes for tid in r.placements
    }
    missing = [t for t in tasks if t.task_id not in placed]
    if not missing:
        return routes

    # 在现有 routes 上逐一补插（best feasible position each time）
    for task in missing:
        if deadline is not None and deadline.expired():
            return None
        candidates = generate_insertion_candidates(
            task,
            routes,
            tasks_by_id,
            engine,
            station_map,
            matrix,
            passenger_capacities,
            cargo_capacities,
            initial_passenger_loads,
            initial_cargo_loads,
            candidate_size=1,
            # 同上：这里也不接出口预算（d20 实测会改结果，理由见 `_complete_or_discard` 里那条注释）。
        )
        if not candidates:
            return None
        cand = candidates[0]
        routes[cand.vehicle_index].insert_task(
            task, cand.pickup_index, cand.delivery_index
        )
    return routes


# ─── 可行性 / 目标 ───────────────────────────────────────────


def _routes_feasible(
    routes, tasks_by_id, engine,
    passenger_capacities, cargo_capacities,
    initial_passenger_loads, initial_cargo_loads,
    station_map, matrix,
) -> bool:
    """全部车辆 RouteGenome 同时可行，且必须覆盖全部任务（部分解不算可行）。

    早期版本只校验单车约束，未校验"任务是否被落下"：局部搜索/ALNS 丢任务后的
    部分解会被当成"可行更优解"采纳，最后 _ensure_complete 又因搜索预算耗尽补不回来，
    于是整批返回 INCOMPLETE_SOLUTION（一键演示现场表现就是"算法判定无可行解"）。
    """
    if not _is_complete(routes, list(tasks_by_id.values())):
        return False
    for route in routes:
        if route.task_count() == 0:
            continue
        route_tasks = {
            e.task_id: tasks_by_id[e.task_id]
            for e in route.events
            if e.task_id and e.task_id in tasks_by_id
        }
        result = engine.check(
            route,
            route_tasks,
            passenger_capacities[route.vehicle_index],
            cargo_capacities[route.vehicle_index],
            initial_passenger_loads[route.vehicle_index],
            initial_cargo_loads[route.vehicle_index],
            station_map=station_map,
            matrix=matrix,
        )
        if not result.feasible:
            return False
    return True


def _missing_tasks(
    routes: list[RouteGenome], tasks: list[TaskBlock],
) -> list[TaskBlock]:
    """找出未被任何车辆路线覆盖的任务（覆盖完整性的必要不充分条件）。"""
    placed = {tid for r in routes for tid in r.placements}
    return [t for t in tasks if t.task_id not in placed]


def _is_complete(routes: list[RouteGenome], tasks: list[TaskBlock]) -> bool:
    """全部任务都已分配到某辆车。缺失任何任务都不是合法完整解。"""
    return not _missing_tasks(routes, tasks)


def _split_unservable(
    tasks: list[TaskBlock], templates: list[RouteGenome], station_map: dict,
    matrix: DistanceMatrix | None, penalty_km: float,
) -> tuple[list[TaskBlock], list[TaskBlock]]:
    """搜索前淘汰「无论如何都带不动」的任务（规格 §二.5 舍弃惩罚高但有限）。

    判据必须是**可靠下界**，否则会误杀本可服务的订单：
    带某任务点 p 必然要把车开到 p，而绕行增量 ≥ p 到线路所经任一点的最短距离，
    所以只有当 min_v dist(p, v) 已经超过舍弃惩罚时，才判定不可服务。
    反过来，若某任务只是暂时插不进（后续站位更近了就能插），这里不会淘汰它，
    留给输出阶段的 `_complete_or_discard` 按实际增量比价。
    """
    if penalty_km is None or penalty_km <= 0:
        return tasks, []
    # 只有「有运营骨架可偏离」的车才存在「怎么带都要绕穿全城」的订单：
    # 纯 VRP 车没有固定线路，某点远不远取决于路线怎么写，交给插入时刻按实际增量比价。
    if not any(t.skeleton for t in templates):
        return tasks, []
    others = [st for st in station_map.values()]
    if len(others) < 2:
        return tasks, []
    active: list[TaskBlock] = []
    discarded: list[TaskBlock] = []
    for task in tasks:
        # 乘客不参与舍弃：公交每站必停拉客，到站是既定动作，不产生「为一个人绕路」的代价；
        # 装不下只可能是座位容量硬约束（由 FeasibilityEngine / precheck 判无解），
        # 而不是「不值得绕」。项目也没有线上预约乘车，所以不存在「改约别处上车」的降级。
        if task.task_type == TaskType.PASSENGER:
            active.append(task)
            continue
        # 有骨架 = 存在「就近站交接」这条服务路径，远不等于不可服务。
        # 在这里就把远距离订单判死，会绕开本该生效的交接（实测把一个可交接的揽收单丢了），
        # 所以只让没有骨架可交接的纯 VRP 车走这条下界淘汰。
        if any(t.skeleton for t in templates):
            active.append(task)
            continue
        pts = [task.pickup_station, task.delivery_station]
        nearest = float("inf")
        for pid in pts:
            st = station_map.get(pid)
            if st is None:
                continue
            # 下界：带这一单至少要从「别的一个点」往返一趟 → 2 × 最近邻距离
            nearest = min(nearest, min(
                _segment(st, a, matrix)[0]
                for a in others if a.stationId != pid
            ))
        if math.isfinite(nearest) and 2.0 * nearest > penalty_km:
            discarded.append(task)
        else:
            active.append(task)
    return active, discarded


def _split_provably_late(
    tasks: list[TaskBlock], templates: list[RouteGenome], station_map: dict,
    matrix: DistanceMatrix | None, base_time_s: float,
) -> tuple[list[TaskBlock], list[TaskBlock]]:
    """筛出「最乐观也赶不上承诺时刻」的货运任务（可靠下界，绝不误杀可服务的单）。

    下界怎么取（每一项都朝「更容易按时」的方向偏）：
      - 取货点允许换成客户步行 ≤ `DETOUR_RELAXED_MAX_KM` 内的任一站点（就近交接本来就
        是产品既有形态），送货点同理；
      - 从各车发车站到候选点取**最小**行驶时间，两腿之间也取**最小**行驶时间
        （真实方案不可能比「各自最快」更快，所以这是合法下界）；
      - 等待 readyTime 是物理必需的，因此按 `max(到达时刻, readyTime)` 计；
      - 服务时间按 validators 的既有口径计入。
    乘客不参与：公交每站必停拉客，时效字段本来也只定义在货运契约上。
    """
    from ..validators import service_duration

    if not tasks or not station_map:
        return [], tasks
    stations = list(station_map.values())
    depots = sorted({t.depot_station for t in templates if t.depot_station in station_map})
    if not depots:
        return [], tasks
    depot_objs = [station_map[d] for d in depots]

    def _near(pid: str) -> list:
        origin = station_map.get(pid)
        if origin is None:
            return depot_objs
        out = [origin]
        for s in stations:
            if s.stationId == origin.stationId:
                continue
            if _segment(origin, s, matrix)[0] <= DETOUR_RELAXED_MAX_KM + 1e-6:
                out.append(s)
        return out

    late: list[TaskBlock] = []
    keep: list[TaskBlock] = []
    for task in tasks:
        if task.task_type == TaskType.PASSENGER or (
            task.pickup_deadline_s is None
            and task.delivery_deadline_s is None
            and task.ready_time_s is None
        ):
            keep.append(task)
            continue
        p_cand = _near(task.pickup_station)
        # 揽收完成的最早时刻
        arrive_p = min(
            min(_segment(d, p, matrix)[1] for p in p_cand) for d in depot_objs
        )
        ready_wait = (
            max(0.0, task.ready_time_s - base_time_s) if task.ready_time_s is not None else 0.0
        )
        pickup_finish = base_time_s + max(arrive_p, ready_wait) \
            + service_duration(StopAction.PICKUP)
        miss = (
            task.pickup_deadline_s is not None
            and pickup_finish > task.pickup_deadline_s + 1e-6
        )
        if not miss and task.delivery_deadline_s is not None:
            d_cand = _near(task.delivery_station)
            leg = min(
                _segment(p, d, matrix)[1] for p in p_cand for d in d_cand
            ) if task.delivery_station != task.pickup_station else 0.0
            deliver_finish = pickup_finish + leg + service_duration(StopAction.DELIVER)
            miss = deliver_finish > task.delivery_deadline_s + 1e-6
        (late if miss else keep).append(task)
    return late, keep


def _split_capacity_overflow(
    tasks: list[TaskBlock], cargo_capacities: dict[int, int],
) -> tuple[list[TaskBlock], list[TaskBlock]]:
    """总舱位装不下整批货运时，按规范序留下能装的、其余进舍弃通道。

    口径与 `FeasibilityEngine._check_cargo_capacity` 一致：每车出程派送累计
    （CargoOut）与返程揽收累计（CargoIn）都 ≤ cargoCapacity，
    所以 Σ需求 > Σ舱位 是「必然带不下」的可靠判据 —— 不是「暂时插不进」。
    留下谁由 `_canonical_order`（priority 降序 → task_id）决定，
    因此稀缺舱位先给高优先级单，被舍弃的自然是低优先级那些。
    """
    total_cap = sum(cargo_capacities.values())
    if not tasks or total_cap <= 0:
        return [], tasks

    def _legs(t: TaskBlock) -> tuple[int, int]:
        d = t.size if t.task_type in (TaskType.DELIVERY, TaskType.SHIPMENT) else 0
        p = t.size if t.task_type in (TaskType.PICKUP, TaskType.SHIPMENT) else 0
        if t.task_type == TaskType.DELIVERY and t.cargo_source == CargoSource.PRELOADED:
            d = 0   # 预装件由 initialCargoLoad 承载，不占本批出程舱位（同 _precheck 口径）
        return d, p

    if max(sum(_legs(t)[0] for t in tasks), sum(_legs(t)[1] for t in tasks)) <= total_cap:
        return [], tasks

    kept: list[TaskBlock] = []
    shed: list[TaskBlock] = []
    out_sum = in_sum = 0
    for task in sorted(tasks, key=_canonical_order):
        d, p = _legs(task)
        if max(out_sum + d, in_sum + p) > total_cap:
            shed.append(task)
            continue
        out_sum += d
        in_sum += p
        kept.append(task)
    return shed, kept


def _shed_peak_overflow(
    kept: list[TaskBlock], request: PlanRequest, total_cap: int,
) -> tuple[list[TaskBlock], list[TaskBlock]]:
    """按项目既有的峰值口径（`_estimate_peak_cargo_load`）逐次舍弃带不动的货运单。

    为什么还要这一层：`_check_cargo_capacity` 按方向分别封顶
    （CargoOut 出程累计 ≤ 舱、CargoIn 返程累计 ≤ 舱，与 OR-Tools 同口径），
    所以「发车时舱里已有 8 件、再揽收 3 件、舱容 10」这种**净载荷**超限
    在方向口径下是合法的 —— 拦住它的从来是 `_precheck` 的峰值预检。
    现在不再用它把整批（连同乘客）判无解，而是拿同一个函数当舍弃判据：
    只削到峰值口径不再超限为止，削不动了就交回原逻辑。
    受害者按 `_canonical_order` 从后往前取，因此先掉的是低优先级货运单，乘客绝不在内。
    """
    def _peak_of(ids: set[str]) -> int:
        probe = request.model_copy(update={
            "orders": [o for o in request.orders if o.orderId in ids],
            "shipments": [s for s in request.shipments if s.shipmentId in ids],
        })
        return _estimate_peak_cargo_load(probe)

    order_ids = {oid for t in kept for oid in t.order_ids}
    if _peak_of(order_ids) <= total_cap:
        return [], kept

    kept = list(kept)
    shed: list[TaskBlock] = []
    while kept:
        freight = [
            t for t in kept
            if t.task_type in (TaskType.DELIVERY, TaskType.PICKUP, TaskType.SHIPMENT)
        ]
        if not freight:
            break   # 只剩乘客：不可舍弃，交回原有判定
        victim = max(freight, key=_canonical_order)
        trial = [t for t in kept if t.task_id != victim.task_id]
        ids = {oid for t in trial for oid in t.order_ids}
        if _peak_of(ids) <= total_cap:
            return [victim] + shed, trial
        # 削掉这一件还不够：继续削（每次都以「峰值口径」为唯一依据，不另设阈值）
        kept = trial
        shed.append(victim)
    return shed, kept


def _handover_variant(
    task: TaskBlock, routes: list[RouteGenome], station_map: dict,
    matrix: DistanceMatrix | None,
) -> tuple[TaskBlock | None, int | None]:
    """把订单改写成「就近骨架站交接」形态：取货点/送货点各自换到最近的骨架站。

    这是超阈值订单的**首选**处置，排在舍弃之前：客户走到最近公交站是产品既有形态
    （快递进村在站点交接），比整单放弃的服务率高得多。
    配对运单两条腿不得塌到同一站（否则货从未被运过却报称已服务）→ 返回 None 交给舍弃。
    """
    skeleton_by_index = {
        r.vehicle_index: list(r.skeleton) for r in routes if r.skeleton
    }
    if not skeleton_by_index:
        return None, None
    # 交接的成立前提：客户走到交接站的距离 ≤ 车本来可以绕的上限（3km）。
    # 「让客户走 220 公里到站取件」不是服务，那种订单只能进多段联运。
    walk_ceiling = DETOUR_RELAXED_MAX_KM
    best: tuple[TaskBlock, int, float] | None = None
    for vi, skel in skeleton_by_index.items():
        pk_cands = _skeleton_candidates(skel, task.pickup_station, station_map, matrix)
        dl_cands = _skeleton_candidates(skel, task.delivery_station, station_map, matrix)
        if not pk_cands or not dl_cands:
            continue
        pu = station_map.get(task.pickup_station)
        dl0 = station_map.get(task.delivery_station)
        if not (pu and dl0):
            continue
        paired = task.task_type in (TaskType.PASSENGER, TaskType.SHIPMENT)
        # 两腿各自按距离依次试，跳过会塌到同一站的组合（货没被运过却报称已服务）
        pk = dl = walk = None
        if not paired:
            # 单腿任务（独立揽收 / 独立派送）的取点与送点是**同一个点位**
            # （TaskBlock 里 pickup_station == delivery_station），
            # 按两腿求和等于把客户的步行上限误砍一半：
            # 实测距站 2.6km 的揽收单（本应 3km 内可交接）在这里被判「无可交接位」，
            # 整单让给多段联运 —— 服务率就是这么静默漏掉的。
            for c_pk, k1 in pk_cands:
                if c_pk == task.pickup_station:
                    continue
                if k1 > walk_ceiling:
                    continue
                pk = dl = c_pk
                walk = k1
                break
        else:
            for c_pk, k1 in pk_cands:
                for c_dl, k2 in dl_cands:
                    if c_pk == c_dl:
                        continue
                    if k1 + k2 > walk_ceiling:
                        continue
                    pk, dl, walk = c_pk, c_dl, k1 + k2
                    break
                if pk is not None:
                    break
        if pk is None or dl is None or walk is None:
            continue
        a = station_map.get(pk)
        b = station_map.get(dl)
        if not (a and b):
            continue
        if best is None or walk < best[2]:
            variant = copy.copy(task)
            variant.pickup_station = pk
            variant.delivery_station = dl
            best = (variant, vi, walk)
    if best is None:
        return None, None
    return best[0], best[1]


def _complete_or_discard(
    routes, tasks, tasks_by_id, engine,
    passenger_capacities, cargo_capacities,
    initial_passenger_loads, initial_cargo_loads,
    station_map, matrix,
    deadline: SearchDeadline | None = None,
    discard_penalty_km: float | None = None,
) -> tuple[list[RouteGenome] | None, list[TaskBlock]]:
    """补全与舍弃的取舍点（规格 §二.5：订单可舍弃，惩罚高但有限）。

    早先这里是「补不回就判整批无解」：为了一单偏远件把整车绕几十公里，
    或者干脆整批 infeasible，两种都不是产品要的行为。现在按同一把尺子比价：
    插进去要多绕的公里 > 舍弃惩罚(km 等价) → 舍弃，交回后端多段联运。

    返回 (routes, discarded)：routes 为 None 表示连不舍弃都救不回来（不该发生，
    因为舍弃总能成立），调用方据此决定是出方案还是判无解。
    """
    placed = {tid for r in routes for tid in r.placements}
    missing = [t for t in tasks if t.task_id not in placed]
    if not missing:
        return routes, [], {}

    routes = [r.copy() for r in routes]
    discarded: list[TaskBlock] = []
    handovers: dict[str, dict] = {}
    penalty = (float(discard_penalty_km) if discard_penalty_km is not None else None)
    # 活任务表：每舍弃一单就把它从表里摘掉。
    # 原来这里的交接判定拿**全量表**跑 `_routes_feasible`，而后者要求覆盖全部任务，
    # 于是只要同时缺 ≥2 单，每一次「就近站交接」都会被判不可行而回滚 ——
    # 第②级改站在出口几乎完全不生效（实测 mean_walk_km=0.0、relocation 0.0022），
    # 本该「客户走两公里到站取件」的单子被整单判给了多段联运。
    live = dict(tasks_by_id)
    for task in sorted(missing, key=_canonical_order):
        if deadline is not None and deadline.expired():
            # 预算耗尽：宁可舍弃这一单，也不交一个绕远的半成品方案。
            # 但乘客不在"可以舍弃"的集合里（业务底线）：把没安置的乘客写进
            # unassignedOrderIds 会让后端按契约直接拒收整个方案
            # （`AlgorithmResultValidator` 对乘客舍弃抛错），那比判这一批补不全更糟。
            # 所以这里与下面 `must_serve` 同一处理：交回 None，让调用方走
            # "回退到最后一个完整解 / 如实判 INCOMPLETE_SOLUTION"那条诚实出口。
            if task.task_type == TaskType.PASSENGER:
                return None, [], {}
            discarded.append(task)
            live.pop(task.task_id, None)
            continue
        candidates = generate_insertion_candidates(
            task,
            routes,
            live,
            engine,
            station_map,
            matrix,
            passenger_capacities,
            cargo_capacities,
            initial_passenger_loads,
            initial_cargo_loads,
            candidate_size=1,
            # 有意**不**把出口预算传进这里 —— 试过，实测变差（d20 对照 d19，2026-10-12）：
            # 传进去之后 41 行结果被改（按业务键 25 差 / 16 好），penalized gap 4.641→9.796、
            # 总里程 1324.65→1375.59km，而且差的那几行多在字典序更高的一维上退步
            # （r_village-M-002 1 车→2 车、r_line-L-005 与 r_multidepot-L-000 2 车→3 车），
            # haco 墙钟总量 812.9→845.6s（跨轮墙钟本身带负载噪声，只当旁证，不当判据）。
            # 代码事实（可复核）：这三处扫描原本不接 `deadline`，而 construction.py 的到期检查
            # 是 `_check_count % 32 == 0 and deadline is not None and deadline.expired()` ⇒
            # 没有 deadline 就永不提前收，所以 `OUTPUT_COMPLETION_BUDGET_SECONDS` 只在单与单之间生效。
            # 要收这笔账，先得有"同样预算下不改结果"的更便宜扫描（任务 #30），不是先接闸。
        )
        must_serve = task.task_type == TaskType.PASSENGER   # 乘客不可舍弃（业务底线）
        best = candidates[0] if candidates else None
        extra_km = float(best.delta_distance or 0.0) if best is not None else float("inf")
        over = best is None or (penalty is not None and extra_km > penalty)
        if over:
            # 先试「就近骨架站交接」，再考虑舍弃：客户走到最近公交站是产品既有形态
            # （快递在站点交接），服务率远高于整单放弃；配对任务两腿不得同站。
            variant, vi = _handover_variant(task, routes, station_map, matrix)
            placed_by_handover = False
            if variant is not None and vi is not None:
                v_cands = generate_insertion_candidates(
                    variant, routes, live, engine, station_map, matrix,
                    passenger_capacities, cargo_capacities,
                    initial_passenger_loads, initial_cargo_loads,
                    candidate_size=1,
                )
                if v_cands:
                    saved_route = routes[vi].copy()
                    try:
                        routes[vi].insert_task(
                            variant, v_cands[0].pickup_index, v_cands[0].delivery_index
                        )
                        # 只核「车上现有的 + 这一单」：其余仍在 missing 队列里的任务
                        # 是「还没处理」，不是「被丢下」。拿全量表（含未处理的其它缺单）
                        # 跑覆盖性检查会让每一次交接都被判不可行而回滚。
                        probe_ids = {tid for rt in routes for tid in rt.placements}
                        probe_ids.add(variant.task_id)
                        probe_table = {
                            tid: live[tid] for tid in probe_ids if tid in live
                        }
                        placed_by_handover = _routes_feasible(
                            routes, probe_table, engine,
                            passenger_capacities, cargo_capacities,
                            initial_passenger_loads, initial_cargo_loads,
                            station_map, matrix,
                        )
                    except (ValueError, IndexError):
                        placed_by_handover = False
                    if not placed_by_handover:
                        # 必须回滚：insert_task 已经改了这条路线，
                        # 不回滚就变成「事件留在车上被服务了，清单里又宣告这单舍弃」——
                        # 既虚报服务率，又让后续任何按任务表查事件的检查 KeyError。
                        routes[vi] = saved_route
                if placed_by_handover:
                    for oid in task.order_ids:
                        handovers[oid] = {
                            "service_point": task.pickup_station,
                            "station": variant.pickup_station,
                            "detour_km": (round(extra_km, 3)
                                          if extra_km != float("inf") else None),
                            "reason": (_road_access_reason(task)
                                       or "DETOUR_OVER_THRESHOLD"),
                        }
                    continue
            if must_serve:
                return None, [], {}   # 宁可整批判不可补全，也不把乘客悄悄留下
            discarded.append(task)
            live.pop(task.task_id, None)
            continue
        routes[best.vehicle_index].insert_task(
            task, best.pickup_index, best.delivery_index
        )
    return routes, discarded, handovers


def _ensure_complete(
    routes, tasks, tasks_by_id, engine,
    passenger_capacities, cargo_capacities,
    initial_passenger_loads, initial_cargo_loads,
    station_map, matrix,
    deadline: SearchDeadline | None = None,
) -> list[RouteGenome] | None:
    """确保解覆盖全部任务；有遗漏则贪心补插，插不回返回 None（该解不可用）。"""
    if _is_complete(routes, tasks):
        return routes
    return _repair_unassigned(
        routes, tasks, tasks_by_id, engine,
        passenger_capacities, cargo_capacities,
        initial_passenger_loads, initial_cargo_loads,
        station_map, matrix, deadline,
    )


def _consolidate_into(
    routes, target_used, tasks, tasks_by_id, engine,
    station_map, matrix,
    passenger_capacities, cargo_capacities,
    initial_passenger_loads, initial_cargo_loads,
    deadline: SearchDeadline | None = None,
) -> list[RouteGenome] | None:
    """从**当前解**出发把用车数收到 target_used：每次撤掉装载最少的那台在用車，
    把它的任务插回其余车（修复阶段看不到被撤的那台），一台一台收。

    为什么不能只靠从零重建：`_compact_solution` 原本对每个 m 用
    `_cheapest_insertion(canonical, templates[:m])` 重打一遍，而那套确定性贪心比
    完整搜索（ACO+LS+ALNS）弱得多 —— 实测 V=6（同一条线的两台复制车各自出车，
    76.401km/4 台，而 57.736km/3 台是同一道题的合法解）就是"证不出 m=3 可行"而退回 4 台的；
    同题 V=12 收到 3 台靠的是 ALNS 按 rng 抽样**碰巧**轮到该撤的那台，不是机制在工作。
    这里保留已经打包好的部分、只搬多余的那台，成功率高得多也便宜得多，
    并且把"碰运气"换成"必然试过"。

    只在与主循环无关的固定局部随机源上取值（与 `_compact_solution` 同样的确定性前提）。
    """
    working = [r.copy() for r in routes]
    rng = random.Random(20260903)
    closed_any = 0
    used_before = sum(1 for r in working if r.task_count() > 0)
    while used_before > target_used:
        if deadline is not None and deadline.expired():
            break
        loaded = sorted(
            (r for r in working if r.task_count() > 0),
            key=lambda r: (r.task_count(), r.vehicle_index),
        )
        if len(loaded) < 2:
            break
        victim_index = loaded[0].vehicle_index
        removed = [tasks_by_id[tid] for tid in list(loaded[0].placements)
                   if tid in tasks_by_id]
        if not removed:
            break

        # 只允许搬进**当前在用**的那几台（撤车的语义，不是挪车）。
        # 真正兜住失效模式的是下面"无净进展就停"那道守卫：允许往空候选车插时，
        # 每步都被判成功而用车数一动不动（实测空转到压实预算超时），守卫会立刻停手。
        survivors = [r.copy() for r in working if r.task_count() > 0
                     and r.vehicle_index != victim_index]
        # 搬运用带一层 ejection 的修复：贪心插不进时先挪开同车上的一件、放进来的再塞回去。
        # 只用贪心会「差一件就判撤不掉」：实测 r_village-M-002 125k 臂 4 件里 3 件落位、
        # S:T1 插不进 → 停在 2 车/38.604km，而挪开 K:K3 就能拿到完整可行的 1 车/36.618km
        # （任务 #27；代价由 max_attempts + deadline 两道闸挡住，见 repair_with_ejection）。
        repaired = repair_with_ejection(
            survivors, removed, tasks_by_id, engine, station_map, matrix,
            passenger_capacities, cargo_capacities,
            initial_passenger_loads, initial_cargo_loads, rng,
            deadline=deadline,
        )
        # 被撤的那台清空留在表里；**本来空着的候选车必须原样带过去** ——
        # 只回传"在用的那几台"会让出口阶段（补插暂摘单）可用车变少，是一种静默的服务收窄
        merged = {r.vehicle_index: r for r in repaired}
        candidate_routes = []
        for r in working:
            if r.vehicle_index in merged:
                candidate_routes.append(merged[r.vehicle_index])
            elif r.vehicle_index == victim_index:
                cleared = r.copy()
                for tid in list(cleared.placements):
                    cleared.remove_task(tid)
                candidate_routes.append(cleared)
            else:
                candidate_routes.append(r)
        candidate = sorted(candidate_routes, key=lambda r: r.vehicle_index)

        if not _is_complete(candidate, tasks):
            logger.debug("consolidate: victim=%s 插不回（不完整）", victim_index)
            break
        if not _routes_feasible(
            candidate, tasks_by_id, engine,
            passenger_capacities, cargo_capacities,
            initial_passenger_loads, initial_cargo_loads, station_map, matrix,
        ):
            logger.debug("consolidate: victim=%s 插回了但引擎复核不可行", victim_index)
            break
        used_after = sum(1 for r in candidate if r.task_count() > 0)
        if used_after >= used_before:
            # 用车数没降 = 任务被搬进了本来空着的候选车，这是在挪车不是在撤车。
            # 放任它继续转会把压实预算整个烧光（实测过：交替撤同一对车几十次直到超时），
            # 所以"没有净进展就停"必须写在这里，不能靠调用方一定传 deadline。
            logger.debug("consolidate: victim=%s 无净进展（%d -> %d 台）",
                         victim_index, used_before, used_after)
            break
        closed_any += 1
        working = candidate
        used_before = used_after
    # 已经关掉一台就交回去（哪怕没到 target）：进步不能因为"没走到底"被整段丢掉
    return working if closed_any else None


def _compact_solution(
    routes, tasks, templates, tasks_by_id, engine,
    passenger_capacities, cargo_capacities,
    initial_passenger_loads, initial_cargo_loads,
    station_map, matrix, config, rng,
    deadline: SearchDeadline | None = None,
) -> list[RouteGenome]:
    """车辆数最小化 + 输出确定性重建。

    从 m=1 向上找最小的可行车辆数，用规范任务序(canonical)+固定局部随机源
    （与输入顺序、主循环 RNG 消耗都无关）重建该车数下目标最优的打包。
    效果：同批任务无论以什么顺序到达，最终都用车数一致、总里程一致。

    Objective 中 vehicle_count 优先级最高，所以取最小可行 m；同一 m 内取
    evaluate_route_states 最优的候选。
    """
    used_n = sum(1 for r in routes if r.task_count() > 0)
    if used_n <= 1:
        return routes

    canonical = sorted(tasks, key=_canonical_order)

    # 货物容量下界（合法必要条件，用于快速跳过明显装不下的 m）：
    # 每车 CargoOut/CargoIn 累计 ≤ 各自 cargoCapacity ⇒ m 车总容量须 ≥ 出/入总需求。
    deliveries_total = sum(
        t.size for t in tasks
        if t.task_type in (TaskType.DELIVERY, TaskType.SHIPMENT)
    )
    pickups_total = sum(
        t.size for t in tasks
        if t.task_type in (TaskType.PICKUP, TaskType.SHIPMENT)
    )

    # DISPATCH_CORE_V047 (section 25): compaction must never replace a better HACO solution.
    current_obj = evaluate_route_states(routes, tasks_by_id, station_map, matrix)

    for m in range(1, used_n + 1):
        if deadline is not None and deadline.expired():
            return routes  # 超时，返回当前最优解

        # 容量下界要按「m 台**最大**容量的和」，不是「索引前 m 台」。
        # 真实车队货舱是异质的（语料里 r_village-M-002 是 veh0=8 / veh1=12，
        # r_multidepot-L-001 是 8/4/12），按索引取会拿最小的那台去证 m=1 不可行，
        # 于是连与索引无关的 `_consolidate_into`（在**当前在用**的车之间撤一台）
        # 都被提前跳过 —— 实测 r_village-M-002 两臂因此都停在 2 车/38.604km，
        # 而同一个批次的合法解能到 1 车/34.758km（任务 #26，#6 违背的同源缺口）。
        cap_m = sum(sorted(cargo_capacities.values(), reverse=True)[:m])
        if deliveries_total > cap_m or pickups_total > cap_m:
            continue  # m 车总货仓容量不足，必不可能 → 跳过整轮尝试

        # 先试「保留已打包好的部分、只搬多余那台」（便宜且成功率高），再试从零重建。
        # m == used_n 时没有多余车可搬，直接走重建那条路在同一车数里挑更短的打包。
        if m < used_n:
            logger.debug("compact: 尝试收到 m=%d（当前在用 %d 台）", m, used_n)
            consolidated = _consolidate_into(
                routes, m, tasks, tasks_by_id, engine, station_map, matrix,
                passenger_capacities, cargo_capacities,
                initial_passenger_loads, initial_cargo_loads, deadline,
            )
            if consolidated is not None:
                cons_obj = evaluate_route_states(
                    consolidated, tasks_by_id, station_map, matrix
                )
                if not cons_obj > current_obj:
                    return consolidated

        # 确定性 greedy 重建（规范序）：主入口已把任务按 task_id 排序，
        # 因此压实结果与输入顺序无关；这里不再做昂贵 ACO 重试。
        cand = _cheapest_insertion(
            canonical, templates[:m], tasks_by_id, engine,
            passenger_capacities, cargo_capacities,
            initial_passenger_loads, initial_cargo_loads,
            station_map, matrix, deadline,
        )
        # compact 只接受完整解：部分 seed（complete=False）不得使用
        if cand.complete and cand.routes is not None and _is_complete(cand.routes, tasks) and _routes_feasible(
            cand.routes, tasks_by_id, engine,
            passenger_capacities, cargo_capacities,
            initial_passenger_loads, initial_cargo_loads,
            station_map, matrix,
        ):
            # Safe replacement guard: complete AND feasible AND
            # vehicle_count <= current AND ObjectiveVector <= current.
            cand_obj = evaluate_route_states(cand.routes, tasks_by_id, station_map, matrix)
            if cand_obj > current_obj:
                continue
            return cand.routes  # m 是从小到大，首个可行即贪心能找到的最小可行车数

    return routes


def _adapt_parameters(
    diversity: float | None, config: HacoConfig,
) -> tuple[float, float]:
    """按搜索多样性自适应 alpha/beta（结果真正传入 construction）。"""
    if diversity is None:
        return config.alpha, config.beta

    low = config.adaptive_diversity_low
    high = config.adaptive_diversity_high
    span = max(high - low, 1e-9)

    if diversity < low:
        # 过度收敛：减弱信息素、增强启发式，扩大搜索
        return config.alpha_min, config.beta_max
    if diversity > high:
        # 过度发散：增强信息素、收敛到强解
        return config.alpha_max, config.beta_min

    t = (diversity - low) / span
    alpha = config.alpha_min + t * (config.alpha_max - config.alpha_min)
    beta = config.beta_max - t * (config.beta_max - config.beta_min)
    return alpha, beta


# ─── 编码 / 预检 ─────────────────────────────────────────────


def _canonical_order(task: TaskBlock) -> tuple[int, str]:
    """确定性任务序：先按 priority 降序，再按 task_id 升序。

    与客户端传入顺序、RNG 消耗都无关（保持「同批任务必得同一解」的既有性质），
    同时让稀缺运力/时间余量优先分给高优先级单。契约未给 priority 时全部为 0，
    该键与旧的纯 task_id 序逐项等价 → 既有批次零行为漂移。
    """
    return (-int(task.priority or 0), task.task_id)


def _encode_tasks(request: PlanRequest) -> list[TaskBlock]:
    tasks = []
    for order in request.orders:
        econ = getattr(order, "economicValue", None)
        # 重量/体积：契约可选字段，未配置就是 None（不造假数据、不按件数换算）。
        # 不填进 TaskBlock 的话，FeasibilityEngine 里的载重/体积校验是永远走不到的死代码。
        wt = getattr(order, "weightKg", None)
        vol = getattr(order, "volumeM3", None)
        if order.orderType == OrderType.PASSENGER:
            tasks.append(TaskBlock(
                task_id=f"P:{order.orderId}", task_type=TaskType.PASSENGER,
                pickup_station=order.boardingStationId,
                delivery_station=order.alightingStationId,
                size=1, order_ids=[order.orderId],
                economic_value=econ,   # 乘客不占货舱，重量/体积字段不参与载客约束
            ))
        elif order.orderType == OrderType.DELIVERY:
            tasks.append(TaskBlock(
                task_id=f"D:{order.orderId}", task_type=TaskType.DELIVERY,
                pickup_station=order.stationId,
                delivery_station=order.stationId,
                size=order.itemCount, order_ids=[order.orderId],
                cargo_source=order.cargoSource,
                economic_value=econ,
                weight_kg=wt, volume_m3=vol,
                road_access=getattr(order, "roadAccess", None),
                # 派送单（场站→村）只有这一个 DELIVER 事件点，而判据正是挂在 DELIVER 事件上的
                # （feasibility `_check_timing` ②），所以送达截止有真锚点，直接接入。
                # readyTime 的锚点在早筛（`pickup_finish` 用同站到达时刻，leg=0）：
                # 件没打包好之前，车到了也送不出去。
                ready_time_s=_epoch(getattr(order, "readyTime", None)),
                pickup_deadline_s=_epoch(getattr(order, "pickupDeadline", None)),
                delivery_deadline_s=_epoch(getattr(order, "deliveryDeadline", None)),
                priority=int(getattr(order, "priority", 0) or 0),
            ))
        elif order.orderType == OrderType.PICKUP:
            tasks.append(TaskBlock(
                task_id=f"K:{order.orderId}", task_type=TaskType.PICKUP,
                pickup_station=order.stationId,
                delivery_station=order.stationId,
                size=order.itemCount, order_ids=[order.orderId],
                cargo_source=order.cargoSource,
                economic_value=econ,
                weight_kg=wt, volume_m3=vol,
                road_access=getattr(order, "roadAccess", None),
                # 揽收单（村→场站）的锚点是 PICKUP 事件：最早可取时刻要等、揽收截止要判。
                # deliveryDeadline **故意不接**：这条任务没有 DELIVER 事件（货是随车回场站才到位，
                # 而"回车场"事件不带 task_id），早筛里单节点任务的 leg 恒为 0
                # （`delivery_station == pickup_station`），接上就等于拿"村点揽完的时刻 + 一次派送
                # 服务时长"去比"送到场站截止"—— 既没算回程也没资格加服务时长，会凭空多舍弃。
                # 目前这一侧只有批窗（batchEnd）兜住；要真判，得给 RETURN 事件加锚点，
                # 那属于"实际到时刻回流"那一轮（配套 #34），不在本次口径内。
                ready_time_s=_epoch(getattr(order, "readyTime", None)),
                pickup_deadline_s=_epoch(getattr(order, "pickupDeadline", None)),
                priority=int(getattr(order, "priority", 0) or 0),
            ))
    for shipment in request.shipments:
        econ = getattr(shipment, "economicValue", None)
        wt = getattr(shipment, "weightKg", None)
        vol = getattr(shipment, "volumeM3", None)
        tasks.append(TaskBlock(
            task_id=f"S:{shipment.shipmentId}", task_type=TaskType.SHIPMENT,
            pickup_station=shipment.pickupStationId,
            delivery_station=shipment.deliveryStationId,
            size=shipment.quantity, order_ids=[shipment.shipmentId],
            economic_value=econ,
            weight_kg=wt, volume_m3=vol,
            # 逐单时效：不填就等于契约里那四个字段白写（迟到方案照样报可行）
            ready_time_s=_epoch(getattr(shipment, "readyTime", None)),
            pickup_deadline_s=_epoch(getattr(shipment, "pickupDeadline", None)),
            delivery_deadline_s=_epoch(getattr(shipment, "deliveryDeadline", None)),
            priority=int(getattr(shipment, "priority", 0) or 0),
            # 配对货运单同样要能带通行预检结论：生产侧村→村链路正是公交进不去的那一类
            road_access=getattr(shipment, "roadAccess", None),
        ))
    return tasks


def _epoch(dt) -> float | None:
    """datetime → epoch 秒；缺省仍是 None（不凭空造承诺时刻）。"""
    return None if dt is None else float(dt.timestamp())


def _precheck(request: PlanRequest) -> tuple[SolveOutcome | None, list[str]]:
    """规模/容量预检：返回 (要直接交回的结论, 需要带进正式求解的警告)。

    只有**真无解**才短路（乘客超座位、车辆配置错误、预装不足、批窗非法）；
    货运超总舱属于「舍弃一部分继续走」的情形，只登记警告、不短路。
    """
    pre_warnings: list[str] = []
    passengers = sum(
        1 for o in request.orders if o.orderType == OrderType.PASSENGER
    )
    deliveries = sum(
        o.itemCount for o in request.orders
        if o.orderType == OrderType.DELIVERY
        and o.cargoSource != CargoSource.PRELOADED
    )
    pickups = sum(
        o.itemCount for o in request.orders
        if o.orderType == OrderType.PICKUP
    )
    shipment_qty = sum(s.quantity for s in request.shipments)
    deliveries += shipment_qty
    pickups += shipment_qty

    if not request.orders and not request.shipments:
        return SolveOutcome(status="feasible"), pre_warnings

    for v in request.vehicles:
        if v.initialPassengerLoad >= v.passengerCapacity:
            return SolveOutcome(status="infeasible", reason_code="OVER_CAPACITY"), pre_warnings
        if v.initialCargoLoad > v.cargoCapacity:
            return SolveOutcome(status="infeasible", reason_code="OVER_CAPACITY"), pre_warnings

    total_pcap = sum(
        v.passengerCapacity - v.initialPassengerLoad
        for v in request.vehicles
    )
    if passengers > total_pcap:
        return SolveOutcome(status="infeasible", reason_code="OVER_CAPACITY"), pre_warnings

    total_ccap = sum(v.cargoCapacity for v in request.vehicles)
    # 货运超总舱**不再判整批无解**（规格 §二.5「订单可舍弃」+ 业务底线「乘客不可舍弃」）：
    # 以前 deliveries/pickups 一超总货仓就 OVER_CAPACITY 回绝，等于为一票货取消整班公交，
    # 乘客因此没车可坐 —— 而正确处置是装得下的照走、装不下的如实进多段联运。
    # 真正无解的只有：乘客超座位（上面已判）、车辆自身配置错误（initialCargoLoad 超舱）。
    freight_overflow = deliveries > total_ccap or pickups > total_ccap
    peak = _estimate_peak_cargo_load(request)   # 出程派送→返程揽收 timeline，不按总和误判
    if freight_overflow or peak > total_ccap:
        # 只带警告继续求解：超量部分由搜索按 priority 依次舍弃，
        # 出口用 unassignedOrderIds 如实申报（不是「拒绝整班」）。
        pre_warnings.append(
            f"CARGO_OVERFLOW_SHEDDING=deliveries {deliveries}/pickups {pickups}"
            f"/peak {peak} vs cap {total_ccap}"
        )

    total_preloaded_delivery = sum(
        o.itemCount for o in request.orders
        if o.orderType == OrderType.DELIVERY
        and o.cargoSource == CargoSource.PRELOADED
    )
    total_initial_cargo = sum(v.initialCargoLoad for v in request.vehicles)
    if total_preloaded_delivery > total_initial_cargo:
        return SolveOutcome(
            status="infeasible", reason_code="PRELOAD_INSUFFICIENT"
        ), pre_warnings

    window_seconds = int(
        request.batchEnd.timestamp() - request.batchStart.timestamp()
    )
    if window_seconds <= 0:
        return SolveOutcome(
            status="infeasible", reason_code="TIME_WINDOW_EXCEEDED"
        ), pre_warnings
    return None, pre_warnings



def _estimate_peak_cargo_load(request: PlanRequest) -> int:
    """峰值载重：出程派送在前、返程揽收在后（净载荷复用货仓）。"""
    peaks: list[int] = []
    for v in request.vehicles:
        stations = list(v.skeleton or [])
        load = v.initialCargoLoad
        peak = load
        evs: list[tuple[int, int]] = []

        def _sidx(sid: str | None) -> int:
            if not sid:
                return 10 ** 6
            try:
                return stations.index(sid)
            except ValueError:
                return 10 ** 5

        for o in request.orders:
            if o.orderType == OrderType.DELIVERY:
                if o.cargoSource == CargoSource.PRELOADED:
                    evs.append((0, +o.itemCount))  # 预装起点占用
                evs.append((_sidx(o.stationId), -o.itemCount))
            elif o.orderType == OrderType.PICKUP:
                evs.append((_sidx(o.stationId) + 10_000, +o.itemCount))
        for s in request.shipments:
            evs.append((_sidx(s.pickupStationId) * 2, +s.quantity))
            evs.append((_sidx(s.deliveryStationId) * 2 + 1, -s.quantity))
        evs.sort(key=lambda x: x[0])
        for _, delta in evs:
            load += delta
            peak = max(peak, load)
        peaks.append(peak)
    return max(peaks) if peaks else 0


# ─── RouteGenome → VehiclePlan ───────────────────────────────


# ─── 绕行软约束：超阈值改「最近骨架站交接」 ────────────────────
# 产品口径（docs/dispatch/DISPATCH_V047_REPORT.md §164）：批次全局求解里
# maxDetourDistanceKm 是**停靠级软决策**（accepted=False + serviceMode=NEAREST_STATION，
# 方案仍 feasible）；trip 级绕行硬预算属于动态调度层。
# 之前这个语义只在 construction 的 Stage A 预筛里存在，种子 / repair / 局部搜索 /
# ALNS / 合车全都不看它，而发射层又给每个货运停靠无条件贴 serviceMode=NEAREST_STATION
# —— 于是「交接」只是个标签，真实交接从未发生。这里统一在出口处执行。
DETOUR_RELAXED_MAX_KM = 3.0   # 闲置运力充足时可放宽到的上限（见 models.py 注释口径）


def _route_cargo_load(route: RouteGenome, tasks_by_id: dict) -> int:
    total = 0
    for tid in route.placements:
        task = tasks_by_id.get(tid)
        if task is None or task.task_type == TaskType.PASSENGER:
            continue
        total += max(1, int(task.size or 1))
    return total


def _detour_limit_for(route: RouteGenome, tasks_by_id: dict, request: PlanRequest,
                      config: HacoConfig, cargo_capacities: dict[int, int]) -> float:
    """该车当前可用的绕行上限：请求显式给值优先，否则按闲置运力自适应。

    自适应规则可解释、可复现：装得越满越收紧到 base，闲置越多越放宽到
    DETOUR_RELAXED_MAX_KM；单调、不依赖墙钟。
    """
    given = getattr(request.algorithmConfig, "maxDetourDistanceKm", None)
    base = float(getattr(config, "max_detour_km", 2.0) or 2.0)
    if given is not None:
        return float(given)
    cap = max(1, int(cargo_capacities.get(route.vehicle_index, 1) or 1))
    loaded = _route_cargo_load(route, tasks_by_id)
    idle = max(0.0, 1.0 - loaded / cap)
    ceiling = max(base, DETOUR_RELAXED_MAX_KM)
    return round(base + (ceiling - base) * idle, 3)


def _on_skeleton(route: RouteGenome, station_id: str) -> bool:
    """该点是否就是本车运营骨架上的站（骨架站每站必停，不构成绕行）。"""
    return bool(route.skeleton) and station_id in route.skeleton


# 通行预检原因码表：与 app/baseline/ortools_solver.py 的 ROAD_ACCESS_REASON 同一张表
# （两求解器必须给后端同一个原因码，否则 HYBRID 取更优解时前端要认两套）。
# 不直接 import baseline：那条链路会把 OR-Tools 依赖拖进 HACO 路径。
ROAD_ACCESS_REASON = {
    "BLOCKED": "ROAD_NOT_BUS_ACCESSIBLE",        # 只能经巷道/窄路，公交车进不去
    "NEAR_STATION": "NEAR_STATION_WITHIN_100M",  # 站点 ≤100m，顺路在站交接即可
}


def _road_access_reason(task: TaskBlock | None) -> str | None:
    """该任务的点位是否被业务侧判定为「公交进不去 / 不必进」。

    未做预检（None）= 不干预；给了未知取值也按「进不去」处理 ——
    保守方向是「不把车开进可疑的巷子」，而不是替业务侧猜一个乐观解释。
    """
    ra = getattr(task, "road_access", None)
    if not ra:
        return None
    return ROAD_ACCESS_REASON.get(ra, "ROAD_NOT_BUS_ACCESSIBLE")


def _event_detour_km(route: RouteGenome, idx: int, station_map: dict,
                     matrix: DistanceMatrix | None) -> float:
    """该事件相对「不在此站停靠」多绕了多少公里。骨架站恒为 0。"""
    if idx <= 0 or idx >= len(route.events):
        return 0.0
    if _on_skeleton(route, route.events[idx].station_id):
        return 0.0
    prev_st = station_map.get(route.events[idx - 1].station_id)
    nxt_st = station_map.get(route.events[idx + 1].station_id)
    cur_st = station_map.get(route.events[idx].station_id)
    if not (prev_st and nxt_st and cur_st):
        return 0.0
    return max(0.0, _segment(prev_st, cur_st, matrix)[0]
               + _segment(cur_st, nxt_st, matrix)[0]
               - _segment(prev_st, nxt_st, matrix)[0])


def _skeleton_candidates(skeleton: list[str], from_station_id: str, station_map: dict,
                         matrix: DistanceMatrix | None) -> list[tuple[str, float]]:
    """骨架停靠站按真实路段距离由近到远排序；源点缺失时返回空表。

    必须返回**整张有序候选表**而不是只给最近的一个：配对运单的另一条腿常常就落在
    最近站上，此时只试一个站就会把整单判成「无法交接」而舍弃 —— 换成次近的骨架站
    其实既保住运输、又省掉绕行（实测 case8 就是这样被误舍的）。
    """
    src = station_map.get(from_station_id)
    if src is None:
        return []
    out: list[tuple[str, float]] = []
    for sid in skeleton:
        st = station_map.get(sid)
        if st is None:
            continue
        out.append((sid, _segment(src, st, matrix)[0]))
    out.sort(key=lambda x: (x[1], x[0]))
    return out


def _nearest_skeleton(skeleton: list[str], from_station_id: str, station_map: dict,
                      matrix: DistanceMatrix | None) -> str | None:
    cands = _skeleton_candidates(skeleton, from_station_id, station_map, matrix)
    return cands[0][0] if cands else None


def _soften_over_detour(
    routes: list[RouteGenome], tasks_by_id: dict, engine: FeasibilityEngine,
    station_map: dict, matrix: DistanceMatrix | None, request: PlanRequest,
    config: HacoConfig, passenger_capacities: dict[int, int],
    cargo_capacities: dict[int, int], initial_passenger_loads: dict[int, int],
    initial_cargo_loads: dict[int, int],
) -> tuple[dict[str, dict], list[TaskBlock], dict]:
    """把超阈值货运停靠改成最近骨架站交接；返回 (order_id → 交接信息, 被舍弃任务, 计数)。

    每一次改写都要过 FeasibilityEngine 复核，不过就回滚 —— 软化的前提是
    绝不为了「看起来合规」交出损坏的方案。

    三级政策（规格：硬约束转软决策）：
      ① 绕行 ≤ 阈值 → 正常在车服务；
      ② 超阈值但客户走到最近骨架站 ≤ 放宽上限 → 站点交接（服务率不降）；
      ③ 连交接都不合理（客户点太远 / 两腿会塌到同站）→ 舍弃该货运单，
         交回后端多段联运；乘客绝不走到这一步（由 ①② 的 must_serve 保护）。
    """
    handovers: dict[str, dict] = {}
    dropped: list[TaskBlock] = []
    stats = {"drop_blocked": 0, "drop_ok": 0, "no_candidate": 0, "no_task": 0,
             "rewrite_blocked": 0}
    # 摘单之后必须用「活任务表」做校验：`_routes_feasible` 会检查覆盖性，
    # 继续拿全量 tasks_by_id 会让任何一次摘单之后的改写全部被判不可行而回滚
    # （实测就是它把 K1 的就近站交接挡掉的）。
    live_tasks = dict(tasks_by_id)

    def _drop_task(rt: RouteGenome, task_id: str) -> bool:
        """从路线上摘掉一个货运任务；摘完整体仍可行才生效，否则回滚。"""
        if not task_id:
            return False
        saved = rt.copy()
        probe_tasks = {k: v for k, v in live_tasks.items() if k != task_id}
        rt.remove_task(task_id)
        if _routes_feasible(
            routes, probe_tasks, engine,
            passenger_capacities, cargo_capacities,
            initial_passenger_loads, initial_cargo_loads,
            station_map, matrix,
        ):
            return True
        rt.events = saved.events
        rt.placements = saved.placements
        return False

    for _round in range(8):
        changed = False
        for route in routes:
            if not route.skeleton:
                continue   # 纯 VRP 车没有运营路线，不存在「偏离路线」语义
            limit = _detour_limit_for(route, tasks_by_id, request, config, cargo_capacities)
            for idx, ev in enumerate(route.events):
                if ev.event_type not in (EventType.PICKUP, EventType.DELIVER) or not ev.order_id:
                    continue
                # 通行预检排在绕行预算前面：公交根本进不去的点位，绕得多便宜都不该停。
                # 这与「绕行超阈值」是两种成因（去不了 vs 不划算），处置同一套三级政策。
                access_reason = _road_access_reason(live_tasks.get(ev.task_id or ""))
                if access_reason is not None and _on_skeleton(route, ev.station_id):
                    continue   # 数据自相矛盾（骨架站本就是公交站）：没什么可改的
                det = _event_detour_km(route, idx, station_map, matrix)
                if access_reason is None and det <= limit:
                    continue
                point_st = station_map.get(ev.station_id)
                other_station = next(
                    (o.station_id for j, o in enumerate(route.events)
                     if j != idx and o.task_id == ev.task_id), None
                )
                # 按距离依次试候选站：跳过原点本身、会塌腿的站、以及步行超上限的站
                # （让客户走 220 公里到站取件不是服务）。
                target, walk_km = None, float("inf")
                for sid, km in _skeleton_candidates(
                    route.skeleton, ev.station_id, station_map, matrix
                ):
                    if sid == ev.station_id:
                        continue
                    if other_station is not None and sid == other_station:
                        continue
                    if km > DETOUR_RELAXED_MAX_KM + 1e-6:
                        continue
                    target, walk_km = sid, km
                    break
                if target is None:
                    stats["no_candidate"] += 1
                handover_ok = target is not None
                if not handover_ok:
                    task = live_tasks.get(ev.task_id or "")
                    if task is None:
                        stats["no_task"] += 1   # 计数不能省：静默 continue 会让「没尝试」看起来像「试过且挡回」
                        continue
                    if task.task_type == TaskType.PASSENGER:
                        continue   # 乘客不可舍弃（业务底线）
                    if _drop_task(route, ev.task_id):
                        dropped.append(task)
                        live_tasks.pop(ev.task_id, None)
                        stats["drop_ok"] += 1
                        changed = True
                        # 摘除会移位下标：只重扫**本条路线**，其它路线本轮继续处理
                        break
                    stats["drop_blocked"] += 1
                    continue
                original = route.events[idx]
                route.events[idx] = RouteEvent(
                    station_id=target, event_type=ev.event_type, task_id=ev.task_id,
                    order_id=ev.order_id, task_type=ev.task_type,
                )
                if not _routes_feasible(
                    routes, live_tasks, engine,
                    passenger_capacities, cargo_capacities,
                    initial_passenger_loads, initial_cargo_loads,
                    station_map, matrix,
                ):
                    route.events[idx] = original
                    stats["rewrite_blocked"] += 1
                    continue
                handovers[ev.order_id] = {"service_point": ev.station_id,
                                          "station": target,
                                          "detour_km": round(det, 3),
                                          "reason": access_reason or "DETOUR_OVER_THRESHOLD"}
                changed = True
                break       # 改写会移位下标，重扫本条路线
        # 改写/摘除会挪动相邻停靠的前后关系，从而改变**其它**货运停靠的绕行距离，
        # 所以必须迭代到不动点，否则「已交接」之后又冒出超阈值的在车服务停靠。
        if not changed:
            break
    stats["_last_round_changed"] = changed
    return handovers, dropped, stats


def _region_spans(route: RouteGenome) -> list[tuple[str, str]]:
    """骨架切出的地理区间：R_0=(发车点,s1)，R_i=(s_i,s_{i+1})，最后一段到收班点。"""
    pts = [route.depot_station] + list(route.skeleton) + [route.end_station]
    return [(pts[i], pts[i + 1]) for i in range(len(pts) - 1)]


def _geo_candidates(point_id: str, spans: list[tuple[str, str]], station_map: dict,
                   matrix: DistanceMatrix | None) -> tuple[int, ...]:
    """点位地理上贴近哪几段行程（规格 §一 geo_candidates(p)）。

    判据用真实路段口径：从区间两端绕到该点的额外里程
    excess = d(a,p) + d(p,b) - d(a,b) 足够小，就认为 p 落在这段走廊附近。
    没有一段达标时退回 excess 最小的一段 —— 候选集永远非空，
    这样「地理上属于 R12、执行归到 R23」才是可表达的（规格 §二.2）。
    """
    if not spans or point_id not in station_map:
        return ()
    p = station_map[point_id]
    excess: list[float] = []
    for a_id, b_id in spans:
        a, b = station_map.get(a_id), station_map.get(b_id)
        if a is None or b is None:
            excess.append(float("inf"))
            continue
        excess.append(
            _segment(a, p, matrix)[0] + _segment(p, b, matrix)[0]
            - _segment(a, b, matrix)[0]
        )
    finite = [e for e in excess if math.isfinite(e)]
    if not finite:
        return ()
    tol = max(0.5, 0.25 * min(finite))   # 贴着走廊就算候选（0.5km 起底）
    cand = tuple(i for i, e in enumerate(excess) if e <= tol)
    return cand or (min(range(len(excess)), key=lambda i: excess[i]),)


def _routes_to_vehicle_plans(
    routes: list[RouteGenome],
    request: PlanRequest,
    station_map: dict,
    matrix: DistanceMatrix | None,
    handovers: dict[str, dict] | None = None,
) -> list[VehiclePlan]:
    """按 RouteGenome.events 逐事件展开成 RouteStop。

    货运 PICKUP/DELIVER 经停按"插入该站相对前后站"计算真实 detour：
      detourDistance = (prev→cargo + cargo→next) - (prev→next) ≥ 0
      detourDuration = (prev→cargo_dur + cargo→next_dur) - (prev→next_dur) ≥ 0
      passengerImpact = detourDuration × 当时车上乘客数
    禁止占位 0。

    handovers 里的订单 = 已被改成「最近骨架站交接」的停靠：serviceMode=NEAREST_STATION、
    servicePoint=车真正停靠的那一站（与 baseline 同口径：servicePoint 是服务实际发生的点，
    客户自己的点位在订单记录里，不在这里重复）、reasonCode 说明*为什么*没上门
    （DETOUR_OVER_THRESHOLD / ROAD_NOT_BUS_ACCESSIBLE / NEAR_STATION_WITHIN_100M）；
    其余货运停靠是真实车上服务，不再无条件贴 NEAREST_STATION 标签。
    注：交接停靠这里仍发 accepted=True，而 baseline 发 False —— 两求解器的这层
    分歧已登记（任务 #13），不在本轮顺手改，避免同时动判据与发射两处口径。
    """
    plans: list[VehiclePlan] = []
    handovers = handovers or {}

    for route in routes:
        if route.task_count() == 0:
            continue

        events = route.events
        vehicle_id = request.vehicles[route.vehicle_index].vehicleId
        initial_pax = request.vehicles[route.vehicle_index].initialPassengerLoad

        # 先按事件序收集访问行（含上车人数累计），再填 detour
        rows: list[dict] = []
        current = station_map[route.depot_station]
        scaled_total = 0
        pax = initial_pax  # 初始载荷乘客全程在车，同样承受绕行影响

        for event in events[1:]:
            to = station_map[event.station_id]
            seg_km, seg_sec = _segment(current, to, matrix)
            scaled_total += int(round(seg_km * DISTANCE_SCALE))
            action = _EVENT_TO_ACTION.get(
                event.event_type, StopAction.PASS
            )
            rows.append({
                "station": event.station_id,
                "action": action,
                "order_id": event.order_id,
                "km": seg_km,
                "sec": seg_sec,
                "pax_before": pax,
            })
            if action == StopAction.BOARD:
                pax += 1
            elif action == StopAction.ALIGHT:
                pax = max(0, pax - 1)
            current = to

        stops = [RouteStop(
            stationId=route.depot_station,
            action=StopAction.DEPART,
            segmentDistance=0.0,
            segmentDuration=None,
        )]

        for i, row in enumerate(rows):
            is_cargo = row["action"] in (
                StopAction.PICKUP, StopAction.DELIVER,
            )
            is_service = is_cargo or row["action"] in (
                StopAction.BOARD, StopAction.ALIGHT,
            )
            # 执行区间归属：到此刻为止已经停靠了几个骨架站，就归到第几段。
            # 已经停靠过 S2 之后再执行的动作，不可能回到 R12 —— 这就是规格 §四
            # allowed_exec 的下界（exec_region ≥ 已停靠到的站点序号）。
            exec_region = sum(
                1 for e in events[:i + 1] if e.event_type == EventType.PASS
            ) if route.skeleton else None
            geo: tuple[int, ...] = ()
            if exec_region is not None and is_service:
                spans = _region_spans(route)
                geo = _geo_candidates(row["station"], spans, station_map, matrix)
            detour_km = None
            detour_sec = None
            impact = None

            if is_cargo:
                if _on_skeleton(route, row["station"]):
                    # 骨架站到站是既定动作：绕行为 0，不影响乘客
                    detour_km = 0.0
                    detour_sec = 0.0
                else:
                    prev_st = (station_map[rows[i - 1]["station"]] if i > 0
                               else station_map[route.depot_station])
                    nxt_st = station_map[rows[i + 1]["station"]]
                    cur_st = station_map[row["station"]]
                    seg1 = _segment(prev_st, cur_st, matrix)
                    seg2 = _segment(cur_st, nxt_st, matrix)
                    seg0 = _segment(prev_st, nxt_st, matrix)
                    detour_km = max(0.0, seg1[0] + seg2[0] - seg0[0])
                    detour_sec = max(0.0, seg1[1] + seg2[1] - seg0[1])
                if row["pax_before"] > 0:
                    impact = round(detour_sec * row["pax_before"], 1)

            ho = handovers.get(row["order_id"]) if is_cargo else None
            stops.append(RouteStop(
                stationId=row["station"],
                orderId=row["order_id"],
                action=row["action"],
                segmentDistance=round(row["km"], 3),
                segmentDuration=round(row["sec"], 1),
                accepted=True,
                # 项目既定口径（与 baseline 一致）：货运经停一律是「就近站点交接」形态，
                # servicePoint = 本次实际发生服务的点位。
                serviceMode=("NEAREST_STATION" if is_cargo else None),
                servicePoint=(row["station"] if is_cargo else None),
                detourDistance=(
                    round(detour_km, 3) if detour_km is not None else None
                ),
                detourDuration=(
                    round(detour_sec, 1) if detour_sec is not None else None
                ),
                passengerImpact=impact,
                # 只在真的做过「超阈值→就近站」改写时给原因码，供后台解释为什么没上门。
                reasonCode=(ho.get("reason", "DETOUR_OVER_THRESHOLD") if ho else None),
                execRegion=exec_region if is_service else None,
                geoCandidates=list(geo) if is_service else [],
            ))

        plans.append(VehiclePlan(
            vehicleId=vehicle_id,
            stops=stops,
            totalDistance=round(scaled_total / DISTANCE_SCALE, 3),
        ))

    return plans


def _segment(a, b, matrix: DistanceMatrix | None) -> tuple[float, float]:
    """两点间 (距离, 秒)。与 1.4 链其它模块口径一致：
    有矩阵用矩阵 (km, 秒)；无矩阵用欧氏度（与 baseline/API distanceUnit=degree 一致）。
    秒数与 FeasibilityEngine._check_duration 完全一致，保证搜索期时间窗与输出停靠时间吻合。
    """
    if matrix is not None:
        key = (a.stationId, b.stationId)
        if key in matrix:
            km, sec = matrix[key]
            return km, float(sec or 0.0)
    dist = compute_distance(a, b, matrix)
    sec = compute_duration(a, b, matrix)
    return dist, sec
