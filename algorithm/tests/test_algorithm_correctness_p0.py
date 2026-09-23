"""P0 算法正确性回归：SA 接受概率 / Swap 原子性 / RouteGenome 不变量 / Gap。"""

from __future__ import annotations

import math
import random
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.haco.alns_v14 import sa_accept
from app.haco.encoding import ObjectiveVector, TaskBlock, TaskType
from app.haco.local_search_v14 import (
    MoveType,
    NeighborhoodMove,
    apply_move,
    apply_move_detailed,
)
from app.haco.route_genome import EventType, RouteGenome


def _task(tid: str, ttype=TaskType.SHIPMENT, p="A", d="B") -> TaskBlock:
    return TaskBlock(
        task_id=tid,
        task_type=ttype,
        pickup_station=p,
        delivery_station=d,
        size=1,
    )


def _route(vid=0, skeleton=None) -> RouteGenome:
    return RouteGenome(
        vehicle_index=vid,
        vehicle_id=vid + 1,
        depot_station="D",
        skeleton=skeleton or [],
    )


# ═══════════════════════════════════════════════════════════════
# SA Acceptance
# ═══════════════════════════════════════════════════════════════


class TestSaAccept:
    def test_better_always_accepted(self):
        rng = random.Random(1)
        cur = ObjectiveVector(vehicle_count=2, total_distance=100)
        cand = ObjectiveVector(vehicle_count=1, total_distance=100)
        for _ in range(50):
            assert sa_accept(cur, cand, temperature=0.001, rng=rng)

    def test_equal_always_accepted(self):
        rng = random.Random(1)
        cur = ObjectiveVector(vehicle_count=1, total_distance=50)
        cand = ObjectiveVector(vehicle_count=1, total_distance=50)
        for _ in range(50):
            assert sa_accept(cur, cand, temperature=0.001, rng=rng)

    def test_slight_worse_higher_prob_than_severe(self):
        """轻微变差接受概率应显著高于严重变差。"""
        rng = random.Random(42)
        cur = ObjectiveVector(total_distance=100.0)
        slight = ObjectiveVector(total_distance=101.0)  # delta energy = 0.1
        severe = ObjectiveVector(total_distance=1000.0)  # delta energy = 90

        t = 1.0
        n = 2000
        slight_acc = sum(
            1 for _ in range(n) if sa_accept(cur, slight, t, rng)
        )
        severe_acc = sum(
            1 for _ in range(n) if sa_accept(cur, severe, t, rng)
        )
        assert slight_acc > severe_acc
        # slight: exp(-0.1/1) ≈ 0.90
        assert 0.7 < slight_acc / n < 1.0
        # severe: exp(-90/1) ≈ 0
        assert severe_acc / n < 0.05

    def test_lower_temperature_lowers_acceptance(self):
        cur = ObjectiveVector(total_distance=100.0)
        cand = ObjectiveVector(total_distance=200.0)  # delta energy = 10
        rng_hi = random.Random(7)
        rng_lo = random.Random(7)
        n = 1500
        hi = sum(1 for _ in range(n) if sa_accept(cur, cand, 10.0, rng_hi))
        lo = sum(1 for _ in range(n) if sa_accept(cur, cand, 0.1, rng_lo))
        assert hi > lo
        # exp(-10/10)=0.37, exp(-10/0.1)≈0
        assert 0.2 < hi / n < 0.6
        assert lo / n < 0.05

    def test_temperature_nonpositive_no_nan(self):
        rng = random.Random(1)
        cur = ObjectiveVector(total_distance=10)
        cand = ObjectiveVector(total_distance=20)
        for t in (0.0, -1.0, float("nan"), float("inf")):
            result = sa_accept(cur, cand, t, rng)
            assert result is False or result is True
            assert not (isinstance(result, float) and math.isnan(result))

    def test_infeasible_rejected_by_sa(self):
        rng = random.Random(1)
        cur = ObjectiveVector(infeasibility=0.0, total_distance=100)
        cand = ObjectiveVector(infeasibility=5.0, total_distance=1)
        # 硬约束不可行不能被 SA 随机接受
        for _ in range(30):
            assert sa_accept(cur, cand, temperature=1000.0, rng=rng) is False

    def test_nonfinite_delta_safe(self):
        rng = random.Random(1)
        cur = ObjectiveVector(
            infeasibility=0.0, total_distance=1.0, total_duration=1.0
        )
        cand = ObjectiveVector(
            infeasibility=0.0,
            total_distance=float("inf"),
            total_duration=1.0,
        )
        result = sa_accept(cur, cand, temperature=1.0, rng=rng)
        assert result is True or result is False

    def test_scalar_delta_worse_sign(self):
        better = ObjectiveVector(total_distance=10)
        worse = ObjectiveVector(total_distance=20)
        assert worse.scalar_delta_worse(better) > 0
        assert better.scalar_delta_worse(worse) == 0.0
        assert better.scalar_delta_worse(better) == 0.0

    def test_objective_to_scalar_not_tuple(self):
        obj = ObjectiveVector(
            infeasibility=0,
            vehicle_count=1,
            passenger_impact=10,
            cargo_detour=1,
            total_distance=5,
            total_duration=3,
        )
        s = obj.objective_to_scalar()
        assert isinstance(s, float)
        assert math.isfinite(s)


