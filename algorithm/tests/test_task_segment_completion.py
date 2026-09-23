"""TaskSegmentCompletion 测试：ARRIVED != COMPLETED。"""

from app.dispatch_opt.segment_completion import (
    OrderStatus,
    SegmentStatus,
    TaskSegmentCompletionValidator,
)


def test_arrived_not_equal_completed():
    v = TaskSegmentCompletionValidator()
    r = v.validate_segment(arrived=True, required_actions={"PICKUP"}, completed_actions=set())
    assert not r.ok
    assert r.segment_status == SegmentStatus.ARRIVED


def test_pickup_segment_complete():
    v = TaskSegmentCompletionValidator()
    r = v.validate_segment(
        arrived=True, required_actions={"PICKUP"}, completed_actions={"PICKUP"}
    )
    assert r.ok
    assert r.segment_status == SegmentStatus.SEGMENT_COMPLETED


def test_handover_unconfirmed_not_complete():
    v = TaskSegmentCompletionValidator()
    r = v.validate_segment(
        arrived=True,
        required_actions={"HANDOVER"},
        completed_actions={"HANDOVER"},
        handover_confirmed=False,
    )
    assert not r.ok
    assert r.reason_code == "HANDOVER_NOT_CONFIRMED"


def test_multileg_order_complete_only_last_leg():
    v = TaskSegmentCompletionValidator()
    assert (
        v.order_status(
            [SegmentStatus.SEGMENT_COMPLETED, SegmentStatus.IN_PROGRESS, SegmentStatus.PLANNED],
            final_delivery_completed=False,
        )
        == OrderStatus.IN_TRANSIT
    )
    assert (
        v.order_status(
            [SegmentStatus.SEGMENT_COMPLETED] * 3,
            final_delivery_completed=True,
        )
        == OrderStatus.ORDER_COMPLETED
    )
