"""统一求解入口：根据 algorithmMode 调度 HACO-CPS 1.4.1 或 OR-Tools Baseline。

版本：
- BASELINE: ortools-1.3.0
- HACO:     haco-cps-1.4.1（RouteGenome 主链：Construction → FeasibilityEngine →
            ObjectiveVector → Pheromone → Local Search → ALNS → Archive）
- HYBRID:   HACO 1.4 + OR-Tools portfolio（两个求解器都跑，选更优解）

fallback 身份规则：
- 只有真回落 OR-Tools 的结果才允许标 ortools-1.3.0；
- 禁止把 OR-Tools 结果伪装成任何 haco-cps 版本；
- HACO 模式本身不静默回落：无解/异常就返回 infeasible（不掩盖）。
"""

from __future__ import annotations

import logging
import time
from dataclasses import dataclass, field
from typing import Optional

from .distance import DistanceMatrix
from .haco.deadline import SearchDeadline
from .models import AlgorithmMode, PlanRequest, VehiclePlan

logger = logging.getLogger(__name__)

# 版本常量
BASELINE_ALGORITHM_VERSION = "ortools-1.3.0"
HACO_1_3_VERSION = "haco-cps-1.3.0"
HACO_1_4_VERSION = "haco-cps-1.4.1"
ALGORITHM_VERSION = HACO_1_4_VERSION  # 默认（HACO）
PARAMETER_VERSION = "haco-cps-default-v1.4.1"

# 请求级墙钟保险丝的默认值（调用方可用 algorithmConfig.overall_time_limit 覆盖）。
# 口径只有一处权威说明：docs/algorithm-integration.md「时限与终止口径」。
DEFAULT_OVERALL_TIME_LIMIT_SECONDS = 5.0


@dataclass
class SolveOutcome:
    status: str
    reason_code: str | None = None
    vehicle_plans: list[VehiclePlan] = field(default_factory=list)
    total_distance: float = 0.0
    algorithm_version: str = ALGORITHM_VERSION
    parameter_version: str = PARAMETER_VERSION
    warnings: list[str] = field(default_factory=list)
    iteration_stats: list[dict] = field(default_factory=list)
    # 绕行硬约束/覆盖不足时未分配的订单编号（后端交给多段联运 MultiLegPlanner 处理）
    unassigned_order_ids: list[str] = field(default_factory=list)


def _overall_limit_seconds(request: PlanRequest) -> float:
    """请求级时限：调用方给就用调用方的，没给用默认 5s。

    `AlgorithmConfig` 是 `extra="allow"`，所以时限既可能来自显式字段也可能来自扩展键；
    非法值不静默当 0（0 = 一上来就超时，会把所有请求判成无解），退回默认档并在遥测里如实报。
    """
    cfg = getattr(request, "algorithmConfig", None)
    raw = getattr(cfg, "overall_time_limit", None) if cfg is not None else None
    try:
        val = float(raw) if raw is not None else DEFAULT_OVERALL_TIME_LIMIT_SECONDS
    except (TypeError, ValueError):
        val = DEFAULT_OVERALL_TIME_LIMIT_SECONDS
    if val <= 0:
        val = DEFAULT_OVERALL_TIME_LIMIT_SECONDS
    return val


def _fuse_telemetry(outcome: SolveOutcome, limit: float, elapsed: float) -> None:
    """把保险丝的读数挂在结果上：谁跑了多久、上限是多少、有没有真的触发。"""
    notes = [f"OVERALL_LIMIT_S={round(limit, 3)}", f"ELAPSED_MS={round(elapsed * 1000, 1)}"]
    if elapsed > limit:
        notes.append(f"OVERALL_FUSE_TRIPPED=+{round((elapsed - limit) * 1000, 1)}ms")
    outcome.warnings = list(outcome.warnings or []) + notes


def solve(request: PlanRequest, matrix: DistanceMatrix | None = None) -> SolveOutcome:
    """统一求解入口：按 mode 分流，不再把 HACO/HYBRID 折叠。

    这一层是**请求边界**，所以 `overall_time_limit` 由它兑现：进入即建保险丝，
    分流前把剩余预算发给各臂（HACO 钳自己的搜索预算、BASELINE 钳 OR-Tools 每次搜索），
    返回时把 `ELAPSED_MS` / `OVERALL_FUSE_TRIPPED` 挂在 warnings 上。
    终止的**正常**依据仍是评估计数（`max_evaluations`）——墙钟只是保险丝，不是质量口径。
    """
    limit = _overall_limit_seconds(request)
    fuse = SearchDeadline.from_seconds(limit)
    t0 = time.perf_counter()
    outcome = _dispatch(request, matrix, fuse)
    _fuse_telemetry(outcome, limit, time.perf_counter() - t0)
    return outcome


