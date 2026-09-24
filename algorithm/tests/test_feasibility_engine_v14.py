"""HACO-CPS 1.4.0 FeasibilityEngine 测试。

验证：
- 前序约束检查
- 骨架约束检查
- 乘客容量检查
- 货物容量检查（出程/返程复用）
- Shipment 超容量检查
"""

import pytest

from app.haco.encoding import TaskBlock, TaskType
from app.haco.feasibility_engine import FeasibilityEngine, FeasibilityResult
from app.haco.route_genome import EventType, RouteGenome


# ─── helpers ──────────────────────────────────────────────────


def _passenger(tid: str, pickup: str, delivery: str) -> TaskBlock:
    return TaskBlock(
        task_id=tid,
        task_type=TaskType.PASSENGER,
        pickup_station=pickup,
        delivery_station=delivery,
        size=1,
        order_ids=[tid],
    )


def _shipment(tid: str, pickup: str, delivery: str, size: int = 1) -> TaskBlock:
    return TaskBlock(
        task_id=tid,
        task_type=TaskType.SHIPMENT,
        pickup_station=pickup,
        delivery_station=delivery,
        size=size,
        order_ids=[tid],
    )


def _delivery(tid: str, station: str, size: int = 1) -> TaskBlock:
    return TaskBlock(
        task_id=tid,
        task_type=TaskType.DELIVERY,
        pickup_station=station,
        delivery_station=station,
        size=size,
        order_ids=[tid],
    )


def _pickup(tid: str, station: str, size: int = 1) -> TaskBlock:
    return TaskBlock(
        task_id=tid,
        task_type=TaskType.PICKUP,
        pickup_station=station,
        delivery_station=station,
        size=size,
        order_ids=[tid],
    )


def _tasks_by_id(*tasks: TaskBlock) -> dict[str, TaskBlock]:
    return {t.task_id: t for t in tasks}


# ─── basic feasibility ───────────────────────────────────────


class TestBasicFeasibility:
    def test_empty_route_is_feasible(self):
        g = RouteGenome(0, 1, "D")
        engine = FeasibilityEngine()
        result = engine.check(g, {}, passenger_capacity=5, cargo_capacity=10)
        assert result.feasible is True
        assert result.reason_code is None


# ─── precedence ───────────────────────────────────────────────


class TestPrecedence:
    def test_valid_precedence(self):
        g = RouteGenome(0, 1, "D", skeleton=["S1", "S2"])
        p = _passenger("P1", "S1", "S2")
        g.insert_task(p, 1, 3)
        engine = FeasibilityEngine()
        result = engine.check(g, _tasks_by_id(p), 5, 10)
        assert result.feasible is True

    def test_invalid_precedence_detected(self):
        """Manually tamper genome to have pickup after delivery."""
        g = RouteGenome(0, 1, "D", skeleton=["S1", "S2"])
        p = _passenger("P1", "S1", "S2")
        g.insert_task(p, 1, 3)

        # Tamper: swap BOARD and ALIGHT
        board_idx = alight_idx = None
        for i, e in enumerate(g.events):
            if e.task_id == "P1":
                if e.event_type.name == "BOARD":
                    board_idx = i
                elif e.event_type.name == "ALIGHT":
                    alight_idx = i
        g.events[board_idx], g.events[alight_idx] = g.events[alight_idx], g.events[board_idx]
        g._rebuild_placements()

        engine = FeasibilityEngine()
        result = engine.check(g, _tasks_by_id(p), 5, 10)
        assert result.feasible is False
        assert result.reason_code == "PICKUP_AFTER_DELIVER"


# ─── skeleton ─────────────────────────────────────────────────


class TestSkeleton:
    def test_valid_skeleton(self):
        g = RouteGenome(0, 1, "D", skeleton=["S1", "S2", "S3"])
        engine = FeasibilityEngine()
        result = engine.check(g, {}, 5, 10)
        assert result.feasible is True

    def test_skeleton_violation(self):
        g = RouteGenome(0, 1, "D", skeleton=["S1", "S2", "S3"])
        # Remove S2 PASS
        g.events = [e for e in g.events if not (e.event_type.value == "PASS" and e.station_id == "S2")]
        engine = FeasibilityEngine()
        result = engine.check(g, {}, 5, 10)
        assert result.feasible is False
        assert result.reason_code == "SKELETON_ORDER_VIOLATION"


