"""RemainingSegmentReplan 测试。"""

from app.dispatch_opt.remaining_replan import (
    PlanSegment,
    RemainingSegmentReplan,
    ReplanRequest,
    ReplanTrigger,
)


def test_completed_and_locked_not_in_free():
    rp = RemainingSegmentReplan()
    segs = [
        PlanSegment("s1", completed=True),
        PlanSegment("s2", locked=True, mandatory=True),
        PlanSegment("s3"),
        PlanSegment("s4"),
    ]
    scope = rp.build_scope(ReplanRequest(ReplanTrigger.REACHABILITY_CHANGED, segs))
    assert "s1" in scope.completed_segment_ids
    assert "s2" in scope.locked_segment_ids
    assert set(scope.free_segment_ids) == {"s3", "s4"}


def test_locked_passenger_stop_preserved():
    rp = RemainingSegmentReplan()
    segs = [PlanSegment("mp1", locked=True, mandatory=True), PlanSegment("x")]
    scope = rp.build_scope(ReplanRequest(ReplanTrigger.MANUAL_REPLAN, segs))
    assert "mp1" not in scope.free_segment_ids


def test_future_tasks_adjustable():
    rp = RemainingSegmentReplan()
    scope = rp.build_scope(
        ReplanRequest(
            ReplanTrigger.ORDER_CANCELLATION,
            [PlanSegment("done", completed=True), PlanSegment("future")],
        )
    )
    assert scope.free_segment_ids == ["future"]


def test_no_global_on_ordinary_progress():
    rp = RemainingSegmentReplan()
    scope = rp.build_scope(
        ReplanRequest(
            ReplanTrigger.REACHABILITY_CHANGED,
            [PlanSegment("a"), PlanSegment("b")],
        )
    )
    assert scope.allow_global is False
    assert scope.reason_code == "LOCAL_REMAINING"


def test_global_only_when_no_free_and_hard_trigger():
    rp = RemainingSegmentReplan()
    scope = rp.build_scope(
        ReplanRequest(
            ReplanTrigger.CURRENT_SEGMENT_IMPOSSIBLE,
            [PlanSegment("a", locked=True, mandatory=True)],
        )
    )
    assert scope.allow_global is True
    assert scope.reason_code == "GLOBAL_ESCALATION"


def test_invariants_ok():
    rp = RemainingSegmentReplan()
    req = ReplanRequest(
        ReplanTrigger.VEHICLE_FAILURE,
        [PlanSegment("c", completed=True), PlanSegment("f")],
        completed_task_ids=("c",),
    )
    scope = rp.build_scope(req)
    ok, reason = rp.assert_invariants(scope, req)
    assert ok, reason
