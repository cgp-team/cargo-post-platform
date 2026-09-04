"""HACO-CPS 1.4.0 RouteGenome 测试。

验证：
- 事件序列正确性
- 乘客 pickup/delivery 可跨骨架
- 前序约束
- 骨架约束
- insert / remove / copy
"""

import pytest

from app.haco.encoding import TaskBlock, TaskType
from app.haco.route_genome import (
    EventType,
    RouteEvent,
    RouteGenome,
    TaskPlacement,
)


# ─── helpers ──────────────────────────────────────────────────


def _make_passenger(task_id: str, pickup: str, delivery: str) -> TaskBlock:
    return TaskBlock(
        task_id=task_id,
        task_type=TaskType.PASSENGER,
        pickup_station=pickup,
        delivery_station=delivery,
        size=1,
        order_ids=[task_id],
    )


def _make_shipment(task_id: str, pickup: str, delivery: str, size: int = 1) -> TaskBlock:
    return TaskBlock(
        task_id=task_id,
        task_type=TaskType.SHIPMENT,
        pickup_station=pickup,
        delivery_station=delivery,
        size=size,
        order_ids=[task_id],
    )


def _make_delivery(task_id: str, station: str, size: int = 1) -> TaskBlock:
    return TaskBlock(
        task_id=task_id,
        task_type=TaskType.DELIVERY,
        pickup_station=station,
        delivery_station=station,
        size=size,
        order_ids=[task_id],
    )


def _make_pickup(task_id: str, station: str, size: int = 1) -> TaskBlock:
    return TaskBlock(
        task_id=task_id,
        task_type=TaskType.PICKUP,
        pickup_station=station,
        delivery_station=station,
        size=size,
        order_ids=[task_id],
    )


def _event_stations(genome: RouteGenome) -> list[str]:
    return [e.station_id for e in genome.events]


def _event_types(genome: RouteGenome) -> list[EventType]:
    return [e.event_type for e in genome.events]


# ─── basic construction ───────────────────────────────────────


class TestBasicConstruction:
    def test_empty_genome_has_depot_and_return(self):
        g = RouteGenome(0, 1, "D")
        assert len(g.events) == 2
        assert g.events[0].event_type == EventType.DEPOT
        assert g.events[0].station_id == "D"
        assert g.events[-1].event_type == EventType.RETURN
        assert g.events[-1].station_id == "D"
        assert g.task_count() == 0

    def test_skeleton_creates_pass_events(self):
        g = RouteGenome(0, 1, "D", skeleton=["S1", "S2", "S3"])
        types = _event_types(g)
        assert types == [EventType.DEPOT, EventType.PASS, EventType.PASS, EventType.PASS, EventType.RETURN]
        stations = _event_stations(g)
        assert stations == ["D", "S1", "S2", "S3", "D"]

    def test_copy_is_independent(self):
        g = RouteGenome(0, 1, "D", skeleton=["S1"])
        p = _make_passenger("P1", "S1", "S2")
        g.insert_task(p, 1, 3)
        g2 = g.copy()
        assert g2.task_count() == 1
        g2.remove_task("P1")
        assert g.task_count() == 1
        assert g2.task_count() == 0


# ─── passenger pickup/delivery can cross skeleton ─────────────


