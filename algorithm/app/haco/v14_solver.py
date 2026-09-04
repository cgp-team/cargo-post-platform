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
from .alns_v14 import alns_search
from .config import HacoConfig
from .construction import construct_ant_solution_v14, generate_insertion_candidates
from .deadline import SearchDeadline
from .elite_archive import EliteArchive
from .encoding import ObjectiveVector, TaskBlock, TaskType
from .evaluator import compute_diversity, evaluate_route_states
from .feasibility_engine import FeasibilityEngine
from .heuristic import compute_distance, compute_duration
from .local_search_v14 import local_search_improve
from .pheromone import PheromoneMatrix, extract_vehicle_task_sequences
from .route_genome import EventType, RouteGenome

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

    precheck = _precheck(request)
    if precheck is not None:
        return precheck

    station_map = {s.stationId: s for s in request.stations}
    station_map[request.depot.stationId] = request.depot

    tasks = _encode_tasks(request)
    if not tasks:
        return SolveOutcome(status="feasible")

    # 规范任务序：主搜索（构造/信息素/随机数消耗）与客户端订单输入顺序解耦。
    # 同批任务无论以什么顺序到达，都得到完全相同的求解 → 用车数/里程稳定。
    tasks = sorted(tasks, key=lambda t: t.task_id)

    tasks_by_id = {t.task_id: t for t in tasks}

    # 每车一个 RouteGenome（events 是唯一真实路线顺序）
    templates = [
        RouteGenome(
            vehicle_index=i,
            vehicle_id=v.vehicleId,
            depot_station=request.depot.stationId,
            skeleton=list(v.skeleton) if v.skeleton else None,
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

    window_seconds = int(
        request.batchEnd.timestamp() - request.batchStart.timestamp()
    )

    # 时间窗约束渗透进每一次内部 check（含服务时间）
    engine = FeasibilityEngine(
        station_map=station_map,
        matrix=matrix,
        max_duration=float(window_seconds),
    )

    # ── Hard Deadline：搜索阶段时间预算 ──────────────────────────
    # haco_time_limit = HACO 搜索硬截止（ACO + LS + ALNS + Archive + 编码输出）
    # overall_time_limit = 外层应用预留的总时限上限（当前由调用方 solver.py 控制）
    # 取两者较小值作为本函数内搜索阶段的硬截止
    haco_search_budget = min(config.haco_time_limit, config.overall_time_limit)
    deadline = SearchDeadline.from_seconds(haco_search_budget)

    # ── 初始解 ────────────────────────────────────────────────
    initial, seed_telemetry = _generate_initial_solutions(
        tasks, tasks_by_id, templates, engine,
        passenger_capacities, cargo_capacities,
        initial_passenger_loads, initial_cargo_loads,
        station_map, matrix, config, rng, deadline,
    )
    feasible_initials = [
        s for s in initial if s is not None
    ]
    if not feasible_initials:
        # 归类无解原因：去掉时间窗约束若仍能构造出可行解 → 是时间窗过紧
        probe_engine = FeasibilityEngine(
            station_map=station_map, matrix=matrix,
        )  # 无 max_duration
        # Give the diagnostic probe its own limited budget (max 2s),
        # not the main search deadline, so it can complete diagnosis.
        probe_deadline = SearchDeadline.from_seconds(min(2.0, deadline.remaining()))
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
                warnings=["NO_FEASIBLE_V14_SOLUTION_WITHIN_TIME_WINDOW"],
            )
        return SolveOutcome(
            status="infeasible",
            reason_code="NO_FEASIBLE_INITIAL_SOLUTION",
            warnings=["NO_FEASIBLE_V14_SOLUTION"],
        )

    best_routes, best_obj = min(
        feasible_initials, key=lambda x: x[1]
    )

    # ── 信息素初始化（MMAS）────────────────────────────────
    task_ids = [t.task_id for t in tasks] + ["DEPOT"]
    pheromone = PheromoneMatrix(task_ids, config)
    if best_obj.normalized_cost() > 0:
        pheromone.initialize_tau0(best_obj.normalized_cost())

    archive = EliteArchive(max_size=config.archive_size)
    archive.add(best_routes, best_obj, tasks_by_id, station_map, matrix)

    warnings: list[str] = []
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

    for iteration in range(config.max_iterations):
        if deadline.expired():
            warnings.append("HACO_TIME_LIMIT_REACHED")
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
            if deadline.expired():
                warnings.append("HACO_TIME_LIMIT_REACHED")
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
                    deadline=deadline,
                )
                iter_cand_ms += (time.perf_counter() - _t0) * 1000
                # 构造可能丢任务 → 贪心补插
                ant_routes = _repair_unassigned(
                    ant_routes, tasks, tasks_by_id, engine,
                    passenger_capacities, cargo_capacities,
                    initial_passenger_loads, initial_cargo_loads,
                    station_map, matrix, deadline,
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
                deadline=deadline,
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
    if not deadline.expired():
        alns_iterations = max(30, min(200, config.max_iterations * 3))
        _t0 = time.perf_counter()
        refined = alns_search(
            best_routes, tasks_by_id, engine, station_map, matrix,
            passenger_capacities, cargo_capacities,
            initial_passenger_loads, initial_cargo_loads,
            config, rng, max_iterations=alns_iterations,
            deadline=deadline,
        )
        alns_ms = (time.perf_counter() - _t0) * 1000
        # ALNS destroy/repair 可能丢任务 → 必须补全后才可能被采纳
        refined = _ensure_complete(
            refined, tasks, tasks_by_id, engine,
            passenger_capacities, cargo_capacities,
            initial_passenger_loads, initial_cargo_loads,
            station_map, matrix, deadline,
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
        best_routes, best_obj = local_search_improve(
            best_routes, tasks_by_id, station_map, matrix, engine,
            passenger_capacities, cargo_capacities,
            initial_passenger_loads, initial_cargo_loads,
            rounds=config.local_search_rounds + 1,
            deadline=deadline,
        )

    # 存档里若有更优（且可行）精英则采纳
    best_entry = archive.get_best()
    if best_entry is not None and best_entry.objective < best_obj:
        best_routes = best_entry.routes
        best_obj = best_entry.objective

    # 车辆数最小化：把全部任务重新打包进尽可能少的车（稳定用车数）。
    # 注意：主搜索每代已对 iteration_best 做过 Best Improvement LS，这里不再重复 LS，
    # 避免大骨架规模下重复全枚举拖慢求解。
    compacted = _compact_solution(
        best_routes, tasks, templates, tasks_by_id, engine,
        passenger_capacities, cargo_capacities,
        initial_passenger_loads, initial_cargo_loads,
        station_map, matrix, config, rng, deadline,
    )
    if compacted is not None:
        best_routes = compacted
        best_obj = evaluate_route_states(
            best_routes, tasks_by_id, station_map, matrix
        )

    # ── 输出（兜底：输出前强制解覆盖全部任务）──────────────
    best_routes = _ensure_complete(
        best_routes, tasks, tasks_by_id, engine,
        passenger_capacities, cargo_capacities,
        initial_passenger_loads, initial_cargo_loads,
        station_map, matrix, deadline,
    )
    if best_routes is None:
        return SolveOutcome(
            status="infeasible",
            reason_code="INCOMPLETE_SOLUTION",
            warnings=["NO_COMPLETE_V14_SOLUTION"],
        )

    vehicle_plans = _routes_to_vehicle_plans(
        best_routes, request, station_map, matrix
    )

    total_search_ms = (time.perf_counter() - total_search_start) * 1000

    # 阶段耗时 telemetry（便于定位超时阶段）
    warnings.append(f"SEARCH_START_MS={round((total_search_start - t_solve_start) * 1000, 1)}")
    if alns_ms > 0:
        warnings.append(f"ALNS_MS={alns_ms:.0f}")
    warnings.append(f"TOTAL_SEARCH_MS={total_search_ms:.0f}")
    warnings.append(f"ITERATION_COUNT={len(iteration_stats)}")
    if iteration_stats:
        warnings.append(
            f"CANDIDATE_MS={round(sum(s.get('candidate_ms', 0) for s in iteration_stats), 1)}"
        )
        warnings.append(
            f"LOCAL_SEARCH_MS={round(sum(s.get('local_search_ms', 0) for s in iteration_stats), 1)}"
        )

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
    )


# ─── 初始解生成 ───────────────────────────────────────────────


def _generate_initial_solutions(
    tasks, tasks_by_id, templates, engine,
    passenger_capacities, cargo_capacities,
    initial_passenger_loads, initial_cargo_loads,
    station_map, matrix, config, rng,
    deadline: SearchDeadline | None = None,
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


def _cheapest_insertion(
    tasks, templates, tasks_by_id, engine,
    passenger_capacities, cargo_capacities,
    initial_passenger_loads, initial_cargo_loads,
    station_map, matrix,
    deadline: SearchDeadline | None = None,
) -> GreedySeedResult:
    """确定性 cheapest-insertion：每步选全局启发式得分最低的可行 (task,位置)。

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

        best = None  # (score, task, route_idx, pickup, delivery)
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
            if candidates:
                cand = candidates[0]
                score = cand.heuristic_score
                if best is None or score < best[0]:
                    best = (
                        score, task, cand.vehicle_index,
                        cand.pickup_index, cand.delivery_index,
                    )
        if best is None:
            return _partial()  # 存在剩余任务无法放置 → 非全量解
        _, task, vi, pickup, delivery = best
        routes[vi].insert_task(task, pickup, delivery)
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
    """全部车辆 RouteGenome 同时可行。"""
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

    canonical = sorted(tasks, key=lambda t: t.task_id)

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

    for m in range(1, used_n + 1):
        if deadline is not None and deadline.expired():
            return routes  # 超时，返回当前最优解

        cap_m = sum(cargo_capacities[i] for i in range(min(m, len(cargo_capacities))))
        if deliveries_total > cap_m or pickups_total > cap_m:
            continue  # m 车总货仓容量不足，必不可能 → 跳过整轮尝试

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


def _encode_tasks(request: PlanRequest) -> list[TaskBlock]:
    tasks = []
    for order in request.orders:
        if order.orderType == OrderType.PASSENGER:
            tasks.append(TaskBlock(
                task_id=f"P:{order.orderId}", task_type=TaskType.PASSENGER,
                pickup_station=order.boardingStationId,
                delivery_station=order.alightingStationId,
                size=1, order_ids=[order.orderId],
            ))
        elif order.orderType == OrderType.DELIVERY:
            tasks.append(TaskBlock(
                task_id=f"D:{order.orderId}", task_type=TaskType.DELIVERY,
                pickup_station=order.stationId,
                delivery_station=order.stationId,
                size=order.itemCount, order_ids=[order.orderId],
                cargo_source=order.cargoSource,
            ))
        elif order.orderType == OrderType.PICKUP:
            tasks.append(TaskBlock(
                task_id=f"K:{order.orderId}", task_type=TaskType.PICKUP,
                pickup_station=order.stationId,
                delivery_station=order.stationId,
                size=order.itemCount, order_ids=[order.orderId],
                cargo_source=order.cargoSource,
            ))
    for shipment in request.shipments:
        tasks.append(TaskBlock(
            task_id=f"S:{shipment.shipmentId}", task_type=TaskType.SHIPMENT,
            pickup_station=shipment.pickupStationId,
            delivery_station=shipment.deliveryStationId,
            size=shipment.quantity, order_ids=[shipment.shipmentId],
        ))
    return tasks


def _precheck(request: PlanRequest) -> SolveOutcome | None:
    """规模/容量预检（与 baseline 口径一致，避免进搜索前即无解）。"""
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
        return SolveOutcome(status="feasible")

    for v in request.vehicles:
        if v.initialPassengerLoad >= v.passengerCapacity:
            return SolveOutcome(status="infeasible", reason_code="OVER_CAPACITY")
        if v.initialCargoLoad > v.cargoCapacity:
            return SolveOutcome(status="infeasible", reason_code="OVER_CAPACITY")

    total_pcap = sum(
        v.passengerCapacity - v.initialPassengerLoad
        for v in request.vehicles
    )
    total_ccap = sum(
        v.cargoCapacity - v.initialCargoLoad for v in request.vehicles
    )
    if passengers > total_pcap or deliveries > total_ccap or pickups > total_ccap:
        return SolveOutcome(status="infeasible", reason_code="OVER_CAPACITY")

    total_preloaded_delivery = sum(
        o.itemCount for o in request.orders
        if o.orderType == OrderType.DELIVERY
        and o.cargoSource == CargoSource.PRELOADED
    )
    total_initial_cargo = sum(v.initialCargoLoad for v in request.vehicles)
    if total_preloaded_delivery > total_initial_cargo:
        return SolveOutcome(
            status="infeasible", reason_code="PRELOAD_INSUFFICIENT"
        )

    window_seconds = int(
        request.batchEnd.timestamp() - request.batchStart.timestamp()
    )
    if window_seconds <= 0:
        return SolveOutcome(
            status="infeasible", reason_code="TIME_WINDOW_EXCEEDED"
        )
    return None


# ─── RouteGenome → VehiclePlan ───────────────────────────────


def _routes_to_vehicle_plans(
    routes: list[RouteGenome],
    request: PlanRequest,
    station_map: dict,
    matrix: DistanceMatrix | None,
) -> list[VehiclePlan]:
    """按 RouteGenome.events 逐事件展开成 RouteStop。

    货运 PICKUP/DELIVER 经停按"插入该站相对前后站"计算真实 detour：
      detourDistance = (prev→cargo + cargo→next) - (prev→next) ≥ 0
      detourDuration = (prev→cargo_dur + cargo→next_dur) - (prev→next_dur) ≥ 0
      passengerImpact = detourDuration × 当时车上乘客数
    禁止占位 0。
    """
    plans: list[VehiclePlan] = []

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
            detour_km = None
            detour_sec = None
            impact = None

            if is_cargo:
                prev_st = station_map[rows[i - 1]["station"]] if i > 0 else station_map[route.depot_station]
                nxt_st = station_map[rows[i + 1]["station"]]
                cur_st = station_map[row["station"]]
                seg1 = _segment(prev_st, cur_st, matrix)
                seg2 = _segment(cur_st, nxt_st, matrix)
                seg0 = _segment(prev_st, nxt_st, matrix)
                detour_km = max(0.0, seg1[0] + seg2[0] - seg0[0])
                detour_sec = max(0.0, seg1[1] + seg2[1] - seg0[1])
                if row["pax_before"] > 0:
                    impact = round(detour_sec * row["pax_before"], 1)

            stops.append(RouteStop(
                stationId=row["station"],
                orderId=row["order_id"],
                action=row["action"],
                segmentDistance=round(row["km"], 3),
                segmentDuration=round(row["sec"], 1),
                accepted=True,
                serviceMode=(
                    "NEAREST_STATION" if is_cargo else None
                ),
                servicePoint=(
                    row["station"] if is_cargo else None
                ),
                detourDistance=(
                    round(detour_km, 3) if detour_km is not None else None
                ),
                detourDuration=(
                    round(detour_sec, 1) if detour_sec is not None else None
                ),
                passengerImpact=impact,
                reasonCode=None,
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
