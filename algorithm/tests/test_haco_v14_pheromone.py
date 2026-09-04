"""HACO-CPS 1.4.0 Pheromone 测试。

验证：
- multi-vehicle pheromone 不产生跨车 edge
- extract_vehicle_task_sequences 正确提取
- deposit_multi_vehicle 独立沉积
"""

import pytest

from app.haco.config import HacoConfig
from app.haco.encoding import TaskBlock, TaskType
from app.haco.pheromone import PheromoneMatrix, extract_vehicle_task_sequences
from app.haco.route_genome import EventType, RouteGenome


# ─── helpers ──────────────────────────────────────────────────


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


# ─── extract_vehicle_task_sequences ──────────────────────────


class TestExtractVehicleTaskSequences:
    def test_extracts_per_vehicle(self):
        """每辆车应独立提取任务序列。"""
        r1 = RouteGenome(0, 1, "D", skeleton=["S1", "S2"])
        r2 = RouteGenome(1, 2, "D", skeleton=["S3", "S4"])
        p1 = _passenger("P1", "S1", "S2")
        p2 = _passenger("P2", "S3", "S4")
        r1.insert_task(p1, 1, 3)
        r2.insert_task(p2, 1, 3)

        seqs = extract_vehicle_task_sequences([r1, r2])
        assert 0 in seqs
        assert 1 in seqs
        assert seqs[0] == ["DEPOT", "P1"]
        assert seqs[1] == ["DEPOT", "P2"]

    def test_no_cross_vehicle_edges(self):
        """不应有跨车边：每辆车的序列独立。"""
        r1 = RouteGenome(0, 1, "D", skeleton=["S1", "S2"])
        r2 = RouteGenome(1, 2, "D", skeleton=["S3", "S4"])
        p1 = _passenger("P1", "S1", "S2")
        p2 = _passenger("P2", "S3", "S4")
        r1.insert_task(p1, 1, 3)
        r2.insert_task(p2, 1, 3)

        seqs = extract_vehicle_task_sequences([r1, r2])
        # 验证每辆车的序列不包含其他车的任务
        for vi, seq in seqs.items():
            for other_vi, other_seq in seqs.items():
                if vi == other_vi:
                    continue
                for tid in seq:
                    if tid == "DEPOT":
                        continue
                    assert tid not in other_seq, (
                        f"Vehicle {vi} sequence contains task from vehicle {other_vi}: {tid}"
                    )

    def test_only_pickup_events_included(self):
        """只应包含 pickup 类型事件（BOARD/PICKUP/DELIVER）。"""
        r = RouteGenome(0, 1, "D", skeleton=["S1", "S2"])
        s = _shipment("T1", "S1", "S2", size=2)
        r.insert_task(s, 1, 3)

        seqs = extract_vehicle_task_sequences([r])
        # Shipment 产生 PICKUP 和 DELIVER 事件
        # 但序列中只应包含一次 T1（PICKUP 事件）
        assert seqs[0].count("T1") == 1

    def test_delivery_only_task(self):
        """独立 DELIVERY 任务应被包含。"""
        r = RouteGenome(0, 1, "D", skeleton=["S1"])
        d = _delivery("D1", "S1", size=3)
        r.insert_task(d, 1)

        seqs = extract_vehicle_task_sequences([r])
        assert "D1" in seqs[0]

    def test_pickup_only_task(self):
        """独立 PICKUP 任务应被包含。"""
        r = RouteGenome(0, 1, "D", skeleton=["S1"])
        k = _pickup("K1", "S1", size=2)
        r.insert_task(k, 1)

        seqs = extract_vehicle_task_sequences([r])
        assert "K1" in seqs[0]


# ─── deposit_multi_vehicle ──────────────────────────────────


class TestDepositMultiVehicle:
    def test_deposit_independent(self):
        """每辆车的信息素应独立沉积。"""
        config = HacoConfig()
        task_ids = ["DEPOT", "P1", "P2"]
        pheromone = PheromoneMatrix(task_ids, config)

        vehicle_seqs = {
            0: ["DEPOT", "P1"],
            1: ["DEPOT", "P2"],
        }

        initial_tau = pheromone.get("DEPOT", "P1")
        pheromone.deposit_multi_vehicle(vehicle_seqs, cost=10.0, weight=1.0)

        # DEPOT → P1 应有信息素增加
        assert pheromone.get("DEPOT", "P1") > initial_tau
        # DEPOT → P2 也应有信息素增加
        assert pheromone.get("DEPOT", "P2") > initial_tau

    def test_no_cross_vehicle_deposit(self):
        """跨车边不应有信息素沉积。"""
        config = HacoConfig()
        task_ids = ["DEPOT", "P1", "P2"]
        pheromone = PheromoneMatrix(task_ids, config)

        vehicle_seqs = {
            0: ["DEPOT", "P1"],
            1: ["DEPOT", "P2"],
        }

        # 记录 P1 → P2 的初始信息素
        initial_cross = pheromone.get("P1", "P2")
        pheromone.deposit_multi_vehicle(vehicle_seqs, cost=10.0, weight=1.0)

        # P1 → P2 不应有信息素增加（跨车边）
        assert pheromone.get("P1", "P2") == initial_cross

    def test_deposit_with_zero_cost(self):
        """cost=0 时不应沉积。"""
        config = HacoConfig()
        task_ids = ["DEPOT", "P1"]
        pheromone = PheromoneMatrix(task_ids, config)

        initial_tau = pheromone.get("DEPOT", "P1")
        pheromone.deposit_multi_vehicle({0: ["DEPOT", "P1"]}, cost=0.0)
        assert pheromone.get("DEPOT", "P1") == initial_tau


# ─── 多车辆信息素完整性 ──────────────────────────────────────


class TestMultiVehicleIntegrity:
    def test_full_cycle(self):
        """完整循环：提取 → 沉积 → 验证无跨车边。"""
        config = HacoConfig()
        task_ids = ["DEPOT", "P1", "P2", "P3"]
        pheromone = PheromoneMatrix(task_ids, config)

        # 构建两辆车的路线
        r1 = RouteGenome(0, 1, "D", skeleton=["S1", "S2"])
        r2 = RouteGenome(1, 2, "D", skeleton=["S3", "S4"])
        p1 = _passenger("P1", "S1", "S2")
        p2 = _passenger("P2", "S3", "S4")
        r3 = _passenger("P3", "S1", "S3")
        r1.insert_task(p1, 1, 3)
        r2.insert_task(p2, 1, 3)

        # 提取序列
        seqs = extract_vehicle_task_sequences([r1, r2])

        # 验证无跨车边
        all_tasks_in_seqs = set()
        for seq in seqs.values():
            all_tasks_in_seqs.update(seq)
        all_tasks_in_seqs.discard("DEPOT")

        # 每个 task 只应出现在一辆车中
        for task_id in all_tasks_in_seqs:
            count = sum(1 for seq in seqs.values() if task_id in seq)
            assert count == 1, f"Task {task_id} appears in {count} vehicles"

        # 沉积
        pheromone.deposit_multi_vehicle(seqs, cost=10.0)

        # 验证信息素只在同车边增加
        for vi, seq in seqs.items():
            for i in range(len(seq) - 1):
                assert pheromone.get(seq[i], seq[i + 1]) > pheromone.tau0
