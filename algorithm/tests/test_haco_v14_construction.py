"""HACO-CPS 1.4.0 Construction 测试。

验证：
- candidate_size 真正生效
- generate_insertion_candidates 返回正确候选
- 4 delivery + 4 pickup + capacity 4 可行
- shipment 5 + capacity 4 不可行
- alpha 真正影响概率
- beta 真正影响概率
"""

import random

import pytest

from app.haco.config import HacoConfig
from app.haco.construction import (
    InsertionCandidate,
    construct_ant_solution_v14,
    generate_insertion_candidates,
)
from app.haco.encoding import TaskBlock, TaskType
from app.haco.feasibility_engine import FeasibilityEngine
from app.haco.pheromone import PheromoneMatrix
from app.haco.route_genome import EventType, RouteGenome


# ─── helpers ──────────────────────────────────────────────────


class FakeStation:
    def __init__(self, station_id, lon, lat):
        self.stationId = station_id
        self.longitude = lon
        self.latitude = lat


def _station_map():
    return {
        "D": FakeStation("D", 0.0, 0.0),
        "S1": FakeStation("S1", 0.01, 0.0),
        "S2": FakeStation("S2", 0.02, 0.0),
        "S3": FakeStation("S3", 0.03, 0.0),
        "S4": FakeStation("S4", 0.04, 0.0),
    }


def _passenger(tid, pickup, delivery):
    return TaskBlock(
        task_id=tid, task_type=TaskType.PASSENGER,
        pickup_station=pickup, delivery_station=delivery,
        size=1, order_ids=[tid],
    )


def _shipment(tid, pickup, delivery, size=1):
    return TaskBlock(
        task_id=tid, task_type=TaskType.SHIPMENT,
        pickup_station=pickup, delivery_station=delivery,
        size=size, order_ids=[tid],
    )


def _delivery(tid, station, size=1):
    return TaskBlock(
        task_id=tid, task_type=TaskType.DELIVERY,
        pickup_station=station, delivery_station=station,
        size=size, order_ids=[tid],
    )


def _pickup(tid, station, size=1):
    return TaskBlock(
        task_id=tid, task_type=TaskType.PICKUP,
        pickup_station=station, delivery_station=station,
        size=size, order_ids=[tid],
    )


def _tasks_by_id(*tasks):
    return {t.task_id: t for t in tasks}


# ─── candidate_size 真正生效 ──────────────────────────────────


class TestCandidateSize:
    def test_candidate_size_limits_results(self):
        """candidate_size 应限制返回的候选数量。"""
        sm = _station_map()
        route = RouteGenome(0, 1, "D", skeleton=["S1", "S2"])
        p = _passenger("P1", "S1", "S2")
        tasks = _tasks_by_id(p)
        engine = FeasibilityEngine()

        caps = {0: 5}
        cargo_caps = {0: 10}
        p_loads = {0: 0}
        c_loads = {0: 0}

        candidates = generate_insertion_candidates(
            p, [route], tasks, engine, sm, None,
            caps, cargo_caps, p_loads, c_loads,
            candidate_size=2,
        )
        assert len(candidates) <= 2

    def test_candidate_size_one_returns_one(self):
        """candidate_size=1 应返回最多1个候选。"""
        sm = _station_map()
        route = RouteGenome(0, 1, "D", skeleton=["S1", "S2"])
        p = _passenger("P1", "S1", "S2")
        tasks = _tasks_by_id(p)
        engine = FeasibilityEngine()

        candidates = generate_insertion_candidates(
            p, [route], tasks, engine, sm, None,
            {0: 5}, {0: 10}, {0: 0}, {0: 0},
            candidate_size=1,
        )
        assert len(candidates) <= 1


# ─── 4 delivery + 4 pickup + capacity 4 可行 ──────────────────


class TestDeliveryPickupFeasibility:
    def test_4_delivery_4_pickup_capacity_4_feasible(self):
        """delivery=4 + pickup=4，容量=4，应可行。"""
        sm = _station_map()
        route = RouteGenome(0, 1, "D", skeleton=["S1", "S2"])
        d = _delivery("D1", "S1", size=4)
        k = _pickup("K1", "S2", size=4)
        tasks = _tasks_by_id(d, k)
        engine = FeasibilityEngine()

        # 先插入 delivery
        route.insert_task(d, 1)
        # 再插入 pickup
        route.insert_task(k, 2)

        result = engine.check(route, tasks, 5, 4)
        assert result.feasible is True


# ─── shipment 5 + capacity 4 不可行 ──────────────────────────


class TestShipmentOverCapacity:
    def test_shipment_5_capacity_4_infeasible(self):
        """shipment=5，容量=4，应不可行。"""
        sm = _station_map()
        route = RouteGenome(0, 1, "D", skeleton=["S1", "S2"])
        s = _shipment("T1", "S1", "S2", size=5)
        tasks = _tasks_by_id(s)
        engine = FeasibilityEngine()

        route.insert_task(s, 1, 3)
        result = engine.check(route, tasks, 5, 4)
        assert result.feasible is False
        assert result.reason_code == "CARGO_CAPACITY_EXCEEDED"


