"""Decision Trace（DISPATCH_CORE_V047）。

每个候选一条记录：为什么选它、为什么没选其他候选，必须可解释、可输出。
"""

from __future__ import annotations

import json
from dataclasses import asdict, dataclass, field
from typing import Any, Sequence


@dataclass
class DecisionTraceRecord:
    order_id: str
    candidate_id: str
    candidate_type: str
    vehicle_id: int | None = None
    route_id: str = ""
    shift_id: str = ""
    feasible: bool = False
    reason_code: str = ""
    pickup_eta: float | None = None
    delivery_eta: float | None = None
    sla_slack_s: float | None = None
    delta_distance_m: float = 0.0
    delta_duration_s: float = 0.0
    passenger_impact_s: float = 0.0
    economic_value: float | None = None
    incremental_cost: float = 0.0
    efficiency: float | None = None
    handover_count: int = 0
    waiting_s: float = 0.0
    risk: float = 0.0
    flexibility: str | None = None
    selected: bool = False
    cost_formal: bool = False
    detail: dict[str, Any] = field(default_factory=dict)

    def as_dict(self) -> dict:
        raw = asdict(self)
        # API 输出统一 camelCase（与 PlanResult 等既有契约一致）
        return {
            "orderId": raw["order_id"],
            "candidateId": raw["candidate_id"],
            "candidateType": raw["candidate_type"],
            "vehicleId": raw["vehicle_id"],
            "routeId": raw["route_id"],
            "shiftId": raw["shift_id"],
            "feasible": raw["feasible"],
            "reasonCode": raw["reason_code"],
            "pickupEta": raw["pickup_eta"],
            "deliveryEta": raw["delivery_eta"],
            "slaSlackS": raw["sla_slack_s"],
            "deltaDistanceM": raw["delta_distance_m"],
            "deltaDurationS": raw["delta_duration_s"],
            "passengerImpactS": raw["passenger_impact_s"],
            "economicValue": raw["economic_value"],
            "incrementalCost": raw["incremental_cost"],
            "efficiency": raw["efficiency"],
            "handoverCount": raw["handover_count"],
            "waitingS": raw["waiting_s"],
            "risk": raw["risk"],
            "flexibility": raw["flexibility"],
            "selected": raw["selected"],
            "costFormal": raw["cost_formal"],
            "detail": raw["detail"],
        }


class DecisionTrace:
    """一次动态调度的完整候选轨迹。"""

    def __init__(self, order_id: str):
        self.order_id = order_id
        self.records: list[DecisionTraceRecord] = []
        self.level: str | None = None
        self.selected_candidate_id: str | None = None
        self.notes: list[str] = []

    def add(self, record: DecisionTraceRecord) -> DecisionTraceRecord:
        record.order_id = self.order_id
        self.records.append(record)
        return record

    def mark_selected(self, candidate_id: str, level: str | None = None) -> None:
        self.selected_candidate_id = candidate_id
        if level is not None:
            self.level = level
        for r in self.records:
            r.selected = r.candidate_id == candidate_id

    @property
    def selected(self) -> DecisionTraceRecord | None:
        for r in self.records:
            if r.selected:
                return r
        return None

    def rejected(self) -> list[DecisionTraceRecord]:
        return [r for r in self.records if not r.selected]

    def why_selected(self) -> str:
        sel = self.selected
        if sel is None:
            return "未选中任何候选（HOLD / 人工复核）。"
        parts = [
            f"选择 {sel.candidate_type} {sel.route_id}/{sel.shift_id}",
            f"reason={sel.reason_code}",
        ]
        if sel.cost_formal:
            parts.append(
                f"真实增量 Δ{sel.delta_distance_m:.0f}m/Δ{sel.delta_duration_s:.0f}s"
            )
        else:
            parts.append("成本 UNKNOWN（未获得正式真实道路）")
        parts.append(f"乘客影响 proxy {sel.passenger_impact_s:.0f}s")
        if sel.incremental_cost:
            parts.append(f"增量成本 {sel.incremental_cost:.2f}")
        return "；".join(parts) + "。"

    def why_not_others(self) -> list[str]:
        out: list[str] = []
        for r in self.rejected():
            if not r.feasible:
                out.append(f"{r.candidate_type} {r.route_id}: 不可行({r.reason_code})")
            else:
                out.append(f"{r.candidate_type} {r.route_id}: 可行但排名靠后")
        return out

    def as_dict(self) -> dict:
        return {
            "orderId": self.order_id,
            "level": self.level,
            "selectedCandidateId": self.selected_candidate_id,
            "whySelected": self.why_selected(),
            "whyNotOthers": self.why_not_others(),
            "notes": list(self.notes),
            "candidates": [r.as_dict() for r in self.records],
        }

    def to_json(self, **kwargs) -> str:
        return json.dumps(self.as_dict(), ensure_ascii=False, **kwargs)


def summarize_traces(traces: Sequence[DecisionTrace]) -> dict:
    """Benchmark / 报告用汇总：选中分布与拒绝原因分布。"""
    by_type: dict[str, int] = {}
    reject_reasons: dict[str, int] = {}
    for t in traces:
        sel = t.selected
        key = sel.candidate_type if sel else "HOLD"
        by_type[key] = by_type.get(key, 0) + 1
        for r in t.rejected():
            if not r.feasible:
                reject_reasons[r.reason_code] = reject_reasons.get(r.reason_code, 0) + 1
    return {
        "orders": len(traces),
        "selectedByType": by_type,
        "rejectReasons": reject_reasons,
    }


__all__ = [
    "DecisionTraceRecord",
    "DecisionTrace",
    "summarize_traces",
]
