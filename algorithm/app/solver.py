"""统一求解入口：根据 algorithmMode 调度 HACO-CPS 或 OR-Tools Baseline。

默认模式：HACO
Fallback：HACO 失败/超时时回退到 OR-Tools baseline（在 warnings 中标注）

算法版本：
- HACO: haco-cps-1.0.0
- Baseline: ortools-1.3.0
"""

from __future__ import annotations

import logging
from dataclasses import dataclass, field

from .distance import DistanceMatrix
from .models import AlgorithmMode, PlanRequest, VehiclePlan

logger = logging.getLogger(__name__)

# 版本常量
BASELINE_ALGORITHM_VERSION = "ortools-1.3.0"
ALGORITHM_VERSION = "haco-cps-1.0.0"


@dataclass
class SolveOutcome:
    status: str  # "feasible" | "infeasible"
    reason_code: str | None = None
    vehicle_plans: list[VehiclePlan] = field(default_factory=list)
    total_distance: float = 0.0
    algorithm_version: str = ALGORITHM_VERSION
    parameter_version: str = "haco-cps-default-v1"
    warnings: list[str] = field(default_factory=list)


def solve(request: PlanRequest, matrix: DistanceMatrix | None = None) -> SolveOutcome:
    """统一求解入口。

    根据 request.algorithmConfig.algorithmMode 调度：
    - HACO (默认): HACO-CPS 元启发式
    - BASELINE: OR-Tools PATH_CHEAPEST_ARC
    - HYBRID: HACO + OR-Tools refinement

    HACO 失败时自动 fallback 到 baseline，并在 warnings 中标注。
    """
    mode = getattr(request.algorithmConfig, "algorithmMode", AlgorithmMode.HACO)

    if mode == AlgorithmMode.BASELINE:
        return _solve_baseline(request, matrix)

    # 默认 HACO 模式
    try:
        from .haco.config import HacoConfig
        from .haco.solver import solve_haco

        config = HacoConfig.from_algorithm_config(request.algorithmConfig)
        result = solve_haco(request, matrix, config)

        if result.status == "feasible":
            return result

        # HACO 无解，尝试 fallback
        logger.warning("HACO returned %s (%s), falling back to baseline", result.status, result.reason_code)
        baseline = _solve_baseline(request, matrix)
        baseline.warnings = list(result.warnings) + ["HACO_FALLBACK_TO_BASELINE"]
        baseline.algorithm_version = ALGORITHM_VERSION
        baseline.parameter_version = "haco-cps-fallback-v1"
        return baseline

    except Exception as e:
        # HACO 异常，fallback 到 baseline
        logger.error("HACO failed with exception: %s, falling back to baseline", e)
        baseline = _solve_baseline(request, matrix)
        baseline.warnings = ["HACO_FALLBACK_TO_BASELINE", f"HACO_ERROR: {type(e).__name__}"]
        baseline.algorithm_version = ALGORITHM_VERSION
        baseline.parameter_version = "haco-cps-fallback-v1"
        return baseline


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
