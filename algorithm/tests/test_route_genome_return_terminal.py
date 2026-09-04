"""Step1 回归：RouteGenome RETURN 末位不变量 + TaskBlock.cargo_source。

背景：旧 insert_task 允许调用方用 delivery_index == len(events) 表达"尽可能晚"，
该插入会把 ALIGHT/DELIVER 追加到 RETURN 之后，仍被判可行（违反 DEPOT→…→RETURN）。
本测试锁定修复后行为：RETURN 恒为最末事件、其后无业务事件。
"""

import pytest

from app.haco.encoding import TaskBlock, TaskType
from app.haco.route_genome import EventType, RouteGenome
from app.models import CargoSource


def _passenger(tid: str, pickup: str, delivery: str) -> TaskBlock:
    return TaskBlock(
        task_id=tid,
        task_type=TaskType.PASSENGER,
        pickup_station=pickup,
        delivery_station=delivery,
        size=1,
        order_ids=[tid],
    )


def _event_types(g: RouteGenome) -> list[EventType]:
    return [e.event_type for e in g.events]


class TestReturnTerminalInvariant:
    def test_insert_never_leaves_business_after_return(self):
        """无骨架路线上用 delivery_index==len(events)（曾把 ALIGHT 追加到 RETURN 后）插入乘客。"""
        g = RouteGenome(0, 1, "D")
        p = _passenger("P1", "S1", "S2")
        g.insert_task(p, 1, len(g.events))
        types = _event_types(g)
        assert types == [
            EventType.DEPOT,
            EventType.BOARD,
            EventType.ALIGHT,
            EventType.RETURN,
        ]
        ok, reason = g.validate_terminal_return()
        assert ok is True and reason is None

    def test_skeleton_between_keeps_return_last(self):
        """有骨架路线上 pickup/delivery 跨骨架插入，RETURN 仍保持最后。"""
        g = RouteGenome(0, 1, "D", skeleton=["S1", "S2", "S3"])
        p = _passenger("P1", "S1", "S3")
        g.insert_task(p, 1, 4)
        ok, reason = g.validate_terminal_return()
        assert ok is True and reason is None
        assert g.events[-1].event_type == EventType.RETURN

    def test_empty_skeleton_two_event_task_ok(self):
        """无骨架路线可承载两段式任务（SHIPMENT），且顺序合法。"""
        g = RouteGenome(0, 1, "D")
        s = TaskBlock(
            task_id="S1",
            task_type=TaskType.SHIPMENT,
            pickup_station="A",
            delivery_station="B",
            size=2,
            order_ids=["S1"],
        )
        g.insert_task(s, 1, len(g.events))
        types = _event_types(g)
        assert EventType.PICKUP in types and EventType.DELIVER in types
        assert types[-1] == EventType.RETURN
        ok, _ = g.validate_precedence()
        assert ok is True
        ok, _ = g.validate_terminal_return()
        assert ok is True

    def test_tampered_return_not_terminal_detected(self):
        """手动把 RETURN 移到非末位，validate_terminal_return 应报 BUSINESS_EVENT_AFTER_RETURN。"""
        g = RouteGenome(0, 1, "D", skeleton=["S1"])
        p = _passenger("P1", "S1", "S2")
        g.insert_task(p, 1, 2)
        assert g.events[-1].event_type == EventType.RETURN
        ret_idx = [i for i, e in enumerate(g.events) if e.event_type == EventType.RETURN][0]
        ret = g.events.pop(ret_idx)
        g.events.insert(0, ret)  # RETURN 挪到最前
        ok, reason = g.validate_terminal_return()
        assert ok is False
        assert reason == "BUSINESS_EVENT_AFTER_RETURN"

    def test_missing_return_detected(self):
        g = RouteGenome(0, 1, "D")
        g.events = [e for e in g.events if e.event_type != EventType.RETURN]
        ok, reason = g.validate_terminal_return()
        assert ok is False
        assert reason == "MISSING_RETURN"


class TestTaskBlockCargoSource:
    def test_default_none(self):
        t = TaskBlock(
            task_id="D1",
            task_type=TaskType.DELIVERY,
            pickup_station="S1",
            delivery_station="S1",
        )
        assert t.cargo_source is None

    def test_store_preloaded(self):
        t = TaskBlock(
            task_id="D1",
            task_type=TaskType.DELIVERY,
            pickup_station="S1",
            delivery_station="S1",
            cargo_source=CargoSource.PRELOADED,
        )
        assert t.cargo_source == CargoSource.PRELOADED