# ═══════════════════════════════════════════════════════════════
# RouteGenome Invariants
# ═══════════════════════════════════════════════════════════════


class TestRouteGenomeInvariants:
    def test_empty_route_valid(self):
        r = _route()
        ok, reason = r.assert_invariants()
        assert ok, reason

    def test_return_always_last(self):
        r = _route()
        t = _task("t1")
        r.insert_task(t, 1, 2)
        ok, reason = r.assert_invariants()
        assert ok, reason
        assert r.events[-1].event_type == EventType.RETURN

    def test_delivery_index_eq_len_events_keeps_return_last(self):
        """delivery_index == len(events) 表达“尽可能晚”，RETURN 仍必须最后。"""
        r = _route()
        t = _task("t1")
        r.insert_task(t, 1, len(r.events))
        ok, reason = r.assert_invariants()
        assert ok, reason
        assert r.events[-1].event_type == EventType.RETURN
        # 业务事件不得出现在 RETURN 之后
        ret_i = next(
            i for i, e in enumerate(r.events) if e.event_type == EventType.RETURN
        )
        assert ret_i == len(r.events) - 1

    def test_pickup_before_delivery(self):
        r = _route()
        t = _task("t1")
        r.insert_task(t, 1, 2)
        p = r.placements["t1"]
        assert p.pickup_index < p.delivery_index
        ok, reason = r.validate_precedence()
        assert ok, reason

    def test_passenger_board_before_alight(self):
        r = _route()
        t = _task("p1", TaskType.PASSENGER, "X", "Y")
        r.insert_task(t, 1, 2)
        types = [e.event_type for e in r.events if e.task_id == "p1"]
        assert types == [EventType.BOARD, EventType.ALIGHT]
        ok, reason = r.assert_invariants()
        assert ok, reason

    def test_skeleton_not_broken(self):
        r = _route(skeleton=["S1", "S2"])
        t = _task("t1")
        r.insert_task(t, 1, 3)
        ok, reason = r.validate_skeleton()
        assert ok, reason
        ok, reason = r.assert_invariants()
        assert ok, reason

    def test_no_task_loss_or_duplication(self):
        r = _route()
        r.insert_task(_task("a"), 1, 2)
        r.insert_task(_task("b"), 1, 3)
        r.remove_task("a")
        ok, reason = r.assert_invariants()
        assert ok, reason
        assert "a" not in r.placements
        assert "b" in r.placements

    def test_duplicate_insert_rejected(self):
        r = _route()
        r.insert_task(_task("a"), 1, 2)
        with pytest.raises(ValueError):
            r.insert_task(_task("a"), 1, 2)

    def test_pickup_before_depot_rejected(self):
        r = _route()
        with pytest.raises(ValueError):
            r.insert_task(_task("a"), 0, 1)

    def test_copy_isolated_from_mutation(self):
        r = _route()
        r.insert_task(_task("a"), 1, 2)
        c = r.copy()
        r.remove_task("a")
        assert "a" in c.placements
        assert "a" not in r.placements
        ok, reason = c.assert_invariants()
        assert ok, reason


# ═══════════════════════════════════════════════════════════════
# Gap-aware ops
# ═══════════════════════════════════════════════════════════════