def _dispatch(
    request: PlanRequest,
    matrix: DistanceMatrix | None,
    fuse: SearchDeadline,
) -> SolveOutcome:
    mode = getattr(request.algorithmConfig, "algorithmMode", AlgorithmMode.HACO)

    if mode == AlgorithmMode.BASELINE:
        return _solve_baseline(request, matrix, deadline=fuse)

    if mode == AlgorithmMode.HACO:
        return _solve_haco(request, matrix, deadline=fuse)

    # HYBRID：HACO 1.4 + OR-Tools portfolio
    return _solve_hybrid(request, matrix, deadline=fuse)


def _solve_haco(
    request: PlanRequest,
    matrix: DistanceMatrix | None = None,
    *,
    deadline: Optional[SearchDeadline] = None,
) -> SolveOutcome:
    """HACO-CPS 1.4.1。不静默回落 OR-Tools：失败即如实返回 infeasible。"""
    try:
        from .haco.config import HacoConfig
        from .haco.v14_solver import solve as solve_v14

        config = HacoConfig.from_algorithm_config(request.algorithmConfig)
        if deadline is not None:
            # v14 内部搜索截止 = min(haco_time_limit, overall_time_limit)，
            # 所以把 overall 钳到保险丝的**剩余**即可让整条主链受同一只表约束。
            config.overall_time_limit = min(
                config.overall_time_limit, max(0.05, deadline.remaining()),
            )
        return solve_v14(request, matrix, config)

    except Exception as e:  # noqa: BLE001
        logger.error("HACO-CPS 1.4.1 failed with exception: %s", e, exc_info=True)
        return SolveOutcome(
            status="infeasible",
            reason_code="INTERNAL_ERROR",
            algorithm_version=HACO_1_4_VERSION,
            parameter_version="haco-cps-fallback-v1.4.1",
            warnings=["HACO_ERROR: " + type(e).__name__],
        )


def _solve_hybrid(
    request: PlanRequest,
    matrix: DistanceMatrix | None = None,
    *,
    deadline: Optional[SearchDeadline] = None,
) -> SolveOutcome:
    """HYBRID：并行跑 HACO-1.4 与 OR-Tools baseline，取更优者（portfolio）。

    并行执行可把 wall-clock 从「两者之和」压到「较慢者」，在 10s 契约预算内
    留出更多搜索时间。排序键：统一 6 维 ObjectiveVector。
    绝不把 baseline 结果标成 haco 版本。
    """
    from concurrent.futures import ThreadPoolExecutor

    from .objective_compare import solution_key

    with ThreadPoolExecutor(max_workers=2, thread_name_prefix="hybrid-portfolio") as pool:
        # 两臂共享**同一只**保险丝：并行不会把总时限偷偷变成「两臂之和」
        haco_future = pool.submit(_solve_haco, request, matrix, deadline=deadline)
        baseline_future = pool.submit(_solve_baseline, request, matrix, deadline=deadline)
        haco = haco_future.result()
        baseline = baseline_future.result()

    if solution_key(baseline) < solution_key(haco):
        # baseline 严格更优 → 返回 baseline，如实标 ortools-1.3.0
        logger.info("HYBRID: baseline chosen over HACO-1.4")
        baseline.warnings = list(haco.warnings or []) + list(baseline.warnings or []) + [
            "HYBRID_PORTFOLIO_CHOSE_BASELINE"
        ]
        return baseline

    if haco.status != "feasible" and baseline.status == "feasible":
        # HACO 无解、baseline 有解 → 采用 baseline（HACO 失败回落，如实标注）
        baseline.warnings = list(haco.warnings or []) + list(baseline.warnings or []) + [
            "HACO_FALLBACK_TO_BASELINE"
        ]
        return baseline

    # HACO 更优或打平 → 返回 HACO 结果（haco-cps-1.4.1）
    return haco


def _solve_baseline(
    request: PlanRequest,
    matrix: DistanceMatrix | None = None,
    *,
    deadline: Optional[SearchDeadline] = None,
) -> SolveOutcome:
    """调用 OR-Tools baseline 求解器。"""
    from .baseline.ortools_solver import solve as baseline_solve

    result = baseline_solve(request, matrix, deadline=deadline)
    return SolveOutcome(
        status=result.status,
        reason_code=result.reason_code,
        vehicle_plans=result.vehicle_plans,
        total_distance=result.total_distance,
        algorithm_version=BASELINE_ALGORITHM_VERSION,
        parameter_version="default-v1",
        warnings=[],
    )
