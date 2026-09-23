"""调度失败用例分类与 Worst-20 输出（DISPATCH_CORE_V047 section 41）。

每轮回归后调用 `write_failure_cases(...)`，把失败的调度用例分类写入
`algorithm/data/dispatch_v047_failure_cases.json`，便于定位与回滚。
"""

from __future__ import annotations

import json
from dataclasses import asdict, dataclass, field
from enum import Enum
from pathlib import Path
from typing import Iterable, Sequence


class DispatchFailureCategory(str, Enum):
    PLACEHOLDER_COST = "PLACEHOLDER_COST"
    ROUTING_FALLBACK = "ROUTING_FALLBACK"
    REACHABILITY_ERROR = "REACHABILITY_ERROR"
    SLA_ERROR = "SLA_ERROR"
    LOCK_ERROR = "LOCK_ERROR"
    MULTILEG_ERROR = "MULTILEG_ERROR"
    HANDOVER_ERROR = "HANDOVER_ERROR"
    CAPACITY_ERROR = "CAPACITY_ERROR"
    DETOUR_ERROR = "DETOUR_ERROR"
    PASSENGER_ERROR = "PASSENGER_ERROR"
    CANDIDATE_POOL_MISS = "CANDIDATE_POOL_MISS"
    OBJECTIVE_REGRESSION = "OBJECTIVE_REGRESSION"
    STATE_REGRESSION = "STATE_REGRESSION"
    UNKNOWN = "UNKNOWN"


@dataclass
class DispatchFailureCase:
    case_id: str
    category: DispatchFailureCategory
    order_id: str = ""
    candidate_type: str = ""
    reason_code: str = ""
    severity: float = 0.0
    detail: dict = field(default_factory=dict)

    def as_dict(self) -> dict:
        d = asdict(self)
        d["category"] = self.category.value
        return d


_REASON_TO_CATEGORY: dict[str, DispatchFailureCategory] = {
    "PLACEHOLDER_COST": DispatchFailureCategory.PLACEHOLDER_COST,
    "ROUTE_UNKNOWN": DispatchFailureCategory.ROUTING_FALLBACK,
    "BASELINE_NOT_FORMAL": DispatchFailureCategory.ROUTING_FALLBACK,
    "CANDIDATE_NOT_FORMAL": DispatchFailureCategory.ROUTING_FALLBACK,
    "COST_NOT_FORMAL": DispatchFailureCategory.ROUTING_FALLBACK,
    "UNKNOWN_PENDING_CONFIRMATION": DispatchFailureCategory.REACHABILITY_ERROR,
    "LOCATION_STALE": DispatchFailureCategory.REACHABILITY_ERROR,
    "NETWORK_UNCERTAIN": DispatchFailureCategory.REACHABILITY_ERROR,
    "ROAD_UNREACHABLE": DispatchFailureCategory.REACHABILITY_ERROR,
    "ALREADY_PASSED": DispatchFailureCategory.REACHABILITY_ERROR,
    "VEHICLE_ACCESS_BLOCKED": DispatchFailureCategory.REACHABILITY_ERROR,
    "USER_POINT_UNSERVABLE": DispatchFailureCategory.REACHABILITY_ERROR,
    "ETA_MISSED": DispatchFailureCategory.SLA_ERROR,
    "SLA_MISSED": DispatchFailureCategory.SLA_ERROR,
    "LOCKED_ACTIVE_TRIP": DispatchFailureCategory.LOCK_ERROR,
    "TRIP_TERMINAL": DispatchFailureCategory.LOCK_ERROR,
    "REALTIME_DETOUR_TOO_LARGE": DispatchFailureCategory.LOCK_ERROR,
    "REALTIME_DURATION_TOO_LARGE": DispatchFailureCategory.LOCK_ERROR,
    "REALTIME_PAX_IMPACT_TOO_LARGE": DispatchFailureCategory.LOCK_ERROR,
    "HANDOVER_INFEASIBLE": DispatchFailureCategory.HANDOVER_ERROR,
    "HANDOVER_DISTANCE_EXCEEDED": DispatchFailureCategory.HANDOVER_ERROR,
    "NEXT_TRIP_DEPARTED": DispatchFailureCategory.HANDOVER_ERROR,
    "VEHICLE_CAPACITY_UNAVAILABLE": DispatchFailureCategory.CAPACITY_ERROR,
    "PASSENGER_CAPACITY_UNAVAILABLE": DispatchFailureCategory.CAPACITY_ERROR,
    "NEXT_CAPACITY_UNAVAILABLE": DispatchFailureCategory.CAPACITY_ERROR,
    "CARGO_CAPACITY_EXCEEDED": DispatchFailureCategory.CAPACITY_ERROR,
    "DETOUR_TOO_LARGE": DispatchFailureCategory.DETOUR_ERROR,
    "DETOUR_BUDGET_EXCEEDED": DispatchFailureCategory.DETOUR_ERROR,
    "PASSENGER_IMPACT_EXCEEDED": DispatchFailureCategory.PASSENGER_ERROR,
    "CANDIDATE_POOL_MISS": DispatchFailureCategory.CANDIDATE_POOL_MISS,
    "OBJECTIVE_REGRESSION": DispatchFailureCategory.OBJECTIVE_REGRESSION,
    "STATE_REGRESSION": DispatchFailureCategory.STATE_REGRESSION,
}


def classify_reason(reason_code: str) -> DispatchFailureCategory:
    return _REASON_TO_CATEGORY.get(reason_code, DispatchFailureCategory.UNKNOWN)


def worst_cases(
    cases: Iterable[DispatchFailureCase], limit: int = 20
) -> list[DispatchFailureCase]:
    return sorted(cases, key=lambda c: (-c.severity, c.category.value, c.case_id))[:limit]


def default_failure_cases_path() -> Path:
    # algorithm/app/dispatch_opt/failure_cases.py -> algorithm/data/...
    return Path(__file__).resolve().parents[2] / "data" / "dispatch_v047_failure_cases.json"


def write_failure_cases(
    cases: Sequence[DispatchFailureCase],
    *,
    path: Path | None = None,
    round_label: str = "",
) -> Path:
    target = path or default_failure_cases_path()
    target.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "version": "DISPATCH_CORE_V047",
        "round": round_label,
        "total": len(cases),
        "worst20": [c.as_dict() for c in worst_cases(cases, 20)],
        "byCategory": _by_category(cases),
    }
    target.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    return target


def _by_category(cases: Iterable[DispatchFailureCase]) -> dict[str, int]:
    out: dict[str, int] = {}
    for c in cases:
        out[c.category.value] = out.get(c.category.value, 0) + 1
    return out


__all__ = [
    "DispatchFailureCategory",
    "DispatchFailureCase",
    "classify_reason",
    "worst_cases",
    "write_failure_cases",
    "default_failure_cases_path",
]