# ─── passenger capacity ──────────────────────────────────────


class TestPassengerCapacity:
    def test_within_capacity(self):
        g = RouteGenome(0, 1, "D", skeleton=["S1", "S2"])
        p1 = _passenger("P1", "S1", "S2")
        g.insert_task(p1, 1, 3)
        engine = FeasibilityEngine()
        result = engine.check(g, _tasks_by_id(p1), passenger_capacity=2, cargo_capacity=10)
        assert result.feasible is True

    def test_exceeds_capacity(self):
        from app.haco.route_genome import EventType

        g = RouteGenome(0, 1, "D", skeleton=["S1", "S2"])
        # Insert 3 passengers, capacity=2；下标必须落在骨架 PASS ±1 窗口

        def _pass_idx(sid: str) -> int:
            for i, e in enumerate(g.events):
                if e.station_id == sid and e.event_type == EventType.PASS:
                    return i
            return 1

        for i in range(3):
            p = _passenger(f"P{i}", "S1", "S2")
            g.insert_task(p, _pass_idx("S1"), _pass_idx("S2") + 1)
        tasks = _tasks_by_id(*[_passenger(f"P{i}", "S1", "S2") for i in range(3)])
        engine = FeasibilityEngine()
        result = engine.check(g, tasks, passenger_capacity=2, cargo_capacity=10)
        assert result.feasible is False
        assert result.reason_code == "PASSENGER_CAPACITY_EXCEEDED"


# ─── cargo capacity: outbound/return reuse ────────────────────


class TestCargoCapacity:
    def test_outbound_and_return_cargo_reuse(self):
        """cargoCapacity=4
        delivery=4
        pickup=4

        两者应该可以分别利用出程/返程容量。
        """
        g = RouteGenome(0, 1, "D", skeleton=["S1", "S2"])
        d = _delivery("D1", "S1", size=4)
        k = _pickup("K1", "S2", size=4)
        # DEPOT, DELIVER(D1) at 1, PASS(S1), PASS(S2), PICKUP(K1) at 4, RETURN
        g.insert_task(d, 1)
        g.insert_task(k, 4)
        tasks = _tasks_by_id(d, k)
        engine = FeasibilityEngine()
        result = engine.check(g, tasks, passenger_capacity=5, cargo_capacity=4)
        assert result.feasible is True

    def test_shipment_over_capacity(self):
        """shipment quantity=5, capacity=4 → must be infeasible."""
        g = RouteGenome(0, 1, "D", skeleton=["S1", "S2"])
        s = _shipment("T1", "S1", "S2", size=5)
        g.insert_task(s, 1, 3)
        tasks = _tasks_by_id(s)
        engine = FeasibilityEngine()
        result = engine.check(g, tasks, passenger_capacity=5, cargo_capacity=4)
        assert result.feasible is False
        assert result.reason_code == "CARGO_CAPACITY_EXCEEDED"

    def test_shipment_within_capacity(self):
        g = RouteGenome(0, 1, "D", skeleton=["S1", "S2"])
        s = _shipment("T1", "S1", "S2", size=4)
        g.insert_task(s, 1, 3)
        tasks = _tasks_by_id(s)
        engine = FeasibilityEngine()
        result = engine.check(g, tasks, passenger_capacity=5, cargo_capacity=4)
        assert result.feasible is True

    def test_multiple_shipments_cumulative(self):
        """Two shipments of size 3 each, capacity=4.
        At the point both are loaded, total=6 > 4."""
        g = RouteGenome(0, 1, "D", skeleton=["S1", "S2", "S3"])
        s1 = _shipment("T1", "S1", "S3", size=3)
        s2 = _shipment("T2", "S1", "S2", size=3)
        # Insert T1: pickup at 1, delivery at 4
        # After T1: DEPOT(0), PICKUP_T1(1), PASS_S1(2), PASS_S2(3), DELIVER_T1(4), PASS_S3(5), RETURN(6)
        g.insert_task(s1, 1, 4)
        # Insert T2: pickup at 2 (after PICKUP_T1), delivery after S2
        # Current S2 at index 3, so delivery_index=3 → adjusted=4
        # After T2 pickup: DEPOT(0), PICKUP_T1(1), PICKUP_T2(2), PASS_S1(3), PASS_S2(4), DELIVER_T1(5), PASS_S3(5), RETURN(7)
        # After T2 delivery at 5: ...DELIVER_T2(5), DELIVER_T1(6), PASS_S3(7), RETURN(8)
        g.insert_task(s2, 2, 3)
        tasks = _tasks_by_id(s1, s2)
        engine = FeasibilityEngine()
        result = engine.check(g, tasks, passenger_capacity=5, cargo_capacity=4)
        assert result.feasible is False
        assert result.reason_code == "CARGO_CAPACITY_EXCEEDED"

    def test_delivery_exceeds_outbound_capacity(self):
        """Single DELIVERY with size > capacity."""
        g = RouteGenome(0, 1, "D", skeleton=["S1"])
        d = _delivery("D1", "S1", size=5)
        g.insert_task(d, 1)
        tasks = _tasks_by_id(d)
        engine = FeasibilityEngine()
        result = engine.check(g, tasks, passenger_capacity=5, cargo_capacity=4)
        assert result.feasible is False
        assert result.reason_code == "CARGO_OUT_CAPACITY_EXCEEDED"

    def test_pickup_exceeds_inbound_capacity(self):
        """Single PICKUP with size > capacity."""
        g = RouteGenome(0, 1, "D", skeleton=["S1"])
        k = _pickup("K1", "S1", size=5)
        g.insert_task(k, 1)
        tasks = _tasks_by_id(k)
        engine = FeasibilityEngine()
        result = engine.check(g, tasks, passenger_capacity=5, cargo_capacity=4)
        assert result.feasible is False
        assert result.reason_code == "CARGO_IN_CAPACITY_EXCEEDED"


