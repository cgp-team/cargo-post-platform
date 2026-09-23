"""SLA 承诺引擎：可承诺 / 临界 / 拒绝 三级。"""
from __future__ import annotations

from dataclasses import dataclass
from enum import Enum


class SlaLevel(str, Enum):
    COMMITTED = "COMMITTED"      # 可承诺
    CRITICAL = "CRITICAL"        # 临界（可接但需监控/加价）
    REJECT = "REJECT"            # 不可承诺


@dataclass(frozen=True)
class SlaDecision:
    level: SlaLevel
    slack_s: float | None
    reason_code: str
    explanation: str


def evaluate_sla(
    *,
    estimated_delivery_s: float,
    deadline_s: float | None,
    now_s: float = 0.0,
    critical_ratio: float = 0.15,
    min_buffer_s: float = 300.0,
) -> SlaDecision:
    """`deadline_s` 为绝对秒（相对 epoch/班次原点）。无截止时间视为不紧迫（COMMITTED）。"""
    if deadline_s is None:
        return SlaDecision(SlaLevel.COMMITTED, None, "SLA_NO_DEADLINE", "无明确时效，按可承诺处理。")

    slack = deadline_s - estimated_delivery_s
    window = max(1.0, deadline_s - now_s)
    if slack < 0:
        return SlaDecision(
            SlaLevel.REJECT, slack, "SLA_MISSED",
            f"预计送达已超截止 {abs(slack):.0f}s，不可承诺；建议加急专送/改联运/改约。",
        )
    if slack < min_buffer_s or slack < window * critical_ratio:
        return SlaDecision(
            SlaLevel.CRITICAL, slack, "SLA_CRITICAL",
            f"余量仅 {slack:.0f}s，临界可承诺：优先插当班、监控 ETA，超时自动升级联运/专送。",
        )
    return SlaDecision(
        SlaLevel.COMMITTED, slack, "SLA_OK",
        f"余量 {slack:.0f}s，可承诺送达。",
    )