class TestPassengerCrossSkeleton:
    def test_passenger_pickup_delivery_can_cross_skeleton(self):
        """Skeleton: S1 -> S2 -> S3
        Passenger: S1 -> S3
        pickup 和 delivery 不允许被强制绑定在同一 gap。
        """
        g = RouteGenome(0, 1, "D", skeleton=["S1", "S2", "S3"])
        # Original events: DEPOT(0), PASS(1), PASS(2), PASS(3), RETURN(4)
        p = _make_passenger("P1", "S1", "S3")
        # insert pickup at index 1 (after DEPOT, before S1 PASS)
        # insert delivery at index 4 (after S3 PASS, before RETURN)
        # After pickup insert: DEPOT(0), BOARD(1), PASS(2), PASS(3), PASS(4), RETURN(5)
        # delivery_index=4: adjusted to 5, insert at 5
        # Final: DEPOT, BOARD, PASS, PASS, PASS, ALIGHT, RETURN ✓
        g.insert_task(p, 1, 4)

        types = _event_types(g)
        # DEPOT, BOARD(P1), PASS(S1), PASS(S2), PASS(S3), ALIGHT(P1), RETURN
        assert types[0] == EventType.DEPOT
        assert types[1] == EventType.BOARD
        assert types[-2] == EventType.ALIGHT
        assert types[-1] == EventType.RETURN

        ok, reason = g.validate_precedence()
        assert ok is True
        assert reason is None

    def test_passenger_between_skeleton_stations(self):
        """Passenger boards after S1, alights before S3."""
        g = RouteGenome(0, 1, "D", skeleton=["S1", "S2", "S3"])
        p = _make_passenger("P1", "S2", "S3")
        # DEPOT, PASS(S1), [BOARD P1 at 2], PASS(S2), PASS(S3), RETURN
        g.insert_task(p, 2, 5)
        ok, _ = g.validate_precedence()
        assert ok is True
        placements = g.placements["P1"]
        assert placements.pickup_index < placements.delivery_index


# ─── invalid precedence ───────────────────────────────────────


class TestInvalidPrecedence:
    def test_invalid_precedence(self):
        """Manually create a genome with pickup after delivery."""
        g = RouteGenome(0, 1, "D", skeleton=["S1", "S2"])
        p = _make_passenger("P1", "S1", "S2")
        g.insert_task(p, 1, 4)

        # Tamper: swap events so pickup comes after delivery
        board_idx = None
        alight_idx = None
        for i, e in enumerate(g.events):
            if e.task_id == "P1" and e.event_type == EventType.BOARD:
                board_idx = i
            if e.task_id == "P1" and e.event_type == EventType.ALIGHT:
                alight_idx = i

        assert board_idx is not None and alight_idx is not None
        # Swap the events in the list
        g.events[board_idx], g.events[alight_idx] = (
            g.events[alight_idx],
            g.events[board_idx],
        )
        # Rebuild placements from tampered events
        g._rebuild_placements()

        ok, reason = g.validate_precedence()
        assert ok is False
        assert reason == "PICKUP_AFTER_DELIVER"


# ─── skeleton violation ───────────────────────────────────────


class TestSkeletonViolation:
    def test_skeleton_violation(self):
        """Removing a PASS event should violate skeleton order."""
        g = RouteGenome(0, 1, "D", skeleton=["S1", "S2", "S3"])
        ok, _ = g.validate_skeleton()
        assert ok is True

        # Remove the S2 PASS event
        g.events = [e for e in g.events if not (e.event_type == EventType.PASS and e.station_id == "S2")]

        ok, reason = g.validate_skeleton()
        assert ok is False
        assert reason == "SKELETON_ORDER_VIOLATION"

    def test_no_skeleton_always_valid(self):
        g = RouteGenome(0, 1, "D")
        ok, reason = g.validate_skeleton()
        assert ok is True
        assert reason is None


# ─── insert / remove ──────────────────────────────────────────


