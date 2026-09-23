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
from dataclasses import dataclass, field

from .distance import DistanceMatrix
from .models import AlgorithmMode, PlanRequest, VehiclePlan

logger = logging.getLogger(__name__)

# 版本常量
BASELINE_ALGORITHM_VERSION = "ortools-1.3.0"
HACO_1_3_VERSION = "haco-cps-1.3.0"
HACO_1_4_VERSION = "haco-cps-1.4.1"
ALGORITHM_VERSION = HACO_1_4_VERSION  # 默认（HACO）
PARAMETER_VERSION = "haco-cps-default-v1.4.1"


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


def solve(request: PlanRequest, matrix: DistanceMatrix | None = None) -> SolveOutcome:
    """统一求解入口：按 mode 分流，不再把 HACO/HYBRID 折叠。"""
    mode = getattr(request.algorithmConfig, "algorithmMode", AlgorithmMode.HACO)

    if mode == AlgorithmMode.BASELINE:
        return _solve_baseline(request, matrix)

    if mode == AlgorithmMode.HACO:
        return _solve_haco(request, matrix)

    # HYBRID：HACO 1.4 + OR-Tools portfolio
    return _solve_hybrid(request, matrix)


def _solve_haco(
    request: PlanRequest,
    matrix: DistanceMatrix | None = None,
) -> SolveOutcome:
    """HACO-CPS 1.4.1。不静默回落 OR-Tools：失败即如实返回 infeasible。"""
    try:
        from .haco.config import HacoConfig
        from .haco.v14_solver import solve as solve_v14

        config = HacoConfig.from_algorithm_config(request.algorithmConfig)
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
) -> SolveOutcome:
    """HYBRID：并行跑 HACO-1.4 与 OR-Tools baseline，取更优者（portfolio）。

    并行执行可把 wall-clock 从「两者之和」压到「较慢者」，在 10s 契约预算内
    留出更多搜索时间。排序键：统一 6 维 ObjectiveVector。
    绝不把 baseline 结果标成 haco 版本。
    """
    from concurrent.futures import ThreadPoolExecutor

    from .objective_compare import solution_key

    with ThreadPoolExecutor(max_workers=2, thread_name_prefix="hybrid-portfolio") as pool:
        haco_future = pool.submit(_solve_haco, request, matrix)
        baseline_future = pool.submit(_solve_baseline, request, matrix)
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


def _solve_baseline(request: PlanRequest, matrix: DistanceMatrix | None = None) -> SolveOutcome:
    """调用 OR-Tools baseline 求解器。"""
    from .baseline.ortools_solver import solve as baseline_solve

    result = baseline_solve(request, matrix)
    return SolveOutcome(
        status=result.status,
        reason_code=result.reason_code,
        vehicle_plans=result.vehicle_plans,
        total_distance=result.total_distance,
        algorithm_version=BASELINE_ALGORITHM_VERSION,
        parameter_version="default-v1",
        warnings=[],
    )