class TestGapAware:
    def test_gap_ranges_empty_skeleton(self):
        r = _route()
        gaps = r.get_gap_ranges()
        assert len(gaps) == 1
        assert gaps[0][0] == 1

    def test_gap_ranges_with_skeleton(self):
        r = _route(skeleton=["S1", "S2"])
        gaps = r.get_gap_ranges()
        # DEPOT | gap0 | PASS S1 | gap1 | PASS S2 | gap2 | RETURN
        assert len(gaps) == 3

    def test_can_insert_into_gap(self):
        r = _route(skeleton=["S1"])
        ok, _ = r.can_insert_into_gap(1, 2)
        assert ok
        ok, _ = r.can_insert_into_gap(1, len(r.events))
        assert ok

    def test_insert_rejects_after_return(self):
        r = _route()
        with pytest.raises(IndexError):
            r.insert_task(_task("x"), len(r.events), None)

    def test_move_task_between_gaps(self):
        r = _route(skeleton=["S1", "S2"])
        t = _task("t1")
        r.insert_task(t, 1, 2)
        ok = r.move_task_between_gaps(t, 3, 4)
        assert ok
        ok, reason = r.assert_invariants()
        assert ok, reason

    def test_swap_tasks_between_gaps(self):
        r1 = _route(0, ["S1"])
        r2 = _route(1, ["S2"])
        t1 = _task("t1", p="A", d="B")
        t2 = _task("t2", p="C", d="D")
        r1.insert_task(t1, 1, 2)
        r2.insert_task(t2, 1, 2)
        ok = r1.swap_tasks_between_gaps(
            r2, "t1", "t2", (1, 2), (1, 2), t1, t2
        )
        assert ok
        assert "t2" in r1.placements
        assert "t1" in r2.placements
        ok, reason = r1.assert_invariants()
        assert ok, reason
        ok, reason = r2.assert_invariants()
        assert ok, reason


# ═══════════════════════════════════════════════════════════════
# Swap evaluate/apply 一致性
# ═══════════════════════════════════════════════════════════════


