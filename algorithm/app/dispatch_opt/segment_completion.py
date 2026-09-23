"""TaskSegmentCompletionValidator：ARRIVED != COMPLETED。"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum


class SegmentStatus(str, Enum):
    PLANNED = "PLANNED"
    IN_PROGRESS = "IN_PROGRESS"
    ARRIVED = "ARRIVED"
    SEGMENT_COMPLETED = "SEGMENT_COMPLETED"
    FAILED = "FAILED"


class OrderStatus(str, Enum):
    PLANNED = "PLANNED"
    IN_TRANSIT = "IN_TRANSIT"
    ORDER_COMPLETED = "ORDER_COMPLETED"
    FAILED = "FAILED"


@dataclass
class SegmentCompletionResult:
    ok: bool
    segment_status: SegmentStatus
    reason_code: str


class TaskSegmentCompletionValidator:
    """货运段完成 = ARRIVED + 必要动作（PICKUP/DELIVERY/HANDOVER）全部完成。"""

    def validate_segment(
        self,
        *,
        arrived: bool,
        required_actions: set[str],
        completed_actions: set[str],
        handover_confirmed: bool | None = None,
    ) -> SegmentCompletionResult:
        if not arrived:
            return SegmentCompletionResult(
                False, SegmentStatus.IN_PROGRESS, "NOT_ARRIVED"
            )

        missing = set(required_actions) - set(completed_actions)
        if missing:
            return SegmentCompletionResult(
                False,
                SegmentStatus.ARRIVED,
                f"ACTIONS_INCOMPLETE:{','.join(sorted(missing))}",
            )

        if "HANDOVER" in required_actions and handover_confirmed is False:
            return SegmentCompletionResult(
                False, SegmentStatus.ARRIVED, "HANDOVER_NOT_CONFIRMED"
            )

        return SegmentCompletionResult(
            True, SegmentStatus.SEGMENT_COMPLETED, "SEGMENT_COMPLETED"
        )

    def order_status(
        self,
        leg_statuses: list[SegmentStatus],
        final_delivery_completed: bool,
    ) -> OrderStatus:
        if not leg_statuses:
            return OrderStatus.FAILED
        if not final_delivery_completed:
            return OrderStatus.IN_TRANSIT
        if all(s == SegmentStatus.SEGMENT_COMPLETED for s in leg_statuses):
            return OrderStatus.ORDER_COMPLETED
        return OrderStatus.IN_TRANSIT