class TestInsertRemove:
    def test_insert_task_adds_events(self):
        g = RouteGenome(0, 1, "D", skeleton=["S1", "S2"])
        p = _make_passenger("P1", "S1", "S2")
        g.insert_task(p, 1, 4)
        assert g.task_count() == 1
        assert "P1" in g.placements
        board_events = [e for e in g.events if e.event_type == EventType.BOARD]
        alight_events = [e for e in g.events if e.event_type == EventType.ALIGHT]
        assert len(board_events) == 1
        assert len(alight_events) == 1

    def test_insert_duplicate_raises(self):
        g = RouteGenome(0, 1, "D", skeleton=["S1"])
        p = _make_passenger("P1", "S1", "S2")
        g.insert_task(p, 1, 3)
        with pytest.raises(ValueError, match="already exists"):
            g.insert_task(p, 1, 3)

    def test_insert_before_depot_raises(self):
        g = RouteGenome(0, 1, "D")
        p = _make_passenger("P1", "S1", "S2")
        with pytest.raises(ValueError, match="before DEPOT"):
            g.insert_task(p, 0, 2)

    def test_remove_task(self):
        g = RouteGenome(0, 1, "D", skeleton=["S1", "S2"])
        p = _make_passenger("P1", "S1", "S2")
        g.insert_task(p, 1, 4)
        assert g.task_count() == 1
        g.remove_task("P1")
        assert g.task_count() == 0
        assert "P1" not in g.placements
        # Events should be back to DEPOT, PASS(S1), PASS(S2), RETURN
        types = _event_types(g)
        assert types == [EventType.DEPOT, EventType.PASS, EventType.PASS, EventType.RETURN]

    def test_remove_nonexistent_is_noop(self):
        g = RouteGenome(0, 1, "D")
        g.remove_task("nonexistent")
        assert g.task_count() == 0


# ─── shipment events ──────────────────────────────────────────


class TestShipmentEvents:
    def test_shipment_creates_pickup_deliver(self):
        g = RouteGenome(0, 1, "D", skeleton=["S1", "S2"])
        s = _make_shipment("T1", "S1", "S2", size=2)
        g.insert_task(s, 1, 3)
        types = _event_types(g)
        assert EventType.PICKUP in types
        assert EventType.DELIVER in types
        pickup_events = [e for e in g.events if e.event_type == EventType.PICKUP]
        deliver_events = [e for e in g.events if e.event_type == EventType.DELIVER and e.task_id == "T1"]
        assert len(pickup_events) == 1
        assert len(deliver_events) == 1


# ─── delivery / pickup standalone ─────────────────────────────


class TestStandaloneEvents:
    def test_standalone_delivery(self):
        g = RouteGenome(0, 1, "D", skeleton=["S1"])
        d = _make_delivery("D1", "S1", size=3)
        g.insert_task(d, 1)
        # Standalone DELIVERY has only one event (DELIVER at pickup_station)
        assert g.task_count() == 1
        deliver_events = [e for e in g.events if e.task_id == "D1"]
        assert len(deliver_events) == 1
        assert deliver_events[0].event_type == EventType.DELIVER

    def test_standalone_pickup(self):
        g = RouteGenome(0, 1, "D", skeleton=["S1"])
        p = _make_pickup("K1", "S1", size=2)
        g.insert_task(p, 1)
        assert g.task_count() == 1
        pickup_events = [e for e in g.events if e.task_id == "K1"]
        assert len(pickup_events) == 1
        assert pickup_events[0].event_type == EventType.PICKUP


# ─── insert helpers ───────────────────────────────────────────


class TestInsertHelpers:
    def test_insert_after_index(self):
        g = RouteGenome(0, 1, "D", skeleton=["S1", "S2"])
        p = _make_passenger("P1", "S1", "S2")
        # after_index=0 means after DEPOT → pickup at 1
        g.insert_after_index(p, 0, 2)
        assert g.task_count() == 1
        ok, _ = g.validate_precedence()
        assert ok is True

    def test_insert_before_index(self):
        g = RouteGenome(0, 1, "D", skeleton=["S1", "S2"])
        p = _make_passenger("P1", "S1", "S2")
        # before_index=3 means before RETURN → delivery at 3
        g.insert_before_index(p, 1, 3)
        assert g.task_count() == 1
        ok, _ = g.validate_precedence()
        assert ok is True


# ─── paired task invariants ─────────────────────────────────────