class TestSwapAtomic:
    def _two_vehicle_setup(self):
        r1 = _route(0, ["S1"])
        r2 = _route(1, ["S2"])
        t1 = _task("t1", p="A", d="B")
        t2 = _task("t2", p="C", d="D")
        r1.insert_task(t1, 1, 2)
        r2.insert_task(t2, 1, 2)
        return r1, r2, t1, t2

    def test_apply_swap_exchanges_both_tasks(self):
        r1, r2, t1, t2 = self._two_vehicle_setup()
        routes = [r1, r2]
        tasks = {"t1": t1, "t2": t2}

        move = NeighborhoodMove(
            move_type=MoveType.SWAP,
            source_vehicle=0,
            target_vehicle=1,
            task_ids=("t1", "t2"),
            pickup_positions=(1, 1),
            delivery_positions=(2, 2),
            objective=ObjectiveVector(),
        )
        result = apply_move_detailed(routes, move, tasks)
        assert result.ok, result.reason
        new_r1, new_r2 = result.routes
        # 两个任务都必须真正交换
        assert "t2" in new_r1.placements
        assert "t1" in new_r2.placements
        assert "t1" not in new_r1.placements
        assert "t2" not in new_r2.placements
        # task 数量不变
        assert new_r1.task_count() == 1
        assert new_r2.task_count() == 1

    def test_swap_task_set_unchanged(self):
        r1, r2, t1, t2 = self._two_vehicle_setup()
        before = {tid for r in (r1, r2) for tid in r.placements}
        move = NeighborhoodMove(
            move_type=MoveType.SWAP,
            source_vehicle=0,
            target_vehicle=1,
            task_ids=("t1", "t2"),
            pickup_positions=(1, 1),
            delivery_positions=(2, 2),
            objective=ObjectiveVector(),
        )
        result = apply_move_detailed([r1, r2], move, {"t1": t1, "t2": t2})
        assert result.ok
        after = {tid for r in result.routes for tid in r.placements}
        assert after == before

    def test_swap_failure_rollback(self):
        r1, r2, t1, t2 = self._two_vehicle_setup()
        # 非法插入位（pickup 在 RETURN 处）应失败并 rollback
        move = NeighborhoodMove(
            move_type=MoveType.SWAP,
            source_vehicle=0,
            target_vehicle=1,
            task_ids=("t1", "t2"),
            pickup_positions=(99, 99),
            delivery_positions=(None, None),
            objective=ObjectiveVector(),
        )
        snapshot_ids = {tid for r in (r1, r2) for tid in r.placements}
        result = apply_move_detailed([r1, r2], move, {"t1": t1, "t2": t2})
        assert not result.ok
        after = {tid for r in result.routes for tid in r.placements}
        assert after == snapshot_ids
        assert "t1" in result.routes[0].placements
        assert "t2" in result.routes[1].placements

    def test_swap_preserves_precedence(self):
        r1, r2, t1, t2 = self._two_vehicle_setup()
        move = NeighborhoodMove(
            move_type=MoveType.SWAP,
            source_vehicle=0,
            target_vehicle=1,
            task_ids=("t1", "t2"),
            pickup_positions=(1, 1),
            delivery_positions=(2, 2),
            objective=ObjectiveVector(),
        )
        result = apply_move_detailed([r1, r2], move, {"t1": t1, "t2": t2})
        assert result.ok
        for r in result.routes:
            ok, reason = r.validate_precedence()
            assert ok, reason
            ok, reason = r.validate_paired_task()
            assert ok, reason

    def test_swap_passenger_board_alight(self):
        r1 = _route(0, ["S1"])
        r2 = _route(1, ["S2"])
        p = _task("p1", TaskType.PASSENGER, "X", "Y")
        c = _task("c1", TaskType.SHIPMENT, "C", "D")
        r1.insert_task(p, 1, 2)
        r2.insert_task(c, 1, 2)
        move = NeighborhoodMove(
            move_type=MoveType.SWAP,
            source_vehicle=0,
            target_vehicle=1,
            task_ids=("p1", "c1"),
            pickup_positions=(1, 1),
            delivery_positions=(2, 2),
            objective=ObjectiveVector(),
        )
        result = apply_move_detailed([r1, r2], move, {"p1": p, "c1": c})
        assert result.ok
        # 乘客任务到 r2 后仍是 BOARD/ALIGHT
        types = [e.event_type for e in result.routes[1].events if e.task_id == "p1"]
        assert types == [EventType.BOARD, EventType.ALIGHT]

    def test_relocate_moves_single_task(self):
        r1 = _route(0, ["S1"])
        r2 = _route(1, ["S2"])
        t1 = _task("t1")
        r1.insert_task(t1, 1, 2)
        move = NeighborhoodMove(
            move_type=MoveType.RELOCATE,
            source_vehicle=0,
            target_vehicle=1,
            task_ids=("t1",),
            pickup_positions=(1,),
            delivery_positions=(2,),
            objective=ObjectiveVector(),
        )
        result = apply_move_detailed([r1, r2], move, {"t1": t1})
        assert result.ok
        assert "t1" in result.routes[1].placements
        assert "t1" not in result.routes[0].placements


# ═══════════════════════════════════════════════════════════════
# Passenger impact 一致性
# ═══════════════════════════════════════════════════════════════


class TestPassengerImpact:
    def test_initial_load_affects_impact(self):
        from app.haco.evaluator import calculate_passenger_impact

        r = _route()
        cargo = _task("c1", TaskType.SHIPMENT, "A", "B")
        r.insert_task(cargo, 1, 2)

        class S:
            def __init__(self, sid):
                self.stationId = sid
                self.longitude = 0.0
                self.latitude = 0.0

        sm = {"D": S("D"), "A": S("A"), "B": S("B")}
        # 非零坐标才能产生 detour
        sm["A"].longitude = 0.01
        sm["B"].longitude = 0.02

        m0 = calculate_passenger_impact(r, {"c1": cargo}, sm, None, 0)
        m2 = calculate_passenger_impact(r, {"c1": cargo}, sm, None, 2)
        # initial load 高时 passenger impact 不应更低
        assert m2["passenger_impact"] >= m0["passenger_impact"]

    def test_calculate_and_evaluate_same(self):
        from app.haco.evaluator import (
            calculate_passenger_impact,
            evaluate_route_genome,
        )

        r = _route()
        t = _task("c1", TaskType.SHIPMENT, "A", "B")
        r.insert_task(t, 1, 2)

        class S:
            def __init__(self, sid, lon=0.0):
                self.stationId = sid
                self.longitude = lon
                self.latitude = 0.0

        sm = {"D": S("D"), "A": S("A", 0.01), "B": S("B", 0.03)}
        a = calculate_passenger_impact(r, {"c1": t}, sm, None, 1)
        b = evaluate_route_genome(r, {"c1": t}, sm, None, initial_passenger_load=1)
        assert a == b