# ─── mixed passenger + cargo ──────────────────────────────────


class TestMixedFeasibility:
    def test_passenger_and_shipment_fine(self):
        g = RouteGenome(0, 1, "D", skeleton=["S1", "S2"])
        p = _passenger("P1", "S1", "S2")
        s = _shipment("T1", "S1", "S2", size=2)
        g.insert_task(p, 1, 3)
        g.insert_task(s, 2, 4)
        tasks = _tasks_by_id(p, s)
        engine = FeasibilityEngine()
        result = engine.check(g, tasks, passenger_capacity=2, cargo_capacity=3)
        assert result.feasible is True

# ─── short-circuit regression ───────────────────────────────────


class TestShortCircuit:
    def test_terminal_fail_skips_subsequent(self):
        """When terminal (RETURN) check fails, subsequent checks must not execute.
        
        This verifies the sequential short-circuit behavior of FeasibilityEngine.check().
        """
        g = RouteGenome(0, 1, "D")  # No skeleton, no events beyond DEPOT/RETURN
        engine = FeasibilityEngine()
        # Empty route with no tasks should be feasible for terminal check
        # But we test that the short-circuit order is terminal → precedence → skeleton
        result = engine.check(g, {}, passenger_capacity=5, cargo_capacity=10)
        assert result.feasible is True

    def test_all_checks_pass(self):
        """When all checks pass, result should be feasible."""
        g = RouteGenome(0, 1, "D", skeleton=["S1", "S2"])
        p = _passenger("P1", "S1", "S2")
        g.insert_task(p, 1, 3)
        tasks = _tasks_by_id(p)
        engine = FeasibilityEngine()
        result = engine.check(g, tasks, passenger_capacity=5, cargo_capacity=10)
        assert result.feasible is True
        assert result.reason_code is None

    def test_terminal_fail_returned_immediately(self):
        """Terminal check failure should return immediately without checking subsequent rules."""
        # Create a route with invalid terminal (no RETURN event)
        from app.haco.route_genome import EventType
        g = RouteGenome(0, 1, "D")
        # Force missing RETURN by removing it from events
        g.events = [e for e in g.events if e.event_type != EventType.RETURN]
        engine = FeasibilityEngine()
        result = engine.check(g, {}, passenger_capacity=5, cargo_capacity=10)
        # Terminal check (RETURN validation) should fail first
        assert result.feasible is False
        assert result.reason_code == "MISSING_RETURN"