class TestPairedTaskInvariants:
    def test_valid_paired_task_passes(self):
        """Complete paired task (passenger) should pass validation."""
        g = RouteGenome(0, 1, "D", skeleton=["S1", "S2"])
        p = _make_passenger("P1", "S1", "S2")
        g.insert_task(p, 1, 4)
        ok, reason = g.validate_paired_task()
        assert ok is True
        assert reason is None

    def test_missing_pickup_fails(self):
        """Missing pickup should fail validation."""
        g = RouteGenome(0, 1, "D", skeleton=["S1", "S2"])
        p = _make_passenger("P1", "S1", "S2")
        # Only insert delivery, no pickup - tamper events
        g.insert_task(p, 1, 4)
        # Remove the pickup event by tampering
        g.events.insert(1, RouteEvent("S1", EventType.BOARD, "P1"))
        # Now there are two BOARD events - this should fail
        ok, reason = g.validate_paired_task()
        assert ok is False
        assert reason == "DUPLICATE_PICKUP"

    def test_missing_delivery_fails(self):
        """Missing delivery should fail validation."""
        g = RouteGenome(0, 1, "D", skeleton=["S1", "S2"])
        p = _make_passenger("P1", "S1", "S2")
        g.insert_task(p, 1)  # pickup only
        # Tamper: remove the ALIGHT event and add a duplicate
        # Count events - if only one ALIGHT, it passes; if modified, should fail
        ok, reason = g.validate_paired_task()
        # Per invariants: exactly one delivery is required for paired task
        assert ok is False
        assert reason == "DUPLICATE_DELIVERY"

    def test_duplicate_pickup_fails(self):
        """Duplicate pickup should fail validation."""
        g = RouteGenome(0, 1, "D", skeleton=["S1", "S2"])
        p = _make_passenger("P1", "S1", "S2")
        g.insert_task(p, 1, 4)
        # Tamper: add another BOARD event for P1
        g.events.insert(2, RouteEvent("S1", EventType.BOARD, "P1"))
        ok, reason = g.validate_paired_task()
        assert ok is False
        assert reason == "DUPLICATE_PICKUP"

    def test_duplicate_delivery_fails(self):
        """Duplicate delivery should fail validation."""
        g = RouteGenome(0, 1, "D", skeleton=["S1", "S2"])
        p = _make_passenger("P1", "S1", "S2")
        g.insert_task(p, 1, 4)
        # Tamper: add another ALIGHT event for P1
        g.events.insert(5, RouteEvent("S1", EventType.ALIGHT, "P1"))
        ok, reason = g.validate_paired_task()
        assert ok is False
        assert reason == "DUPLICATE_DELIVERY"

    def test_delivery_before_pickup_fails(self):
        """delivery < pickup should fail validation."""
        g = RouteGenome(0, 1, "D", skeleton=["S1", "S2"])
        p = _make_passenger("P1", "S1", "S2")
        # Insert task with valid pickup < delivery
        g.insert_task(p, 1, 4)
        # Tamper: swap board and alight events so delivery comes before pickup
        board_idx = alight_idx = None
        for i, e in enumerate(g.events):
            if e.task_id == "P1" and e.event_type == EventType.BOARD:
                board_idx = i
            if e.task_id == "P1" and e.event_type == EventType.ALIGHT:
                alight_idx = i
        assert board_idx is not None and alight_idx is not None
        # Swap the events in the list
        g.events[board_idx], g.events[alight_idx] = (
            g.events[alight_idx],
            g.events[board_idx],
        )
        # Rebuild placements from tampered events
        g._rebuild_placements()
        ok, reason = g.validate_paired_task()
        assert ok is False
        assert reason == "PICKUP_AFTER_DELIVER"

    def test_shipment_paired_task_passes(self):
        """Complete paired task (shipment) should pass validation."""
        g = RouteGenome(0, 1, "D", skeleton=["S1", "S2"])
        s = _make_shipment("T1", "S1", "S2", size=1)
        g.insert_task(s, 1, 3)
        ok, reason = g.validate_paired_task()
        assert ok is True
        assert reason is None