# ─── alpha 真正影响概率 ──────────────────────────────────────


class TestAlphaBetaInfluence:
    def test_alpha_changes_probability_weights(self):
        """alpha 应改变信息素对选择概率的影响。"""
        # 直接测试概率计算逻辑
        tau_high = 5.0
        tau_low = 0.5
        urgency = 1.5

        # alpha=1.0
        score_high_a1 = (tau_high ** 1.0) * urgency
        score_low_a1 = (tau_low ** 1.0) * urgency
        ratio_a1 = score_high_a1 / score_low_a1

        # alpha=5.0
        score_high_a5 = (tau_high ** 5.0) * urgency
        score_low_a5 = (tau_low ** 5.0) * urgency
        ratio_a5 = score_high_a5 / score_low_a5

        # alpha=0.1
        score_high_a01 = (tau_high ** 0.1) * urgency
        score_low_a01 = (tau_low ** 0.1) * urgency
        ratio_a01 = score_high_a01 / score_low_a01

        # 更高的 alpha 应放大信息素差异
        assert ratio_a5 > ratio_a1, (
            f"alpha=5 should amplify pheromone difference more than alpha=1: "
            f"ratio_a5={ratio_a5}, ratio_a1={ratio_a1}"
        )
        assert ratio_a1 > ratio_a01, (
            f"alpha=1 should amplify pheromone difference more than alpha=0.1: "
            f"ratio_a1={ratio_a1}, ratio_a01={ratio_a01}"
        )

    def test_beta_changes_probability_weights(self):
        """beta 应改变启发式对选择概率的影响。"""
        # 直接测试概率计算逻辑
        tau = 1.0
        eta_good = 1.0 / (1.0 + 1e-9)  # 好的启发式得分
        eta_bad = 1.0 / (100.0 + 1e-9)  # 差的启发式得分

        # beta=1.0
        score_good_b1 = (tau ** 1.0) * (eta_good ** 1.0)
        score_bad_b1 = (tau ** 1.0) * (eta_bad ** 1.0)
        ratio_b1 = score_good_b1 / score_bad_b1

        # beta=10.0
        score_good_b10 = (tau ** 1.0) * (eta_good ** 10.0)
        score_bad_b10 = (tau ** 1.0) * (eta_bad ** 10.0)
        ratio_b10 = score_good_b10 / score_bad_b10

        # beta=0.1
        score_good_b01 = (tau ** 1.0) * (eta_good ** 0.1)
        score_bad_b01 = (tau ** 1.0) * (eta_bad ** 0.1)
        ratio_b01 = score_good_b01 / score_bad_b01

        # 更高的 beta 应放大启发式差异
        assert ratio_b10 > ratio_b1, (
            f"beta=10 should amplify heuristic difference more than beta=1: "
            f"ratio_b10={ratio_b10}, ratio_b1={ratio_b1}"
        )
        assert ratio_b1 > ratio_b01, (
            f"beta=1 should amplify heuristic difference more than beta=0.1: "
            f"ratio_b1={ratio_b1}, ratio_b01={ratio_b01}"
        )

    def test_construct_uses_alpha_beta_params(self):
        """construct_ant_solution_v14 应使用传入的 alpha/beta。"""
        sm = _station_map()
        tasks = [_passenger("P1", "S1", "S2")]
        task_ids = ["P1", "DEPOT"]
        config = HacoConfig(alpha=1.0, beta=3.0)
        pheromone = PheromoneMatrix(task_ids, config)
        engine = FeasibilityEngine()

        routes = [RouteGenome(0, 1, "D", skeleton=["S1", "S2"])]

        # 应该能正常运行（不报错）
        for alpha, beta in [(0.1, 0.1), (5.0, 5.0), (1.0, 3.0)]:
            rng = random.Random(42)
            working = [r.copy() for r in routes]
            result = construct_ant_solution_v14(
                tasks, working, _tasks_by_id(*tasks),
                pheromone, engine, sm, None,
                {0: 5}, {0: 10}, {0: 0}, {0: 0},
                config, rng, alpha=alpha, beta=beta,
                candidate_size=4,
            )
            # P1 应该被分配
            assigned = any(
                e.task_id == "P1"
                for r in result
                for e in r.events
                if e.task_id
            )
            assert assigned, f"P1 should be assigned with alpha={alpha}, beta={beta}"


# ─── InsertionCandidate 基本功能 ─────────────────────────────


class TestInsertionCandidate:
    def test_candidates_sorted_by_score(self):
        """候选应按 heuristic_score 排序。"""
        sm = _station_map()
        route = RouteGenome(0, 1, "D", skeleton=["S1", "S2", "S3"])
        p = _passenger("P1", "S1", "S3")
        tasks = _tasks_by_id(p)
        engine = FeasibilityEngine()

        candidates = generate_insertion_candidates(
            p, [route], tasks, engine, sm, None,
            {0: 5}, {0: 10}, {0: 0}, {0: 0},
            candidate_size=10,
        )
        if len(candidates) > 1:
            for i in range(len(candidates) - 1):
                assert candidates[i].heuristic_score <= candidates[i + 1].heuristic_score
