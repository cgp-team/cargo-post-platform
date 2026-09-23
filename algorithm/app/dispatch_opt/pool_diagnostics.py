"""Candidate Pool 诊断（DISPATCH_CORE_V047 section 26）。

当前 Coordinator **不做 top-k 截断**（逐一完整检查每个候选），因此：

- `screened_count == pool_size == full_checked_count == 候选总数`
- `feasible_outside_pool == 0`，不可能出现 `CANDIDATE_POOL_MISS`

该模块把上述事实显式量化输出，供未来引入 cheap pre-screen / top-k pool 时对比：
一旦引入截断，只需传入 `pool_size`，本函数即可指出“真正可行 candidate 落在 pool 外”的漏检。
"""

from __future__ import annotations

from dataclasses import dataclass, field

from .coordinator import DispatchPlan


@dataclass
class PoolDiagnostics:
    screened_count: int
    pool_size: int
    full_checked_count: int
    feasible_inside_pool: int
    feasible_outside_pool: int
    best_inside_pool: str | None
    best_outside_pool: str | None
    pool_miss: bool
    reason_code: str
    notes: list[str] = field(default_factory=list)

    def as_dict(self) -> dict:
        return {
            "screenedCount": self.screened_count,
            "poolSize": self.pool_size,
            "fullCheckedCount": self.full_checked_count,
            "feasibleInsidePool": self.feasible_inside_pool,
            "feasibleOutsidePool": self.feasible_outside_pool,
            "bestInsidePool": self.best_inside_pool,
            "bestOutsidePool": self.best_outside_pool,
            "poolMiss": self.pool_miss,
            "reasonCode": self.reason_code,
            "notes": list(self.notes),
        }


def diagnose_candidate_pool(
    plan: DispatchPlan,
    *,
    pool_size: int | None = None,
    screened_count: int | None = None,
) -> PoolDiagnostics:
    """给出候选池漏检诊断；`pool_size` 缺省 = 全量（不截断）。"""
    records = plan.trace.records
    total = len(records)
    full_checked = total
    screened = screened_count if screened_count is not None else total
    effective_pool = pool_size if pool_size is not None else total

    inside = records[:effective_pool]
    outside = records[effective_pool:]
    feasible_inside = [r for r in inside if r.feasible]
    feasible_outside = [r for r in outside if r.feasible]

    pool_miss = bool(feasible_outside) and not feasible_inside
    notes: list[str] = []
    if pool_size is None:
        notes.append("No top-k truncation today: every candidate is fully checked.")
    if pool_miss:
        notes.append("CANDIDATE_POOL_MISS: a truly feasible candidate lies outside the pool.")

    return PoolDiagnostics(
        screened_count=screened,
        pool_size=effective_pool,
        full_checked_count=full_checked,
        feasible_inside_pool=len(feasible_inside),
        feasible_outside_pool=len(feasible_outside),
        best_inside_pool=(
            min(feasible_inside, key=lambda r: r.incremental_cost).candidate_id
            if feasible_inside
            else None
        ),
        best_outside_pool=(
            min(feasible_outside, key=lambda r: r.incremental_cost).candidate_id
            if feasible_outside
            else None
        ),
        pool_miss=pool_miss,
        reason_code="CANDIDATE_POOL_MISS" if pool_miss else "NO_POOL_MISS",
        notes=notes,
    )


__all__ = ["PoolDiagnostics", "diagnose_candidate_pool"]
