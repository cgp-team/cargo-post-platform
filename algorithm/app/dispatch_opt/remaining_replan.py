"""Remaining Segment Replan：只重优化未执行/未锁定部分。"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Sequence


class ReplanTrigger(str, Enum):
    VEHICLE_FAILURE = "VEHICLE_FAILURE"
    ROAD_UNAVAILABLE = "ROAD_UNAVAILABLE"
    ORDER_CANCELLATION = "ORDER_CANCELLATION"
    EMERGENCY_ORDER = "EMERGENCY_ORDER"
    MANUAL_REPLAN = "MANUAL_REPLAN"
    DRIVER_REQUEST = "DRIVER_REQUEST"
    CURRENT_SEGMENT_IMPOSSIBLE = "CURRENT_SEGMENT_IMPOSSIBLE"
    REACHABILITY_CHANGED = "REACHABILITY_CHANGED"
    ROAD_BECAME_UNAVAILABLE = "ROAD_BECAME_UNAVAILABLE"
    SERVICE_POINT_BLOCKED = "SERVICE_POINT_BLOCKED"
    VEHICLE_ACCESS_CHANGED = "VEHICLE_ACCESS_CHANGED"
    ORDER_TARGET_UNREACHABLE = "ORDER_TARGET_UNREACHABLE"


@dataclass
class PlanSegment:
    segment_id: str
    locked: bool = False
    completed: bool = False
    mandatory: bool = False
    data: Any = None


@dataclass
class ReplanRequest:
    trigger: ReplanTrigger
    segments: Sequence[PlanSegment]
    completed_task_ids: Sequence[str] = ()
    locked_task_ids: Sequence[str] = ()


@dataclass
class ReplanScope:
    free_segment_ids: list[str] = field(default_factory=list)
    locked_segment_ids: list[str] = field(default_factory=list)
    completed_segment_ids: list[str] = field(default_factory=list)
    allow_global: bool = False
    reason_code: str = "LOCAL_REMAINING"


class RemainingSegmentReplan:
    """执行过程不因普通到站自动全局 Smart Dispatch。

    异常事件只把 UNEXECUTED / UNLOCKED 作为搜索空间；
    保留 COMPLETED / LOCKED / 已服务乘客站点。
    """

    def build_scope(self, request: ReplanRequest) -> ReplanScope:
        free: list[str] = []
        locked: list[str] = []
        completed: list[str] = []
        for seg in request.segments:
            if seg.completed or seg.segment_id in set(request.completed_task_ids):
                completed.append(seg.segment_id)
            elif seg.locked or seg.segment_id in set(request.locked_task_ids) or seg.mandatory:
                locked.append(seg.segment_id)
            else:
                free.append(seg.segment_id)

        # 普通到站不触发：本类只处理正式异常/人工事件
        allow_global = request.trigger in (
            ReplanTrigger.VEHICLE_FAILURE,
            ReplanTrigger.CURRENT_SEGMENT_IMPOSSIBLE,
            ReplanTrigger.EMERGENCY_ORDER,
            ReplanTrigger.MANUAL_REPLAN,
        ) and not free

        reason = "LOCAL_REMAINING" if free else (
            "GLOBAL_ESCALATION" if allow_global else "NO_FREE_SEGMENT_HOLD"
        )
        return ReplanScope(
            free_segment_ids=free,
            locked_segment_ids=locked,
            completed_segment_ids=completed,
            allow_global=allow_global,
            reason_code=reason,
        )

    def assert_invariants(self, scope: ReplanScope, request: ReplanRequest) -> tuple[bool, str | None]:
        done = set(scope.completed_segment_ids)
        lock = set(scope.locked_segment_ids)
        if done & lock:
            return False, "SEGMENT_BOTH_COMPLETED_AND_LOCKED"
        for tid in request.completed_task_ids:
            if tid not in done and tid not in lock:
                # completed tasks must not appear in free
                pass
        for tid in scope.free_segment_ids:
            if tid in set(request.completed_task_ids) or tid in set(request.locked_task_ids):
                return False, f"LOCKED_OR_COMPLETED_IN_FREE:{tid}"
        return True, None
